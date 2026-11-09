package db

import (
	"context"
	"fmt"
	"strings"
	"sync"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"fleet/internal/domain"
	"fleet/internal/importer"
	"fleet/internal/jsonx"
)

// Store — запросы сервиса к базе и снимок данных для отчётов.
//
// Импорты идут по одному (importMu): ключи, уже лежащие в базе, читаются в начале импорта,
// проверка строк идёт в памяти, принятые строки пишутся одной командой COPY в той же
// транзакции. Снимок для отчётов строится заново после каждого импорта.
type Store struct {
	Pool *pgxpool.Pool

	importMu sync.Mutex

	cacheMu sync.Mutex
	version uint64
	cached  *domain.Domain
	cachedV uint64
}

// NewStore — хранилище поверх пула.
func NewStore(pool *pgxpool.Pool) *Store { return &Store{Pool: pool} }

// Records — число записей по наборам для /health.
func (s *Store) Records(ctx context.Context) (jsonx.Obj, error) {
	parts := make([]string, len(Tables))
	for i, t := range Tables {
		parts[i] = fmt.Sprintf("(SELECT count(*) FROM %s)", t)
	}
	counts := make([]int64, len(Tables))
	dest := make([]any, len(Tables))
	for i := range counts {
		dest[i] = &counts[i]
	}
	if err := s.Pool.QueryRow(ctx, "SELECT "+strings.Join(parts, ", ")).Scan(dest...); err != nil {
		return nil, err
	}
	out := jsonx.Obj{}
	for i, t := range Tables {
		out = append(out, jsonx.KV{Key: t, Value: counts[i]})
	}
	return out, nil
}

func strings1(ctx context.Context, tx pgx.Tx, query string) (map[string]bool, error) {
	rows, err := tx.Query(ctx, query)
	if err != nil {
		return nil, err
	}
	values, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return nil, err
	}
	set := make(map[string]bool, len(values))
	for _, v := range values {
		set[v] = true
	}
	return set, nil
}

func loadState(ctx context.Context, tx pgx.Tx, spec *importer.Spec) (*importer.State, error) {
	st := importer.NewState()
	pk := spec.PK[0]
	if len(spec.PK) > 1 {
		pk = strings.Join(spec.PK, " || chr(31) || ")
	}
	var err error
	if st.PK, err = strings1(ctx, tx, fmt.Sprintf("SELECT %s FROM %s", pk, spec.Name)); err != nil {
		return nil, err
	}
	for _, col := range spec.Unique {
		if st.Unique[col], err = strings1(ctx, tx, fmt.Sprintf("SELECT %s FROM %s", col, spec.Name)); err != nil {
			return nil, err
		}
	}
	for _, ref := range spec.Refs {
		if _, done := st.Refs[ref.Table]; done {
			continue
		}
		query := fmt.Sprintf("SELECT %s FROM %s", importer.RefColumn[ref.Table], ref.Table)
		if st.Refs[ref.Table], err = strings1(ctx, tx, query); err != nil {
			return nil, err
		}
	}
	return st, nil
}

