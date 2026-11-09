package ru.x5.practice.fleet

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Ошибка, которая уходит клиенту в едином формате api-contract.md, 1.3. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

fun errorBody(code: String, message: String, details: Map<String, Any?> = emptyMap()): Map<String, Any?> =
    mapOf("error" to mapOf("code" to code, "message" to message, "details" to details))

fun badRequest(code: String, message: String, details: Map<String, Any?> = emptyMap()) =
    ApiException(HttpStatus.BAD_REQUEST, code, message, details)

fun invalidParameter(name: String, value: String?, message: String) =
    badRequest("INVALID_PARAMETER", message, mapOf("parameter" to name, "value" to value))

fun notFound(entity: String, id: String) =
    ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$entity $id not found", mapOf("id" to id))

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(e.status).body(errorBody(e.code, e.message, e.details))

    /**
     * Всё остальное. Ошибки самого Spring (неизвестный роут, не тот метод, битый запрос) сохраняют
     * свой статус, но отвечают в нашем формате; неожиданное — 500 с INTERNAL_ERROR.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<Map<String, Any?>> {
        if (e is ErrorResponse) {
            val status = e.statusCode
            val code = when (status.value()) {
                400 -> "INVALID_PARAMETER"
                404 -> "NOT_FOUND"
                else -> "HTTP_${status.value()}"
            }
            return ResponseEntity.status(status).body(errorBody(code, e.body.detail ?: status.toString()))
        }
        log.error("unhandled error", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(errorBody("INTERNAL_ERROR", "unexpected error", mapOf("type" to e.javaClass.simpleName)))
    }
}
