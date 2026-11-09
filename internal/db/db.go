// Package db — подключение к PostgreSQL по DATABASE_URL, схема и запросы сервиса.
//
// На проверке платформа сама поднимает базу и передаёт её адрес; локально его задаёт compose.yaml.
package db

import (
	"context"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Идентификаторы и времена хранятся текстом с побайтовой сортировкой (COLLATE "C"): порядок
// строк в контракте — по кодовым точкам, без учёта локали базы.
const schema = `
CREATE TABLE IF NOT EXISTS vehicles (
    vehicle_id               TEXT COLLATE "C" PRIMARY KEY,
    plate                    TEXT COLLATE "C" NOT NULL UNIQUE,
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
    driver_id           TEXT COLLATE "C" PRIMARY KEY,
    full_name           TEXT NOT NULL,
    license_number      TEXT COLLATE "C" NOT NULL UNIQUE,
    license_categories  TEXT NOT NULL,
    license_valid_until TEXT COLLATE "C" NOT NULL,
    hired_at            TEXT COLLATE "C" NOT NULL,
    status              TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS fuel_norms (
    fuel_type   TEXT COLLATE "C" NOT NULL,
    season      TEXT COLLATE "C" NOT NULL,
    coefficient DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (fuel_type, season)
);
CREATE TABLE IF NOT EXISTS waybills (
    waybill_id        TEXT COLLATE "C" PRIMARY KEY,
    vehicle_id        TEXT COLLATE "C" NOT NULL,
    driver_id         TEXT COLLATE "C" NOT NULL,
    departure_at      TEXT COLLATE "C" NOT NULL,
    return_at         TEXT COLLATE "C" NOT NULL,
    odometer_start    INTEGER NOT NULL,
    odometer_end      INTEGER NOT NULL,
    route_description TEXT
);
CREATE INDEX IF NOT EXISTS ix_waybills_departure ON waybills (departure_at, waybill_id);
CREATE INDEX IF NOT EXISTS ix_waybills_vehicle ON waybills (vehicle_id, departure_at);
CREATE INDEX IF NOT EXISTS ix_waybills_driver ON waybills (driver_id, departure_at);
CREATE TABLE IF NOT EXISTS fuel_records (
    seq             BIGSERIAL UNIQUE,
    record_id       TEXT COLLATE "C" PRIMARY KEY,
    vehicle_id      TEXT COLLATE "C" NOT NULL,
    driver_id       TEXT COLLATE "C",
    filled_at       TEXT COLLATE "C" NOT NULL,
    liters          DOUBLE PRECISION NOT NULL,
    price_per_liter DOUBLE PRECISION NOT NULL,
    station         TEXT
);
CREATE TABLE IF NOT EXISTS maintenance (
    maintenance_id TEXT COLLATE "C" PRIMARY KEY,
    vehicle_id     TEXT COLLATE "C" NOT NULL,
    type           TEXT NOT NULL,
    started_at     TEXT COLLATE "C" NOT NULL,
    finished_at    TEXT COLLATE "C",
    odometer_km    INTEGER NOT NULL,
    cost           DOUBLE PRECISION NOT NULL,
    description    TEXT
);
`

// Tables — таблицы наборов в порядке /health.
var Tables = []string{"vehicles", "drivers", "fuel_norms", "waybills", "fuel_records", "maintenance"}

const timeout = 5 * time.Second

// URL — адрес базы из DATABASE_URL, без неё — локальная база из compose.yaml.
func URL() string {
	if url := os.Getenv("DATABASE_URL"); url != "" {
		return url
	}
	return "postgresql://app:app@localhost:5432/app"
}

// Connect открывает пул соединений и проверяет, что база отвечает.
func Connect(ctx context.Context) (*pgxpool.Pool, error) {
	config, err := pgxpool.ParseConfig(URL())
	if err != nil {
		return nil, err
	}
	config.ConnConfig.ConnectTimeout = timeout
	config.MaxConns = 8
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return pool, nil
}

// Migrate создаёт схему; повторный запуск ничего не меняет.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, schema)
	return err
}

// Reset очищает все таблицы (для тестов).
func Reset(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, "TRUNCATE vehicles, drivers, fuel_norms, waybills, fuel_records, maintenance RESTART IDENTITY")
	return err
}
