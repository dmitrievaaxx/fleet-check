package ru.x5.practice.fleet.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.x5.practice.fleet.Params;

/**
 * Отчёты: {"report", "params", "rows"} в JSON или те же строки в CSV. Параметры проверяются раньше
 * данных: неверный параметр — 400, даже если нет справочника норм.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private static final List<String> FORMATS = List.of("json", "csv");
    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=utf-8");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReportController(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/fuel")
    public ResponseEntity<Object> fuel(@RequestParam(required = false) String year,
            @RequestParam(required = false) String month, @RequestParam(required = false) String format) {
        int y = Params.requiredInteger("year", year, 2000, 2100);
        int m = Params.requiredInteger("month", month, 1, 12);
        String fmt = Params.oneOf("format", format, FORMATS, "json");
        List<Map<String, Object>> rows = Reports.fuel(Snapshot.load(jdbc), y, m);
        return respond("fuel", params("year", y, "month", m), rows, Reports.FUEL_COLUMNS, fmt);
    }

    @GetMapping("/maintenance-due")
    public ResponseEntity<Object> maintenanceDue(@RequestParam(name = "as_of", required = false) String asOf,
            @RequestParam(required = false) String format) {
        LocalDate date = Params.requiredDate("as_of", asOf);
        String fmt = Params.oneOf("format", format, FORMATS, "json");
        List<Map<String, Object>> rows = Reports.maintenanceDue(Snapshot.load(jdbc), date);
        return respond("maintenance-due", params("as_of", date.toString()), rows,
                Reports.MAINTENANCE_DUE_COLUMNS, fmt);
    }

    @GetMapping("/violations")
    public ResponseEntity<Object> violations(@RequestParam(required = false) String type,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String format) {
        String violationType = Params.oneOf("type", type, Violations.TYPES, null);
        LocalDate dateFrom = Params.date("from", from);
        LocalDate dateTo = Params.date("to", to);
        Params.checkRange(dateFrom, dateTo);
        String fmt = Params.oneOf("format", format, FORMATS, "json");
        List<Map<String, Object>> rows = Reports.violations(Snapshot.load(jdbc), violationType, dateFrom, dateTo);
        return respond("violations", params("type", violationType,
                "from", dateFrom == null ? null : dateFrom.toString(),
                "to", dateTo == null ? null : dateTo.toString()), rows, Reports.VIOLATION_COLUMNS, fmt);
    }

    @GetMapping("/utilization")
    public ResponseEntity<Object> utilization(@RequestParam(required = false) String year,
            @RequestParam(required = false) String month, @RequestParam(required = false) String format) {
        int y = Params.requiredInteger("year", year, 2000, 2100);
        int m = Params.requiredInteger("month", month, 1, 12);
        String fmt = Params.oneOf("format", format, FORMATS, "json");
        List<Map<String, Object>> rows = Reports.utilization(Snapshot.load(jdbc), y, m);
        return respond("utilization", params("year", y, "month", m), rows, Reports.UTILIZATION_COLUMNS, fmt);
    }

    @GetMapping("/drivers")
    public ResponseEntity<Object> drivers(@RequestParam(required = false) String year,
            @RequestParam(required = false) String month, @RequestParam(required = false) String format) {
        int y = Params.requiredInteger("year", year, 2000, 2100);
        int m = Params.requiredInteger("month", month, 1, 12);
        String fmt = Params.oneOf("format", format, FORMATS, "json");
        List<Map<String, Object>> rows = Reports.drivers(Snapshot.load(jdbc), y, m);
        return respond("drivers", params("year", y, "month", m), rows, Reports.DRIVERS_COLUMNS, fmt);
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put((String) pairs[i], pairs[i + 1]);
        }
        return params;
    }

    private ResponseEntity<Object> respond(String report, Map<String, Object> params,
            List<Map<String, Object>> rows, List<String> columns, String format) {
        if (format.equals("csv")) {
            return ResponseEntity.ok().contentType(CSV).body(CsvWriter.write(columns, rows, json));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("report", report);
        body.put("params", params);
        body.put("rows", rows);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
