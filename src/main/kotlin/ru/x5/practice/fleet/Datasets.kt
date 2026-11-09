package ru.x5.practice.fleet

import java.sql.Types
import java.time.LocalDateTime

/** Значение не соответствует типу или шаблону поля: INVALID_FORMAT. */
class FormatError : Exception()

typealias Parser = (String) -> Any
typealias Check = (Any) -> Boolean

/**
 * Описание одного набора из data-contract.md, раздел 2: колонки, обязательные поля, разбор и
 * проверки в порядке раздела 3.5.
 */
class DatasetSpec(
    val name: String,
    val columns: List<String>,
    val required: List<String>,
    val primaryKey: List<String>,
    val parsers: Map<String, Parser> = emptyMap(),
    /** проверки допустимых значений над разобранным значением, по порядку */
    val values: Map<String, Check> = emptyMap(),
    /** межполевая проверка: имя поля с ошибкой или null */
    val cross: ((Map<String, Any?>) -> String?)? = null,
    /** поле → набор, на идентификатор которого оно ссылается */
    val references: Map<String, String> = emptyMap(),
    val unique: List<String> = emptyList(),
    /** SQL-тип колонок, не являющихся строками */
    val sqlTypes: Map<String, Int> = emptyMap(),
) {
    val table: String get() = name

    fun sqlType(column: String): Int = sqlTypes[column] ?: Types.VARCHAR
}

private val PLATE = Regex("[АВЕКМНОРСТУХ][0-9]{3}[АВЕКМНОРСТУХ]{2}[0-9]{2,3}")
private val LICENSE = Regex("[0-9]{2} [0-9]{2} [0-9]{6}")
private val INT = Regex("[0-9]{1,9}")

private fun pattern(regex: Regex): Parser = { s -> if (regex.matches(s)) s else throw FormatError() }

private fun id(prefix: String, digits: Int): Parser = pattern(Regex("$prefix-[0-9]{$digits}"))

private val integer: Parser = { s -> if (INT.matches(s)) s.toInt() else throw FormatError() }

private fun decimal(places: Int): Parser {
    val regex = Regex("[0-9]{1,9}(\\.[0-9]{1,$places})?")
    return { s -> if (regex.matches(s)) s.toDouble() else throw FormatError() }
}

private val date: Parser = { s -> parseDate(s) ?: throw FormatError() }

private val dateTime: Parser = { s -> parseDateTime(s) ?: throw FormatError() }

/** Не длиннее max кодовых точек Unicode. */
private fun text(max: Int): Parser = { s -> if (s.codePointCount(0, s.length) > max) throw FormatError() else s }

/** Список категорий: непустые элементы через «;» без пробелов и повторов. Перечисление — отдельно. */
private val categoryList: Parser = { s ->
    val parts = s.split(';')
    val broken = parts.any { part -> part.isEmpty() || part.any { it.isWhitespace() || it == '\u0085' } }
    if (broken || parts.toSet().size != parts.size) throw FormatError()
    parts
}

private fun oneOf(vararg allowed: String): Check = { v -> v in allowed }

private fun between(lo: Double, hi: Double): Check = { v -> (v as Number).toDouble() in lo..hi }

private val ENUM_CATEGORIES = setOf("B", "C", "D")

