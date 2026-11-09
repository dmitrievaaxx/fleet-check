"""Импорт CSV по data-contract.md: разбор, проверки в заданном порядке, идемпотентная запись."""
from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Any, Callable, Optional

from .errors import ApiError

ID_PATTERNS = {
    "vehicle_id": r"^V-\d{3}$",
    "driver_id": r"^D-\d{3}$",
    "waybill_id": r"^WB-\d{6}$",
    "record_id": r"^F-\d{6}$",
    "maintenance_id": r"^M-\d{5}$",
}
PLATE_RE = r"^[АВЕКМНОРСТУХ]\d{3}[АВЕКМНОРСТУХ]{2}\d{2,3}$"
LICENSE_RE = r"^\d{2} \d{2} \d{6}$"
INT_RE = re.compile(r"^\d{1,9}$", re.ASCII)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$", re.ASCII)
DATETIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$", re.ASCII)


class FormatError(ValueError):
    pass


def p_int(s: str) -> int:
    if not INT_RE.match(s):
        raise FormatError
    return int(s)


def p_decimal(places: int) -> Callable[[str], float]:
    pattern = re.compile(r"^\d{1,9}(\.\d{1,%d})?$" % places, re.ASCII)

    def parse(s: str) -> float:
        if not pattern.match(s):
            raise FormatError
        return float(s)
    return parse


def p_date(s: str) -> str:
    if not DATE_RE.match(s):
        raise FormatError
    try:
        date.fromisoformat(s)
    except ValueError as e:
        raise FormatError from e
    return s


def p_datetime(s: str) -> str:
    if not DATETIME_RE.match(s):
        raise FormatError
    try:
        datetime.strptime(s, "%Y-%m-%dT%H:%M:%S")
    except ValueError as e:
        raise FormatError from e
    return s


def p_pattern(regex: str) -> Callable[[str], str]:
    compiled = re.compile(regex, re.ASCII)

    def parse(s: str) -> str:
        if not compiled.match(s):
            raise FormatError
        return s
    return parse


def p_str(max_len: int) -> Callable[[str], str]:
    def parse(s: str) -> str:
        if len(s) > max_len:
            raise FormatError
        return s
    return parse


def p_category_list(s: str) -> list[str]:
    """Структура списка: непустые элементы через ';' без повторов. Принадлежность перечислению проверяется отдельно."""
    parts = s.split(";")
    if any(p == "" or p != p.strip() for p in parts) or len(set(parts)) != len(parts):
        raise FormatError
    return parts


def categories_known(parts: list[str]) -> bool:
    return all(p in ("B", "C", "D") for p in parts)


def in_enum(*values: str) -> Callable[[Any], bool]:
    return lambda v: v in values


def in_range(lo: float, hi: float) -> Callable[[Any], bool]:
    return lambda v: lo <= v <= hi


@dataclass
class DatasetSpec:
    table: str
    columns: list[str]
    required: list[str]
    primary_key: list[str]
    parsers: dict[str, Callable[[str], Any]]
    # проверки допустимых значений выполняются над разобранным значением
    values: dict[str, Callable[[Any], bool]] = field(default_factory=dict)
    # межполевые проверки возвращают имя поля с ошибкой или None
    cross: Optional[Callable[[dict[str, Any]], Optional[str]]] = None
    references: dict[str, tuple[str, str]] = field(default_factory=dict)   # поле -> (таблица, колонка)
    unique: list[str] = field(default_factory=list)


def _cross_vehicle(r: dict[str, Any]) -> Optional[str]:
    if r["last_service_odometer_km"] is not None and r["last_service_odometer_km"] > r["odometer_km"]:
        return "last_service_odometer_km"
    return None


def _cross_waybill(r: dict[str, Any]) -> Optional[str]:
    if r["return_at"] <= r["departure_at"]:
        return "return_at"
    if r["odometer_end"] < r["odometer_start"]:
        return "odometer_end"
    return None


def _cross_maintenance(r: dict[str, Any]) -> Optional[str]:
    if r["finished_at"] is not None and r["finished_at"] < r["started_at"]:
        return "finished_at"
    return None


