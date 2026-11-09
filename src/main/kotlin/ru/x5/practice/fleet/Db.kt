package ru.x5.practice.fleet

import jakarta.annotation.PostConstruct
import java.sql.SQLException
import java.util.concurrent.Executor
import javax.sql.DataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

const val SCHEMA = """
CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_id               TEXT PRIMARY KEY,
    plate                    TEXT NOT NULL UNIQUE,
    brand                    TEXT NOT NULL,
    model                    TEXT NOT NULL,
    type                     TEXT NOT NULL,
    year                     INTEGER NOT NULL,
    fuel_type                TEXT NOT NULL,
    base_consumption_l_100km DOUBLE PRECISION NOT NULL,
    odometer_km              INTEGER NOT NULL,
    last_service_odometer_km INTEGER,
    service_interval_km      INTEGER NOT NULL,
    license_category         TEXT NOT NULL,
    status                   TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS drivers (
    driver_id           TEXT PRIMARY KEY,
    full_name           TEXT NOT NULL,
    license_number      TEXT NOT NULL UNIQUE,
    license_categories  TEXT NOT NULL,
    license_valid_until DATE NOT NULL,
    hired_at            DATE NOT NULL,
    status              TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS fuel_norms (
    fuel_type   TEXT NOT NULL,
    season      TEXT NOT NULL,
    coefficient DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (fuel_type, season)
);
CREATE TABLE IF NOT EXISTS waybills (
    waybill_id        TEXT PRIMARY KEY,
    vehicle_id        TEXT NOT NULL REFERENCES vehicles (vehicle_id),
    driver_id         TEXT NOT NULL REFERENCES drivers (driver_id),
    departure_at      TIMESTAMP NOT NULL,
    return_at         TIMESTAMP NOT NULL,
    odometer_start    INTEGER NOT NULL,
    odometer_end      INTEGER NOT NULL,
    route_description TEXT
);
CREATE INDEX IF NOT EXISTS ix_waybills_vehicle ON waybills (vehicle_id, departure_at);
CREATE INDEX IF NOT EXISTS ix_waybills_driver ON waybills (driver_id, departure_at);
CREATE INDEX IF NOT EXISTS ix_waybills_departure ON waybills (departure_at, waybill_id);
CREATE TABLE IF NOT EXISTS fuel_records (
    record_id       TEXT PRIMARY KEY,
    vehicle_id      TEXT NOT NULL REFERENCES vehicles (vehicle_id),
    driver_id       TEXT REFERENCES drivers (driver_id),
    filled_at       TIMESTAMP NOT NULL,
    liters          DOUBLE PRECISION NOT NULL,
    price_per_liter DOUBLE PRECISION NOT NULL,
    station         TEXT
);
CREATE TABLE IF NOT EXISTS maintenance (
    maintenance_id TEXT PRIMARY KEY,
    vehicle_id     TEXT NOT NULL REFERENCES vehicles (vehicle_id),
    type           TEXT NOT NULL,
    started_at     TIMESTAMP NOT NULL,
    finished_at    TIMESTAMP,
    odometer_km    INTEGER NOT NULL,
    cost           DOUBLE PRECISION NOT NULL,
    description    TEXT
);
"""

private const val TIMEOUT_MS = 5_000

/** Схема базы, проверка, что база отвечает, и счётчики записей для /health. */
@Component
class Db(private val jdbc: JdbcTemplate, private val dataSource: DataSource) {

    @PostConstruct
    fun migrate() {
        jdbc.execute(SCHEMA) // схема создаётся при старте и переживает перезапуск сервиса
    }

    fun ping(): Boolean =
        try {
            dataSource.connection.use { conn ->
                conn.setNetworkTimeout(Executor { it.run() }, TIMEOUT_MS) // зависшая база — тоже «нет»
                conn.createStatement().use { it.execute("SELECT 1") }
            }
            true
        } catch (e: SQLException) {
            false
        }

    /** Число записей по каждой из шести сущностей, в порядке контракта. */
    fun recordCounts(): Map<String, Int> {
        val sql = TABLES.joinToString(", ") { "(SELECT COUNT(*) FROM $it)" }
        return jdbc.queryForObject("SELECT $sql") { rs, _ ->
            TABLES.withIndex().associate { (i, table) -> table to rs.getInt(i + 1) }
        }!!
    }
}
