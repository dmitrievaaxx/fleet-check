"""Загрузка данных из БД в память и общие вычисления из business-rules.md, раздел 1."""
from __future__ import annotations

import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime
from typing import Optional

FUEL_OVERRUN_THRESHOLD = 0.10
DUE_SOON_FRACTION = 0.10
ODOMETER_GAP_TOLERANCE_KM = 5
WINTER_MONTHS = {11, 12, 1, 2, 3}


def parse_dt(s: str) -> datetime:
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%S")


def fmt_dt(d: datetime) -> str:
    return d.strftime("%Y-%m-%dT%H:%M:%S")


def season_of(d: date) -> str:
    return "winter" if d.month in WINTER_MONTHS else "summer"


def overlaps(a_start: datetime, a_end: Optional[datetime], b_start: datetime, b_end: Optional[datetime]) -> bool:
    """Пересечение полуоткрытых интервалов; None означает открытый правый конец."""
    left = a_start < b_end if b_end is not None else True
    right = b_start < a_end if a_end is not None else True
    return left and right


@dataclass
class Vehicle:
    vehicle_id: str
    plate: str
    type: str
    fuel_type: str
    base_consumption: float
    odometer_km: int
    last_service_odometer_km: Optional[int]
    service_interval_km: int
    license_category: str
    status: str


@dataclass
class Driver:
    driver_id: str
    full_name: str
    categories: list[str]
    license_valid_until: date
    status: str


@dataclass
class Waybill:
    waybill_id: str
    vehicle_id: str
    driver_id: str
    departure_at: datetime
    return_at: datetime
    odometer_start: int
    odometer_end: int

    @property
    def mileage(self) -> int:
        return self.odometer_end - self.odometer_start

    @property
    def key(self) -> tuple[datetime, str]:
        return (self.departure_at, self.waybill_id)


@dataclass
class FuelRecord:
    vehicle_id: str
    filled_at: datetime
    liters: float


@dataclass
class Maintenance:
    maintenance_id: str
    vehicle_id: str
    type: str
    started_at: datetime
    finished_at: Optional[datetime]
    odometer_km: int

    @property
    def key(self) -> tuple[datetime, str]:
        return (self.started_at, self.maintenance_id)


class Domain:
    """Снимок базы в памяти с индексами, нужными правилам и отчётам."""

    def __init__(self, conn: sqlite3.Connection):
        self.vehicles: dict[str, Vehicle] = {}
        for r in conn.execute("SELECT * FROM vehicles ORDER BY vehicle_id"):
            self.vehicles[r["vehicle_id"]] = Vehicle(
                r["vehicle_id"], r["plate"], r["type"], r["fuel_type"], r["base_consumption_l_100km"],
                r["odometer_km"], r["last_service_odometer_km"], r["service_interval_km"], r["license_category"], r["status"])
        self.drivers: dict[str, Driver] = {}
        for r in conn.execute("SELECT * FROM drivers ORDER BY driver_id"):
            self.drivers[r["driver_id"]] = Driver(
                r["driver_id"], r["full_name"], r["license_categories"].split(";"),
                date.fromisoformat(r["license_valid_until"]), r["status"])
        self.norms: dict[tuple[str, str], float] = {
            (r["fuel_type"], r["season"]): r["coefficient"] for r in conn.execute("SELECT * FROM fuel_norms")}
        self.waybills: list[Waybill] = [
            Waybill(r["waybill_id"], r["vehicle_id"], r["driver_id"], parse_dt(r["departure_at"]), parse_dt(r["return_at"]),
                    r["odometer_start"], r["odometer_end"])
            for r in conn.execute("SELECT * FROM waybills ORDER BY departure_at, waybill_id")]
        self.fuel: list[FuelRecord] = [
            FuelRecord(r["vehicle_id"], parse_dt(r["filled_at"]), r["liters"])
            for r in conn.execute("SELECT vehicle_id, filled_at, liters FROM fuel_records")]
        self.maintenance: list[Maintenance] = [
            Maintenance(r["maintenance_id"], r["vehicle_id"], r["type"], parse_dt(r["started_at"]),
                        parse_dt(r["finished_at"]) if r["finished_at"] else None, r["odometer_km"])
            for r in conn.execute("SELECT * FROM maintenance ORDER BY started_at, maintenance_id")]

        self.waybills_by_vehicle: dict[str, list[Waybill]] = defaultdict(list)
        self.waybills_by_driver: dict[str, list[Waybill]] = defaultdict(list)
        for wb in self.waybills:
            self.waybills_by_vehicle[wb.vehicle_id].append(wb)
            self.waybills_by_driver[wb.driver_id].append(wb)
        self.maintenance_by_vehicle: dict[str, list[Maintenance]] = defaultdict(list)
        for m in self.maintenance:
            self.maintenance_by_vehicle[m.vehicle_id].append(m)

    def service_odometer(self, vehicle_id: str, at: datetime) -> tuple[int, Optional[Maintenance]]:
        """Пробег на последнем плановом ТО к моменту at (business-rules.md, 1.5)."""
        v = self.vehicles[vehicle_id]
        scheduled = [m for m in self.maintenance_by_vehicle[vehicle_id] if m.type == "scheduled" and m.started_at <= at]
        if scheduled:
            m = max(scheduled, key=lambda m: m.key)
            return m.odometer_km, m
        if v.last_service_odometer_km is not None:
            return v.last_service_odometer_km, None
        return v.odometer_km, None

    def missing_norms(self, waybills: list[Waybill]) -> list[dict[str, str]]:
        """Сочетания топлива и сезона, нужные для расчёта и отсутствующие в справочнике."""
        needed = {(self.vehicles[wb.vehicle_id].fuel_type, season_of(wb.departure_at.date())) for wb in waybills}
        return [{"fuel_type": ft, "season": s} for ft, s in sorted(needed) if (ft, s) not in self.norms]

    def norm_liters(self, wb: Waybill) -> float:
        v = self.vehicles[wb.vehicle_id]
        coef = self.norms[(v.fuel_type, season_of(wb.departure_at.date()))]
        return wb.mileage * v.base_consumption * coef / 100
