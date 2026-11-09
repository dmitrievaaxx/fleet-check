package ru.x5.practice.fleet.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.x5.practice.fleet.ApiError;

/**
 * Снимок базы в памяти с индексами, которые нужны правилам и отчётам, и общие вычисления из
 * business-rules.md, раздел 1. Анализ выполняется при каждом запросе отчёта: на десятке тысяч
 * листов это доли секунды.
 */
public final class Snapshot {

    public static final double FUEL_OVERRUN_THRESHOLD = 0.10;
    public static final double DUE_SOON_FRACTION = 0.10;
    public static final int ODOMETER_GAP_TOLERANCE_KM = 5;
    private static final Set<Integer> WINTER_MONTHS = Set.of(11, 12, 1, 2, 3);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    public record Vehicle(String id, String plate, String type, String fuelType, double baseConsumption,
            int odometerKm, Integer lastServiceOdometerKm, int serviceIntervalKm, String licenseCategory,
            String status) {}

    public record Driver(String id, String fullName, List<String> categories, LocalDate licenseValidUntil,
            String status) {}

    public record FuelNorm(String fuelType, String season, double coefficient) {}

    public record Waybill(String id, String vehicleId, String driverId, LocalDateTime departureAt,
            LocalDateTime returnAt, int odometerStart, int odometerEnd) {
        public int mileage() {
            return odometerEnd - odometerStart;
        }
    }

    public record FuelRecord(String vehicleId, LocalDateTime filledAt, double liters) {}

    public record Maintenance(String id, String vehicleId, String type, LocalDateTime startedAt,
            LocalDateTime finishedAt, int odometerKm) {}

    /** Пробег на последнем плановом ТО и запись, которая его дала (null — из справочника ТС). */
    public record ServiceMark(int odometer, Maintenance maintenance) {}

    /** Порядок листов 1.4: departure_at, затем waybill_id. */
    public static final Comparator<Waybill> WAYBILL_ORDER =
            Comparator.comparing(Waybill::departureAt).thenComparing(Waybill::id);
    public static final Comparator<Maintenance> MAINTENANCE_ORDER =
            Comparator.comparing(Maintenance::startedAt).thenComparing(Maintenance::id);

    final TreeMap<String, Vehicle> vehicles = new TreeMap<>();
    final TreeMap<String, Driver> drivers = new TreeMap<>();
    final Map<String, Double> norms = new HashMap<>();
    final List<Waybill> waybills;
    final List<FuelRecord> fuel;
    final List<Maintenance> maintenance;
    final TreeMap<String, List<Waybill>> waybillsByVehicle = new TreeMap<>();
    final TreeMap<String, List<Waybill>> waybillsByDriver = new TreeMap<>();
    final Map<String, List<Maintenance>> maintenanceByVehicle = new HashMap<>();

    public Snapshot(Collection<Vehicle> vehicles, Collection<Driver> drivers, Collection<FuelNorm> norms,
            Collection<Waybill> waybills, Collection<FuelRecord> fuel, Collection<Maintenance> maintenance) {
        vehicles.forEach(v -> this.vehicles.put(v.id(), v));
        drivers.forEach(d -> this.drivers.put(d.id(), d));
        norms.forEach(n -> this.norms.put(n.fuelType() + "/" + n.season(), n.coefficient()));
        this.waybills = new ArrayList<>(waybills);
        this.waybills.sort(WAYBILL_ORDER);
        this.fuel = List.copyOf(fuel);
        this.maintenance = new ArrayList<>(maintenance);
        this.maintenance.sort(MAINTENANCE_ORDER);
        for (Waybill wb : this.waybills) {
            waybillsByVehicle.computeIfAbsent(wb.vehicleId(), k -> new ArrayList<>()).add(wb);
            waybillsByDriver.computeIfAbsent(wb.driverId(), k -> new ArrayList<>()).add(wb);
        }
        for (Maintenance m : this.maintenance) {
            maintenanceByVehicle.computeIfAbsent(m.vehicleId(), k -> new ArrayList<>()).add(m);
        }
    }

