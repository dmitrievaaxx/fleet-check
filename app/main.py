"""HTTP-интерфейс по api-contract.md."""
from __future__ import annotations

import csv
import io
import json
import logging
from contextlib import asynccontextmanager
from datetime import date
from typing import Any, AsyncIterator, Callable, Optional

from fastapi import FastAPI, Request
from starlette.datastructures import UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response

from . import db, importer, reports
from .domain import Domain
from .errors import ApiError, invalid_parameter, not_found
from .rules import VIOLATION_TYPES

log = logging.getLogger("atp")


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    db.init_db()
    log.info("database ready at %s", db.db_path())
    yield


app = FastAPI(title="ATP Information System", version="1.0.0", docs_url="/docs", lifespan=lifespan)


@app.exception_handler(ApiError)
async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content=exc.body())


@app.exception_handler(RequestValidationError)
async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    first = exc.errors()[0] if exc.errors() else {}
    name = ".".join(str(p) for p in first.get("loc", [])[1:]) or None
    err = ApiError(400, "INVALID_PARAMETER", first.get("msg", "invalid request"), {"parameter": name, "value": first.get("input")})
    return JSONResponse(status_code=err.status, content=err.body())


@app.exception_handler(Exception)
async def unexpected_error_handler(_: Request, exc: Exception) -> JSONResponse:
    log.exception("unhandled error")
    err = ApiError(500, "INTERNAL_ERROR", "unexpected error", {"type": type(exc).__name__})
    return JSONResponse(status_code=err.status, content=err.body())


# --- разбор параметров запроса ---------------------------------------------------------------

def q_int(name: str, raw: Optional[str], lo: int, hi: int, default: Optional[int] = None, required: bool = False) -> Optional[int]:
    if raw is None or raw == "":
        if required:
            raise invalid_parameter(name, raw, f"{name} is required")
        return default
    try:
        value = int(raw)
    except ValueError as e:
        raise invalid_parameter(name, raw, f"{name} must be an integer") from e
    if not lo <= value <= hi:
        raise invalid_parameter(name, raw, f"{name} must be between {lo} and {hi}")
    return value


def q_date(name: str, raw: Optional[str], required: bool = False) -> Optional[date]:
    if raw is None or raw == "":
        if required:
            raise invalid_parameter(name, raw, f"{name} is required")
        return None
    try:
        return date.fromisoformat(raw)
    except ValueError as e:
        raise invalid_parameter(name, raw, f"{name} must be a date YYYY-MM-DD") from e


def q_enum(name: str, raw: Optional[str], allowed: list[str], default: Optional[str] = None) -> Optional[str]:
    if raw is None or raw == "":
        return default
    if raw not in allowed:
        raise invalid_parameter(name, raw, f"{name} must be one of {', '.join(allowed)}")
    return raw


def q_range(date_from: Optional[date], date_to: Optional[date]) -> None:
    if date_from and date_to and date_from > date_to:
        raise invalid_parameter("from", date_from.isoformat(), "from must not be after to")


# --- представление ------------------------------------------------------------------------------

def page(items: list[dict[str, Any]], total: int, limit: int, offset: int) -> dict[str, Any]:
    return {"items": items, "total": total, "limit": limit, "offset": offset}


def report_response(name: str, params: dict[str, Any], rows: list[dict[str, Any]], columns: list[str], fmt: str) -> Response:
    if fmt == "csv":
        buf = io.StringIO()
        writer = csv.writer(buf, lineterminator="\n")
        writer.writerow(columns)
        for row in rows:
            writer.writerow([_csv_value(row[c]) for c in columns])
        return Response(content=buf.getvalue(), media_type="text/csv; charset=utf-8")
    return JSONResponse({"report": name, "params": params, "rows": rows})


def _csv_value(v: Any) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (dict, list)):
        return json.dumps(v, ensure_ascii=False)
    return str(v)


def vehicle_dict(r: Any) -> dict[str, Any]:
    return {k: r[k] for k in importer.SPECS["vehicles"].columns}


def driver_dict(r: Any) -> dict[str, Any]:
    d = {k: r[k] for k in importer.SPECS["drivers"].columns}
    d["license_categories"] = r["license_categories"].split(";")
    return d