SPECS: dict[str, DatasetSpec] = {
    "vehicles": DatasetSpec(
        table="vehicles",
        columns=["vehicle_id", "plate", "brand", "model", "type", "year", "fuel_type", "base_consumption_l_100km",
                 "odometer_km", "last_service_odometer_km", "service_interval_km", "license_category", "status"],
        required=["vehicle_id", "plate", "brand", "model", "type", "year", "fuel_type", "base_consumption_l_100km",
                  "odometer_km", "service_interval_km", "license_category", "status"],
        primary_key=["vehicle_id"],
        parsers={"vehicle_id": p_pattern(ID_PATTERNS["vehicle_id"]), "plate": p_pattern(PLATE_RE),
                 "brand": p_str(50), "model": p_str(50), "year": p_int, "base_consumption_l_100km": p_decimal(1),
                 "odometer_km": p_int, "last_service_odometer_km": p_int, "service_interval_km": p_int},
        values={"type": in_enum("truck", "bus", "car"), "year": in_range(1990, 2026),
                "fuel_type": in_enum("diesel", "petrol", "gas"), "base_consumption_l_100km": in_range(3.0, 60.0),
                "odometer_km": in_range(0, 2_000_000), "last_service_odometer_km": in_range(0, 2_000_000),
                "service_interval_km": in_range(5_000, 50_000), "license_category": in_enum("B", "C", "D"),
                "status": in_enum("active", "decommissioned")},
        cross=_cross_vehicle,
        unique=["plate"],
    ),
    "drivers": DatasetSpec(
        table="drivers",
        columns=["driver_id", "full_name", "license_number", "license_categories", "license_valid_until", "hired_at", "status"],
        required=["driver_id", "full_name", "license_number", "license_categories", "license_valid_until", "hired_at", "status"],
        primary_key=["driver_id"],
        parsers={"driver_id": p_pattern(ID_PATTERNS["driver_id"]), "full_name": p_str(100),
                 "license_number": p_pattern(LICENSE_RE), "license_categories": p_category_list,
                 "license_valid_until": p_date, "hired_at": p_date},
        values={"license_categories": categories_known, "status": in_enum("active", "dismissed")},
        unique=["license_number"],
    ),
    "fuel_norms": DatasetSpec(
        table="fuel_norms",
        columns=["fuel_type", "season", "coefficient"],
        required=["fuel_type", "season", "coefficient"],
        primary_key=["fuel_type", "season"],
        parsers={"coefficient": p_decimal(2)},
        values={"fuel_type": in_enum("diesel", "petrol", "gas"), "season": in_enum("winter", "summer"),
                "coefficient": in_range(0.5, 2.0)},
    ),
    "waybills": DatasetSpec(
        table="waybills",
        columns=["waybill_id", "vehicle_id", "driver_id", "departure_at", "return_at", "odometer_start", "odometer_end", "route_description"],
        required=["waybill_id", "vehicle_id", "driver_id", "departure_at", "return_at", "odometer_start", "odometer_end"],
        primary_key=["waybill_id"],
        parsers={"waybill_id": p_pattern(ID_PATTERNS["waybill_id"]), "vehicle_id": p_pattern(ID_PATTERNS["vehicle_id"]),
                 "driver_id": p_pattern(ID_PATTERNS["driver_id"]), "departure_at": p_datetime, "return_at": p_datetime,
                 "odometer_start": p_int, "odometer_end": p_int, "route_description": p_str(200)},
        values={"odometer_start": in_range(0, 2_000_000), "odometer_end": in_range(0, 2_000_000)},
        cross=_cross_waybill,
        references={"vehicle_id": ("vehicles", "vehicle_id"), "driver_id": ("drivers", "driver_id")},
    ),
    "fuel_records": DatasetSpec(
        table="fuel_records",
        columns=["record_id", "vehicle_id", "driver_id", "filled_at", "liters", "price_per_liter", "station"],
        required=["record_id", "vehicle_id", "filled_at", "liters", "price_per_liter"],
        primary_key=["record_id"],
        parsers={"record_id": p_pattern(ID_PATTERNS["record_id"]), "vehicle_id": p_pattern(ID_PATTERNS["vehicle_id"]),
                 "driver_id": p_pattern(ID_PATTERNS["driver_id"]), "filled_at": p_datetime,
                 "liters": p_decimal(2), "price_per_liter": p_decimal(2), "station": p_str(100)},
        values={"liters": in_range(0.01, 1000.0), "price_per_liter": in_range(0.01, 500.0)},
        references={"vehicle_id": ("vehicles", "vehicle_id"), "driver_id": ("drivers", "driver_id")},
    ),
    "maintenance": DatasetSpec(
        table="maintenance",
        columns=["maintenance_id", "vehicle_id", "type", "started_at", "finished_at", "odometer_km", "cost", "description"],
        required=["maintenance_id", "vehicle_id", "type", "started_at", "odometer_km", "cost"],
        primary_key=["maintenance_id"],
        parsers={"maintenance_id": p_pattern(ID_PATTERNS["maintenance_id"]), "vehicle_id": p_pattern(ID_PATTERNS["vehicle_id"]),
                 "started_at": p_datetime, "finished_at": p_datetime, "odometer_km": p_int, "cost": p_decimal(2),
                 "description": p_str(200)},
        values={"type": in_enum("scheduled", "repair"), "odometer_km": in_range(0, 2_000_000), "cost": in_range(0, 10_000_000)},
        cross=_cross_maintenance,
        references={"vehicle_id": ("vehicles", "vehicle_id")},
    ),
}


@dataclass
class RowError:
    row: int
    field: Optional[str]
    code: str
    message: str

    def as_dict(self) -> dict[str, Any]:
        return {"row": self.row, "field": self.field, "code": self.code, "message": self.message}


def decode(content: bytes) -> str:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as e:
        raise ApiError(400, "INVALID_ENCODING", "file is not valid UTF-8") from e
    return text.lstrip("﻿")


