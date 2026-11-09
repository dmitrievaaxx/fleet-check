package rules_test

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"fleet/internal/domain"
	"fleet/internal/reports"
	"fleet/internal/rules"
)

func intp(n int) *int       { return &n }
func strp(s string) *string { return &s }

// fleet — мини-набор: три ТС, три водителя, полный справочник норм.
func fleet(waybills []*domain.Waybill, maintenance []*domain.Maintenance, fuel []*domain.Fuel) *domain.Domain {
	vehicles := []*domain.Vehicle{
		{ID: "V-001", Plate: "А123ВС77", Type: "truck", FuelType: "diesel", BaseConsumption: 32.5, Odometer: 214500,
			LastService: intp(210000), ServiceInterval: 15000, LicenseCategory: "C", Status: "active"},
		{ID: "V-002", Plate: "В456ЕК77", Type: "bus", FuelType: "diesel", BaseConsumption: 24.0, Odometer: 98200,
			ServiceInterval: 20000, LicenseCategory: "D", Status: "active"},
		{ID: "V-003", Plate: "Е789КМ77", Type: "car", FuelType: "petrol", BaseConsumption: 7.8, Odometer: 45100,
			LastService: intp(40000), ServiceInterval: 15000, LicenseCategory: "B", Status: "decommissioned"},
	}
	drivers := []*domain.Driver{
		{ID: "D-001", FullName: "Иванов", Categories: []string{"B", "C"}, LicenseValidUntil: "2027-05-20", Status: "active"},
		{ID: "D-002", FullName: "Смирнова", Categories: []string{"B"}, LicenseValidUntil: "2026-02-10", Status: "active"},
		{ID: "D-003", FullName: "Кузнецов", Categories: []string{"B", "C", "D"}, LicenseValidUntil: "2025-06-30", Status: "active"},
	}
	norms := map[[2]string]float64{}
	for _, ft := range []string{"diesel", "petrol", "gas"} {
		norms[[2]string{ft, "winter"}] = 1.1
		norms[[2]string{ft, "summer"}] = 1.0
	}
	return domain.Build(vehicles, drivers, norms, waybills, fuel, maintenance)
}

func wb(id, vehicle, driver, dep, ret string, start, end int) *domain.Waybill {
	return &domain.Waybill{ID: id, VehicleID: vehicle, DriverID: driver, Departure: dep, Return: ret, OdoStart: start, OdoEnd: end}
}

func byType(t *testing.T, d *domain.Domain, vtype string) []rules.Violation {
	rows, err := rules.Report(d, vtype)
	require.Nil(t, err)
	return rows
}

func TestOverlapsGiveOneViolationPerPair(t *testing.T) {
	d := fleet([]*domain.Waybill{
		wb("WB-000120", "V-001", "D-001", "2025-01-10T10:00:00", "2025-01-10T18:00:00", 214500, 214600),
		wb("WB-000121", "V-001", "D-001", "2025-01-10T15:00:00", "2025-01-10T20:00:00", 214600, 214700),
		wb("WB-000122", "V-001", "D-001", "2025-01-10T20:00:00", "2025-01-10T22:00:00", 214700, 214710), // касание
	}, nil, nil)
	rows := byType(t, d, "VEHICLE_OVERLAP")
	require.Len(t, rows, 1)
	assert.Equal(t, "WB-000121", rows[0].SubjectID)
	assert.Equal(t, "WB-000120", *rows[0].RelatedID)
	assert.Len(t, byType(t, d, "DRIVER_OVERLAP"), 1)
}

func TestLicenseRules(t *testing.T) {
	d := fleet([]*domain.Waybill{
		wb("WB-000001", "V-002", "D-003", "2025-06-30T06:00:00", "2025-06-30T10:00:00", 98200, 98300),
		wb("WB-000002", "V-002", "D-003", "2025-07-01T06:00:00", "2025-07-01T10:00:00", 98300, 98400),
		wb("WB-000003", "V-001", "D-002", "2025-07-01T06:00:00", "2025-07-01T10:00:00", 214500, 214600),
	}, nil, nil)
	expired := byType(t, d, "LICENSE_EXPIRED")
	require.Len(t, expired, 1)
	assert.Equal(t, "WB-000002", expired[0].SubjectID)
	mismatch := byType(t, d, "LICENSE_CATEGORY_MISMATCH")
	require.Len(t, mismatch, 1)
	assert.Equal(t, "WB-000003", mismatch[0].SubjectID)
}

