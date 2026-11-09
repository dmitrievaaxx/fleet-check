package ru.x5.practice.fleet.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.x5.practice.fleet.ApiError;
import ru.x5.practice.fleet.analysis.Snapshot.Driver;
import ru.x5.practice.fleet.analysis.Snapshot.FuelNorm;
import ru.x5.practice.fleet.analysis.Snapshot.FuelRecord;
import ru.x5.practice.fleet.analysis.Snapshot.Maintenance;
import ru.x5.practice.fleet.analysis.Snapshot.Vehicle;
import ru.x5.practice.fleet.analysis.Snapshot.Waybill;

/** Граничные случаи business-rules.md на мини-наборе из трёх ТС и двух водителей. */
class RulesTest {

    static final Vehicle TRUCK = new Vehicle("V-001", "А123ВС77", "truck", "diesel", 32.5, 214500, 210000, 15000,
            "C", "active");
    static final Vehicle BUS = new Vehicle("V-002", "В456ЕК77", "bus", "diesel", 24.0, 98200, null, 20000, "D",
            "decommissioned");
    static final Vehicle CAR = new Vehicle("V-003", "Е789КМ77", "car", "petrol", 7.8, 45100, 40000, 15000, "B",
            "active");
    static final Driver IVANOV = new Driver("D-001", "Иванов Пётр Сергеевич", List.of("B", "C"),
            LocalDate.parse("2027-05-20"), "active");
    static final Driver SMIRNOVA = new Driver("D-002", "Смирнова Анна Олеговна", List.of("B"),
            LocalDate.parse("2025-06-30"), "active");
    static final List<FuelNorm> NORMS = List.of(new FuelNorm("diesel", "winter", 1.12),
            new FuelNorm("diesel", "summer", 1.0), new FuelNorm("petrol", "winter", 1.1),
            new FuelNorm("petrol", "summer", 1.0));

    static LocalDateTime at(String value) {
        return LocalDateTime.parse(value);
    }

    static Waybill wb(String id, Vehicle v, Driver d, String from, String to, int start, int end) {
        return new Waybill(id, v.id(), d.id(), at(from), at(to), start, end);
    }

    static Snapshot snapshot(List<Waybill> waybills, List<FuelRecord> fuel, List<Maintenance> maintenance) {
        return new Snapshot(List.of(TRUCK, BUS, CAR), List.of(IVANOV, SMIRNOVA), NORMS, waybills, fuel, maintenance);
    }

    static List<Map<String, Object>> ofType(Snapshot s, String type) {
        return Violations.find(s, type);
    }

    @Test
    void overlapIsOnePerPairWithTheLaterWaybillAsSubject() {
        Snapshot s = snapshot(List.of(
                wb("WB-000120", TRUCK, IVANOV, "2025-01-09T10:00:00", "2025-01-09T18:00:00", 214500, 214600),
                wb("WB-000121", TRUCK, IVANOV, "2025-01-09T15:00:00", "2025-01-09T20:00:00", 214600, 214700),
                // соприкосновение концами — не пересечение
                wb("WB-000122", TRUCK, IVANOV, "2025-01-09T20:00:00", "2025-01-09T22:00:00", 214700, 214750)),
                List.of(), List.of());
        List<Map<String, Object>> rows = ofType(s, "VEHICLE_OVERLAP");
        assertEquals(1, rows.size());
        assertEquals("WB-000121", rows.get(0).get("subject_id"));
        assertEquals("WB-000120", rows.get(0).get("related_id"));
        assertEquals(1, ofType(s, "DRIVER_OVERLAP").size());
    }

    @Test
    void odometerGapWithinToleranceIsNotAViolation() {
        Snapshot s = snapshot(List.of(
                wb("WB-000001", TRUCK, IVANOV, "2025-01-09T06:30:00", "2025-01-09T17:45:00", 214500, 214812),
                wb("WB-000002", TRUCK, IVANOV, "2025-01-10T06:40:00", "2025-01-10T16:20:00", 214815, 215090),
                wb("WB-000003", TRUCK, IVANOV, "2025-01-11T06:35:00", "2025-01-11T15:00:00", 215300, 215600)),
                List.of(), List.of());
        List<Map<String, Object>> rows = ofType(s, "ODOMETER_GAP");
        assertEquals(1, rows.size());
        assertEquals("WB-000003", rows.get(0).get("subject_id"));
        assertEquals("WB-000002", rows.get(0).get("related_id"));
        assertEquals(Map.of("expected", 215090, "actual", 215300, "delta", 210), rows.get(0).get("details"));
    }

