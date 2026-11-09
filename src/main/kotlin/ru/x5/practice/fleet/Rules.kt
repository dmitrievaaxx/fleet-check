package ru.x5.practice.fleet

import java.time.LocalDateTime

val VIOLATION_TYPES = listOf(
    "VEHICLE_OVERLAP", "DRIVER_OVERLAP", "LICENSE_CATEGORY_MISMATCH", "LICENSE_EXPIRED", "ODOMETER_GAP",
    "VEHICLE_DECOMMISSIONED", "VEHICLE_IN_MAINTENANCE", "MAINTENANCE_OVERDUE", "FUEL_OVERRUN",
)

data class Violation(
    val type: String,
    val occurredAt: String,
    val vehicleId: String,
    val driverId: String?,
    val subjectType: String,
    val subjectId: String,
    val relatedId: String?,
    val details: Map<String, Any?>,
) {
    fun toRow(): Map<String, Any?> = linkedMapOf(
        "type" to type, "occurred_at" to occurredAt, "vehicle_id" to vehicleId, "driver_id" to driverId,
        "subject_type" to subjectType, "subject_id" to subjectId, "related_id" to relatedId, "details" to details,
    )

    companion object {
        /** Сортировка отчёта: occurred_at, type, subject_id, related_id (null первым). */
        val ORDER: Comparator<Violation> =
            compareBy<Violation>({ it.occurredAt }, { it.type }, { it.subjectId }, { it.relatedId ?: "" })
    }
}

/** Девять типов нарушений по business-rules.md, раздел 2. */
class Rules(private val dom: Domain) {

    private fun onWaybill(type: String, wb: Waybill, relatedId: String?, details: Map<String, Any?>) =
        Violation(type, wb.departureAt.iso(), wb.vehicleId, wb.driverId, "waybill", wb.waybillId, relatedId, details)

    private fun overlapping(groups: Map<String, List<Waybill>>, type: String): List<Violation> {
        val rows = mutableListOf<Violation>()
        for ((_, list) in groups.toSortedMap()) {
            for (j in list.indices) {
                val b = list[j]
                for (i in 0 until j) {
                    val a = list[i]
                    if (overlaps(a.departureAt, a.returnAt, b.departureAt, b.returnAt)) {
                        rows += onWaybill(
                            type, b, a.waybillId,
                            mapOf("related_departure_at" to a.departureAt.iso(), "related_return_at" to a.returnAt.iso()),
                        )
                    }
                }
            }
        }
        return rows
    }