func TestOdometerGapAndDecommissioned(t *testing.T) {
	d := fleet([]*domain.Waybill{
		wb("WB-000001", "V-001", "D-001", "2025-01-09T06:30:00", "2025-01-09T17:45:00", 214500, 214812),
		wb("WB-000003", "V-001", "D-001", "2025-01-10T06:40:00", "2025-01-10T16:20:00", 214815, 215090),
		wb("WB-000004", "V-001", "D-001", "2025-01-11T06:35:00", "2025-01-11T16:00:00", 215300, 215600),
		wb("WB-000005", "V-003", "D-002", "2025-01-11T06:35:00", "2025-01-11T16:00:00", 45100, 45200),
	}, nil, nil)
	gaps := byType(t, d, "ODOMETER_GAP")
	require.Len(t, gaps, 1)
	assert.Equal(t, "WB-000004", gaps[0].SubjectID)
	assert.Equal(t, "WB-000003", *gaps[0].RelatedID)
	assert.Equal(t, 210, gaps[0].Details.Get("delta"))
	assert.Len(t, byType(t, d, "VEHICLE_DECOMMISSIONED"), 1)
}

func TestMaintenanceOverdueEpisodes(t *testing.T) {
	maintenance := []*domain.Maintenance{
		{ID: "M-00001", VehicleID: "V-001", Type: "scheduled", Started: "2025-02-03T08:00:00",
			Finished: strp("2025-02-03T18:00:00"), Odometer: 225400},
		{ID: "M-00002", VehicleID: "V-002", Type: "repair", Started: "2025-06-10T10:00:00", Odometer: 98300},
	}
	d := fleet([]*domain.Waybill{
		wb("WB-000001", "V-001", "D-001", "2025-01-09T06:00:00", "2025-01-09T18:00:00", 214500, 224900),
		wb("WB-000002", "V-001", "D-001", "2025-01-10T06:00:00", "2025-01-10T18:00:00", 224900, 225100),
		wb("WB-000003", "V-001", "D-001", "2025-01-11T06:00:00", "2025-01-11T18:00:00", 225100, 225400),
		wb("WB-000004", "V-001", "D-001", "2025-02-04T06:00:00", "2025-02-04T18:00:00", 225400, 225500),
		wb("WB-000005", "V-002", "D-003", "2025-06-10T08:00:00", "2025-06-10T12:00:00", 98200, 98300),
	}, maintenance, nil)
	overdue := byType(t, d, "MAINTENANCE_OVERDUE")
	require.Len(t, overdue, 1)
	assert.Equal(t, "WB-000002", overdue[0].SubjectID)
	assert.Equal(t, 15100, overdue[0].Details.Get("km_since_service"))
	inMaint := byType(t, d, "VEHICLE_IN_MAINTENANCE")
	require.Len(t, inMaint, 1)
	assert.Equal(t, "M-00002", *inMaint[0].RelatedID)

	due := reports.MaintenanceDue(d, "2025-01-31")
	require.Len(t, due, 2)
	assert.Equal(t, "V-001", due[0].Get("vehicle_id"))
	assert.Equal(t, "overdue", due[0].Get("status"))
	assert.Equal(t, -400, due[0].Get("km_remaining"))
}

func TestFuelOverrunAndMissingNorms(t *testing.T) {
	waybills := []*domain.Waybill{
		wb("WB-000001", "V-001", "D-001", "2025-01-09T06:30:00", "2025-01-09T17:45:00", 214500, 214812),
	}
	fuel := []*domain.Fuel{{VehicleID: "V-001", FilledAt: "2025-01-09T18:00:00", Liters: 200}}
	d := fleet(waybills, nil, fuel)
	rows := byType(t, d, "FUEL_OVERRUN")
	require.Len(t, rows, 1)
	assert.Equal(t, "2025-01-01T00:00:00", rows[0].OccurredAt)
	assert.Equal(t, 111.54, rows[0].Details.Get("norm_liters")) // 312 × 32.5 × 1.1 / 100

	report, err := reports.Fuel(d, 2025, 1)
	require.Nil(t, err)
	require.Len(t, report, 1)
	assert.Equal(t, true, report[0].Get("overrun"))

	delete(d.Norms, [2]string{"diesel", "winter"})
	_, err = rules.Report(d, "")
	require.NotNil(t, err)
	assert.Equal(t, "FUEL_NORM_MISSING", err.Code)
	_, err = rules.Report(d, "ODOMETER_GAP")
	assert.Nil(t, err)
}
