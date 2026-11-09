package ru.x5.practice.fleet

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Форматы дат и чисел из data-contract.md, 1.2: общие для импорта, параметров запросов и отчётов. */

private val DATE_RE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val DATETIME_RE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")
private val DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")

/** YYYY-MM-DD, календарно корректная дата; иначе null. */
fun parseDate(s: String): LocalDate? {
    if (!DATE_RE.matches(s)) return null
    return try {
        LocalDate.parse(s).takeIf { it.year >= 1 }
    } catch (e: DateTimeParseException) {
        null
    }
}

/** YYYY-MM-DDTHH:MM:SS без зоны и дробных секунд; иначе null. */
fun parseDateTime(s: String): LocalDateTime? {
    if (!DATETIME_RE.matches(s)) return null
    return try {
        LocalDateTime.parse(s).takeIf { it.year >= 1 }
    } catch (e: DateTimeParseException) {
        null
    }
}

fun LocalDateTime.iso(): String = format(DATETIME_FORMAT)

fun LocalDate.iso(): String = toString()

/** Округление как у round() в Python: точное двоичное значение, половина — к чётному. */
fun round(x: Double, places: Int): Double = BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).toDouble()

/** Пробельные символы Unicode по краям, включая неразрывный пробел (data-contract.md, 1.2). */
fun String.trimSpaces(): String = trim { it.isWhitespace() || it == '\u0085' }
