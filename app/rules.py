"""Нарушения по business-rules.md, раздел 2."""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime
from typing import Any, Optional

from .domain import FUEL_OVERRUN_THRESHOLD, ODOMETER_GAP_TOLERANCE_KM, Domain, Waybill, fmt_dt, overlaps
from .errors import ApiError

VIOLATION_TYPES = [
    "VEHICLE_OVERLAP", "DRIVER_OVERLAP", "LICENSE_CATEGORY_MISMATCH", "LICENSE_EXPIRED", "ODOMETER_GAP",
    "VEHICLE_DECOMMISSIONED", "VEHICLE_IN_MAINTENANCE", "MAINTENANCE_OVERDUE", "FUEL_OVERRUN",
]


def _row(vtype: str, wb: Optional[Waybill], vehicle_id: str, occurred_at: datetime, subject_type: str,
         subject_id: str, related_id: Optional[str], details: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": vtype, "occurred_at": fmt_dt(occurred_at), "vehicle_id": vehicle_id,
        "driver_id": wb.driver_id if wb is not None else None, "subject_type": subject_type,
        "subject_id": subject_id, "related_id": related_id, "details": details,
    }


def _overlap_rows(groups: dict[str, list[Waybill]], vtype: str) -> list[dict[str, Any]]:
    rows = []
    for _k, lst in sorted(groups.items()):
        lst = sorted(lst, key=lambda w: w.key)
        for j, b in enumerate(lst):
            for a in lst[:j]:
                if overlaps(a.departure_at, a.return_at, b.departure_at, b.return_at):
                    rows.append(_row(vtype, b, b.vehicle_id, b.departure_at, "waybill", b.waybill_id, a.waybill_id,
                                     {"related_departure_at": fmt_dt(a.departure_at), "related_return_at": fmt_dt(a.return_at)}))
    return rows


def waybill_violations(dom: Domain) -> list[dict[str, Any]]:
    rows = _overlap_rows(dom.waybills_by_vehicle, "VEHICLE_OVERLAP") + _overlap_rows(dom.waybills_by_driver, "DRIVER_OVERLAP")

    for wb in dom.waybills:
        v, d = dom.vehicles[wb.vehicle_id], dom.drivers[wb.driver_id]
        if v.license_category not in d.categories:
            rows.append(_row("LICENSE_CATEGORY_MISMATCH", wb, v.vehicle_id, wb.departure_at, "waybill", wb.waybill_id, None,
                             {"required": v.license_category, "driver_categories": list(d.categories)}))
        if d.license_valid_until < wb.departure_at.date():
            rows.append(_row("LICENSE_EXPIRED", wb, v.vehicle_id, wb.departure_at, "waybill", wb.waybill_id, None,
                             {"license_valid_until": d.license_valid_until.isoformat()}))
        if v.status == "decommissioned":
            rows.append(_row("VEHICLE_DECOMMISSIONED", wb, v.vehicle_id, wb.departure_at, "waybill", wb.waybill_id, None, {}))
        hits = [m for m in dom.maintenance_by_vehicle[v.vehicle_id]
                if overlaps(wb.departure_at, wb.return_at, m.started_at, m.finished_at)]
        if hits:
            m = min(hits, key=lambda m: m.key)
            rows.append(_row("VEHICLE_IN_MAINTENANCE", wb, v.vehicle_id, wb.departure_at, "waybill", wb.waybill_id, m.maintenance_id,
                             {"maintenance_type": m.type, "started_at": fmt_dt(m.started_at),
                              "finished_at": fmt_dt(m.finished_at) if m.finished_at else None}))

    for vid, lst in sorted(dom.waybills_by_vehicle.items()):
        v = dom.vehicles[vid]
        expected = v.odometer_km
        prev: Optional[Waybill] = None
        in_episode, last_service = False, None
        for wb in sorted(lst, key=lambda w: w.key):
            delta = wb.odometer_start - expected
            if delta < 0 or delta > ODOMETER_GAP_TOLERANCE_KM:
                rows.append(_row("ODOMETER_GAP", wb, vid, wb.departure_at, "waybill", wb.waybill_id,
                                 prev.waybill_id if prev else None,
                                 {"expected": expected, "actual": wb.odometer_start, "delta": delta}))
            expected, prev = wb.odometer_end, wb

            svc, _m = dom.service_odometer(vid, wb.departure_at)
            if svc != last_service:
                in_episode, last_service = False, svc
            km_since = wb.odometer_end - svc
            if not in_episode and km_since > v.service_interval_km:
                in_episode = True
                rows.append(_row("MAINTENANCE_OVERDUE", wb, vid, wb.departure_at, "waybill", wb.waybill_id, None,
                                 {"service_odometer": svc, "odometer_end": wb.odometer_end,
                                  "km_since_service": km_since, "service_interval_km": v.service_interval_km}))
    return rows


def fuel_overruns(dom: Domain) -> list[dict[str, Any]]:
    missing = dom.missing_norms(dom.waybills)
    if missing:
        raise ApiError(422, "FUEL_NORM_MISSING", "fuel norm coefficients are missing", {"missing": missing})
    norm: dict[tuple[str, int, int], float] = defaultdict(float)
    mileage: dict[tuple[str, int, int], int] = defaultdict(int)
    actual: dict[tuple[str, int, int], float] = defaultdict(float)
    for wb in dom.waybills:
        key = (wb.vehicle_id, wb.departure_at.year, wb.departure_at.month)
        norm[key] += dom.norm_liters(wb)
        mileage[key] += wb.mileage
    for fr in dom.fuel:
        actual[(fr.vehicle_id, fr.filled_at.year, fr.filled_at.month)] += fr.liters
    rows = []
    for key in sorted(set(norm) | set(actual)):
        n, a = norm.get(key, 0.0), actual.get(key, 0.0)
        if a > n * (1 + FUEL_OVERRUN_THRESHOLD):
            vid, year, month = key
            rows.append({
                "type": "FUEL_OVERRUN", "occurred_at": fmt_dt(datetime(year, month, 1)), "vehicle_id": vid,
                "driver_id": None, "subject_type": "vehicle_month", "subject_id": vid, "related_id": None,
                "details": {"year": year, "month": month, "mileage_km": mileage.get(key, 0), "norm_liters": round(n, 2),
                            "actual_liters": round(a, 2), "deviation_pct": None if n == 0 else round((a - n) / n * 100, 1)},
            })
    return rows


def violations(dom: Domain, vtype: Optional[str] = None) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if vtype is None or vtype != "FUEL_OVERRUN":
        rows += waybill_violations(dom)
    if vtype is None or vtype == "FUEL_OVERRUN":
        rows += fuel_overruns(dom)
    if vtype is not None:
        rows = [r for r in rows if r["type"] == vtype]
    rows.sort(key=lambda r: (r["occurred_at"], r["type"], r["subject_id"], r["related_id"] or ""))
    return rows