    public static Snapshot load(JdbcTemplate jdbc) {
        List<Vehicle> vehicles = jdbc.query("""
                SELECT vehicle_id, plate, type, fuel_type, base_consumption_l_100km, odometer_km,
                       last_service_odometer_km, service_interval_km, license_category, status
                FROM vehicles""",
                (rs, n) -> new Vehicle(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getDouble(5), rs.getInt(6), nullableInt(rs, 7), rs.getInt(8), rs.getString(9),
                        rs.getString(10)));
        List<Driver> drivers = jdbc.query(
                "SELECT driver_id, full_name, license_categories, license_valid_until, status FROM drivers",
                (rs, n) -> new Driver(rs.getString(1), rs.getString(2), Arrays.asList(rs.getString(3).split(";")),
                        rs.getObject(4, LocalDate.class), rs.getString(5)));
        List<FuelNorm> norms = jdbc.query("SELECT fuel_type, season, coefficient FROM fuel_norms",
                (rs, n) -> new FuelNorm(rs.getString(1), rs.getString(2), rs.getDouble(3)));
        List<Waybill> waybills = jdbc.query("""
                SELECT waybill_id, vehicle_id, driver_id, departure_at, return_at, odometer_start, odometer_end
                FROM waybills""",
                (rs, n) -> new Waybill(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class),
                        rs.getInt(6), rs.getInt(7)));
        List<FuelRecord> fuel = jdbc.query(
                "SELECT vehicle_id, filled_at, liters FROM fuel_records ORDER BY filled_at, record_id",
                (rs, n) -> new FuelRecord(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getDouble(3)));
        List<Maintenance> maintenance = jdbc.query(
                "SELECT maintenance_id, vehicle_id, type, started_at, finished_at, odometer_km FROM maintenance",
                (rs, n) -> new Maintenance(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDateTime.class), rs.getObject(5, LocalDateTime.class), rs.getInt(6)));
        return new Snapshot(vehicles, drivers, norms, waybills, fuel, maintenance);
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    // --- общие вычисления ------------------------------------------------------------------------

    public static String format(LocalDateTime value) {
        return value == null ? null : DATETIME.format(value);
    }

    /** Сезон по дате (data-contract.md, 1.4): с ноября по март — зима. */
    public static String season(LocalDate date) {
        return WINTER_MONTHS.contains(date.getMonthValue()) ? "winter" : "summer";
    }

    /** Пересечение полуоткрытых интервалов (1.3); null — открытый правый конец. */
    public static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart,
            LocalDateTime bEnd) {
        boolean left = bEnd == null || aStart.isBefore(bEnd);
        boolean right = aEnd == null || bStart.isBefore(aEnd);
        return left && right;
    }

    /** Округление как в отчётах: к ближайшему, половина — к чётному, по точному значению double. */
    public static double round(double value, int places) {
        return new java.math.BigDecimal(value).setScale(places, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    public List<Waybill> waybillsOf(String vehicleId) {
        return waybillsByVehicle.getOrDefault(vehicleId, List.of());
    }

    public List<Maintenance> maintenanceOf(String vehicleId) {
        return maintenanceByVehicle.getOrDefault(vehicleId, List.of());
    }

    /** service_odometer(T) из 1.5. */
    public ServiceMark serviceOdometer(String vehicleId, LocalDateTime at) {
        Maintenance last = null;
        for (Maintenance m : maintenanceOf(vehicleId)) { // по возрастанию (started_at, id)
            if (m.type().equals("scheduled") && !m.startedAt().isAfter(at)) {
                last = m;
            }
        }
        if (last != null) {
            return new ServiceMark(last.odometerKm(), last);
        }
        Vehicle v = vehicles.get(vehicleId);
        int odometer = v.lastServiceOdometerKm() != null ? v.lastServiceOdometerKm() : v.odometerKm();
        return new ServiceMark(odometer, null);
    }

    /** Сочетания топлива и сезона, нужные листам и отсутствующие в справочнике норм. */
    public List<Map<String, Object>> missingNorms(List<Waybill> list) {
        TreeSet<String> needed = new TreeSet<>();
        for (Waybill wb : list) {
            needed.add(vehicles.get(wb.vehicleId()).fuelType() + "/" + season(wb.departureAt().toLocalDate()));
        }
        List<Map<String, Object>> missing = new ArrayList<>();
        for (String key : needed) {
            if (!norms.containsKey(key)) {
                String[] parts = key.split("/");
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("fuel_type", parts[0]);
                pair.put("season", parts[1]);
                missing.add(pair);
            }
        }
        return missing;
    }

    public void requireNorms(List<Waybill> list) {
        List<Map<String, Object>> missing = missingNorms(list);
        if (!missing.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("missing", missing);
            throw new ApiError(422, "FUEL_NORM_MISSING", "fuel norm coefficients are missing", details);
        }
    }

    /** Норма листа (1.6): пробег × базовая норма × коэффициент / 100, без округления. */
    public double normLiters(Waybill wb) {
        Vehicle v = vehicles.get(wb.vehicleId());
        double coefficient = norms.get(v.fuelType() + "/" + season(wb.departureAt().toLocalDate()));
        return wb.mileage() * v.baseConsumption() * coefficient / 100;
    }
}
