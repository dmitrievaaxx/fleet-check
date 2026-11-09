package ru.x5.practice.fleet;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Разбор параметров запроса. Параметры приходят строками и проверяются вручную: штатная привязка
 * Spring на «20x5» отвечает своим форматом, а контракт требует 400 INVALID_PARAMETER.
 * Пустое значение (?year=) равнозначно отсутствующему.
 */
public final class Params {

    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private Params() {}

    public static boolean absent(String raw) {
        return raw == null || raw.isEmpty();
    }

    public static Integer integer(String name, String raw, int min, int max, Integer fallback) {
        if (absent(raw)) {
            return fallback;
        }
        if (!INTEGER.matcher(raw).matches()) {
            throw ApiError.invalidParameter(name, raw, name + " must be an integer");
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) { // больше 19 цифр — заведомо вне диапазона
            value = raw.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        if (value < min || value > max) {
            throw ApiError.invalidParameter(name, raw, name + " must be between " + min + " and " + max);
        }
        return (int) value;
    }

    public static int requiredInteger(String name, String raw, int min, int max) {
        if (absent(raw)) {
            throw ApiError.invalidParameter(name, raw, name + " is required");
        }
        return integer(name, raw, min, max, null);
    }

    public static LocalDate date(String name, String raw) {
        if (absent(raw)) {
            return null;
        }
        try {
            if (DATE.matcher(raw).matches()) {
                return LocalDate.parse(raw); // ISO_LOCAL_DATE строгий: 2025-02-30 не пройдёт
            }
        } catch (DateTimeParseException e) {
            // ниже — тот же ответ, что и для неверного формата
        }
        throw ApiError.invalidParameter(name, raw, name + " must be a date YYYY-MM-DD");
    }

    public static LocalDate requiredDate(String name, String raw) {
        if (absent(raw)) {
            throw ApiError.invalidParameter(name, raw, name + " is required");
        }
        return date(name, raw);
    }

    public static String oneOf(String name, String raw, Collection<String> allowed, String fallback) {
        if (absent(raw)) {
            return fallback;
        }
        if (!allowed.contains(raw)) {
            throw ApiError.invalidParameter(name, raw, name + " must be one of " + String.join(", ", allowed));
        }
        return raw;
    }

    public static void checkRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiError.invalidParameter("from", from.toString(), "from must not be after to");
        }
    }
}
