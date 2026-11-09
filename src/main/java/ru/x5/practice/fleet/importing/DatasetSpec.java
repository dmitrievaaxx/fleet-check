package ru.x5.practice.fleet.importing;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Описание одного набора данных из data-contract.md, раздел 2.
 *
 * @param columns    колонки таблицы в порядке вставки; в этом же порядке проверяется формат
 * @param sqlTypes   типы колонок для вставки NULL (java.sql.Types), по порядку columns
 * @param parsers    разбор и проверка формата (INVALID_FORMAT); у перечислений разбора нет
 * @param values     допустимые значения над разобранным значением (INVALID_VALUE), по порядку
 * @param cross      межполевые проверки: имя поля с ошибкой или null
 * @param references поле-ссылка → таблица, где оно первичный ключ (REFERENCE_NOT_FOUND)
 * @param unique     вторичные уникальные ключи (UNIQUE_VIOLATION)
 */
public record DatasetSpec(
        String table,
        List<String> columns,
        List<Integer> sqlTypes,
        List<String> required,
        List<String> primaryKey,
        Map<String, Parser> parsers,
        Map<String, Predicate<Object>> values,
        Function<Map<String, Object>, String> cross,
        Map<String, String> references,
        List<String> unique) {

    /** Разбор значения поля; неверный формат — {@link FormatException}. */
    @FunctionalInterface
    public interface Parser {
        Object parse(String value);
    }

    /** Значение не соответствует типу или шаблону поля. */
    public static final class FormatException extends RuntimeException {
        public static final FormatException INSTANCE = new FormatException();

        private FormatException() {
            super("invalid format", null, false, false);
        }
    }
}
