// Package rules — девять типов нарушений business-rules.md, раздел 2.
package rules

import (
	"fmt"
	"sort"

	"fleet/internal/apperr"
	"fleet/internal/domain"
	"fleet/internal/jsonx"
)

// Types — типы нарушений в порядке контракта.
var Types = []string{
	"VEHICLE_OVERLAP", "DRIVER_OVERLAP", "LICENSE_CATEGORY_MISMATCH", "LICENSE_EXPIRED", "ODOMETER_GAP",
	"VEHICLE_DECOMMISSIONED", "VEHICLE_IN_MAINTENANCE", "MAINTENANCE_OVERDUE", "FUEL_OVERRUN",
}

// Columns — колонки отчёта о нарушениях.
var Columns = []string{"type", "occurred_at", "vehicle_id", "driver_id", "subject_type", "subject_id", "related_id", "details"}

// Violation — одно нарушение (reports-contract.md, 4).
type Violation struct {
	Type        string
	OccurredAt  string
	VehicleID   string
	DriverID    *string
	SubjectType string
	SubjectID   string
	RelatedID   *string
	Details     jsonx.Obj
}

// Obj — строка отчёта.
func (v Violation) Obj() jsonx.Obj {
	details := v.Details
	if details == nil {
		details = jsonx.Obj{}
	}
	return jsonx.O("type", v.Type, "occurred_at", v.OccurredAt, "vehicle_id", v.VehicleID,
		"driver_id", v.DriverID, "subject_type", v.SubjectType, "subject_id", v.SubjectID,
		"related_id", v.RelatedID, "details", details)
}

func str(s string) *string { return &s }

func onWaybill(vtype string, w *domain.Waybill, related *string, details jsonx.Obj) Violation {
	return Violation{Type: vtype, OccurredAt: w.Departure, VehicleID: w.VehicleID, DriverID: str(w.DriverID),
		SubjectType: "waybill", SubjectID: w.ID, RelatedID: related, Details: details}
}

func sortedKeys[T any](m map[string]T) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// overlaps: одна запись на пару пересекающихся листов группы; субъект — более поздний лист.
func overlaps(groups map[string][]*domain.Waybill, vtype string) []Violation {
	var out []Violation
	for _, key := range sortedKeys(groups) {
		list := groups[key]
		for j, b := range list {
			for _, a := range list[:j] {
				if domain.Overlaps(a.Departure, &a.Return, b.Departure, &b.Return) {
					out = append(out, onWaybill(vtype, b, str(a.ID),
						jsonx.O("related_departure_at", a.Departure, "related_return_at", a.Return)))
				}
			}
		}
	}
	return out
}

// WaybillViolations — все типы, кроме FUEL_OVERRUN.
func WaybillViolations(d *domain.Domain) []Violation {
	out := overlaps(d.ByVehicle, "VEHICLE_OVERLAP")
	out = append(out, overlaps(d.ByDriver, "DRIVER_OVERLAP")...)

	for _, w := range d.Waybills {
		v, dr := d.Vehicles[w.VehicleID], d.Drivers[w.DriverID]
		has := false
		for _, c := range dr.Categories {
			if c == v.LicenseCategory {
				has = true
				break
			}
		}
		if !has {
			cats := append([]string{}, dr.Categories...)
			out = append(out, onWaybill("LICENSE_CATEGORY_MISMATCH", w, nil,
				jsonx.O("required", v.LicenseCategory, "driver_categories", cats)))
		}
		if dr.LicenseValidUntil < w.Departure[:10] {
			out = append(out, onWaybill("LICENSE_EXPIRED", w, nil, jsonx.O("license_valid_until", dr.LicenseValidUntil)))
		}
		if v.Status == "decommissioned" {
			out = append(out, onWaybill("VEHICLE_DECOMMISSIONED", w, nil, jsonx.Obj{}))
		}
		// записи ТС упорядочены по (started_at, maintenance_id): первая пересекающаяся — наименьшая
		for _, m := range d.MaintByVehicle[v.ID] {
			if domain.Overlaps(w.Departure, &w.Return, m.Started, m.Finished) {
				out = append(out, onWaybill("VEHICLE_IN_MAINTENANCE", w, str(m.ID),
					jsonx.O("maintenance_type", m.Type, "started_at", m.Started, "finished_at", m.Finished)))
				break
			}
		}
	}

	for _, vid := range sortedKeys(d.ByVehicle) {
		v := d.Vehicles[vid]
		expected := v.Odometer
		var prev *domain.Waybill
		inEpisode, lastService, haveLast := false, 0, false
		for _, w := range d.ByVehicle[vid] {
			delta := w.OdoStart - expected
			if delta < 0 || delta > domain.OdometerGapToleranceKm {
				var related *string
				if prev != nil {
					related = str(prev.ID)
				}
				out = append(out, onWaybill("ODOMETER_GAP", w, related,
					jsonx.O("expected", expected, "actual", w.OdoStart, "delta", delta)))
			}
			expected, prev = w.OdoEnd, w

			svc, _ := d.ServiceOdometer(vid, w.Departure)
			if !haveLast || svc != lastService {
				inEpisode, lastService, haveLast = false, svc, true
			}
			kmSince := w.OdoEnd - svc
			if !inEpisode && kmSince > v.ServiceInterval {
				inEpisode = true
				out = append(out, onWaybill("MAINTENANCE_OVERDUE", w, nil,
					jsonx.O("service_odometer", svc, "odometer_end", w.OdoEnd,
						"km_since_service", kmSince, "service_interval_km", v.ServiceInterval)))
			}
		}
	}
	return out
}

