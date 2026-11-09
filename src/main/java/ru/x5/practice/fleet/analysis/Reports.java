package ru.x5.practice.fleet.analysis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import ru.x5.practice.fleet.analysis.Snapshot.Driver;
import ru.x5.practice.fleet.analysis.Snapshot.FuelRecord;
import ru.x5.practice.fleet.analysis.Snapshot.Maintenance;
import ru.x5.practice.fleet.analysis.Snapshot.ServiceMark;
import ru.x5.practice.fleet.analysis.Snapshot.Vehicle;
import ru.x5.practice.fleet.analysis.Snapshot.Waybill;

/** Пять отчётов из reports-contract.md по величинам business-rules.md, раздел 3. */
public final class Reports {

    public static final List<String> FUEL_COLUMNS = List.of("vehicle_id", "plate", "fuel_type", "mileage_km",
            "norm_liters", "actual_liters", "deviation_liters", "deviation_pct", "overrun");
    public static final List<String> MAINTENANCE_DUE_COLUMNS = List.of("vehicle_id", "plate",
            "current_odometer_km", "service_odometer_km", "last_service_at", "service_interval_km",
            "km_since_service", "km_remaining", "status");
    public static final List<String> VIOLATION_COLUMNS = List.of("type", "occurred_at", "vehicle_id", "driver_id",
            "subject_type", "subject_id", "related_id", "details");
    public static final List<String> UTILIZATION_COLUMNS = List.of("vehicle_id", "plate", "type", "days_in_month",
            "days_on_line", "days_in_maintenance", "days_idle", "utilization");
    public static final List<String> DRIVERS_COLUMNS = List.of("driver_id", "full_name", "trips", "mileage_km",
            "violations");

    private static final Set<String> DRIVER_VIOLATIONS =
            Set.of("DRIVER_OVERLAP", "LICENSE_CATEGORY_MISMATCH", "LICENSE_EXPIRED");

    private Reports() {}

    private static boolean inMonth(LocalDateTime at, int year, int month) {
        return at.getYear() == year && at.getMonthValue() == month;
    }

    /** 3.1: строка на ТС с листами или заправками в месяце. */
    public static List<Map<String, Object>> fuel(Snapshot s, int year, int month) {
        List<Waybill> trips = s.waybills.stream().filter(wb -> inMonth(wb.departureAt(), year, month)).toList();
        s.requireNorms(trips); // проверяются только листы этого месяца
        Map<String, Double> norm = new HashMap<>();
        Map<String, Integer> mileage = new HashMap<>();
        Map<String, Double> actual = new HashMap<>();
        for (Waybill wb : trips) {
            norm.merge(wb.vehicleId(), s.normLiters(wb), Double::sum);
            mileage.merge(wb.vehicleId(), wb.mileage(), Integer::sum);
        }
        for (FuelRecord fr : s.fuel) {
            if (inMonth(fr.filledAt(), year, month)) {
                actual.merge(fr.vehicleId(), fr.liters(), Double::sum);
            }
        }
        TreeSet<String> ids = new TreeSet<>(norm.keySet());
        ids.addAll(actual.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ids) {
            Vehicle v = s.vehicles.get(id);
            double n = norm.getOrDefault(id, 0.0);
            double a = actual.getOrDefault(id, 0.0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicle_id", id);
            row.put("plate", v.plate());
            row.put("fuel_type", v.fuelType());
            row.put("mileage_km", mileage.getOrDefault(id, 0));
            row.put("norm_liters", Snapshot.round(n, 2));
            row.put("actual_liters", Snapshot.round(a, 2));
            row.put("deviation_liters", Snapshot.round(a - n, 2));
            row.put("deviation_pct", n == 0 ? null : Snapshot.round((a - n) / n * 100, 1));
            row.put("overrun", a > n * (1 + Snapshot.FUEL_OVERRUN_THRESHOLD));
            rows.add(row);
        }
        return rows;
    }

    /** 3.2: строка на каждое активное ТС, сортировка по km_remaining, затем vehicle_id. */
    public static List<Map<String, Object>> maintenanceDue(Snapshot s, LocalDate asOf) {
        LocalDateTime asOfTs = asOf.atTime(LocalTime.of(23, 59, 59));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Vehicle v : s.vehicles.values()) {
            if (!v.status().equals("active")) {
                continue;
            }
            Waybill last = null; // последний в порядке 1.4 среди вернувшихся к as_of
            for (Waybill wb : s.waybillsOf(v.id())) {
                if (!wb.returnAt().isAfter(asOfTs)) {
                    last = wb;
                }
            }
            int current = last != null ? last.odometerEnd() : v.odometerKm();
            ServiceMark service = s.serviceOdometer(v.id(), asOfTs);
            current = Math.max(current, service.odometer());
            int since = current - service.odometer();
            int remaining = v.serviceIntervalKm() - since;
            String status;
            if (remaining < 0) {
                status = "overdue";
            } else if (remaining <= v.serviceIntervalKm() * Snapshot.DUE_SOON_FRACTION) {
                status = "due_soon";
            } else {
                status = "ok";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicle_id", v.id());
            row.put("plate", v.plate());
            row.put("current_odometer_km", current);
            row.put("service_odometer_km", service.odometer());
            row.put("last_service_at",
                    service.maintenance() == null ? null : Snapshot.format(service.maintenance().startedAt()));
            row.put("service_interval_km", v.serviceIntervalKm());
            row.put("km_since_service", since);
            row.put("km_remaining", remaining);
            row.put("status", status);
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (Integer) r.get("km_remaining"))
                .thenComparing(r -> (String) r.get("vehicle_id")));
        return rows;
    }

