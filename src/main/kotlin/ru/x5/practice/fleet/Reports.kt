package ru.x5.practice.fleet

import java.time.LocalDate
import java.time.YearMonth

val FUEL_COLUMNS = listOf(
    "vehicle_id", "plate", "fuel_type", "mileage_km", "norm_liters", "actual_liters", "deviation_liters",
    "deviation_pct", "overrun",
)
val MAINTENANCE_DUE_COLUMNS = listOf(
    "vehicle_id", "plate", "current_odometer_km", "service_odometer_km", "last_service_at", "service_interval_km",
    "km_since_service", "km_remaining", "status",
)
val VIOLATION_COLUMNS = listOf(
    "type", "occurred_at", "vehicle_id", "driver_id", "subject_type", "subject_id", "related_id", "details",
)
val UTILIZATION_COLUMNS = listOf(
    "vehicle_id", "plate", "type", "days_in_month", "days_on_line", "days_in_maintenance", "days_idle", "utilization",
)
val DRIVERS_COLUMNS = listOf("driver_id", "full_name", "trips", "mileage_km", "violations")

private val DRIVER_VIOLATION_TYPES = setOf("DRIVER_OVERLAP", "LICENSE_CATEGORY_MISMATCH", "LICENSE_EXPIRED")

typealias Row = Map<String, Any?>

/** Пять отчётов по reports-contract.md и business-rules.md, раздел 3. */
class Reports(private val dom: Domain) {

    private fun Waybill.inMonth(year: Int, month: Int) = departureAt.year == year && departureAt.monthValue == month

    fun fuel(year: Int, month: Int): List<Row> {
        val trips = dom.waybills.filter { it.inMonth(year, month) }
        dom.requireNorms(trips)
        val norm = mutableMapOf<String, Double>()
        val mileage = mutableMapOf<String, Int>()
        val actual = mutableMapOf<String, Double>()
        for (wb in trips) {
            norm.merge(wb.vehicleId, dom.normLiters(wb), Double::plus)
            mileage.merge(wb.vehicleId, wb.mileage, Int::plus)
        }
        for (record in dom.fuel) {
            if (record.filledAt.year == year && record.filledAt.monthValue == month) {
                actual.merge(record.vehicleId, record.liters, Double::plus)
            }
        }
        return (norm.keys + actual.keys).sorted().map { vehicleId ->
            val v = dom.vehicle(vehicleId)
            val n = norm[vehicleId] ?: 0.0
            val a = actual[vehicleId] ?: 0.0
            linkedMapOf(
                "vehicle_id" to vehicleId, "plate" to v.plate, "fuel_type" to v.fuelType,
                "mileage_km" to (mileage[vehicleId] ?: 0), "norm_liters" to round(n, 2), "actual_liters" to round(a, 2),
                "deviation_liters" to round(a - n, 2),
                "deviation_pct" to if (n == 0.0) null else round((a - n) / n * 100, 1),
                "overrun" to (a > n * (1 + FUEL_OVERRUN_THRESHOLD)),
            )
        }
    }

    fun maintenanceDue(asOf: LocalDate): List<Row> {
        val asOfEnd = asOf.atTime(23, 59, 59)
        val rows = dom.vehicles.values.filter { it.status == "active" }.map { v ->
            val done = dom.waybillsByVehicle[v.vehicleId].orEmpty().filter { it.returnAt <= asOfEnd }
            val service = dom.serviceOdometer(v.vehicleId, asOfEnd)
            val current = maxOf(done.maxWithOrNull(Waybill.ORDER)?.odometerEnd ?: v.odometerKm, service.odometerKm)
            val since = current - service.odometerKm
            val remaining = v.serviceIntervalKm - since
            val status = when {
                remaining < 0 -> "overdue"
                remaining <= v.serviceIntervalKm * DUE_SOON_FRACTION -> "due_soon"
                else -> "ok"
            }
            linkedMapOf(
                "vehicle_id" to v.vehicleId, "plate" to v.plate, "current_odometer_km" to current,
                "service_odometer_km" to service.odometerKm, "last_service_at" to service.maintenance?.startedAt?.iso(),
                "service_interval_km" to v.serviceIntervalKm, "km_since_service" to since, "km_remaining" to remaining,
                "status" to status,
            )
        }
        return rows.sortedWith(compareBy({ it["km_remaining"] as Int }, { it["vehicle_id"] as String }))
    }

    fun violations(type: String?, from: LocalDate?, to: LocalDate?): List<Row> =
        Rules(dom).violations(type)
            .filter { from == null || it.occurredAt.substring(0, 10) >= from.iso() }
            .filter { to == null || it.occurredAt.substring(0, 10) <= to.iso() }
            .map { it.toRow() }

    fun utilization(year: Int, month: Int): List<Row> {
        val daysInMonth = YearMonth.of(year, month).lengthOfMonth()
        val days = (1..daysInMonth).map { LocalDate.of(year, month, it).atStartOfDay() }
        return dom.vehicles.values.filter { it.status == "active" }.map { v ->
            val trips = dom.waybillsByVehicle[v.vehicleId].orEmpty()
            val repairs = dom.maintenanceByVehicle[v.vehicleId].orEmpty()
            var onLine = 0
            var inMaintenance = 0
            var busy = 0
            for (day in days) {
                val next = day.plusDays(1)
                val working = trips.any { overlaps(day, next, it.departureAt, it.returnAt) }
                val repaired = repairs.any { overlaps(day, next, it.startedAt, it.finishedAt) }
                if (working) onLine++
                if (repaired) inMaintenance++
                if (working || repaired) busy++
            }
            linkedMapOf(
                "vehicle_id" to v.vehicleId, "plate" to v.plate, "type" to v.type, "days_in_month" to daysInMonth,
                "days_on_line" to onLine, "days_in_maintenance" to inMaintenance, "days_idle" to daysInMonth - busy,
                "utilization" to round(onLine.toDouble() / daysInMonth, 3),
            )
        }
    }

    fun drivers(year: Int, month: Int): List<Row> {
        val trips = mutableMapOf<String, Int>()
        val mileage = mutableMapOf<String, Int>()
        for (wb in dom.waybills.filter { it.inMonth(year, month) }) {
            trips.merge(wb.driverId, 1, Int::plus)
            mileage.merge(wb.driverId, wb.mileage, Int::plus)
        }
        val prefix = "%04d-%02d".format(year, month)
        val counts = Rules(dom).waybillViolations()
            .filter { it.type in DRIVER_VIOLATION_TYPES && it.occurredAt.startsWith(prefix) }
            .groupingBy { it.driverId!! }
            .eachCount()
        return (trips.keys + counts.keys).sorted().map { driverId ->
            linkedMapOf(
                "driver_id" to driverId, "full_name" to dom.drivers.getValue(driverId).fullName,
                "trips" to (trips[driverId] ?: 0), "mileage_km" to (mileage[driverId] ?: 0),
                "violations" to (counts[driverId] ?: 0),
            )
        }
    }
}
