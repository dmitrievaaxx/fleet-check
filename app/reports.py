"""Отчёты по reports-contract.md и business-rules.md, раздел 3."""
from __future__ import annotations

import calendar
from collections import defaultdict
from datetime import date, datetime, time, timedelta
from typing import Any, Optional

from . import rules
from .domain import DUE_SOON_FRACTION, FUEL_OVERRUN_THRESHOLD, Domain, fmt_dt, overlaps
from .errors import ApiError

FUEL_COLUMNS = ["vehicle_id", "plate", "fuel_type", "mileage_km", "norm_liters", "actual_liters", "deviation_liters", "deviation_pct", "overrun"]
MAINTENANCE_DUE_COLUMNS = ["vehicle_id", "plate", "current_odometer_km", "service_odometer_km", "last_service_at",
                           "service_interval_km", "km_since_service", "km_remaining", "status"]
VIOLATION_COLUMNS = ["type", "occurred_at", "vehicle_id", "driver_id", "subject_type", "subject_id", "related_id", "details"]
UTILIZATION_COLUMNS = ["vehicle_id", "plate", "type", "days_in_month", "days_on_line", "days_in_maintenance", "days_idle", "utilization"]
DRIVERS_COLUMNS = ["driver_id", "full_name", "trips", "mileage_km", "violations"]


def fuel(dom: Domain, year: int, month: int) -> list[dict[str, Any]]:
    trips = [wb for wb in dom.waybills if wb.departure_at.year == year and wb.departure_at.month == month]
    missing = dom.missing_norms(trips)
    if missing:
        raise ApiError(422, "FUEL_NORM_MISSING", "fuel norm coefficients are missing", {"missing": missing})
    norm: dict[str, float] = defaultdict(float)
    mileage: dict[str, int] = defaultdict(int)
    actual: dict[str, float] = defaultdict(float)
    for wb in trips:
        norm[wb.vehicle_id] += dom.norm_liters(wb)
        mileage[wb.vehicle_id] += wb.mileage
    for fr in dom.fuel:
        if fr.filled_at.year == year and fr.filled_at.month == month:
            actual[fr.vehicle_id] += fr.liters
    rows = []
    for vid in sorted(set(norm) | set(actual)):
        v = dom.vehicles[vid]
        n, a = norm.get(vid, 0.0), actual.get(vid, 0.0)
        rows.append({
            "vehicle_id": vid, "plate": v.plate, "fuel_type": v.fuel_type, "mileage_km": mileage.get(vid, 0),
            "norm_liters": round(n, 2), "actual_liters": round(a, 2), "deviation_liters": round(a - n, 2),
            "deviation_pct": None if n == 0 else round((a - n) / n * 100, 1),
            "overrun": a > n * (1 + FUEL_OVERRUN_THRESHOLD),
        })
    return rows


def maintenance_due(dom: Domain, as_of: date) -> list[dict[str, Any]]:
    as_of_ts = datetime.combine(as_of, time(23, 59, 59))
    rows = []
    for vid, v in sorted(dom.vehicles.items()):
        if v.status != "active":
            continue
        done = [wb for wb in dom.waybills_by_vehicle[vid] if wb.return_at <= as_of_ts]
        current = max(done, key=lambda w: w.key).odometer_end if done else v.odometer_km
        svc, m = dom.service_odometer(vid, as_of_ts)
        current = max(current, svc)
        since = current - svc
        remaining = v.service_interval_km - since
        if remaining < 0:
            status = "overdue"
        elif remaining <= v.service_interval_km * DUE_SOON_FRACTION:
            status = "due_soon"
        else:
            status = "ok"
        rows.append({
            "vehicle_id": vid, "plate": v.plate, "current_odometer_km": current, "service_odometer_km": svc,
            "last_service_at": fmt_dt(m.started_at) if m else None, "service_interval_km": v.service_interval_km,
            "km_since_service": since, "km_remaining": remaining, "status": status,
        })
    rows.sort(key=lambda r: (r["km_remaining"], r["vehicle_id"]))
    return rows


def violations(dom: Domain, vtype: Optional[str], date_from: Optional[date], date_to: Optional[date]) -> list[dict[str, Any]]:
    rows = rules.violations(dom, vtype)
    if date_from is not None:
        rows = [r for r in rows if r["occurred_at"][:10] >= date_from.isoformat()]
    if date_to is not None:
        rows = [r for r in rows if r["occurred_at"][:10] <= date_to.isoformat()]
    return rows


def utilization(dom: Domain, year: int, month: int) -> list[dict[str, Any]]:
    days_in_month = calendar.monthrange(year, month)[1]
    day_starts = [datetime(year, month, d) for d in range(1, days_in_month + 1)]
    rows = []
    for vid, v in sorted(dom.vehicles.items()):
        if v.status != "active":
            continue
        on_line, in_maint = set(), set()
        for i, day in enumerate(day_starts):
            next_day = day + timedelta(days=1)
            if any(overlaps(day, next_day, wb.departure_at, wb.return_at) for wb in dom.waybills_by_vehicle[vid]):
                on_line.add(i)
            if any(overlaps(day, next_day, m.started_at, m.finished_at) for m in dom.maintenance_by_vehicle[vid]):
                in_maint.add(i)
        rows.append({
            "vehicle_id": vid, "plate": v.plate, "type": v.type, "days_in_month": days_in_month,
            "days_on_line": len(on_line), "days_in_maintenance": len(in_maint),
            "days_idle": days_in_month - len(on_line | in_maint),
            "utilization": round(len(on_line) / days_in_month, 3),
        })
    return rows


DRIVER_VIOLATION_TYPES = {"DRIVER_OVERLAP", "LICENSE_CATEGORY_MISMATCH", "LICENSE_EXPIRED"}


def drivers(dom: Domain, year: int, month: int) -> list[dict[str, Any]]:
    trips: dict[str, int] = defaultdict(int)
    mileage: dict[str, int] = defaultdict(int)
    for wb in dom.waybills:
        if wb.departure_at.year == year and wb.departure_at.month == month:
            trips[wb.driver_id] += 1
            mileage[wb.driver_id] += wb.mileage
    counts: dict[str, int] = defaultdict(int)
    prefix = f"{year:04d}-{month:02d}"
    for r in rules.waybill_violations(dom):
        if r["type"] in DRIVER_VIOLATION_TYPES and r["occurred_at"].startswith(prefix):
            counts[r["driver_id"]] += 1
    rows = []
    for did in sorted(set(trips) | set(counts)):
        d = dom.drivers[did]
        rows.append({"driver_id": did, "full_name": d.full_name, "trips": trips.get(did, 0),
                     "mileage_km": mileage.get(did, 0), "violations": counts.get(did, 0)})
    return rows
