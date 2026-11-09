package ru.x5.practice.fleet.importing;

import static java.util.Map.entry;

import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import ru.x5.practice.fleet.importing.DatasetSpec.FormatException;
import ru.x5.practice.fleet.importing.DatasetSpec.Parser;

/** Шесть наборов данных из data-contract.md, раздел 2, с правилами проверки строк. */
public final class Datasets {

    // Цифры — только ASCII 0–9 (data-contract.md, 1.2): в Java \d без флагов и так ASCII,
    // но [0-9] нагляднее.
    private static final Pattern INT = Pattern.compile("[0-9]{1,9}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern DATETIME =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final Set<String> CATEGORIES = Set.of("B", "C", "D");

    static final Parser VEHICLE_ID = pattern("V-[0-9]{3}");
    static final Parser DRIVER_ID = pattern("D-[0-9]{3}");
    static final Parser WAYBILL_ID = pattern("WB-[0-9]{6}");
    static final Parser RECORD_ID = pattern("F-[0-9]{6}");
    static final Parser MAINTENANCE_ID = pattern("M-[0-9]{5}");
    static final Parser PLATE = pattern("[АВЕКМНОРСТУХ][0-9]{3}[АВЕКМНОРСТУХ]{2}[0-9]{2,3}");
    static final Parser LICENSE_NUMBER = pattern("[0-9]{2} [0-9]{2} [0-9]{6}");

    static final Parser INTEGER = s -> {
        if (!INT.matcher(s).matches()) {
            throw FormatException.INSTANCE;
        }
        return Integer.valueOf(s); // не больше 9 цифр — в int помещается
    };

    static final Parser DATE_VALUE = s -> {
        if (!DATE.matcher(s).matches()) {
            throw FormatException.INSTANCE;
        }
        try {
            LocalDate date = LocalDate.parse(s); // строгий разбор: 2025-02-30 не пройдёт
            if (date.getYear() < 1) {
                throw FormatException.INSTANCE;
            }
            return date;
        } catch (DateTimeParseException e) {
            throw FormatException.INSTANCE;
        }
    };

    static final Parser DATETIME_VALUE = s -> {
        if (!DATETIME.matcher(s).matches()) { // пробел вместо T, доли секунд, Z — формат неверный
            throw FormatException.INSTANCE;
        }
        try {
            LocalDateTime value = LocalDateTime.parse(s);
            if (value.getYear() < 1) {
                throw FormatException.INSTANCE;
            }
            return value;
        } catch (DateTimeParseException e) {
            throw FormatException.INSTANCE;
        }
    };

    /** Список через «;»: пустой элемент, пробельный символ в элементе или повтор — INVALID_FORMAT. */
    static final Parser CATEGORY_LIST = s -> {
        String[] parts = s.split(";", -1);
        Set<String> seen = new HashSet<>();
        for (String part : parts) {
            boolean hasSpace = part.chars().anyMatch(c -> Text.isSpace((char) c));
            if (part.isEmpty() || hasSpace || !seen.add(part)) {
                throw FormatException.INSTANCE;
            }
        }
        return List.of(parts);
    };

    /** Элемент списка категорий, которого нет в перечислении, — INVALID_VALUE. */
    static final Predicate<Object> KNOWN_CATEGORIES =
            v -> ((List<?>) v).stream().allMatch(CATEGORIES::contains);

    public static final Map<String, DatasetSpec> ALL = Map.of(
            "vehicles", vehicles(),
            "drivers", drivers(),
            "fuel_norms", fuelNorms(),
            "waybills", waybills(),
            "fuel_records", fuelRecords(),
            "maintenance", maintenance());

    private Datasets() {}

    static Parser pattern(String regex) {
        Pattern compiled = Pattern.compile(regex);
        return s -> {
            if (!compiled.matcher(s).matches()) {
                throw FormatException.INSTANCE;
            }
            return s;
        };
    }

    static Parser text(int maxLength) {
        return s -> {
            if (Text.length(s) > maxLength) {
                throw FormatException.INSTANCE;
            }
            return s;
        };
    }

    static Parser decimal(int places) {
        Pattern compiled = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1," + places + "})?");
        return s -> {
            if (!compiled.matcher(s).matches()) {
                throw FormatException.INSTANCE;
            }
            return Double.valueOf(s);
        };
    }

    static Predicate<Object> oneOf(String... values) {
        Set<String> allowed = Set.of(values);
        return allowed::contains;
    }

    static Predicate<Object> between(double min, double max) {
        return v -> {
            double d = ((Number) v).doubleValue();
            return min <= d && d <= max;
        };
    }

    @SafeVarargs
    private static <V> Map<String, V> ordered(Map.Entry<String, V>... entries) {
        Map<String, V> map = new LinkedHashMap<>();
        for (Map.Entry<String, V> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    private static DatasetSpec vehicles() {
        return new DatasetSpec(
                "vehicles",
                List.of("vehicle_id", "plate", "brand", "model", "type", "year", "fuel_type",
                        "base_consumption_l_100km", "odometer_km", "last_service_odometer_km",
                        "service_interval_km", "license_category", "status"),
                List.of(Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.INTEGER, Types.VARCHAR, Types.DOUBLE, Types.INTEGER, Types.INTEGER,
                        Types.INTEGER, Types.VARCHAR, Types.VARCHAR),
                List.of("vehicle_id", "plate", "brand", "model", "type", "year", "fuel_type",
                        "base_consumption_l_100km", "odometer_km", "service_interval_km",
                        "license_category", "status"),
                List.of("vehicle_id"),
                Map.ofEntries(
                        entry("vehicle_id", VEHICLE_ID), entry("plate", PLATE),
                        entry("brand", text(50)), entry("model", text(50)), entry("year", INTEGER),
                        entry("base_consumption_l_100km", decimal(1)), entry("odometer_km", INTEGER),
                        entry("last_service_odometer_km", INTEGER),
                        entry("service_interval_km", INTEGER)),
                ordered(
                        entry("type", oneOf("truck", "bus", "car")),
                        entry("year", between(1990, 2026)),
                        entry("fuel_type", oneOf("diesel", "petrol", "gas")),
                        entry("base_consumption_l_100km", between(3.0, 60.0)),
                        entry("odometer_km", between(0, 2_000_000)),
                        entry("last_service_odometer_km", between(0, 2_000_000)),
                        entry("service_interval_km", between(5_000, 50_000)),
                        entry("license_category", oneOf("B", "C", "D")),
                        entry("status", oneOf("active", "decommissioned"))),
                row -> {
                    Integer last = (Integer) row.get("last_service_odometer_km");
                    return last != null && last > (Integer) row.get("odometer_km")
                            ? "last_service_odometer_km" : null;
                },
                Map.of(),
                List.of("plate"));
    }

    private static DatasetSpec drivers() {
        return new DatasetSpec(
                "drivers",
                List.of("driver_id", "full_name", "license_number", "license_categories",
                        "license_valid_until", "hired_at", "status"),
                List.of(Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.DATE,
                        Types.DATE, Types.VARCHAR),
                List.of("driver_id", "full_name", "license_number", "license_categories",
                        "license_valid_until", "hired_at", "status"),
                List.of("driver_id"),
                Map.ofEntries(
                        entry("driver_id", DRIVER_ID), entry("full_name", text(100)),
                        entry("license_number", LICENSE_NUMBER),
                        entry("license_categories", CATEGORY_LIST),
                        entry("license_valid_until", DATE_VALUE), entry("hired_at", DATE_VALUE)),
                ordered(
                        entry("license_categories", KNOWN_CATEGORIES),
                        entry("status", oneOf("active", "dismissed"))),
                null,
                Map.of(),
                List.of("license_number"));
    }

    private static DatasetSpec fuelNorms() {
        return new DatasetSpec(
                "fuel_norms",
                List.of("fuel_type", "season", "coefficient"),
                List.of(Types.VARCHAR, Types.VARCHAR, Types.DOUBLE),
                List.of("fuel_type", "season", "coefficient"),
                List.of("fuel_type", "season"),
                Map.of("coefficient", decimal(2)),
                ordered(
                        entry("fuel_type", oneOf("diesel", "petrol", "gas")),
                        entry("season", oneOf("winter", "summer")),
                        entry("coefficient", between(0.5, 2.0))),
                null,
                Map.of(),
                List.of());
    }

    private static DatasetSpec waybills() {
        return new DatasetSpec(
                "waybills",
                List.of("waybill_id", "vehicle_id", "driver_id", "departure_at", "return_at",
                        "odometer_start", "odometer_end", "route_description"),
                List.of(Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP,
                        Types.TIMESTAMP, Types.INTEGER, Types.INTEGER, Types.VARCHAR),
                List.of("waybill_id", "vehicle_id", "driver_id", "departure_at", "return_at",
                        "odometer_start", "odometer_end"),
                List.of("waybill_id"),
                Map.ofEntries(
                        entry("waybill_id", WAYBILL_ID), entry("vehicle_id", VEHICLE_ID),
                        entry("driver_id", DRIVER_ID), entry("departure_at", DATETIME_VALUE),
                        entry("return_at", DATETIME_VALUE), entry("odometer_start", INTEGER),
                        entry("odometer_end", INTEGER), entry("route_description", text(200))),
                ordered(
                        entry("odometer_start", between(0, 2_000_000)),
                        entry("odometer_end", between(0, 2_000_000))),
                row -> {
                    LocalDateTime departure = (LocalDateTime) row.get("departure_at");
                    LocalDateTime ret = (LocalDateTime) row.get("return_at");
                    if (!ret.isAfter(departure)) {
                        return "return_at";
                    }
                    if ((Integer) row.get("odometer_end") < (Integer) row.get("odometer_start")) {
                        return "odometer_end";
                    }
                    return null;
                },
                ordered(entry("vehicle_id", "vehicles"), entry("driver_id", "drivers")),
                List.of());
    }

    private static DatasetSpec fuelRecords() {
        return new DatasetSpec(
                "fuel_records",
                List.of("record_id", "vehicle_id", "driver_id", "filled_at", "liters",
                        "price_per_liter", "station"),
                List.of(Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP, Types.DOUBLE,
                        Types.DOUBLE, Types.VARCHAR),
                List.of("record_id", "vehicle_id", "filled_at", "liters", "price_per_liter"),
                List.of("record_id"),
                Map.ofEntries(
                        entry("record_id", RECORD_ID), entry("vehicle_id", VEHICLE_ID),
                        entry("driver_id", DRIVER_ID), entry("filled_at", DATETIME_VALUE),
                        entry("liters", decimal(2)), entry("price_per_liter", decimal(2)),
                        entry("station", text(100))),
                ordered(
                        entry("liters", between(0.01, 1000.0)),
                        entry("price_per_liter", between(0.01, 500.0))),
                null,
                ordered(entry("vehicle_id", "vehicles"), entry("driver_id", "drivers")),
                List.of());
    }

    private static DatasetSpec maintenance() {
        return new DatasetSpec(
                "maintenance",
                List.of("maintenance_id", "vehicle_id", "type", "started_at", "finished_at",
                        "odometer_km", "cost", "description"),
                List.of(Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP,
                        Types.TIMESTAMP, Types.INTEGER, Types.DOUBLE, Types.VARCHAR),
                List.of("maintenance_id", "vehicle_id", "type", "started_at", "odometer_km", "cost"),
                List.of("maintenance_id"),
                Map.ofEntries(
                        entry("maintenance_id", MAINTENANCE_ID), entry("vehicle_id", VEHICLE_ID),
                        entry("started_at", DATETIME_VALUE), entry("finished_at", DATETIME_VALUE),
                        entry("odometer_km", INTEGER), entry("cost", decimal(2)),
                        entry("description", text(200))),
                ordered(
                        entry("type", oneOf("scheduled", "repair")),
                        entry("odometer_km", between(0, 2_000_000)),
                        entry("cost", between(0, 10_000_000))),
                row -> {
                    LocalDateTime finished = (LocalDateTime) row.get("finished_at");
                    return finished != null && finished.isBefore((LocalDateTime) row.get("started_at"))
                            ? "finished_at" : null;
                },
                ordered(entry("vehicle_id", "vehicles")),
                List.of());
    }
}