// FuelOverruns — перерасход по парам (ТС, месяц); 422, если для какого-либо листа нет нормы.
func FuelOverruns(d *domain.Domain) ([]Violation, *apperr.Error) {
	if missing := d.MissingNorms(d.Waybills); len(missing) > 0 {
		return nil, apperr.FuelNormMissing(missing)
	}
	type key struct {
		vid         string
		year, month int
	}
	norm := map[key]float64{}
	mileage := map[key]int{}
	actual := map[key]float64{}
	seen := map[key]bool{}
	for _, w := range d.Waybills {
		k := key{w.VehicleID, w.Year, w.Month}
		norm[k] += d.NormLiters(w)
		mileage[k] += w.Mileage()
		seen[k] = true
	}
	for _, f := range d.Fuel {
		k := key{f.VehicleID, f.Year, f.Month}
		actual[k] += f.Liters
		seen[k] = true
	}
	keys := make([]key, 0, len(seen))
	for k := range seen {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		a, b := keys[i], keys[j]
		if a.vid != b.vid {
			return a.vid < b.vid
		}
		if a.year != b.year {
			return a.year < b.year
		}
		return a.month < b.month
	})
	threshold := 1 + domain.FuelOverrunThreshold
	var out []Violation
	for _, k := range keys {
		n, a := norm[k], actual[k]
		if a > n*threshold {
			var pct any
			if n != 0 {
				pct = jsonx.Round((a-n)/n*100, 1)
			}
			out = append(out, Violation{
				Type: "FUEL_OVERRUN", OccurredAt: fmt.Sprintf("%04d-%02d-01T00:00:00", k.year, k.month),
				VehicleID: k.vid, SubjectType: "vehicle_month", SubjectID: k.vid,
				Details: jsonx.O("year", k.year, "month", k.month, "mileage_km", mileage[k],
					"norm_liters", jsonx.Round(n, 2), "actual_liters", jsonx.Round(a, 2), "deviation_pct", pct),
			})
		}
	}
	return out, nil
}

// Report — нарушения, при vtype != "" — только этого типа; порядок: occurred_at, type,
// subject_id, related_id (null первым).
func Report(d *domain.Domain, vtype string) ([]Violation, *apperr.Error) {
	var rows []Violation
	if vtype != "FUEL_OVERRUN" {
		rows = WaybillViolations(d)
	}
	if vtype == "" || vtype == "FUEL_OVERRUN" {
		fuel, err := FuelOverruns(d)
		if err != nil {
			return nil, err
		}
		rows = append(rows, fuel...)
	}
	if vtype != "" {
		kept := rows[:0]
		for _, r := range rows {
			if r.Type == vtype {
				kept = append(kept, r)
			}
		}
		rows = kept
	}
	related := func(v Violation) string {
		if v.RelatedID == nil {
			return ""
		}
		return *v.RelatedID
	}
	sort.SliceStable(rows, func(i, j int) bool {
		a, b := rows[i], rows[j]
		if a.OccurredAt != b.OccurredAt {
			return a.OccurredAt < b.OccurredAt
		}
		if a.Type != b.Type {
			return a.Type < b.Type
		}
		if a.SubjectID != b.SubjectID {
			return a.SubjectID < b.SubjectID
		}
		return related(a) < related(b)
	})
	return rows, nil
}
