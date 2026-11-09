package ru.x5.practice.fleet

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RowError(val row: Int, val field: String?, val code: String, val message: String)

data class ImportResult(
    val dataset: String,
    val total: Int,
    val accepted: Int,
    val rejected: Int,
    val skipped: Int,
    val errors: List<RowError>,
)

/**
 * Импорт CSV по data-contract.md, раздел 3: проверки строки в порядке 3.5, идемпотентность по
 * первичному ключу (3.4). Файл импортируется в одной транзакции; ключи, уникальные значения и
 * идентификаторы справочников читаются из базы один раз и дополняются принятыми строками.
 */
@Service
class Importer(private val jdbc: JdbcTemplate) {

    private sealed interface RowCheck {
        data class Valid(val values: Map<String, Any?>) : RowCheck
        data class Invalid(val error: RowError) : RowCheck
    }

    @Transactional
    fun import(dataset: String, content: ByteArray): ImportResult {
        val spec = DATASETS[dataset] ?: throw unknownDataset(dataset)
        val lines = CsvLine.lines(CsvLine.decode(content))
        val headerIndex = lines.indexOfFirst { it.isNotEmpty() }
        if (headerIndex < 0) throw badRequest("EMPTY_FILE", "file has no header row")
        // заголовок, который не разбирается как CSV, — заголовок без колонок (data-contract.md, 3.3)
        val header = CsvLine.parse(lines[headerIndex])?.map { it.trimSpaces() } ?: emptyList()
        val missing = spec.required.filter { it !in header }
        if (missing.isNotEmpty()) {
            throw badRequest("MISSING_COLUMNS", "required columns are missing", mapOf("columns" to missing))
        }

        val keys = existingKeys(spec)
        val uniques = spec.unique.associateWith { column -> existingValues(spec.table, column) }
        val references = spec.references.values.toSet().associateWith { table -> existingIds(table) }

        var total = 0
        var skipped = 0
        val errors = mutableListOf<RowError>()
        val accepted = mutableListOf<Map<String, Any?>>()

        for (index in headerIndex + 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) continue
            total++
            val rowNo = index + 1
            val fields = CsvLine.parse(line)
            val check = if (fields == null) {
                RowCheck.Invalid(RowError(rowNo, null, "PARSE_ERROR", "unterminated quoted field"))
            } else {
                validate(spec, header, fields, rowNo)
            }
            if (check is RowCheck.Invalid) {
                errors += check.error
                continue
            }
            val values = (check as RowCheck.Valid).values
            val brokenReference = spec.references.entries.firstOrNull { (field, table) ->
                val value = values[field]
                value != null && value.toString() !in references.getValue(table)
            }
            if (brokenReference != null) {
                val value = values[brokenReference.key]
                errors += RowError(rowNo, brokenReference.key, "REFERENCE_NOT_FOUND", "${brokenReference.key} $value not found")
                continue
            }
            val key = spec.primaryKey.joinToString("\u0000") { values[it].toString() }
            if (key in keys) {
                skipped++
                continue
            }
            val duplicate = spec.unique.firstOrNull { column -> values[column].toString() in uniques.getValue(column) }
            if (duplicate != null) {
                errors += RowError(rowNo, duplicate, "UNIQUE_VIOLATION", "$duplicate ${values[duplicate]} already exists")
                continue
            }
            keys += key
            spec.unique.forEach { column -> uniques.getValue(column) += values[column].toString() }
            accepted += values
        }

        insert(spec, accepted)
        return ImportResult(dataset, total, accepted.size, errors.size, skipped, errors)
    }

    private fun validate(spec: DatasetSpec, header: List<String>, fields: List<String>, rowNo: Int): RowCheck {
        if (fields.size != header.size) {
            return RowCheck.Invalid(RowError(rowNo, null, "PARSE_ERROR", "expected ${header.size} fields, got ${fields.size}"))
        }
        // при повторе имени колонки берётся последнее вхождение
        val raw = header.zip(fields).filter { (name, _) -> name in spec.columns }
            .associate { (name, value) -> name to value.trimSpaces() }
        spec.required.firstOrNull { raw[it].isNullOrEmpty() }?.let { name ->
            return RowCheck.Invalid(RowError(rowNo, name, "REQUIRED_FIELD_MISSING", "$name is required"))
        }
        val values = linkedMapOf<String, Any?>()
        for (name in spec.columns) {
            val value = raw[name].orEmpty()
            val parser = spec.parsers[name]
            values[name] = when {
                value.isEmpty() -> null
                parser == null -> value
                else -> try {
                    parser(value)
                } catch (e: FormatError) {
                    return RowCheck.Invalid(RowError(rowNo, name, "INVALID_FORMAT", "$name has invalid format: $value"))
                }
            }
        }
        for ((name, allowed) in spec.values) {
            val value = values[name] ?: continue
            if (!allowed(value)) {
                return RowCheck.Invalid(RowError(rowNo, name, "INVALID_VALUE", "$name is out of allowed values: ${raw[name]}"))
            }
        }
        spec.cross?.invoke(values)?.let { name ->
            return RowCheck.Invalid(RowError(rowNo, name, "CROSS_FIELD_VIOLATION", "$name conflicts with another field"))
        }
        return RowCheck.Valid(values)
    }

    private fun existingKeys(spec: DatasetSpec): MutableSet<String> {
        val columns = spec.primaryKey.joinToString(", ")
        return jdbc.query("SELECT $columns FROM ${spec.table}") { rs, _ ->
            spec.primaryKey.indices.joinToString("\u0000") { rs.getString(it + 1) }
        }.toHashSet()
    }

    private fun existingValues(table: String, column: String): MutableSet<String> =
        jdbc.queryForList("SELECT $column FROM $table", String::class.java).filterNotNull().toHashSet()

    private fun existingIds(table: String): Set<String> {
        val column = DATASETS.getValue(table).primaryKey.single()
        return existingValues(table, column)
    }

    private fun insert(spec: DatasetSpec, rows: List<Map<String, Any?>>) {
        if (rows.isEmpty()) return
        val placeholders = spec.columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO ${spec.table} (${spec.columns.joinToString(", ")}) VALUES ($placeholders)"
        val types = spec.columns.map { spec.sqlType(it) }.toIntArray()
        val args = rows.map { row ->
            spec.columns.map { column ->
                when (val value = row[column]) {
                    is List<*> -> value.joinToString(";")
                    else -> value
                }
            }.toTypedArray()
        }
        args.chunked(1000).forEach { chunk -> jdbc.batchUpdate(sql, chunk, types) }
    }
}

fun unknownDataset(dataset: String) =
    badRequest("UNKNOWN_DATASET", "unknown dataset $dataset", mapOf("dataset" to dataset))
