package ru.x5.practice.fleet

import java.time.LocalDate
import java.time.LocalDateTime

/** Сущности так, как они лежат в базе, и их представление в API (api-contract.md, 4). */

data class Vehicle(
    val vehicleId: String,
    val plate: String,
    val brand: String,
    val model: String,
    val type: String,
    val year: Int,
    val fuelType: String,
    val baseConsumption: Double,
    val odometerKm: Int,
    val lastServiceOdometerKm: Int?,
    val serviceIntervalKm: Int,
    val licenseCategory: String,
    val status: String,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "vehicle_id" to vehicleId, "plate" to plate, "brand" to brand, "model" to model, "type" to type,
        "year" to year, "fuel_type" to fuelType, "base_consumption_l_100km" to baseConsumption,
        "odometer_km" to odometerKm, "last_service_odometer_km" to lastServiceOdometerKm,
        "service_interval_km" to serviceIntervalKm, "license_category" to licenseCategory, "status" to status,
    )
}

data class Driver(
    val driverId: String,
    val fullName: String,
    val licenseNumber: String,
    val licenseCategories: List<String>,
    val licenseValidUntil: LocalDate,
    val hiredAt: LocalDate,
    val status: String,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "driver_id" to driverId, "full_name" to fullName, "license_number" to licenseNumber,
        "license_categories" to licenseCategories, "license_valid_until" to licenseValidUntil.iso(),
        "hired_at" to hiredAt.iso(), "status" to status,
    )
}

data class Waybill(
    val waybillId: String,
    val vehicleId: String,
    val driverId: String,
    val departureAt: LocalDateTime,
    val returnAt: LocalDateTime,
    val odometerStart: Int,
    val odometerEnd: Int,
    val routeDescription: String?,
) {
    val mileage: Int get() = odometerEnd - odometerStart

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "waybill_id" to waybillId, "vehicle_id" to vehicleId, "driver_id" to driverId,
        "departure_at" to departureAt.iso(), "return_at" to returnAt.iso(), "odometer_start" to odometerStart,
        "odometer_end" to odometerEnd, "route_description" to routeDescription, "mileage_km" to mileage,
    )

    companion object {
        /** Порядок листов по контракту: departure_at, затем waybill_id. */
        val ORDER: Comparator<Waybill> = compareBy<Waybill>({ it.departureAt }, { it.waybillId })
    }
}

data class FuelRecord(val recordId: String, val vehicleId: String, val filledAt: LocalDateTime, val liters: Double)

data class Maintenance(
    val maintenanceId: String,
    val vehicleId: String,
    val type: String,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val odometerKm: Int,
) {
    companion object {
        val ORDER: Comparator<Maintenance> = compareBy<Maintenance>({ it.startedAt }, { it.maintenanceId })
    }
}

data class Page(val items: List<Map<String, Any?>>, val total: Int, val limit: Int, val offset: Int)
