package ru.x5.practice.fleet.importing;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.x5.practice.fleet.ApiError;
import ru.x5.practice.fleet.importing.DatasetSpec.FormatException;
import ru.x5.practice.fleet.importing.DatasetSpec.Parser;

/**
 * Импорт CSV по data-contract.md, раздел 3: проверки строки в порядке 3.5, идемпотентность по
 * первичному ключу (3.4), ошибки уровня файла (3.3). Ключи базы читаются в память один раз на
 * файл, принятые строки записываются одной пачкой в одной транзакции.
 */
@Service
public class ImportService {

    private static final String KEY_SEPARATOR = "\u0000";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ImportService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public static ApiError unknownDataset(String dataset) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dataset", dataset);
        return new ApiError(400, "UNKNOWN_DATASET", "unknown dataset " + dataset, details);
    }

    public Map<String, Object> importCsv(String dataset, byte[] content) {
        DatasetSpec spec = Datasets.ALL.get(dataset);
        if (spec == null) {
            throw unknownDataset(dataset);
        }
        // \n, \r\n (и одиночный \r) — переводы строк; пустые строки сдвигают нумерацию
        List<String> lines = decode(content).lines().toList();
        int headerIndex = 0;
        while (headerIndex < lines.size() && lines.get(headerIndex).isEmpty()) {
            headerIndex++;
        }
        if (headerIndex == lines.size()) {
            throw new ApiError(400, "EMPTY_FILE", "file has no header row");
        }
        // заголовок, который не разбирается как CSV, — заголовок без обязательных колонок (3.3)
        List<String> parsedHeader = CsvLine.parse(lines.get(headerIndex));
        List<String> header = parsedHeader == null ? List.of()
                : parsedHeader.stream().map(Text::strip).toList();
        List<String> missing = spec.required().stream().filter(c -> !header.contains(c)).toList();
        if (!missing.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("columns", missing);
            throw new ApiError(400, "MISSING_COLUMNS", "required columns are missing", details);
        }

        Keys keys = loadKeys(spec);
        int total = 0;
        int rejected = 0;
        int skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Object[]> accepted = new ArrayList<>();

        for (int i = headerIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue; // полностью пустая строка — не запись
            }
            int rowNo = i + 1;
            total++;
            List<String> fields = CsvLine.parse(line);
            Row row = fields == null
                    ? Row.error(rowNo, null, "PARSE_ERROR", "unterminated or misplaced quote")
                    : validate(spec, header, fields, rowNo);
            if (row.error == null) {
                row = checkReferences(spec, keys, row, rowNo);
            }
            if (row.error == null) {
                String pk = key(spec.primaryKey(), row.values);
                if (keys.primary.contains(pk)) {
                    skipped++; // ключ уже в базе или выше в этом файле
                    continue;
                }
                row = checkUnique(spec, keys, row, rowNo);
                if (row.error == null) {
                    keys.primary.add(pk);
                    for (String column : spec.unique()) {
                        keys.unique.get(column).add((String) row.values.get(column));
                    }
                    accepted.add(toInsertArgs(spec, row.values));
                    continue;
                }
            }
            rejected++;
            errors.add(row.error);
        }

        if (!accepted.isEmpty()) {
            tx.executeWithoutResult(status -> insert(spec, accepted));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset", dataset);
        result.put("total", total);
        result.put("accepted", accepted.size());
        result.put("rejected", rejected);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return result;
    }

    private static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiError(400, "INVALID_ENCODING", "file is not valid UTF-8");
        }
        int start = 0;
        while (start < text.length() && text.charAt(start) == '﻿') {
            start++; // BOM допускается и игнорируется; файл из одного BOM — пустой
        }
        return text.substring(start);
    }

    /** Проверки 1–5 из 3.5: разбор, обязательность, формат, допустимые значения, межполевые. */
    static Row validate(DatasetSpec spec, List<String> header, List<String> fields, int rowNo) {
        if (fields.size() != header.size()) {
            return Row.error(rowNo, null, "PARSE_ERROR",
                    "expected " + header.size() + " fields, got " + fields.size());
        }
        Map<String, String> raw = new HashMap<>();
        for (int k = 0; k < header.size(); k++) {
            if (spec.columns().contains(header.get(k))) {
                raw.put(header.get(k), Text.strip(fields.get(k))); // повтор колонки — последнее
            }
        }
        for (String name : spec.required()) {
            if (raw.getOrDefault(name, "").isEmpty()) {
                return Row.error(rowNo, name, "REQUIRED_FIELD_MISSING", name + " is required");
            }
        }
        Map<String, Object> values = new HashMap<>();
        for (String name : spec.columns()) {
            String value = raw.getOrDefault(name, "");
            if (value.isEmpty()) {
                values.put(name, null);
                continue;
            }
            Parser parser = spec.parsers().get(name);
            if (parser == null) {
                values.put(name, value);
                continue;
            }
            try {
                values.put(name, parser.parse(value));
            } catch (FormatException e) {
                return Row.error(rowNo, name, "INVALID_FORMAT", name + " has invalid format: " + value);
            }
        }
        for (var check : spec.values().entrySet()) {
            Object value = values.get(check.getKey());
            if (value != null && !check.getValue().test(value)) {
                return Row.error(rowNo, check.getKey(), "INVALID_VALUE",
                        check.getKey() + " is out of allowed values: " + raw.get(check.getKey()));
            }
        }
        if (spec.cross() != null) {
            String bad = spec.cross().apply(values);
            if (bad != null) {
                return Row.error(rowNo, bad, "CROSS_FIELD_VIOLATION", bad + " conflicts with another field");
            }
        }
        return Row.ok(values);
    }

    private static Row checkReferences(DatasetSpec spec, Keys keys, Row row, int rowNo) {
        for (String field : spec.references().keySet()) {
            Object value = row.values.get(field);
            if (value != null && !keys.references.get(field).contains((String) value)) {
                return Row.error(rowNo, field, "REFERENCE_NOT_FOUND", field + " " + value + " not found");
            }
        }
        return row;
    }

    private static Row checkUnique(DatasetSpec spec, Keys keys, Row row, int rowNo) {
        for (String column : spec.unique()) {
            Object value = row.values.get(column);
            if (keys.unique.get(column).contains((String) value)) {
                return Row.error(rowNo, column, "UNIQUE_VIOLATION", column + " " + value + " already exists");
            }
        }
        return row;
    }

    private Keys loadKeys(DatasetSpec spec) {
        Keys keys = new Keys();
        int width = spec.primaryKey().size();
        keys.primary.addAll(jdbc.query(
                "SELECT " + String.join(", ", spec.primaryKey()) + " FROM " + spec.table(),
                (rs, n) -> {
                    List<String> parts = new ArrayList<>(width);
                    for (int c = 1; c <= width; c++) {
                        parts.add(rs.getString(c));
                    }
                    return String.join(KEY_SEPARATOR, parts);
                }));
        for (String column : spec.unique()) {
            keys.unique.put(column, new HashSet<>(
                    jdbc.queryForList("SELECT " + column + " FROM " + spec.table(), String.class)));
        }
        for (var ref : spec.references().entrySet()) {
            String field = ref.getKey();
            keys.references.put(field, new HashSet<>(
                    jdbc.queryForList("SELECT " + field + " FROM " + ref.getValue(), String.class)));
        }
        return keys;
    }

    private static String key(List<String> columns, Map<String, Object> values) {
        List<String> parts = new ArrayList<>(columns.size());
        for (String column : columns) {
            parts.add(String.valueOf(values.get(column)));
        }
        return String.join(KEY_SEPARATOR, parts);
    }

    private static Object[] toInsertArgs(DatasetSpec spec, Map<String, Object> values) {
        Object[] args = new Object[spec.columns().size()];
        for (int k = 0; k < args.length; k++) {
            Object value = values.get(spec.columns().get(k));
            args[k] = value instanceof List<?> list ? String.join(";", list.stream().map(String::valueOf).toList())
                    : value;
        }
        return args;
    }

    private void insert(DatasetSpec spec, List<Object[]> rows) {
        String placeholders = String.join(", ", java.util.Collections.nCopies(spec.columns().size(), "?"));
        String sql = "INSERT INTO " + spec.table() + " (" + String.join(", ", spec.columns())
                + ") VALUES (" + placeholders + ")";
        jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Object[] args = rows.get(i);
                for (int k = 0; k < args.length; k++) {
                    if (args[k] == null) {
                        ps.setNull(k + 1, spec.sqlTypes().get(k));
                    } else {
                        ps.setObject(k + 1, args[k]); // LocalDate и LocalDateTime драйвер понимает сам
                    }
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    /** Ключи, уже занятые в базе, и ключи, на которые можно ссылаться. */
    private static final class Keys {
        final Set<String> primary = new HashSet<>();
        final Map<String, Set<String>> unique = new HashMap<>();
        final Map<String, Set<String>> references = new HashMap<>();
    }

    /** Строка после проверок: значения либо ошибка. */
    static final class Row {
        final Map<String, Object> values;
        final Map<String, Object> error;

        private Row(Map<String, Object> values, Map<String, Object> error) {
            this.values = values;
            this.error = error;
        }

        static Row ok(Map<String, Object> values) {
            return new Row(values, null);
        }

        static Row error(int row, String field, String code, String message) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("row", row);
            error.put("field", field);
            error.put("code", code);
            error.put("message", message);
            return new Row(null, error);
        }
    }
}
