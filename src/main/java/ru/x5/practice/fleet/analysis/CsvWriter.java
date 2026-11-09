package ru.x5.practice.fleet.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CSV-представление отчёта (reports-contract.md, 1.1–1.2): заголовок из колонок отчёта, null —
 * пустое поле, объект — JSON-строка, кавычки по RFC 4180 там, где они нужны.
 */
public final class CsvWriter {

    private CsvWriter() {}

    public static String write(List<String> columns, List<Map<String, Object>> rows, ObjectMapper json) {
        StringBuilder out = new StringBuilder();
        line(out, columns);
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (String column : columns) {
                cells.add(cell(row.get(column), json));
            }
            line(out, cells);
        }
        return out.toString();
    }

    static String cell(Object value, ObjectMapper json) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return json.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d).toPlainString(); // без экспоненты
        }
        return value.toString(); // Boolean → true/false
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String cell = cells.get(i);
            if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0
                    || cell.indexOf('\r') >= 0) {
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                out.append(cell);
            }
        }
        out.append('\n');
    }
}
