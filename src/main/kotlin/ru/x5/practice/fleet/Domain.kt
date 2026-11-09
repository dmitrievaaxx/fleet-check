package ru.x5.practice.fleet

import java.time.LocalDate
import java.time.LocalDateTime
import org.springframework.http.HttpStatus

const val FUEL_OVERRUN_THRESHOLD = 0.10
const val DUE_SOON_FRACTION = 0.10
const val ODOMETER_GAP_TOLERANCE_KM = 5
private val WINTER_MONTHS = setOf(11, 12, 1, 2, 3)

fun seasonOf(date: LocalDate): String = if (date.monthValue in WINTER_MONTHS) "winter" else "summer"

/** Пересечение полуоткрытых интервалов [start, end); null — открытый правый конец. */
fun overlaps(aStart: LocalDateTime, aEnd: LocalDateTime?, bStart: LocalDateTime, bEnd: LocalDateTime?): Boolean =
    (bEnd == null || aStart < bEnd) && (aEnd == null || bStart < aEnd)

data class ServicePoint(val odometerKm: Int, val maintenance: Maintenance?)

/**
 * Снимок базы в памяти с индексами, нужными правилам и отчётам (business-rules.md, раздел 1).
 * Анализ идёт при каждом запросе отчёта: на десятке тысяч листов это доли секунды.
 */
class Domain(
    vehicles: List<Vehicle>,
    drivers: List<Driver>,
    val norms: Map<Pair<String, String>, Double>,
    waybills: List<Waybill>,
    fuel: List<FuelRecord>,
    maintenance: List<Maintenance>,
) {
    val vehicles: Map<String, Vehicle> = vehicles.sortedBy { it.vehicleId }.associateBy { it.vehicleId }
    val drivers: Map<String, Driver> = drivers.sortedBy { it.driverId }.associateBy { it.driverId }
    val waybills: List<Waybill> = waybills.sortedWith(Waybill.ORDER)
    val fuel: List<FuelRecord> = fuel.sortedWith(compareBy({ it.filledAt }, { it.recordId }))
    val maintenance: List<Maintenance> = maintenance.sortedWith(Maintenance.ORDER)

    val waybillsByVehicle: Map<String, List<Waybill>> = this.waybills.groupBy { it.vehicleId }
    val waybillsByDriver: Map<String, List<Waybill>> = this.waybills.groupBy { it.driverId }
    val maintenanceByVehicle: Map<String, List<Maintenance>> = this.maintenance.groupBy { it.vehicleId }

    fun vehicle(id: String): Vehicle = vehicles.getValue(id)

    /** Пробег на последнем плановом ТО к моменту at (business-rules.md, 1.5). */
    fun serviceOdometer(vehicleId: String, at: LocalDateTime): ServicePoint {
        val v = vehicle(vehicleId)
        val last = maintenanceByVehicle[vehicleId].orEmpty()
            .filter { it.type == "scheduled" && it.startedAt <= at }
            .maxWithOrNull(Maintenance.ORDER)
        return when {
            last != null -> ServicePoint(last.odometerKm, last)
            v.lastServiceOdometerKm != null -> ServicePoint(v.lastServiceOdometerKm, null)
            else -> ServicePoint(v.odometerKm, null)
        }
    }

    /** Сочетания топлива и сезона, нужные для расчёта и отсутствующие в справочнике; 422, если есть. */
    fun requireNorms(trips: List<Waybill>) {
        val needed = trips.map { vehicle(it.vehicleId).fuelType to seasonOf(it.departureAt.toLocalDate()) }
            .toSortedSet(compareBy({ it.first }, { it.second }))
        val missing = needed.filter { it !in norms }.map { (fuelType, season) -> mapOf("fuel_type" to fuelType, "season" to season) }
        if (missing.isNotEmpty()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "FUEL_NORM_MISSING", "fuel norm coefficients are missing",
                mapOf("missing" to missing),
            )
        }
    }

    fun normLiters(wb: Waybill): Double {
        val v = vehicle(wb.vehicleId)
        val coefficient = norms.getValue(v.fuelType to seasonOf(wb.departureAt.toLocalDate()))
        return wb.mileage * v.baseConsumption * coefficient / 100
    }
}