    /** Отчёт о нарушениях с фильтром по календарной дате occurred_at, включительно. */
    public static List<Map<String, Object>> violations(Snapshot s, String type, LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = Violations.find(s, type);
        if (from != null) {
            String bound = from.toString();
            rows.removeIf(r -> ((String) r.get("occurred_at")).substring(0, 10).compareTo(bound) < 0);
        }
        if (to != null) {
            String bound = to.toString();
            rows.removeIf(r -> ((String) r.get("occurred_at")).substring(0, 10).compareTo(bound) > 0);
        }
        return rows;
    }

    /** 3.3: строка на каждое активное ТС, дни считаются по календарным суткам. */
    public static List<Map<String, Object>> utilization(Snapshot s, int year, int month) {
        int daysInMonth = YearMonth.of(year, month).lengthOfMonth();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Vehicle v : s.vehicles.values()) {
            if (!v.status().equals("active")) {
                continue;
            }
            Set<Integer> onLine = new HashSet<>();
            Set<Integer> inMaintenance = new HashSet<>();
            for (int day = 1; day <= daysInMonth; day++) {
                LocalDateTime start = LocalDate.of(year, month, day).atStartOfDay();
                LocalDateTime end = start.plusDays(1);
                for (Waybill wb : s.waybillsOf(v.id())) {
                    if (Snapshot.overlaps(start, end, wb.departureAt(), wb.returnAt())) {
                        onLine.add(day);
                        break;
                    }
                }
                for (Maintenance m : s.maintenanceOf(v.id())) {
                    if (Snapshot.overlaps(start, end, m.startedAt(), m.finishedAt())) {
                        inMaintenance.add(day);
                        break;
                    }
                }
            }
            Set<Integer> busy = new HashSet<>(onLine);
            busy.addAll(inMaintenance);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicle_id", v.id());
            row.put("plate", v.plate());
            row.put("type", v.type());
            row.put("days_in_month", daysInMonth);
            row.put("days_on_line", onLine.size());
            row.put("days_in_maintenance", inMaintenance.size());
            row.put("days_idle", daysInMonth - busy.size());
            row.put("utilization", Snapshot.round((double) onLine.size() / daysInMonth, 3));
            rows.add(row);
        }
        return rows;
    }

    /** 3.4: водители с листами или водительскими нарушениями в месяце. Нормы не нужны. */
    public static List<Map<String, Object>> drivers(Snapshot s, int year, int month) {
        Map<String, Integer> trips = new HashMap<>();
        Map<String, Integer> mileage = new HashMap<>();
        for (Waybill wb : s.waybills) {
            if (inMonth(wb.departureAt(), year, month)) {
                trips.merge(wb.driverId(), 1, Integer::sum);
                mileage.merge(wb.driverId(), wb.mileage(), Integer::sum);
            }
        }
        String prefix = String.format("%04d-%02d", year, month);
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> r : Violations.waybillViolations(s)) {
            if (DRIVER_VIOLATIONS.contains(r.get("type")) && ((String) r.get("occurred_at")).startsWith(prefix)) {
                counts.merge((String) r.get("driver_id"), 1, Integer::sum);
            }
        }
        TreeMap<String, Driver> selected = new TreeMap<>();
        for (String id : trips.keySet()) {
            selected.put(id, s.drivers.get(id));
        }
        for (String id : counts.keySet()) {
            selected.put(id, s.drivers.get(id));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Driver d : selected.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("driver_id", d.id());
            row.put("full_name", d.fullName());
            row.put("trips", trips.getOrDefault(d.id(), 0));
            row.put("mileage_km", mileage.getOrDefault(d.id(), 0));
            row.put("violations", counts.getOrDefault(d.id(), 0));
            rows.add(row);
        }
        return rows;
    }
}
