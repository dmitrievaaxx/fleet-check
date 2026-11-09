package ru.x5.practice.fleet;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Схема базы, число записей для /health и проверка, что база отвечает. */
@Component
public class Db {

    /** Таблицы шести наборов данных в том порядке, в котором их показывает /health. */
    public static final List<String> TABLES =
            List.of("vehicles", "drivers", "fuel_norms", "waybills", "fuel_records", "maintenance");

    static final String SCHEMA = """
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
            CREATE INDEX IF NOT EXISTS ix_waybills_departure ON waybills (departure_at, waybill_id);
            CREATE INDEX IF NOT EXISTS ix_waybills_vehicle ON waybills (vehicle_id, departure_at);
            CREATE INDEX IF NOT EXISTS ix_waybills_driver ON waybills (driver_id, departure_at);
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
            """;

    private static final int TIMEOUT_MS = 5_000;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public Db(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void migrate() {
        jdbc.execute(SCHEMA); // схема создаётся при старте и переживает перезапуск сервиса
    }

    /** Число записей по каждой из шести сущностей; при пустой базе — нули. */
    public Map<String, Integer> recordCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return counts;
    }

    public boolean ping() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setNetworkTimeout(Runnable::run, TIMEOUT_MS); // зависшая база — тоже «нет»
            try (Statement statement = conn.createStatement()) {
                statement.execute("SELECT 1");
            }
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