def waybill_dict(r: Any) -> dict[str, Any]:
    d = {k: r[k] for k in importer.SPECS["waybills"].columns}
    d["mileage_km"] = r["odometer_end"] - r["odometer_start"]
    return d


# --- служебные роуты ----------------------------------------------------------------------------

@app.get("/health")
def health() -> dict[str, Any]:
    with db.connect() as conn:
        return {"status": "ok"}


@app.post("/import/{dataset}")
async def import_dataset(dataset: str, request: Request) -> dict[str, Any]:
    if dataset not in importer.SPECS:
        raise ApiError(400, "UNKNOWN_DATASET", f"unknown dataset {dataset}", {"dataset": dataset})
    try:
        form = await request.form()
    except Exception as e:  # noqa: BLE001
        raise ApiError(400, "FILE_REQUIRED", "multipart body with field 'file' is required") from e
    file = form.get("file")
    if not isinstance(file, UploadFile):
        raise ApiError(400, "FILE_REQUIRED", "multipart field 'file' is required")
    content = await file.read()
    with db.connect() as conn:
        return importer.import_csv(conn, dataset, content)


# --- сущности ---------------------------------------------------------------------------------------

def _list(table: str, filters: dict[str, Optional[str]], order: str, limit: Optional[str], offset: Optional[str],
          to_dict: Callable[[Any], dict[str, Any]]) -> dict[str, Any]:
    lim = q_int("limit", limit, 1, 1000, default=100)
    off = q_int("offset", offset, 0, 10**9, default=0)
    where, params = [], []
    for column, value in filters.items():
        if value is not None and value != "":
            where.append(f"{column} = ?")
            params.append(value)
    clause = f" WHERE {' AND '.join(where)}" if where else ""
    with db.connect() as conn:
        total = conn.execute(f"SELECT COUNT(*) FROM {table}{clause}", params).fetchone()[0]
        rows = conn.execute(f"SELECT * FROM {table}{clause} ORDER BY {order} LIMIT ? OFFSET ?", [*params, lim, off]).fetchall()
    return page([to_dict(r) for r in rows], total, lim, off)


@app.get("/vehicles")
def list_vehicles(status: Optional[str] = None, type: Optional[str] = None, fuel_type: Optional[str] = None,
                  limit: Optional[str] = None, offset: Optional[str] = None) -> dict[str, Any]:
    q_enum("status", status, ["active", "decommissioned"])
    q_enum("type", type, ["truck", "bus", "car"])
    q_enum("fuel_type", fuel_type, ["diesel", "petrol", "gas"])
    return _list("vehicles", {"status": status, "type": type, "fuel_type": fuel_type}, "vehicle_id", limit, offset, vehicle_dict)


@app.get("/vehicles/{vehicle_id}")
def get_vehicle(vehicle_id: str) -> dict[str, Any]:
    with db.connect() as conn:
        r = conn.execute("SELECT * FROM vehicles WHERE vehicle_id = ?", (vehicle_id,)).fetchone()
    if r is None:
        raise not_found("vehicle", vehicle_id)
    return vehicle_dict(r)


@app.get("/drivers")
def list_drivers(status: Optional[str] = None, limit: Optional[str] = None, offset: Optional[str] = None) -> dict[str, Any]:
    q_enum("status", status, ["active", "dismissed"])
    return _list("drivers", {"status": status}, "driver_id", limit, offset, driver_dict)


@app.get("/drivers/{driver_id}")
def get_driver(driver_id: str) -> dict[str, Any]:
    with db.connect() as conn:
        r = conn.execute("SELECT * FROM drivers WHERE driver_id = ?", (driver_id,)).fetchone()
    if r is None:
        raise not_found("driver", driver_id)
    return driver_dict(r)


