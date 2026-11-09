package ru.x5.practice.fleet

import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

/** Условие списка: SQL-фрагмент и его параметры. */
data class Filter(val sql: String, val value: Any)

/** Чтение сущностей из базы: страницы списков, карточки и полный снимок для отчётов. */
@Repository
class FleetRepository(private val jdbc: JdbcTemplate) {

    fun vehicles(filters: List<Filter>, limit: Int, offset: Int): Page =
        page("vehicles", filters, "vehicle_id COLLATE \"C\"", limit, offset, VEHICLE) { it.toJson() }

    fun drivers(filters: List<Filter>, limit: Int, offset: Int): Page =
        page("drivers", filters, "driver_id COLLATE \"C\"", limit, offset, DRIVER) { it.toJson() }

    fun waybills(filters: List<Filter>, limit: Int, offset: Int): Page =
        page("waybills", filters, "departure_at, waybill_id COLLATE \"C\"", limit, offset, WAYBILL) { it.toJson() }

    fun vehicle(id: String): Vehicle? = one("SELECT * FROM vehicles WHERE vehicle_id = ?", VEHICLE, id)

    fun driver(id: String): Driver? = one("SELECT * FROM drivers WHERE driver_id = ?", DRIVER, id)

    fun waybill(id: String): Waybill? = one("SELECT * FROM waybills WHERE waybill_id = ?", WAYBILL, id)

    /** Всё, что нужно правилам и отчётам, одним снимком. */
    fun snapshot(): Domain = Domain(
        vehicles = jdbc.query("SELECT * FROM vehicles", VEHICLE),
        drivers = jdbc.query("SELECT * FROM drivers", DRIVER),
        norms = jdbc.query("SELECT fuel_type, season, coefficient FROM fuel_norms") { rs, _ ->
            (rs.getString("fuel_type") to rs.getString("season")) to rs.getDouble("coefficient")
        }.toMap(),
        waybills = jdbc.query("SELECT * FROM waybills", WAYBILL),
        fuel = jdbc.query("SELECT record_id, vehicle_id, filled_at, liters FROM fuel_records") { rs, _ ->
            FuelRecord(rs.getString("record_id"), rs.getString("vehicle_id"), rs.dateTime("filled_at")!!, rs.getDouble("liters"))
        },
        maintenance = jdbc.query("SELECT * FROM maintenance", MAINTENANCE),
    )

    private fun <T : Any> one(sql: String, mapper: RowMapper<T>, id: String): T? =
        jdbc.query(sql, mapper, id).firstOrNull()

    private fun <T : Any> page(
        table: String,
        filters: List<Filter>,
        order: String,
        limit: Int,
        offset: Int,
        mapper: RowMapper<T>,
        toJson: (T) -> Map<String, Any?>,
    ): Page {
        val where = if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ") { it.sql }
        val args = filters.map { it.value }
        val total = jdbc.queryForObject("SELECT COUNT(*) FROM $table$where", Int::class.java, *args.toTypedArray())!!
        val items = jdbc.query(
            "SELECT * FROM $table$where ORDER BY $order LIMIT ? OFFSET ?", mapper, *(args + limit + offset).toTypedArray(),
        )
        return Page(items.map(toJson), total, limit, offset)
    }

    companion object {
        private fun ResultSet.int(column: String): Int? = getObject(column)?.let { (it as Number).toInt() }

        private fun ResultSet.date(column: String): LocalDate? = getObject(column, LocalDate::class.java)

        private fun ResultSet.dateTime(column: String): LocalDateTime? = getObject(column, LocalDateTime::class.java)

        val VEHICLE = RowMapper<Vehicle> { rs, _ ->
            Vehicle(
                vehicleId = rs.getString("vehicle_id"), plate = rs.getString("plate"), brand = rs.getString("brand"),
                model = rs.getString("model"), type = rs.getString("type"), year = rs.getInt("year"),
                fuelType = rs.getString("fuel_type"), baseConsumption = rs.getDouble("base_consumption_l_100km"),
                odometerKm = rs.getInt("odometer_km"), lastServiceOdometerKm = rs.int("last_service_odometer_km"),
                serviceIntervalKm = rs.getInt("service_interval_km"), licenseCategory = rs.getString("license_category"),
                status = rs.getString("status"),
            )
        }

        val DRIVER = RowMapper<Driver> { rs, _ ->
            Driver(
                driverId = rs.getString("driver_id"), fullName = rs.getString("full_name"),
                licenseNumber = rs.getString("license_number"),
                licenseCategories = rs.getString("license_categories").split(';'),
                licenseValidUntil = rs.date("license_valid_until")!!, hiredAt = rs.date("hired_at")!!,
                status = rs.getString("status"),
            )
        }

        val WAYBILL = RowMapper<Waybill> { rs, _ ->
            Waybill(
                waybillId = rs.getString("waybill_id"), vehicleId = rs.getString("vehicle_id"),
                driverId = rs.getString("driver_id"), departureAt = rs.dateTime("departure_at")!!,
                returnAt = rs.dateTime("return_at")!!, odometerStart = rs.getInt("odometer_start"),
                odometerEnd = rs.getInt("odometer_end"), routeDescription = rs.getString("route_description"),
            )
        }

        val MAINTENANCE = RowMapper<Maintenance> { rs, _ ->
            Maintenance(
                maintenanceId = rs.getString("maintenance_id"), vehicleId = rs.getString("vehicle_id"),
                type = rs.getString("type"), startedAt = rs.dateTime("started_at")!!,
                finishedAt = rs.dateTime("finished_at"), odometerKm = rs.getInt("odometer_km"),
            )
        }
    }
}
