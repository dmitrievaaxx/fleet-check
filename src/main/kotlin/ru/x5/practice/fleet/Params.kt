package ru.x5.practice.fleet

import java.time.LocalDate

/**
 * Параметры запросов принимаются строками и проверяются здесь: любая ошибка — 400
 * INVALID_PARAMETER в формате контракта, а не стандартный ответ Spring. Пустое значение
 * равнозначно отсутствующему (api-contract.md, 1.5).
 */
object Params {
    private val INTEGER = Regex("-?[0-9]{1,18}")

    fun int(name: String, raw: String?, range: IntRange, default: Int? = null, required: Boolean = false): Int? {
        if (raw.isNullOrEmpty()) {
            if (required) throw invalidParameter(name, raw, "$name is required")
            return default
        }
        val value = if (INTEGER.matches(raw)) raw.toLong() else throw invalidParameter(name, raw, "$name must be an integer")
        if (value < range.first || value > range.last) {
            throw invalidParameter(name, raw, "$name must be between ${range.first} and ${range.last}")
        }
        return value.toInt()
    }

    fun requiredInt(name: String, raw: String?, range: IntRange): Int = int(name, raw, range, required = true)!!

    fun date(name: String, raw: String?, required: Boolean = false): LocalDate? {
        if (raw.isNullOrEmpty()) {
            if (required) throw invalidParameter(name, raw, "$name is required")
            return null
        }
        return parseDate(raw) ?: throw invalidParameter(name, raw, "$name must be a date YYYY-MM-DD")
    }

    fun enum(name: String, raw: String?, allowed: Collection<String>, default: String? = null): String? {
        if (raw.isNullOrEmpty()) return default
        if (raw !in allowed) throw invalidParameter(name, raw, "$name must be one of ${allowed.joinToString(", ")}")
        return raw
    }

    fun period(from: LocalDate?, to: LocalDate?) {
        if (from != null && to != null && from > to) {
            throw invalidParameter("from", from.toString(), "from must not be after to")
        }
    }

    fun format(raw: String?): String = enum("format", raw, listOf("json", "csv"), "json")!!
}