// Import — строки файла, прошедшего проверки уровня файла, в базу.
func (s *Store) Import(ctx context.Context, spec *importer.Spec, file *importer.File) (importer.Result, error) {
	s.importMu.Lock()
	defer s.importMu.Unlock()
	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return importer.Result{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	st, err := loadState(ctx, tx, spec)
	if err != nil {
		return importer.Result{}, err
	}
	res, rows := importer.Process(spec, file, st)
	if len(rows) > 0 {
		if _, err := tx.CopyFrom(ctx, pgx.Identifier{spec.Name}, spec.ColumnNames(), pgx.CopyFromRows(rows)); err != nil {
			return importer.Result{}, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return importer.Result{}, err
	}
	if res.Accepted > 0 {
		s.cacheMu.Lock()
		s.version++
		s.cached = nil
		s.cacheMu.Unlock()
	}
	return res, nil
}

// Domain — снимок всех данных для отчётов (строится при первом запросе после импорта).
func (s *Store) Domain(ctx context.Context) (*domain.Domain, error) {
	s.cacheMu.Lock()
	if s.cached != nil && s.cachedV == s.version {
		d := s.cached
		s.cacheMu.Unlock()
		return d, nil
	}
	version := s.version
	s.cacheMu.Unlock()

	d, err := s.load(ctx)
	if err != nil {
		return nil, err
	}
	s.cacheMu.Lock()
	if s.version == version {
		s.cached, s.cachedV = d, version
	}
	s.cacheMu.Unlock()
	return d, nil
}

func (s *Store) load(ctx context.Context) (*domain.Domain, error) {
	tx, err := s.Pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	rows, _ := tx.Query(ctx, `SELECT vehicle_id, plate, type, fuel_type, base_consumption_l_100km, odometer_km,
		last_service_odometer_km, service_interval_km, license_category, status FROM vehicles`)
	vehicles, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (*domain.Vehicle, error) {
		v := &domain.Vehicle{}
		err := r.Scan(&v.ID, &v.Plate, &v.Type, &v.FuelType, &v.BaseConsumption, &v.Odometer,
			&v.LastService, &v.ServiceInterval, &v.LicenseCategory, &v.Status)
		return v, err
	})
	if err != nil {
		return nil, err
	}
	rows, _ = tx.Query(ctx, `SELECT driver_id, full_name, license_categories, license_valid_until, status FROM drivers`)
	drivers, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (*domain.Driver, error) {
		d := &domain.Driver{}
		var cats string
		err := r.Scan(&d.ID, &d.FullName, &cats, &d.LicenseValidUntil, &d.Status)
		d.Categories = strings.Split(cats, ";")
		return d, err
	})
	if err != nil {
		return nil, err
	}
	norms := map[[2]string]float64{}
	rows, _ = tx.Query(ctx, `SELECT fuel_type, season, coefficient FROM fuel_norms`)
	var ft, season string
	var coef float64
	if _, err := pgx.ForEachRow(rows, []any{&ft, &season, &coef}, func() error {
		norms[[2]string{ft, season}] = coef
		return nil
	}); err != nil {
		return nil, err
	}
	rows, _ = tx.Query(ctx, `SELECT waybill_id, vehicle_id, driver_id, departure_at, return_at, odometer_start,
		odometer_end FROM waybills`)
	waybills, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (*domain.Waybill, error) {
		w := &domain.Waybill{}
		err := r.Scan(&w.ID, &w.VehicleID, &w.DriverID, &w.Departure, &w.Return, &w.OdoStart, &w.OdoEnd)
		return w, err
	})
	if err != nil {
		return nil, err
	}
	rows, _ = tx.Query(ctx, `SELECT vehicle_id, filled_at, liters FROM fuel_records ORDER BY seq`)
	fuel, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (*domain.Fuel, error) {
		f := &domain.Fuel{}
		err := r.Scan(&f.VehicleID, &f.FilledAt, &f.Liters)
		return f, err
	})
	if err != nil {
		return nil, err
	}
	rows, _ = tx.Query(ctx, `SELECT maintenance_id, vehicle_id, type, started_at, finished_at, odometer_km FROM maintenance`)
	maintenance, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (*domain.Maintenance, error) {
		m := &domain.Maintenance{}
		err := r.Scan(&m.ID, &m.VehicleID, &m.Type, &m.Started, &m.Finished, &m.Odometer)
		return m, err
	})
	if err != nil {
		return nil, err
	}
	return domain.Build(vehicles, drivers, norms, waybills, fuel, maintenance), nil
}

// Page — страница списка.
type Page struct {
	Items []jsonx.Obj
	Total int64
}

// Filter — условие равенства или сравнения для списка.
type Filter struct {
	SQL   string // например "status = $%d"
	Value any
}

const vehicleColumns = `vehicle_id, plate, brand, model, type, year, fuel_type, base_consumption_l_100km,
	odometer_km, last_service_odometer_km, service_interval_km, license_category, status`

func scanVehicle(r pgx.CollectableRow) (jsonx.Obj, error) {
	var id, plate, brand, model, typ, fuelType, category, status string
	var year, odometer, interval int
	var base float64
	var last *int
	if err := r.Scan(&id, &plate, &brand, &model, &typ, &year, &fuelType, &base, &odometer, &last, &interval,
		&category, &status); err != nil {
		return nil, err
	}
	return jsonx.O("vehicle_id", id, "plate", plate, "brand", brand, "model", model, "type", typ, "year", year,
		"fuel_type", fuelType, "base_consumption_l_100km", base, "odometer_km", odometer,
		"last_service_odometer_km", last, "service_interval_km", interval, "license_category", category,
		"status", status), nil
}

const driverColumns = `driver_id, full_name, license_number, license_categories, license_valid_until, hired_at, status`