val DATASETS: Map<String, DatasetSpec> = listOf(
    DatasetSpec(
        name = "vehicles",
        columns = listOf(
            "vehicle_id", "plate", "brand", "model", "type", "year", "fuel_type", "base_consumption_l_100km",
            "odometer_km", "last_service_odometer_km", "service_interval_km", "license_category", "status",
        ),
        required = listOf(
            "vehicle_id", "plate", "brand", "model", "type", "year", "fuel_type", "base_consumption_l_100km",
            "odometer_km", "service_interval_km", "license_category", "status",
        ),
        primaryKey = listOf("vehicle_id"),
        parsers = mapOf(
            "vehicle_id" to id("V", 3), "plate" to pattern(PLATE), "brand" to text(50), "model" to text(50),
            "year" to integer, "base_consumption_l_100km" to decimal(1), "odometer_km" to integer,
            "last_service_odometer_km" to integer, "service_interval_km" to integer,
        ),
        values = linkedMapOf(
            "type" to oneOf("truck", "bus", "car"), "year" to between(1990.0, 2026.0),
            "fuel_type" to oneOf("diesel", "petrol", "gas"), "base_consumption_l_100km" to between(3.0, 60.0),
            "odometer_km" to between(0.0, 2_000_000.0), "last_service_odometer_km" to between(0.0, 2_000_000.0),
            "service_interval_km" to between(5_000.0, 50_000.0), "license_category" to oneOf("B", "C", "D"),
            "status" to oneOf("active", "decommissioned"),
        ),
        cross = { r ->
            val last = r["last_service_odometer_km"] as Int?
            if (last != null && last > r["odometer_km"] as Int) "last_service_odometer_km" else null
        },
        unique = listOf("plate"),
        sqlTypes = mapOf(
            "year" to Types.INTEGER, "base_consumption_l_100km" to Types.DOUBLE, "odometer_km" to Types.INTEGER,
            "last_service_odometer_km" to Types.INTEGER, "service_interval_km" to Types.INTEGER,
        ),
    ),
    DatasetSpec(
        name = "drivers",
        columns = listOf(
            "driver_id", "full_name", "license_number", "license_categories", "license_valid_until", "hired_at", "status",
        ),
        required = listOf(
            "driver_id", "full_name", "license_number", "license_categories", "license_valid_until", "hired_at", "status",
        ),
        primaryKey = listOf("driver_id"),
        parsers = mapOf(
            "driver_id" to id("D", 3), "full_name" to text(100), "license_number" to pattern(LICENSE),
            "license_categories" to categoryList, "license_valid_until" to date, "hired_at" to date,
        ),
        values = linkedMapOf(
            "license_categories" to { v: Any -> (v as List<*>).all { it in ENUM_CATEGORIES } },
            "status" to oneOf("active", "dismissed"),
        ),
        unique = listOf("license_number"),
        sqlTypes = mapOf("license_valid_until" to Types.DATE, "hired_at" to Types.DATE),
    ),
    DatasetSpec(
        name = "fuel_norms",
        columns = listOf("fuel_type", "season", "coefficient"),
        required = listOf("fuel_type", "season", "coefficient"),
        primaryKey = listOf("fuel_type", "season"),
        parsers = mapOf("coefficient" to decimal(2)),
        values = linkedMapOf(
            "fuel_type" to oneOf("diesel", "petrol", "gas"), "season" to oneOf("winter", "summer"),
            "coefficient" to between(0.5, 2.0),
        ),
        sqlTypes = mapOf("coefficient" to Types.DOUBLE),
    ),
    DatasetSpec(
        name = "waybills",
        columns = listOf(
            "waybill_id", "vehicle_id", "driver_id", "departure_at", "return_at", "odometer_start", "odometer_end",
            "route_description",
        ),
        required = listOf(
            "waybill_id", "vehicle_id", "driver_id", "departure_at", "return_at", "odometer_start", "odometer_end",
        ),
        primaryKey = listOf("waybill_id"),
        parsers = mapOf(
            "waybill_id" to id("WB", 6), "vehicle_id" to id("V", 3), "driver_id" to id("D", 3),
            "departure_at" to dateTime, "return_at" to dateTime, "odometer_start" to integer,
            "odometer_end" to integer, "route_description" to text(200),
        ),
        values = linkedMapOf(
            "odometer_start" to between(0.0, 2_000_000.0), "odometer_end" to between(0.0, 2_000_000.0),
        ),
        cross = { r ->
            when {
                r["return_at"] as LocalDateTime <= r["departure_at"] as LocalDateTime -> "return_at"
                (r["odometer_end"] as Int) < r["odometer_start"] as Int -> "odometer_end"
                else -> null
            }
        },
        references = linkedMapOf("vehicle_id" to "vehicles", "driver_id" to "drivers"),
        sqlTypes = mapOf(
            "departure_at" to Types.TIMESTAMP, "return_at" to Types.TIMESTAMP,
            "odometer_start" to Types.INTEGER, "odometer_end" to Types.INTEGER,
        ),
    ),
    DatasetSpec(
        name = "fuel_records",
        columns = listOf("record_id", "vehicle_id", "driver_id", "filled_at", "liters", "price_per_liter", "station"),
        required = listOf("record_id", "vehicle_id", "filled_at", "liters", "price_per_liter"),
        primaryKey = listOf("record_id"),
        parsers = mapOf(
            "record_id" to id("F", 6), "vehicle_id" to id("V", 3), "driver_id" to id("D", 3),
            "filled_at" to dateTime, "liters" to decimal(2), "price_per_liter" to decimal(2), "station" to text(100),
        ),
        values = linkedMapOf("liters" to between(0.01, 1000.0), "price_per_liter" to between(0.01, 500.0)),
        references = linkedMapOf("vehicle_id" to "vehicles", "driver_id" to "drivers"),
        sqlTypes = mapOf("filled_at" to Types.TIMESTAMP, "liters" to Types.DOUBLE, "price_per_liter" to Types.DOUBLE),
    ),
    DatasetSpec(
        name = "maintenance",
        columns = listOf(
            "maintenance_id", "vehicle_id", "type", "started_at", "finished_at", "odometer_km", "cost", "description",
        ),
        required = listOf("maintenance_id", "vehicle_id", "type", "started_at", "odometer_km", "cost"),
        primaryKey = listOf("maintenance_id"),
        parsers = mapOf(
            "maintenance_id" to id("M", 5), "vehicle_id" to id("V", 3), "started_at" to dateTime,
            "finished_at" to dateTime, "odometer_km" to integer, "cost" to decimal(2), "description" to text(200),
        ),
        values = linkedMapOf(
            "type" to oneOf("scheduled", "repair"), "odometer_km" to between(0.0, 2_000_000.0),
            "cost" to between(0.0, 10_000_000.0),
        ),
        cross = { r ->
            val finished = r["finished_at"] as LocalDateTime?
            if (finished != null && finished < r["started_at"] as LocalDateTime) "finished_at" else null
        },
        references = linkedMapOf("vehicle_id" to "vehicles"),
        sqlTypes = mapOf(
            "started_at" to Types.TIMESTAMP, "finished_at" to Types.TIMESTAMP, "odometer_km" to Types.INTEGER,
            "cost" to Types.DOUBLE,
        ),
    ),
).associateBy { it.name }

/** Порядок шести наборов: /health и схема базы. */
val TABLES = listOf("vehicles", "drivers", "fuel_norms", "waybills", "fuel_records", "maintenance")
