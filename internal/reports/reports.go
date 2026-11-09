// Package reports — пять отчётов reports-contract.md по величинам business-rules.md, раздел 3.
package reports

import (
	"fmt"
	"sort"
	"time"

	"fleet/internal/apperr"
	"fleet/internal/domain"
	"fleet/internal/jsonx"
	"fleet/internal/rules"
)

// Колонки отчётов в порядке контракта.
var (
	FuelColumns = []string{"vehicle_id", "plate", "fuel_type", "mileage_km", "norm_liters", "actual_liters",
		"deviation_liters", "deviation_pct", "overrun"}
	MaintenanceDueColumns = []string{"vehicle_id", "plate", "current_odometer_km", "service_odometer_km",
		"last_service_at", "service_interval_km", "km_since_service", "km_remaining", "status"}
	UtilizationColumns = []string{"vehicle_id", "plate", "type", "days_in_month", "days_on_line",
		"days_in_maintenance", "days_idle", "utilization"}
	DriversColumns = []string{"driver_id", "full_name", "trips", "mileage_km", "violations"}
)

func sortedKeys[T any](m map[string]T) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// Fuel — расход топлива по ТС за месяц (3.1).
func Fuel(d *domain.Domain, year, month int) ([]jsonx.Obj, *apperr.Error) {
	var trips []*domain.Waybill
	for _, w := range d.Waybills {
		if w.Year == year && w.Month == month {
			trips = append(trips, w)
		}
	}
	if missing := d.MissingNorms(trips); len(missing) > 0 {
		return nil, apperr.FuelNormMissing(missing)
	}
	norm := map[string]float64{}
	mileage := map[string]int{}
	actual := map[string]float64{}
	seen := map[string]bool{}
	for _, w := range trips {
		norm[w.VehicleID] += d.NormLiters(w)
		mileage[w.VehicleID] += w.Mileage()
		seen[w.VehicleID] = true
	}
	for _, f := range d.Fuel {
		if f.Year == year && f.Month == month {
			actual[f.VehicleID] += f.Liters
			seen[f.VehicleID] = true
		}
	}
	threshold := 1 + domain.FuelOverrunThreshold
	rows := []jsonx.Obj{}
	for _, vid := range sortedKeys(seen) {
		v := d.Vehicles[vid]
		n, a := norm[vid], actual[vid]
		var pct any
		if n != 0 {
			pct = jsonx.Round((a-n)/n*100, 1)
		}
		rows = append(rows, jsonx.O(
			"vehicle_id", vid, "plate", v.Plate, "fuel_type", v.FuelType, "mileage_km", mileage[vid],
			"norm_liters", jsonx.Round(n, 2), "actual_liters", jsonx.Round(a, 2),
			"deviation_liters", jsonx.Round(a-n, 2), "deviation_pct", pct, "overrun", a > n*threshold,
		))
	}
	return rows, nil
}

// MaintenanceDue — состояние ТО активных ТС на дату (3.2); asOf — YYYY-MM-DD.
func MaintenanceDue(d *domain.Domain, asOf string) []jsonx.Obj {
	asOfTS := asOf + "T23:59:59"
	type row struct {
		obj       jsonx.Obj
		remaining int
		vid       string
	}
	var out []row
	for _, vid := range d.VehicleIDs {
		v := d.Vehicles[vid]
		if v.Status != "active" {
			continue
		}
		current := v.Odometer
		var last *domain.Waybill
		for _, w := range d.ByVehicle[vid] {
			if w.Return <= asOfTS && (last == nil || domain.WaybillLess(last, w)) {
				last = w
			}
		}
		if last != nil {
			current = last.OdoEnd
		}
		svc, m := d.ServiceOdometer(vid, asOfTS)
		if svc > current {
			current = svc
		}
		since := current - svc
		remaining := v.ServiceInterval - since
		status := "ok"
		if remaining < 0 {
			status = "overdue"
		} else if float64(remaining) <= float64(v.ServiceInterval)*domain.DueSoonFraction {
			status = "due_soon"
		}
		var lastServiceAt any
		if m != nil {
			lastServiceAt = m.Started
		}
		out = append(out, row{remaining: remaining, vid: vid, obj: jsonx.O(
			"vehicle_id", vid, "plate", v.Plate, "current_odometer_km", current, "service_odometer_km", svc,
			"last_service_at", lastServiceAt, "service_interval_km", v.ServiceInterval,
			"km_since_service", since, "km_remaining", remaining, "status", status,
		)})
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].remaining != out[j].remaining {
			return out[i].remaining < out[j].remaining
		}
		return out[i].vid < out[j].vid
	})
	rows := make([]jsonx.Obj, 0, len(out))
	for _, r := range out {
		rows = append(rows, r.obj)
	}
	return rows
}