def parse_line(line: str) -> Optional[list[str]]:
    """Одна физическая строка как одна запись по RFC 4180: None при незакрытой кавычке или кавычке в незакавыченном поле."""
    fields, i, n = [], 0, len(line)
    while True:
        if i < n and line[i] == '"':
            i += 1
            buf = []
            while True:
                if i >= n:
                    return None
                if line[i] == '"':
                    if i + 1 < n and line[i + 1] == '"':
                        buf.append('"'); i += 2
                        continue
                    i += 1
                    break
                buf.append(line[i]); i += 1
            if i < n and line[i] != ",":
                return None
            fields.append("".join(buf))
        else:
            end = line.find(",", i)
            end = n if end < 0 else end
            if '"' in line[i:end]:
                return None
            fields.append(line[i:end])
            i = end
        if i >= n:
            return fields
        i += 1
        if i == n:
            fields.append("")
            return fields


def validate_row(spec: DatasetSpec, header: list[str], fields: list[str], row_no: int) -> tuple[Optional[RowError], Optional[dict[str, Any]]]:
    if len(fields) != len(header):
        return RowError(row_no, None, "PARSE_ERROR", f"expected {len(header)} fields, got {len(fields)}"), None
    raw = {name: value.strip() for name, value in zip(header, fields) if name in spec.columns}
    for name in spec.required:
        if raw.get(name, "") == "":
            return RowError(row_no, name, "REQUIRED_FIELD_MISSING", f"{name} is required"), None
    parsed: dict[str, Any] = {}
    for name in spec.columns:
        value = raw.get(name, "")
        if value == "":
            parsed[name] = None
            continue
        parser = spec.parsers.get(name)
        if parser is None:
            parsed[name] = value
            continue
        try:
            parsed[name] = parser(value)
        except FormatError:
            return RowError(row_no, name, "INVALID_FORMAT", f"{name} has invalid format: {value}"), None
    for name, check in spec.values.items():
        if parsed.get(name) is not None and not check(parsed[name]):
            return RowError(row_no, name, "INVALID_VALUE", f"{name} is out of allowed values: {raw[name]}"), None
    if spec.cross is not None:
        bad = spec.cross(parsed)
        if bad is not None:
            return RowError(row_no, bad, "CROSS_FIELD_VIOLATION", f"{bad} conflicts with another field"), None
    return None, parsed


def import_csv(conn: sqlite3.Connection, dataset: str, content: bytes) -> dict[str, Any]:
    spec = SPECS.get(dataset)
    if spec is None:
        raise ApiError(400, "UNKNOWN_DATASET", f"unknown dataset {dataset}", {"dataset": dataset})
    text = decode(content)
    lines = iter(enumerate(text.splitlines(), start=1))
    header, row_no = None, 0
    for row_no, line in lines:
        if line:
            header = parse_line(line)
            break
    if header is None:
        raise ApiError(400, "EMPTY_FILE", "file has no header row")
    header = [h.strip() for h in header] if header is not None else []
    missing = [c for c in spec.required if c not in header]
    if missing:
        raise ApiError(400, "MISSING_COLUMNS", "required columns are missing", {"columns": missing})

    total = accepted = rejected = skipped = 0
    errors: list[RowError] = []
    placeholders = ", ".join("?" for _ in spec.columns)
    insert_sql = f"INSERT INTO {spec.table} ({', '.join(spec.columns)}) VALUES ({placeholders})"
    pk_where = " AND ".join(f"{c} = ?" for c in spec.primary_key)

    for row_no, line in lines:
        if not line:
            continue
        total += 1
        fields = parse_line(line)
        err, parsed = (RowError(row_no, None, "PARSE_ERROR", "unterminated quoted field"), None) if fields is None \
            else validate_row(spec, header, fields, row_no)
        if err is None:
            assert parsed is not None
            for name, (table, column) in spec.references.items():
                if parsed[name] is not None and conn.execute(
                        f"SELECT 1 FROM {table} WHERE {column} = ?", (parsed[name],)).fetchone() is None:
                    err = RowError(row_no, name, "REFERENCE_NOT_FOUND", f"{name} {parsed[name]} not found")
                    break
        if err is None:
            assert parsed is not None
            pk_values = tuple(parsed[c] for c in spec.primary_key)
            if conn.execute(f"SELECT 1 FROM {spec.table} WHERE {pk_where}", pk_values).fetchone() is not None:
                skipped += 1
                continue
            for name in spec.unique:
                if conn.execute(f"SELECT 1 FROM {spec.table} WHERE {name} = ?", (parsed[name],)).fetchone() is not None:
                    err = RowError(row_no, name, "UNIQUE_VIOLATION", f"{name} {parsed[name]} already exists")
                    break
        if err is not None:
            rejected += 1
            errors.append(err)
            continue
        conn.execute(insert_sql, tuple(";".join(v) if isinstance(v, list) else v for v in (parsed[c] for c in spec.columns)))
        accepted += 1

    return {
        "dataset": dataset, "total": total, "accepted": accepted, "rejected": rejected, "skipped": skipped,
        "errors": [e.as_dict() for e in errors],
    }