    /** Нарушения по путевым листам: всё, кроме FUEL_OVERRUN. */
    fun waybillViolations(): List<Violation> {
        val rows = mutableListOf<Violation>()
        rows += overlapping(dom.waybillsByVehicle, "VEHICLE_OVERLAP")
        rows += overlapping(dom.waybillsByDriver, "DRIVER_OVERLAP")

        for (wb in dom.waybills) {
            val v = dom.vehicle(wb.vehicleId)
            val d = dom.drivers.getValue(wb.driverId)
            if (v.licenseCategory !in d.licenseCategories) {
                rows += onWaybill(
                    "LICENSE_CATEGORY_MISMATCH", wb, null,
                    mapOf("required" to v.licenseCategory, "driver_categories" to d.licenseCategories),
                )
            }
            if (d.licenseValidUntil < wb.departureAt.toLocalDate()) {
                rows += onWaybill("LICENSE_EXPIRED", wb, null, mapOf("license_valid_until" to d.licenseValidUntil.iso()))
            }
            if (v.status == "decommissioned") {
                rows += onWaybill("VEHICLE_DECOMMISSIONED", wb, null, emptyMap())
            }
            val repair = dom.maintenanceByVehicle[v.vehicleId].orEmpty()
                .filter { overlaps(wb.departureAt, wb.returnAt, it.startedAt, it.finishedAt) }
                .minWithOrNull(Maintenance.ORDER)
            if (repair != null) {
                rows += onWaybill(
                    "VEHICLE_IN_MAINTENANCE", wb, repair.maintenanceId,
                    mapOf(
                        "maintenance_type" to repair.type, "started_at" to repair.startedAt.iso(),
                        "finished_at" to repair.finishedAt?.iso(),
                    ),
                )
            }
        }

        for ((vehicleId, list) in dom.waybillsByVehicle.toSortedMap()) {
            val v = dom.vehicle(vehicleId)
            var expected = v.odometerKm
            var prev: Waybill? = null
            var inEpisode = false
            var lastService: Int? = null
            for (wb in list) {
                val delta = wb.odometerStart - expected
                if (delta < 0 || delta > ODOMETER_GAP_TOLERANCE_KM) {
                    rows += onWaybill(
                        "ODOMETER_GAP", wb, prev?.waybillId,
                        mapOf("expected" to expected, "actual" to wb.odometerStart, "delta" to delta),
                    )
                }
                expected = wb.odometerEnd
                prev = wb

                // эпизод пропущенного ТО: одно нарушение до следующего планового ТО
                val service = dom.serviceOdometer(vehicleId, wb.departureAt).odometerKm
                if (service != lastService) {
                    inEpisode = false
                    lastService = service
                }
                val kmSince = wb.odometerEnd - service
                if (!inEpisode && kmSince > v.serviceIntervalKm) {
                    inEpisode = true
                    rows += onWaybill(
                        "MAINTENANCE_OVERDUE", wb, null,
                        mapOf(
                            "service_odometer" to service, "odometer_end" to wb.odometerEnd,
                            "km_since_service" to kmSince, "service_interval_km" to v.serviceIntervalKm,
                        ),
                    )
                }
            }
        }
        return rows
    }

    private data class VehicleMonth(val vehicleId: String, val year: Int, val month: Int)

    /** Перерасход топлива по паре (ТС, месяц); без коэффициентов — 422. */
    fun fuelOverruns(): List<Violation> {
        dom.requireNorms(dom.waybills)
        val norm = mutableMapOf<VehicleMonth, Double>()
        val mileage = mutableMapOf<VehicleMonth, Int>()
        val actual = mutableMapOf<VehicleMonth, Double>()
        for (wb in dom.waybills) {
            val key = VehicleMonth(wb.vehicleId, wb.departureAt.year, wb.departureAt.monthValue)
            norm.merge(key, dom.normLiters(wb), Double::plus)
            mileage.merge(key, wb.mileage, Int::plus)
        }
        for (record in dom.fuel) {
            actual.merge(VehicleMonth(record.vehicleId, record.filledAt.year, record.filledAt.monthValue), record.liters, Double::plus)
        }
        val keys = (norm.keys + actual.keys).sortedWith(compareBy({ it.vehicleId }, { it.year }, { it.month }))
        return keys.mapNotNull { key ->
            val n = norm[key] ?: 0.0
            val a = actual[key] ?: 0.0
            if (a > n * (1 + FUEL_OVERRUN_THRESHOLD)) {
                Violation(
                    "FUEL_OVERRUN", LocalDateTime.of(key.year, key.month, 1, 0, 0).iso(), key.vehicleId, null,
                    "vehicle_month", key.vehicleId, null,
                    linkedMapOf(
                        "year" to key.year, "month" to key.month, "mileage_km" to (mileage[key] ?: 0),
                        "norm_liters" to round(n, 2), "actual_liters" to round(a, 2),
                        "deviation_pct" to if (n == 0.0) null else round((a - n) / n * 100, 1),
                    ),
                )
            } else {
                null
            }
        }
    }

    fun violations(type: String? = null): List<Violation> {
        val rows = mutableListOf<Violation>()
        if (type != "FUEL_OVERRUN") rows += waybillViolations()
        if (type == null || type == "FUEL_OVERRUN") rows += fuelOverruns()
        return rows.filter { type == null || it.type == type }.sortedWith(Violation.ORDER)
    }
}