// Violations — отчёт о нарушениях с фильтрами по типу и календарной дате occurred_at.
func Violations(d *domain.Domain, vtype, from, to string) ([]jsonx.Obj, *apperr.Error) {
	list, err := rules.Report(d, vtype)
	if err != nil {
		return nil, err
	}
	rows := []jsonx.Obj{}
	for _, v := range list {
		day := v.OccurredAt[:10]
		if (from != "" && day < from) || (to != "" && day > to) {
			continue
		}
		rows = append(rows, v.Obj())
	}
	return rows, nil
}

const layout = "2006-01-02T15:04:05"

// Utilization — использование парка за месяц (3.3).
func Utilization(d *domain.Domain, year, month int) []jsonx.Obj {
	first := time.Date(year, time.Month(month), 1, 0, 0, 0, 0, time.UTC)
	days := first.AddDate(0, 1, -1).Day()
	starts := make([]string, days+1)
	for i := 0; i <= days; i++ {
		starts[i] = first.AddDate(0, 0, i).Format(layout)
	}
	rows := []jsonx.Obj{}
	for _, vid := range d.VehicleIDs {
		v := d.Vehicles[vid]
		if v.Status != "active" {
			continue
		}
		onLine, inMaint, busy := 0, 0, 0
		for i := 0; i < days; i++ {
			day, next := starts[i], starts[i+1]
			line, maint := false, false
			for _, w := range d.ByVehicle[vid] {
				if domain.Overlaps(day, &next, w.Departure, &w.Return) {
					line = true
					break
				}
			}
			for _, m := range d.MaintByVehicle[vid] {
				if domain.Overlaps(day, &next, m.Started, m.Finished) {
					maint = true
					break
				}
			}
			if line {
				onLine++
			}
			if maint {
				inMaint++
			}
			if line || maint {
				busy++
			}
		}
		rows = append(rows, jsonx.O(
			"vehicle_id", vid, "plate", v.Plate, "type", v.Type, "days_in_month", days,
			"days_on_line", onLine, "days_in_maintenance", inMaint, "days_idle", days-busy,
			"utilization", jsonx.Round(float64(onLine)/float64(days), 3),
		))
	}
	return rows
}

var driverViolationTypes = map[string]bool{"DRIVER_OVERLAP": true, "LICENSE_CATEGORY_MISMATCH": true, "LICENSE_EXPIRED": true}

// Drivers — сводка по водителям за месяц (3.4).
func Drivers(d *domain.Domain, year, month int) []jsonx.Obj {
	trips := map[string]int{}
	mileage := map[string]int{}
	seen := map[string]bool{}
	for _, w := range d.Waybills {
		if w.Year == year && w.Month == month {
			trips[w.DriverID]++
			mileage[w.DriverID] += w.Mileage()
			seen[w.DriverID] = true
		}
	}
	counts := map[string]int{}
	prefix := fmt.Sprintf("%04d-%02d", year, month)
	for _, v := range rules.WaybillViolations(d) {
		if driverViolationTypes[v.Type] && v.OccurredAt[:7] == prefix && v.DriverID != nil {
			counts[*v.DriverID]++
			seen[*v.DriverID] = true
		}
	}
	rows := []jsonx.Obj{}
	for _, did := range sortedKeys(seen) {
		dr := d.Drivers[did]
		rows = append(rows, jsonx.O("driver_id", did, "full_name", dr.FullName, "trips", trips[did],
			"mileage_km", mileage[did], "violations", counts[did]))
	}
	return rows
}