    @Test
    void licenseIsValidOnItsLastDay() {
        Snapshot s = snapshot(List.of(
                wb("WB-000001", CAR, SMIRNOVA, "2025-06-30T06:00:00", "2025-06-30T10:00:00", 45100, 45150),
                wb("WB-000002", CAR, SMIRNOVA, "2025-07-01T06:00:00", "2025-07-01T10:00:00", 45150, 45200)),
                List.of(), List.of());
        List<Map<String, Object>> rows = ofType(s, "LICENSE_EXPIRED");
        assertEquals(1, rows.size());
        assertEquals("WB-000002", rows.get(0).get("subject_id"));
    }

    @Test
    void maintenanceOverdueIsOnePerEpisode() {
        Vehicle truck = new Vehicle("V-001", "А123ВС77", "truck", "diesel", 32.5, 224000, 210000, 15000, "C", "active");
        Snapshot s = new Snapshot(List.of(truck, CAR), List.of(IVANOV), NORMS, List.of(
                wb("WB-000001", truck, IVANOV, "2025-02-01T08:00:00", "2025-02-01T18:00:00", 224000, 224900),
                wb("WB-000002", truck, IVANOV, "2025-02-02T08:00:00", "2025-02-02T18:00:00", 224900, 225100),
                wb("WB-000003", truck, IVANOV, "2025-02-03T08:00:00", "2025-02-03T18:00:00", 225100, 225400),
                wb("WB-000004", truck, IVANOV, "2025-02-05T08:00:00", "2025-02-05T18:00:00", 225400, 225500)),
                List.of(), List.of(new Maintenance("M-00001", "V-001", "scheduled", at("2025-02-04T08:00:00"),
                        at("2025-02-04T18:00:00"), 225400)));
        List<Map<String, Object>> rows = Violations.find(s, "MAINTENANCE_OVERDUE");
        assertEquals(1, rows.size());
        assertEquals("WB-000002", rows.get(0).get("subject_id"));
    }

    @Test
    void fuelReportNeedsNormsOfItsMonth() {
        Snapshot s = new Snapshot(List.of(TRUCK), List.of(IVANOV), List.of(), List.of(
                wb("WB-000001", TRUCK, IVANOV, "2025-01-09T06:30:00", "2025-01-09T17:45:00", 214500, 214812)),
                List.of(), List.of());
        ApiError error = assertThrows(ApiError.class, () -> Reports.fuel(s, 2025, 1));
        assertEquals("FUEL_NORM_MISSING", error.code());
        assertEquals(List.of(), Reports.fuel(s, 2025, 2)); // в феврале листов нет — норм не нужно
    }

    @Test
    void fuelReportComparesFactWithNorm() {
        Snapshot s = snapshot(List.of(
                wb("WB-000001", TRUCK, IVANOV, "2025-01-09T06:30:00", "2025-01-09T17:45:00", 214500, 214812)),
                List.of(new FuelRecord("V-001", at("2025-01-09T17:50:00"), 130.0)), List.of());
        Map<String, Object> row = Reports.fuel(s, 2025, 1).get(0);
        assertEquals(113.57, (Double) row.get("norm_liters"), 1e-9); // 312 × 32.5 × 1.12 / 100
        assertEquals(true, row.get("overrun"));
        assertEquals(1, Violations.find(s, "FUEL_OVERRUN").size());
    }

    @Test
    void utilizationCountsCalendarDays() {
        Snapshot s = snapshot(List.of(
                wb("WB-000001", CAR, IVANOV, "2025-03-01T22:00:00", "2025-03-02T02:00:00", 45100, 45150)),
                List.of(), List.of(new Maintenance("M-00001", "V-003", "repair", at("2025-03-10T10:00:00"), null,
                        45150)));
        Map<String, Object> car = Reports.utilization(s, 2025, 3).stream()
                .filter(r -> r.get("vehicle_id").equals("V-003")).findFirst().orElseThrow();
        assertEquals(2, car.get("days_on_line"));
        assertEquals(22, car.get("days_in_maintenance"));
        assertEquals(7, car.get("days_idle"));
        assertEquals(0.065, (Double) car.get("utilization"), 1e-9);
        assertEquals(2, Reports.utilization(s, 2025, 3).size()); // списанный автобус не входит
    }
}
