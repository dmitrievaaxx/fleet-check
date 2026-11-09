// Package domain — снимок базы в памяти и общие вычисления business-rules.md, раздел 1.
//
// Даты и времена хранятся строками фиксированной длины (YYYY-MM-DD, YYYY-MM-DDTHH:MM:SS):
// их строковое сравнение совпадает с хронологическим.
package domain

import (
	"sort"
	"strconv"

	"fleet/internal/jsonx"
)

// Константы business-rules.md, 1.7.
const (
	FuelOverrunThreshold   = 0.10
	DueSoonFraction        = 0.10
	OdometerGapToleranceKm = 5
)

// Vehicle — транспортное средство.
type Vehicle struct {
	ID              string
	Plate           string
	Type            string
	FuelType        string
	BaseConsumption float64
	Odometer        int
	LastService     *int
	ServiceInterval int
	LicenseCategory string
	Status          string
}

// Driver — водитель.
type Driver struct {
	ID                string
	FullName          string
	Categories        []string
	LicenseValidUntil string
	Status            string
}

// Waybill — путевой лист; интервал [Departure, Return).
type Waybill struct {
	ID        string
	VehicleID string
	DriverID  string
	Departure string
	Return    string
	OdoStart  int
	OdoEnd    int
	Year      int
	Month     int
}

// Mileage — пробег листа.
func (w *Waybill) Mileage() int { return w.OdoEnd - w.OdoStart }

// Fuel — заправка.
type Fuel struct {
	VehicleID string
	FilledAt  string
	Liters    float64
	Year      int
	Month     int
}

// Maintenance — ТО или ремонт; Finished == nil — интервал открыт вправо.
type Maintenance struct {
	ID        string
	VehicleID string
	Type      string
	Started   string
	Finished  *string
	Odometer  int
}

// Domain — всё, что нужно правилам и отчётам.
type Domain struct {
	Vehicles       map[string]*Vehicle
	VehicleIDs     []string
	Drivers        map[string]*Driver
	Norms          map[[2]string]float64
	Waybills       []*Waybill // по (departure_at, waybill_id)
	Fuel           []*Fuel    // в порядке загрузки
	Maintenance    []*Maintenance
	ByVehicle      map[string][]*Waybill
	ByDriver       map[string][]*Waybill
	MaintByVehicle map[string][]*Maintenance // по (started_at, maintenance_id)
}

// YearMonth — год и месяц из начала строки даты.
func YearMonth(s string) (int, int) {
	if len(s) < 7 {
		return 0, 0
	}
	y, _ := strconv.Atoi(s[0:4])
	m, _ := strconv.Atoi(s[5:7])
	return y, m
}

// Build раскладывает загруженные записи по индексам.
func Build(vehicles []*Vehicle, drivers []*Driver, norms map[[2]string]float64, waybills []*Waybill,
	fuel []*Fuel, maintenance []*Maintenance) *Domain {
	d := &Domain{
		Vehicles: map[string]*Vehicle{}, Drivers: map[string]*Driver{}, Norms: norms,
		Waybills: waybills, Fuel: fuel, Maintenance: maintenance,
		ByVehicle: map[string][]*Waybill{}, ByDriver: map[string][]*Waybill{},
		MaintByVehicle: map[string][]*Maintenance{},
	}
	if d.Norms == nil {
		d.Norms = map[[2]string]float64{}
	}
	for _, v := range vehicles {
		d.Vehicles[v.ID] = v
		d.VehicleIDs = append(d.VehicleIDs, v.ID)
	}
	sort.Strings(d.VehicleIDs)
	for _, dr := range drivers {
		d.Drivers[dr.ID] = dr
	}
	sort.SliceStable(d.Waybills, func(i, j int) bool { return WaybillLess(d.Waybills[i], d.Waybills[j]) })
	for _, w := range d.Waybills {
		w.Year, w.Month = YearMonth(w.Departure)
		d.ByVehicle[w.VehicleID] = append(d.ByVehicle[w.VehicleID], w)
		d.ByDriver[w.DriverID] = append(d.ByDriver[w.DriverID], w)
	}
	for _, f := range d.Fuel {
		f.Year, f.Month = YearMonth(f.FilledAt)
	}
	sort.SliceStable(d.Maintenance, func(i, j int) bool { return MaintenanceLess(d.Maintenance[i], d.Maintenance[j]) })
	for _, m := range d.Maintenance {
		d.MaintByVehicle[m.VehicleID] = append(d.MaintByVehicle[m.VehicleID], m)
	}
	return d
}

// WaybillLess — порядок 1.4: departure_at, затем waybill_id.
func WaybillLess(a, b *Waybill) bool {
	if a.Departure != b.Departure {
		return a.Departure < b.Departure
	}
	return a.ID < b.ID
}

// MaintenanceLess — started_at, затем maintenance_id.
func MaintenanceLess(a, b *Maintenance) bool {
	if a.Started != b.Started {
		return a.Started < b.Started
	}
	return a.ID < b.ID
}

// Overlaps — пересечение полуоткрытых интервалов (1.3); nil — открытый правый конец.
func Overlaps(aStart string, aEnd *string, bStart string, bEnd *string) bool {
	left := bEnd == nil || aStart < *bEnd
	right := aEnd == nil || bStart < *aEnd
	return left && right
}

// Season — сезон по дате выезда (data-contract.md, 1.4).
func Season(departure string) string {
	_, m := YearMonth(departure)
	switch m {
	case 11, 12, 1, 2, 3:
		return "winter"
	}
	return "summer"
}

// ServiceOdometer — пробег на последнем плановом ТО к моменту at (1.5) и запись ТО, если
// значение взято из неё.
func (d *Domain) ServiceOdometer(vehicleID, at string) (int, *Maintenance) {
	var found *Maintenance
	for _, m := range d.MaintByVehicle[vehicleID] {
		if m.Type == "scheduled" && m.Started <= at {
			found = m // список упорядочен по (started_at, maintenance_id): последний — наибольший
		}
	}
	if found != nil {
		return found.Odometer, found
	}
	v := d.Vehicles[vehicleID]
	if v.LastService != nil {
		return *v.LastService, nil
	}
	return v.Odometer, nil
}

// MissingNorms — сочетания топлива и сезона, нужные листам и отсутствующие в справочнике.
func (d *Domain) MissingNorms(waybills []*Waybill) []jsonx.Obj {
	needed := map[[2]string]bool{}
	for _, w := range waybills {
		needed[[2]string{d.Vehicles[w.VehicleID].FuelType, Season(w.Departure)}] = true
	}
	keys := make([][2]string, 0, len(needed))
	for k := range needed {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i][0] != keys[j][0] {
			return keys[i][0] < keys[j][0]
		}
		return keys[i][1] < keys[j][1]
	})
	missing := []jsonx.Obj{}
	for _, k := range keys {
		if _, ok := d.Norms[k]; !ok {
			missing = append(missing, jsonx.O("fuel_type", k[0], "season", k[1]))
		}
	}
	return missing
}

// NormLiters — норма листа (1.6), без округления.
func (d *Domain) NormLiters(w *Waybill) float64 {
	v := d.Vehicles[w.VehicleID]
	coef := d.Norms[[2]string{v.FuelType, Season(w.Departure)}]
	return float64(w.Mileage()) * v.BaseConsumption * coef / 100
}
