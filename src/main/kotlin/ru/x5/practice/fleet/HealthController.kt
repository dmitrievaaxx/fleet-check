package ru.x5.practice.fleet

import org.springframework.dao.DataAccessException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(private val db: Db) {

    /** Готовность и число записей по сущностям (api-contract.md, 2). */
    @GetMapping("/health")
    fun health(): Health {
        val records = try {
            db.recordCounts()
        } catch (e: DataAccessException) {
            null
        }
        return Health(status = if (records != null) "ok" else "degraded", db = records != null, records = records)
    }

    data class Health(val status: String, val db: Boolean, val records: Map<String, Int>?)
}
