package ru.x5.practice.fleet.importing;

import java.util.ArrayList;
import java.util.List;

/**
 * Одна физическая строка файла как одна запись CSV по RFC 4180 (data-contract.md, 1.1): переводов
 * строк внутри значений нет, поэтому файл разбирается построчно, и незакрытая кавычка портит только
 * свою строку.
 */
public final class CsvLine {

    private CsvLine() {}

    /** Поля строки или null, если кавычка не закрыта или стоит внутри незакавыченного поля. */
    public static List<String> parse(String line) {
        List<String> fields = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (true) {
            if (i < n && line.charAt(i) == '"') {
                i++;
                StringBuilder value = new StringBuilder();
                while (true) {
                    if (i >= n) {
                        return null; // незакрытая кавычка
                    }
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && line.charAt(i + 1) == '"') {
                            value.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    value.append(c);
                    i++;
                }
                if (i < n && line.charAt(i) != ',') {
                    return null; // после закрывающей кавычки — не разделитель
                }
                fields.add(value.toString());
            } else {
                int end = line.indexOf(',', i);
                if (end < 0) {
                    end = n;
                }
                String value = line.substring(i, end);
                if (value.indexOf('"') >= 0) {
                    return null; // кавычка внутри незакавыченного поля
                }
                fields.add(value);
                i = end;
            }
            if (i >= n) {
                return fields;
            }
            i++; // запятая
            if (i == n) {
                fields.add(""); // строка кончается запятой — последнее поле пустое
                return fields;
            }
        }
    }
}
