package ru.x5.practice.fleet

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartException
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.util.WebUtils

@RestController
class ImportController(private val importer: Importer) {

    /**
     * Неизвестный набор проверяется раньше файла. Multipart разбирается лениво
     * (spring.servlet.multipart.resolve-lazily), поэтому битое тело тоже даёт FILE_REQUIRED.
     */
    @PostMapping("/import/{dataset}")
    fun import(@PathVariable dataset: String, request: HttpServletRequest): ImportResult {
        if (dataset !in DATASETS) throw unknownDataset(dataset)
        val multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest::class.java)
        val file = try {
            multipart?.getFile("file")
        } catch (e: MultipartException) {
            null
        } ?: throw badRequest("FILE_REQUIRED", "multipart field 'file' is required")
        return importer.import(dataset, file.bytes)
    }
}

@RestController
class EntityController(private val repository: FleetRepository) {

    @GetMapping("/vehicles")
    fun vehicles(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(name = "fuel_type", required = false) fuelType: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) offset: String?,
    ): Page {
        val filters = listOfNotNull(
            Params.enum("status", status, listOf("active", "decommissioned"))?.let { Filter("status = ?", it) },
            Params.enum("type", type, listOf("truck", "bus", "car"))?.let { Filter("type = ?", it) },
            Params.enum("fuel_type", fuelType, listOf("diesel", "petrol", "gas"))?.let { Filter("fuel_type = ?", it) },
        )
        return repository.vehicles(filters, limit(limit), offset(offset))
    }

    @GetMapping("/vehicles/{id}")
    fun vehicle(@PathVariable id: String): Map<String, Any?> =
        repository.vehicle(id)?.toJson() ?: throw notFound("vehicle", id)

    @GetMapping("/drivers")
    fun drivers(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) offset: String?,
    ): Page {
        val filters = listOfNotNull(
            Params.enum("status", status, listOf("active", "dismissed"))?.let { Filter("status = ?", it) },
        )
        return repository.drivers(filters, limit(limit), offset(offset))
    }

    @GetMapping("/drivers/{id}")
    fun driver(@PathVariable id: String): Map<String, Any?> =
        repository.driver(id)?.toJson() ?: throw notFound("driver", id)

    @GetMapping("/waybills")
    fun waybills(
        @RequestParam(name = "vehicle_id", required = false) vehicleId: String?,
        @RequestParam(name = "driver_id", required = false) driverId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) offset: String?,
    ): Page {
        val dateFrom = Params.date("from", from)
        val dateTo = Params.date("to", to)
        Params.period(dateFrom, dateTo)
        val pageLimit = limit(limit)
        val pageOffset = offset(offset)
        val filters = listOfNotNull(
            vehicleId?.takeIf { it.isNotEmpty() }?.let { Filter("vehicle_id = ?", it) },
            driverId?.takeIf { it.isNotEmpty() }?.let { Filter("driver_id = ?", it) },
            dateFrom?.let { Filter("departure_at >= ?", it.atStartOfDay()) },
            dateTo?.let { Filter("departure_at < ?", it.plusDays(1).atStartOfDay()) },
        )
        return repository.waybills(filters, pageLimit, pageOffset)
    }

    @GetMapping("/waybills/{id}")
    fun waybill(@PathVariable id: String): Map<String, Any?> =
        repository.waybill(id)?.toJson() ?: throw notFound("waybill", id)

    private fun limit(raw: String?) = Params.int("limit", raw, 1..1000, default = 100)!!

    private fun offset(raw: String?) = Params.int("offset", raw, 0..1_000_000_000, default = 0)!!
}

@RestController
class ReportController(private val repository: FleetRepository, private val mapper: ObjectMapper) {

    @GetMapping("/reports/fuel")
    fun fuel(
        @RequestParam(required = false) year: String?,
        @RequestParam(required = false) month: String?,
        @RequestParam(required = false) format: String?,
    ): ResponseEntity<*> {
        val y = Params.requiredInt("year", year, 2000..2100)
        val m = Params.requiredInt("month", month, 1..12)
        val fmt = Params.format(format)
        val rows = Reports(repository.snapshot()).fuel(y, m)
        return render("fuel", mapOf("year" to y, "month" to m), rows, FUEL_COLUMNS, fmt)
    }

    @GetMapping("/reports/maintenance-due")
    fun maintenanceDue(
        @RequestParam(name = "as_of", required = false) asOf: String?,
        @RequestParam(required = false) format: String?,
    ): ResponseEntity<*> {
        val date = Params.date("as_of", asOf, required = true)!!
        val fmt = Params.format(format)
        val rows = Reports(repository.snapshot()).maintenanceDue(date)
        return render("maintenance-due", mapOf("as_of" to date.iso()), rows, MAINTENANCE_DUE_COLUMNS, fmt)
    }

    @GetMapping("/reports/violations")
    fun violations(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) format: String?,
    ): ResponseEntity<*> {
        val vtype = Params.enum("type", type, VIOLATION_TYPES)
        val dateFrom = Params.date("from", from)
        val dateTo = Params.date("to", to)
        Params.period(dateFrom, dateTo)
        val fmt = Params.format(format)
        val rows = Reports(repository.snapshot()).violations(vtype, dateFrom, dateTo)
        val params = mapOf("type" to vtype, "from" to dateFrom?.iso(), "to" to dateTo?.iso())
        return render("violations", params, rows, VIOLATION_COLUMNS, fmt)
    }

    @GetMapping("/reports/utilization")
    fun utilization(
        @RequestParam(required = false) year: String?,
        @RequestParam(required = false) month: String?,
        @RequestParam(required = false) format: String?,
    ): ResponseEntity<*> {
        val y = Params.requiredInt("year", year, 2000..2100)
        val m = Params.requiredInt("month", month, 1..12)
        val fmt = Params.format(format)
        val rows = Reports(repository.snapshot()).utilization(y, m)
        return render("utilization", mapOf("year" to y, "month" to m), rows, UTILIZATION_COLUMNS, fmt)
    }

    @GetMapping("/reports/drivers")
    fun drivers(
        @RequestParam(required = false) year: String?,
        @RequestParam(required = false) month: String?,
        @RequestParam(required = false) format: String?,
    ): ResponseEntity<*> {
        val y = Params.requiredInt("year", year, 2000..2100)
        val m = Params.requiredInt("month", month, 1..12)
        val fmt = Params.format(format)
        val rows = Reports(repository.snapshot()).drivers(y, m)
        return render("drivers", mapOf("year" to y, "month" to m), rows, DRIVERS_COLUMNS, fmt)
    }

    private fun render(name: String, params: Map<String, Any?>, rows: List<Row>, columns: List<String>, format: String): ResponseEntity<*> {
        if (format == "csv") {
            return ResponseEntity.ok()
                .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(toCsv(columns, rows))
        }
        return ResponseEntity.ok(mapOf("report" to name, "params" to params, "rows" to rows))
    }

    /** CSV по RFC 4180: заголовок, затем строки; объекты — JSON-строкой, null — пустым полем. */
    private fun toCsv(columns: List<String>, rows: List<Row>): String {
        fun cell(value: Any?): String = when (value) {
            null -> ""
            is Map<*, *>, is List<*> -> mapper.writeValueAsString(value)
            else -> value.toString()
        }
        fun quote(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        return buildString {
            columns.joinTo(this, ",") { quote(it) }
            append('\n')
            for (row in rows) {
                columns.joinTo(this, ",") { quote(cell(row[it])) }
                append('\n')
            }
        }
    }
}
