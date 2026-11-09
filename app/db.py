from __future__ import annotations

import os
import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator


def db_path() -> Path:
    return Path(os.environ.get("ATP_DB_PATH", "./atp.db"))


SCHEMA = """
CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_id TEXT PRIMARY KEY,
    plate TEXT NOT NULL UNIQUE,
    brand TEXT NOT NULL,
    model TEXT NOT NULL,
    type TEXT NOT NULL,
    year INTEGER NOT NULL,
    fuel_type TEXT NOT NULL,
    base_consumption_l_100km REAL NOT NULL,
    odometer_km INTEGER NOT NULL,
    last_service_odometer_km INTEGER,
    service_interval_km INTEGER NOT NULL,
    license_category TEXT NOT NULL,
    status TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS drivers (
    driver_id TEXT PRIMARY KEY,
    full_name TEXT NOT NULL,
    license_number TEXT NOT NULL UNIQUE,
    license_categories TEXT NOT NULL,
    license_valid_until TEXT NOT NULL,
    hired_at TEXT NOT NULL,
    status TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS fuel_norms (
    fuel_type TEXT NOT NULL,
    season TEXT NOT NULL,
    coefficient REAL NOT NULL,
    PRIMARY KEY (fuel_type, season)
);
CREATE TABLE IF NOT EXISTS waybills (
    waybill_id TEXT PRIMARY KEY,
    vehicle_id TEXT NOT NULL REFERENCES vehicles(vehicle_id),
    driver_id TEXT NOT NULL REFERENCES drivers(driver_id),
    departure_at TEXT NOT NULL,
    return_at TEXT NOT NULL,
    odometer_start INTEGER NOT NULL,
    odometer_end INTEGER NOT NULL,
    route_description TEXT
);
CREATE INDEX IF NOT EXISTS ix_waybills_vehicle ON waybills(vehicle_id, departure_at);
CREATE INDEX IF NOT EXISTS ix_waybills_driver ON waybills(driver_id, departure_at);
CREATE INDEX IF NOT EXISTS ix_waybills_departure ON waybills(departure_at, waybill_id);
CREATE TABLE IF NOT EXISTS fuel_records (
    record_id TEXT PRIMARY KEY,
    vehicle_id TEXT NOT NULL REFERENCES vehicles(vehicle_id),
    driver_id TEXT REFERENCES drivers(driver_id),
    filled_at TEXT NOT NULL,
    liters REAL NOT NULL,
    price_per_liter REAL NOT NULL,
    station TEXT
);
CREATE INDEX IF NOT EXISTS ix_fuel_vehicle ON fuel_records(vehicle_id, filled_at);
CREATE TABLE IF NOT EXISTS maintenance (
    maintenance_id TEXT PRIMARY KEY,
    vehicle_id TEXT NOT NULL REFERENCES vehicles(vehicle_id),
    type TEXT NOT NULL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    odometer_km INTEGER NOT NULL,
    cost REAL NOT NULL,
    description TEXT
);
CREATE INDEX IF NOT EXISTS ix_maintenance_vehicle ON maintenance(vehicle_id, started_at);
"""

TABLES = ["vehicles", "drivers", "fuel_norms", "waybills", "fuel_records", "maintenance"]


_initialized: set[Path] = set()
_lock = threading.Lock()


def init_db(path: Path | None = None) -> None:
    path = path or db_path()
    with _lock:
        if path in _initialized:
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(path) as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)
        _initialized.add(path)


@contextmanager
def connect(path: Path | None = None) -> Iterator[sqlite3.Connection]:
    path = path or db_path()
    init_db(path)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def record_counts(conn: sqlite3.Connection) -> dict[str, int]:
    return {t: conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in TABLES}
