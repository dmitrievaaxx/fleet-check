package importer

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// Field — колонка набора: обязательность, разбор формата и проверка допустимых значений
// (data-contract.md, 2). Parse == nil — значение остаётся строкой.
type Field struct {
	Name     string
	Required bool
	Parse    func(string) (any, bool)
	Check    func(any) bool
}

// Ref — ссылочное поле: значение должно быть первичным ключом таблицы Table.
type Ref struct {
	Field string
	Table string
}

// Spec — описание одного набора данных.
type Spec struct {
	Name    string
	Columns []Field
	PK      []string
	Unique  []string
	Refs    []Ref
	// Cross — межполевые правила: имя поля с ошибкой или "".
	Cross func(map[string]any) string
}

// ColumnNames — имена колонок в порядке таблицы.
func (s *Spec) ColumnNames() []string {
	names := make([]string, len(s.Columns))
	for i, c := range s.Columns {
		names[i] = c.Name
	}
	return names
}

func (s *Spec) has(name string) bool {
	for _, c := range s.Columns {
		if c.Name == name {
			return true
		}
	}
	return false
}

// RequiredNames — обязательные колонки в порядке таблицы.
func (s *Spec) RequiredNames() []string {
	var names []string
	for _, c := range s.Columns {
		if c.Required {
			names = append(names, c.Name)
		}
	}
	return names
}

// Цифры только ASCII (data-contract.md, 1.2): в шаблонах [0-9], а не \d.
var (
	intRe      = regexp.MustCompile(`^[0-9]{1,9}$`)
	dateRe     = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}$`)
	datetimeRe = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$`)
)

const (
	vehicleIDPattern     = `^V-[0-9]{3}$`
	driverIDPattern      = `^D-[0-9]{3}$`
	waybillIDPattern     = `^WB-[0-9]{6}$`
	recordIDPattern      = `^F-[0-9]{6}$`
	maintenanceIDPattern = `^M-[0-9]{5}$`
	platePattern         = `^[АВЕКМНОРСТУХ][0-9]{3}[АВЕКМНОРСТУХ]{2}[0-9]{2,3}$`
	licensePattern       = `^[0-9]{2} [0-9]{2} [0-9]{6}$`
)

func pInt(s string) (any, bool) {
	if !intRe.MatchString(s) {
		return nil, false
	}
	n, err := strconv.Atoi(s)
	return n, err == nil
}

func pDecimal(places int) func(string) (any, bool) {
	re := regexp.MustCompile(fmt.Sprintf(`^[0-9]{1,9}(\.[0-9]{1,%d})?$`, places))
	return func(s string) (any, bool) {
		if !re.MatchString(s) {
			return nil, false
		}
		f, err := strconv.ParseFloat(s, 64)
		return f, err == nil
	}
}

// pDate — календарно корректная дата YYYY-MM-DD (года 0000 в календаре нет).
func pDate(s string) (any, bool) {
	if !dateRe.MatchString(s) {
		return nil, false
	}
	t, err := time.Parse("2006-01-02", s)
	if err != nil || t.Year() < 1 {
		return nil, false
	}
	return s, true
}

// pDatetime — YYYY-MM-DDTHH:MM:SS без пояса; строка хранится как есть: фиксированная длина
// делает строковое сравнение хронологическим.
func pDatetime(s string) (any, bool) {
	if !datetimeRe.MatchString(s) {
		return nil, false
	}
	t, err := time.Parse("2006-01-02T15:04:05", s)
	if err != nil || t.Year() < 1 {
		return nil, false
	}
	return s, true
}

func pPattern(pattern string) func(string) (any, bool) {
	re := regexp.MustCompile(pattern)
	return func(s string) (any, bool) {
		if !re.MatchString(s) {
			return nil, false
		}
		return s, true
	}
}

// pStr — строка не длиннее maxLen кодовых точек. Символ NUL PostgreSQL в text не хранит,
// поэтому такая строка тоже считается неверным форматом, а не роняет импорт.
func pStr(maxLen int) func(string) (any, bool) {
	return func(s string) (any, bool) {
		if utf8.RuneCountInString(s) > maxLen || strings.IndexByte(s, 0) >= 0 {
			return nil, false
		}
		return s, true
	}
}

// pCategoryList — структура списка: непустые элементы через ";" без пробельных символов и
// без повторов (INVALID_FORMAT); принадлежность перечислению проверяет categoriesKnown.
func pCategoryList(s string) (any, bool) {
	parts := strings.Split(s, ";")
	seen := make(map[string]bool, len(parts))
	for _, p := range parts {
		if p == "" || strings.IndexFunc(p, unicode.IsSpace) >= 0 || seen[p] {
			return nil, false
		}
		seen[p] = true
	}
	return parts, true
}

func categoriesKnown(v any) bool {
	parts, ok := v.([]string)
	if !ok {
		return false
	}
	for _, p := range parts {
		if p != "B" && p != "C" && p != "D" {
			return false
		}
	}
	return true
}

func inEnum(values ...string) func(any) bool {
	return func(v any) bool {
		s, ok := v.(string)
		if !ok {
			return false
		}
		for _, x := range values {
			if s == x {
				return true
			}
		}
		return false
	}
}

func inRange(lo, hi float64) func(any) bool {
	return func(v any) bool {
		switch x := v.(type) {
		case int:
			return lo <= float64(x) && float64(x) <= hi
		case float64:
			return lo <= x && x <= hi
		}
		return false
	}
}

func req(name string, parse func(string) (any, bool), check func(any) bool) Field {
	return Field{Name: name, Required: true, Parse: parse, Check: check}
}

