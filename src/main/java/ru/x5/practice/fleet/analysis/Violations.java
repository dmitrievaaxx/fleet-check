package ru.x5.practice.fleet.analysis;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ru.x5.practice.fleet.analysis.Snapshot.Driver;
import ru.x5.practice.fleet.analysis.Snapshot.FuelRecord;
import ru.x5.practice.fleet.analysis.Snapshot.Maintenance;
import ru.x5.practice.fleet.analysis.Snapshot.Vehicle;
import ru.x5.practice.fleet.analysis.Snapshot.Waybill;

/** Девять типов нарушений из business-rules.md, раздел 2. Строка — поля reports-contract.md, 4. */
public final class Violations {

    public static final List<String> TYPES = List.of(
            "VEHICLE_OVERLAP", "DRIVER_OVERLAP", "LICENSE_CATEGORY_MISMATCH", "LICENSE_EXPIRED",
            "ODOMETER_GAP", "VEHICLE_DECOMMISSIONED", "VEHICLE_IN_MAINTENANCE", "MAINTENANCE_OVERDUE",
            "FUEL_OVERRUN");

    /** Сортировка отчёта: occurred_at, type, subject_id, related_id (null первым). */
    static final Comparator<Map<String, Object>> ORDER = Comparator
            .comparing((Map<String, Object> r) -> (String) r.get("occurred_at"))
            .thenComparing(r -> (String) r.get("type"))
            .thenComparing(r -> (String) r.get("subject_id"))
            .thenComparing(r -> r.get("related_id") == null ? "" : (String) r.get("related_id"));

    private Violations() {}

