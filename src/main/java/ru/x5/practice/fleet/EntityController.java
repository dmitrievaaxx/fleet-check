package ru.x5.practice.fleet;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.x5.practice.fleet.analysis.Snapshot;

/** Справочники и путевые листы (api-contract.md, 4): страница {items, total, limit, offset}. */
@RestController
public class EntityController {

    private static final String VEHICLE_COLUMNS = "vehicle_id, plate, brand, model, type, year, fuel_type, "
            + "base_consumption_l_100km, odometer_km, last_service_odometer_km, service_interval_km, "
            + "license_category, status";
    private static final String DRIVER_COLUMNS =
            "driver_id, full_name, license_number, license_categories, license_valid_until, hired_at, status";
    private static final String WAYBILL_COLUMNS = "waybill_id, vehicle_id, driver_id, departure_at, return_at, "
            + "odometer_start, odometer_end, route_description";

    private final JdbcTemplate jdbc;

    public EntityController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/vehicles")
    public Map<String, Object> vehicles(@RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(name = "fuel_type", required = false) String fuelType,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String offset) {
        Params.oneOf("status", status, List.of("active", "decommissioned"), null);
        Params.oneOf("type", type, List.of("truck", "bus", "car"), null);
        Params.oneOf("fuel_type", fuelType, List.of("diesel", "petrol", "gas"), null);
        Filter filter = new Filter(limit, offset);
        filter.equal("status", status);
        filter.equal("type", type);
        filter.equal("fuel_type", fuelType);
        return filter.page("vehicles", VEHICLE_COLUMNS, "vehicle_id", EntityController::toVehicle);
    }

    @GetMapping("/vehicles/{id}")
    public Map<String, Object> vehicle(@PathVariable String id) {
        return one("SELECT " + VEHICLE_COLUMNS + " FROM vehicles WHERE vehicle_id = ?", id, EntityController::toVehicle,
                "vehicle");
    }

    @GetMapping("/drivers")
    public Map<String, Object> drivers(@RequestParam(required = false) String status,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String offset) {
        Params.oneOf("status", status, List.of("active", "dismissed"), null);
        Filter filter = new Filter(limit, offset);
        filter.equal("status", status);
        return filter.page("drivers", DRIVER_COLUMNS, "driver_id", EntityController::toDriver);
    }

    @GetMapping("/drivers/{id}")
    public Map<String, Object> driver(@PathVariable String id) {
        return one("SELECT " + DRIVER_COLUMNS + " FROM drivers WHERE driver_id = ?", id, EntityController::toDriver,
                "driver");
    }

    @GetMapping("/waybills")
    public Map<String, Object> waybills(@RequestParam(name = "vehicle_id", required = false) String vehicleId,
            @RequestParam(name = "driver_id", required = false) String driverId,
            @RequestParam(name = "from", required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String offset) {
        LocalDate dateFrom = Params.date("from", from);
        LocalDate dateTo = Params.date("to", to);
        Params.checkRange(dateFrom, dateTo);
        Filter filter = new Filter(limit, offset);
        filter.equal("vehicle_id", vehicleId); // по шаблону не проверяются: нет записей — пустая страница
        filter.equal("driver_id", driverId);
        if (dateFrom != null) { // по календарной дате departure_at, включительно
            filter.where.add("departure_at >= ?");
            filter.args.add(dateFrom.atStartOfDay());
        }
        if (dateTo != null) {
            filter.where.add("departure_at < ?");
            filter.args.add(dateTo.plusDays(1).atStartOfDay());
        }
        return filter.page("waybills", WAYBILL_COLUMNS, "departure_at, waybill_id", EntityController::toWaybill);
    }

    @GetMapping("/waybills/{id}")
    public Map<String, Object> waybill(@PathVariable String id) {
        return one("SELECT " + WAYBILL_COLUMNS + " FROM waybills WHERE waybill_id = ?", id, EntityController::toWaybill,
                "waybill");
    }

    private Map<String, Object> one(String sql, String id, RowMapper<Map<String, Object>> mapper, String entity) {
        List<Map<String, Object>> found = jdbc.query(sql, mapper, id);
        if (found.isEmpty()) {
            throw ApiError.notFound(entity, id);
        }
        return found.get(0);
    }

    /** Фильтры списка и страница; limit 1–1000 (по умолчанию 100), offset ≥ 0. */
    private final class Filter {
        final int limit;
        final int offset;
        final List<String> where = new ArrayList<>();
        final List<Object> args = new ArrayList<>();

        Filter(String limit, String offset) {
            this.limit = Params.integer("limit", limit, 1, 1000, 100);
            this.offset = Params.integer("offset", offset, 0, 1_000_000_000, 0);
        }

        void equal(String column, String value) {
            if (!Params.absent(value)) {
                where.add(column + " = ?");
                args.add(value);
            }
        }

        Map<String, Object> page(String table, String columns, String order, RowMapper<Map<String, Object>> mapper) {
            String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + clause, Integer.class, args.toArray());
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(limit);
            pageArgs.add(offset);
            List<Map<String, Object>> items = jdbc.query(
                    "SELECT " + columns + " FROM " + table + clause + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                    mapper, pageArgs.toArray());
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("items", items);
            page.put("total", total);
            page.put("limit", limit);
            page.put("offset", offset);
            return page;
        }
    }

    // --- представление: поля CSV с теми же именами, числа — числами, пустые — null ---

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Map<String, Object> toVehicle(ResultSet rs, int n) throws SQLException {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("vehicle_id", rs.getString("vehicle_id"));
        v.put("plate", rs.getString("plate"));
        v.put("brand", rs.getString("brand"));
        v.put("model", rs.getString("model"));
        v.put("type", rs.getString("type"));
        v.put("year", rs.getInt("year"));
        v.put("fuel_type", rs.getString("fuel_type"));
        v.put("base_consumption_l_100km", rs.getDouble("base_consumption_l_100km"));
        v.put("odometer_km", rs.getInt("odometer_km"));
        v.put("last_service_odometer_km", nullableInt(rs, "last_service_odometer_km"));
        v.put("service_interval_km", rs.getInt("service_interval_km"));
        v.put("license_category", rs.getString("license_category"));
        v.put("status", rs.getString("status"));
        return v;
    }

    static Map<String, Object> toDriver(ResultSet rs, int n) throws SQLException {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("driver_id", rs.getString("driver_id"));
        d.put("full_name", rs.getString("full_name"));
        d.put("license_number", rs.getString("license_number"));
        d.put("license_categories", Arrays.asList(rs.getString("license_categories").split(";")));
        d.put("license_valid_until", rs.getObject("license_valid_until", LocalDate.class).toString());
        d.put("hired_at", rs.getObject("hired_at", LocalDate.class).toString());
        d.put("status", rs.getString("status"));
        return d;
    }

    static Map<String, Object> toWaybill(ResultSet rs, int n) throws SQLException {
        Map<String, Object> w = new LinkedHashMap<>();
        int start = rs.getInt("odometer_start");
        int end = rs.getInt("odometer_end");
        w.put("waybill_id", rs.getString("waybill_id"));
        w.put("vehicle_id", rs.getString("vehicle_id"));
        w.put("driver_id", rs.getString("driver_id"));
        w.put("departure_at", Snapshot.format(rs.getObject("departure_at", LocalDateTime.class)));
        w.put("return_at", Snapshot.format(rs.getObject("return_at", LocalDateTime.class)));
        w.put("odometer_start", start);
        w.put("odometer_end", end);
        w.put("route_description", rs.getString("route_description"));
        w.put("mileage_km", end - start); // вычисляемое поле
        return w;
    }
}