func opt(name string, parse func(string) (any, bool), check func(any) bool) Field {
	return Field{Name: name, Required: false, Parse: parse, Check: check}
}

func crossVehicle(r map[string]any) string {
	last, ok := r["last_service_odometer_km"].(int)
	if ok && last > r["odometer_km"].(int) {
		return "last_service_odometer_km"
	}
	return ""
}

func crossWaybill(r map[string]any) string {
	if r["return_at"].(string) <= r["departure_at"].(string) {
		return "return_at"
	}
	if r["odometer_end"].(int) < r["odometer_start"].(int) {
		return "odometer_end"
	}
	return ""
}

func crossMaintenance(r map[string]any) string {
	finished, ok := r["finished_at"].(string)
	if ok && finished < r["started_at"].(string) {
		return "finished_at"
	}
	return ""
}

// Order — наборы в рекомендуемом порядке импорта; он же порядок счётчиков в /health.
var Order = []string{"vehicles", "drivers", "fuel_norms", "waybills", "fuel_records", "maintenance"}

// Specs — шесть наборов data-contract.md, 2.
var Specs = map[string]*Spec{
	"vehicles": {
		Name: "vehicles",
		Columns: []Field{
			req("vehicle_id", pPattern(vehicleIDPattern), nil),
			req("plate", pPattern(platePattern), nil),
			req("brand", pStr(50), nil),
			req("model", pStr(50), nil),
			req("type", nil, inEnum("truck", "bus", "car")),
			req("year", pInt, inRange(1990, 2026)),
			req("fuel_type", nil, inEnum("diesel", "petrol", "gas")),
			req("base_consumption_l_100km", pDecimal(1), inRange(3.0, 60.0)),
			req("odometer_km", pInt, inRange(0, 2_000_000)),
			opt("last_service_odometer_km", pInt, inRange(0, 2_000_000)),
			req("service_interval_km", pInt, inRange(5_000, 50_000)),
			req("license_category", nil, inEnum("B", "C", "D")),
			req("status", nil, inEnum("active", "decommissioned")),
		},
		PK:     []string{"vehicle_id"},
		Unique: []string{"plate"},
		Cross:  crossVehicle,
	},
	"drivers": {
		Name: "drivers",
		Columns: []Field{
			req("driver_id", pPattern(driverIDPattern), nil),
			req("full_name", pStr(100), nil),
			req("license_number", pPattern(licensePattern), nil),
			req("license_categories", pCategoryList, categoriesKnown),
			req("license_valid_until", pDate, nil),
			req("hired_at", pDate, nil),
			req("status", nil, inEnum("active", "dismissed")),
		},
		PK:     []string{"driver_id"},
		Unique: []string{"license_number"},
	},
	"fuel_norms": {
		Name: "fuel_norms",
		Columns: []Field{
			req("fuel_type", nil, inEnum("diesel", "petrol", "gas")),
			req("season", nil, inEnum("winter", "summer")),
			req("coefficient", pDecimal(2), inRange(0.5, 2.0)),
		},
		PK: []string{"fuel_type", "season"},
	},
	"waybills": {
		Name: "waybills",
		Columns: []Field{
			req("waybill_id", pPattern(waybillIDPattern), nil),
			req("vehicle_id", pPattern(vehicleIDPattern), nil),
			req("driver_id", pPattern(driverIDPattern), nil),
			req("departure_at", pDatetime, nil),
			req("return_at", pDatetime, nil),
			req("odometer_start", pInt, inRange(0, 2_000_000)),
			req("odometer_end", pInt, inRange(0, 2_000_000)),
			opt("route_description", pStr(200), nil),
		},
		PK:    []string{"waybill_id"},
		Refs:  []Ref{{Field: "vehicle_id", Table: "vehicles"}, {Field: "driver_id", Table: "drivers"}},
		Cross: crossWaybill,
	},
	"fuel_records": {
		Name: "fuel_records",
		Columns: []Field{
			req("record_id", pPattern(recordIDPattern), nil),
			req("vehicle_id", pPattern(vehicleIDPattern), nil),
			opt("driver_id", pPattern(driverIDPattern), nil),
			req("filled_at", pDatetime, nil),
			req("liters", pDecimal(2), inRange(0.01, 1000.0)),
			req("price_per_liter", pDecimal(2), inRange(0.01, 500.0)),
			opt("station", pStr(100), nil),
		},
		PK:   []string{"record_id"},
		Refs: []Ref{{Field: "vehicle_id", Table: "vehicles"}, {Field: "driver_id", Table: "drivers"}},
	},
	"maintenance": {
		Name: "maintenance",
		Columns: []Field{
			req("maintenance_id", pPattern(maintenanceIDPattern), nil),
			req("vehicle_id", pPattern(vehicleIDPattern), nil),
			req("type", nil, inEnum("scheduled", "repair")),
			req("started_at", pDatetime, nil),
			opt("finished_at", pDatetime, nil),
			req("odometer_km", pInt, inRange(0, 2_000_000)),
			req("cost", pDecimal(2), inRange(0, 10_000_000)),
			opt("description", pStr(200), nil),
		},
		PK:    []string{"maintenance_id"},
		Refs:  []Ref{{Field: "vehicle_id", Table: "vehicles"}},
		Cross: crossMaintenance,
	},
}

// RefColumn — столбец первичного ключа таблицы, на которую ссылаются.
var RefColumn = map[string]string{"vehicles": "vehicle_id", "drivers": "driver_id"}