    /** Все нарушения или нарушения одного типа, отсортированные по контракту. */
    public static List<Map<String, Object>> find(Snapshot s, String type) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (type == null || !type.equals("FUEL_OVERRUN")) {
            rows.addAll(waybillViolations(s));
        }
        if (type == null || type.equals("FUEL_OVERRUN")) {
            rows.addAll(fuelOverruns(s)); // требует норм: без них 422
        }
        if (type != null) {
            rows.removeIf(r -> !type.equals(r.get("type")));
        }
        rows.sort(ORDER);
        return rows;
    }

    static Map<String, Object> row(String type, Waybill wb, String vehicleId, LocalDateTime occurredAt,
            String subjectType, String subjectId, String relatedId, Map<String, Object> details) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("occurred_at", Snapshot.format(occurredAt));
        row.put("vehicle_id", vehicleId);
        row.put("driver_id", wb == null ? null : wb.driverId());
        row.put("subject_type", subjectType);
        row.put("subject_id", subjectId);
        row.put("related_id", relatedId);
        row.put("details", details);
        return row;
    }

    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            details.put((String) pairs[i], pairs[i + 1]);
        }
        return details;
    }

    /** 2.1, 2.2: одно нарушение на пару, субъект — более поздний лист в порядке 1.4. */
    private static List<Map<String, Object>> overlaps(Map<String, List<Waybill>> groups, String type) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Waybill> group : groups.values()) {
            List<Waybill> list = new ArrayList<>(group);
            list.sort(Snapshot.WAYBILL_ORDER);
            for (int j = 0; j < list.size(); j++) {
                Waybill b = list.get(j);
                for (int i = 0; i < j; i++) {
                    Waybill a = list.get(i);
                    if (Snapshot.overlaps(a.departureAt(), a.returnAt(), b.departureAt(), b.returnAt())) {
                        rows.add(row(type, b, b.vehicleId(), b.departureAt(), "waybill", b.id(), a.id(),
                                details("related_departure_at", Snapshot.format(a.departureAt()),
                                        "related_return_at", Snapshot.format(a.returnAt()))));
                    }
                }
            }
        }
        return rows;
    }

    /** Нарушения, субъект которых — путевой лист (всё, кроме FUEL_OVERRUN). */
    public static List<Map<String, Object>> waybillViolations(Snapshot s) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(overlaps(s.waybillsByVehicle, "VEHICLE_OVERLAP"));
        rows.addAll(overlaps(s.waybillsByDriver, "DRIVER_OVERLAP"));

        for (Waybill wb : s.waybills) {
            Vehicle v = s.vehicles.get(wb.vehicleId());
            Driver d = s.drivers.get(wb.driverId());
            if (!d.categories().contains(v.licenseCategory())) {
                rows.add(row("LICENSE_CATEGORY_MISMATCH", wb, v.id(), wb.departureAt(), "waybill", wb.id(), null,
                        details("required", v.licenseCategory(), "driver_categories", List.copyOf(d.categories()))));
            }
            if (d.licenseValidUntil().isBefore(wb.departureAt().toLocalDate())) {
                rows.add(row("LICENSE_EXPIRED", wb, v.id(), wb.departureAt(), "waybill", wb.id(), null,
                        details("license_valid_until", d.licenseValidUntil().toString())));
            }
            if (v.status().equals("decommissioned")) {
                rows.add(row("VEHICLE_DECOMMISSIONED", wb, v.id(), wb.departureAt(), "waybill", wb.id(), null,
                        details()));
            }
            Maintenance hit = null; // первая по (started_at, id) из пересекающихся
            for (Maintenance m : s.maintenanceOf(v.id())) {
                if (Snapshot.overlaps(wb.departureAt(), wb.returnAt(), m.startedAt(), m.finishedAt())) {
                    hit = m;
                    break;
                }
            }
            if (hit != null) {
                rows.add(row("VEHICLE_IN_MAINTENANCE", wb, v.id(), wb.departureAt(), "waybill", wb.id(), hit.id(),
                        details("maintenance_type", hit.type(), "started_at", Snapshot.format(hit.startedAt()),
                                "finished_at", Snapshot.format(hit.finishedAt()))));
            }
        }

        for (Map.Entry<String, List<Waybill>> entry : s.waybillsByVehicle.entrySet()) {
            Vehicle v = s.vehicles.get(entry.getKey());
            int expected = v.odometerKm();
            Waybill previous = null;
            boolean inEpisode = false;
            Integer lastService = null;
            for (Waybill wb : entry.getValue()) { // уже в порядке 1.4
                int delta = wb.odometerStart() - expected;
                if (delta < 0 || delta > Snapshot.ODOMETER_GAP_TOLERANCE_KM) {
                    rows.add(row("ODOMETER_GAP", wb, v.id(), wb.departureAt(), "waybill", wb.id(),
                            previous == null ? null : previous.id(),
                            details("expected", expected, "actual", wb.odometerStart(), "delta", delta)));
                }
                expected = wb.odometerEnd(); // цепочка продолжается от фактических значений
                previous = wb;

                int service = s.serviceOdometer(v.id(), wb.departureAt()).odometer();
                if (lastService == null || service != lastService) { // эпизод кончается сменой значения
                    inEpisode = false;
                    lastService = service;
                }
                int kmSince = wb.odometerEnd() - service;
                if (!inEpisode && kmSince > v.serviceIntervalKm()) {
                    inEpisode = true;
                    rows.add(row("MAINTENANCE_OVERDUE", wb, v.id(), wb.departureAt(), "waybill", wb.id(), null,
                            details("service_odometer", service, "odometer_end", wb.odometerEnd(),
                                    "km_since_service", kmSince, "service_interval_km", v.serviceIntervalKm())));
                }
            }
        }
        return rows;
    }

    /** 2.9: перерасход по паре (ТС, месяц); субъект — vehicle_month. */
    public static List<Map<String, Object>> fuelOverruns(Snapshot s) {
        s.requireNorms(s.waybills);
        TreeMap<VehicleMonth, Double> norm = new TreeMap<>();
        TreeMap<VehicleMonth, Integer> mileage = new TreeMap<>();
        TreeMap<VehicleMonth, Double> actual = new TreeMap<>();
        for (Waybill wb : s.waybills) {
            VehicleMonth key = VehicleMonth.of(wb.vehicleId(), wb.departureAt());
            norm.merge(key, s.normLiters(wb), Double::sum);
            mileage.merge(key, wb.mileage(), Integer::sum);
        }
        for (FuelRecord fr : s.fuel) {
            actual.merge(VehicleMonth.of(fr.vehicleId(), fr.filledAt()), fr.liters(), Double::sum);
        }
        TreeMap<VehicleMonth, Boolean> keys = new TreeMap<>();
        norm.keySet().forEach(k -> keys.put(k, true));
        actual.keySet().forEach(k -> keys.put(k, true));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (VehicleMonth key : keys.keySet()) {
            double n = norm.getOrDefault(key, 0.0);
            double a = actual.getOrDefault(key, 0.0);
            if (a > n * (1 + Snapshot.FUEL_OVERRUN_THRESHOLD)) {
                rows.add(row("FUEL_OVERRUN", null, key.vehicleId(),
                        LocalDateTime.of(key.year(), key.month(), 1, 0, 0), "vehicle_month", key.vehicleId(), null,
                        details("year", key.year(), "month", key.month(),
                                "mileage_km", mileage.getOrDefault(key, 0),
                                "norm_liters", Snapshot.round(n, 2), "actual_liters", Snapshot.round(a, 2),
                                "deviation_pct", n == 0 ? null : Snapshot.round((a - n) / n * 100, 1))));
            }
        }
        return rows;
    }

    record VehicleMonth(String vehicleId, int year, int month) implements Comparable<VehicleMonth> {
        static VehicleMonth of(String vehicleId, LocalDateTime at) {
            return new VehicleMonth(vehicleId, at.getYear(), at.getMonthValue());
        }

        @Override
        public int compareTo(VehicleMonth o) {
            int c = vehicleId.compareTo(o.vehicleId);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(year, o.year);
            return c != 0 ? c : Integer.compare(month, o.month);
        }
    }
}