@app.get("/waybills")
def list_waybills(vehicle_id: Optional[str] = None, driver_id: Optional[str] = None,
                  from_: Optional[str] = None, to: Optional[str] = None,
                  limit: Optional[str] = None, offset: Optional[str] = None, request: Request = None) -> dict[str, Any]:
    # параметр называется from, что в Python зарезервировано
    from_ = request.query_params.get("from") if request is not None else from_
    date_from, date_to = q_date("from", from_), q_date("to", to)
    q_range(date_from, date_to)
    lim = q_int("limit", limit, 1, 1000, default=100)
    off = q_int("offset", offset, 0, 10**9, default=0)
    where, params = [], []
    if vehicle_id:
        where.append("vehicle_id = ?"); params.append(vehicle_id)
    if driver_id:
        where.append("driver_id = ?"); params.append(driver_id)
    if date_from:
        where.append("substr(departure_at, 1, 10) >= ?"); params.append(date_from.isoformat())
    if date_to:
        where.append("substr(departure_at, 1, 10) <= ?"); params.append(date_to.isoformat())
    clause = f" WHERE {' AND '.join(where)}" if where else ""
    with db.connect() as conn:
        total = conn.execute(f"SELECT COUNT(*) FROM waybills{clause}", params).fetchone()[0]
        rows = conn.execute(f"SELECT * FROM waybills{clause} ORDER BY departure_at, waybill_id LIMIT ? OFFSET ?",
                            [*params, lim, off]).fetchall()
    return page([waybill_dict(r) for r in rows], total, lim, off)


@app.get("/waybills/{waybill_id}")
def get_waybill(waybill_id: str) -> dict[str, Any]:
    with db.connect() as conn:
        r = conn.execute("SELECT * FROM waybills WHERE waybill_id = ?", (waybill_id,)).fetchone()
    if r is None:
        raise not_found("waybill", waybill_id)
    return waybill_dict(r)


# --- отчёты -------------------------------------------------------------------------------------------

def _domain() -> Domain:
    with db.connect() as conn:
        return Domain(conn)


@app.get("/reports/fuel")
def report_fuel(year: Optional[str] = None, month: Optional[str] = None, format: Optional[str] = None) -> Response:
    y, m = q_int("year", year, 2000, 2100, required=True), q_int("month", month, 1, 12, required=True)
    fmt = q_enum("format", format, ["json", "csv"], default="json")
    rows = reports.fuel(_domain(), y, m)
    return report_response("fuel", {"year": y, "month": m}, rows, reports.FUEL_COLUMNS, fmt)


@app.get("/reports/maintenance-due")
def report_maintenance_due(as_of: Optional[str] = None, format: Optional[str] = None) -> Response:
    d = q_date("as_of", as_of, required=True)
    fmt = q_enum("format", format, ["json", "csv"], default="json")
    rows = reports.maintenance_due(_domain(), d)
    return report_response("maintenance-due", {"as_of": d.isoformat()}, rows, reports.MAINTENANCE_DUE_COLUMNS, fmt)


@app.get("/reports/violations")
def report_violations(request: Request, type: Optional[str] = None, to: Optional[str] = None,
                      format: Optional[str] = None) -> Response:
    vtype = q_enum("type", type, VIOLATION_TYPES)
    date_from, date_to = q_date("from", request.query_params.get("from")), q_date("to", to)
    q_range(date_from, date_to)
    fmt = q_enum("format", format, ["json", "csv"], default="json")
    rows = reports.violations(_domain(), vtype, date_from, date_to)
    params = {"type": vtype, "from": date_from.isoformat() if date_from else None, "to": date_to.isoformat() if date_to else None}
    return report_response("violations", params, rows, reports.VIOLATION_COLUMNS, fmt)


@app.get("/reports/utilization")
def report_utilization(year: Optional[str] = None, month: Optional[str] = None, format: Optional[str] = None) -> Response:
    y, m = q_int("year", year, 2000, 2100, required=True), q_int("month", month, 1, 12, required=True)
    fmt = q_enum("format", format, ["json", "csv"], default="json")
    rows = reports.utilization(_domain(), y, m)
    return report_response("utilization", {"year": y, "month": m}, rows, reports.UTILIZATION_COLUMNS, fmt)


@app.get("/reports/drivers")
def report_drivers(year: Optional[str] = None, month: Optional[str] = None, format: Optional[str] = None) -> Response:
    y, m = q_int("year", year, 2000, 2100, required=True), q_int("month", month, 1, 12, required=True)
    fmt = q_enum("format", format, ["json", "csv"], default="json")
    rows = reports.drivers(_domain(), y, m)
    return report_response("drivers", {"year": y, "month": m}, rows, reports.DRIVERS_COLUMNS, fmt)