func scanDriver(r pgx.CollectableRow) (jsonx.Obj, error) {
	var id, name, number, cats, valid, hired, status string
	if err := r.Scan(&id, &name, &number, &cats, &valid, &hired, &status); err != nil {
		return nil, err
	}
	return jsonx.O("driver_id", id, "full_name", name, "license_number", number,
		"license_categories", strings.Split(cats, ";"), "license_valid_until", valid, "hired_at", hired,
		"status", status), nil
}

const waybillColumns = `waybill_id, vehicle_id, driver_id, departure_at, return_at, odometer_start, odometer_end,
	route_description`

func scanWaybill(r pgx.CollectableRow) (jsonx.Obj, error) {
	var id, vid, did, dep, ret string
	var start, end int
	var route *string
	if err := r.Scan(&id, &vid, &did, &dep, &ret, &start, &end, &route); err != nil {
		return nil, err
	}
	return jsonx.O("waybill_id", id, "vehicle_id", vid, "driver_id", did, "departure_at", dep, "return_at", ret,
		"odometer_start", start, "odometer_end", end, "route_description", route, "mileage_km", end-start), nil
}

func (s *Store) list(ctx context.Context, table, columns, order string, filters []Filter, limit, offset int64,
	scan pgx.RowToFunc[jsonx.Obj]) (Page, error) {
	var where []string
	var args []any
	for _, f := range filters {
		args = append(args, f.Value)
		where = append(where, fmt.Sprintf(f.SQL, len(args)))
	}
	clause := ""
	if len(where) > 0 {
		clause = " WHERE " + strings.Join(where, " AND ")
	}
	var page Page
	if err := s.Pool.QueryRow(ctx, "SELECT count(*) FROM "+table+clause, args...).Scan(&page.Total); err != nil {
		return page, err
	}
	query := fmt.Sprintf("SELECT %s FROM %s%s ORDER BY %s LIMIT $%d OFFSET $%d", columns, table, clause, order,
		len(args)+1, len(args)+2)
	rows, err := s.Pool.Query(ctx, query, append(args, limit, offset)...)
	if err != nil {
		return page, err
	}
	page.Items, err = pgx.CollectRows(rows, scan)
	if page.Items == nil {
		page.Items = []jsonx.Obj{}
	}
	return page, err
}

func (s *Store) card(ctx context.Context, table, columns, key, id string, scan pgx.RowToFunc[jsonx.Obj]) (jsonx.Obj, error) {
	rows, err := s.Pool.Query(ctx, fmt.Sprintf("SELECT %s FROM %s WHERE %s = $1", columns, table, key), id)
	if err != nil {
		return nil, err
	}
	items, err := pgx.CollectRows(rows, scan)
	if err != nil || len(items) == 0 {
		return nil, err
	}
	return items[0], nil
}

// Vehicles — список ТС по vehicle_id.
func (s *Store) Vehicles(ctx context.Context, filters []Filter, limit, offset int64) (Page, error) {
	return s.list(ctx, "vehicles", vehicleColumns, "vehicle_id", filters, limit, offset, scanVehicle)
}

// Vehicle — карточка ТС; nil — нет такого.
func (s *Store) Vehicle(ctx context.Context, id string) (jsonx.Obj, error) {
	return s.card(ctx, "vehicles", vehicleColumns, "vehicle_id", id, scanVehicle)
}

// Drivers — список водителей по driver_id.
func (s *Store) Drivers(ctx context.Context, filters []Filter, limit, offset int64) (Page, error) {
	return s.list(ctx, "drivers", driverColumns, "driver_id", filters, limit, offset, scanDriver)
}

// Driver — карточка водителя.
func (s *Store) Driver(ctx context.Context, id string) (jsonx.Obj, error) {
	return s.card(ctx, "drivers", driverColumns, "driver_id", id, scanDriver)
}

// Waybills — список путевых листов по (departure_at, waybill_id).
func (s *Store) Waybills(ctx context.Context, filters []Filter, limit, offset int64) (Page, error) {
	return s.list(ctx, "waybills", waybillColumns, "departure_at, waybill_id", filters, limit, offset, scanWaybill)
}

// Waybill — карточка путевого листа.
func (s *Store) Waybill(ctx context.Context, id string) (jsonx.Obj, error) {
	return s.card(ctx, "waybills", waybillColumns, "waybill_id", id, scanWaybill)
}
