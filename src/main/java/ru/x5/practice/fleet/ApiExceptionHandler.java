package ru.x5.practice.fleet;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

/**
 * Любая ошибка — в формате {"error": {"code", "message", "details"}}. Штатные ответы Spring
 * (свой JSON, 500 на всё непредвиденное) контракту не соответствуют, поэтому перехватываются здесь.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiError.class)
    public ResponseEntity<Map<String, Object>> apiError(ApiError e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(e.body());
    }

    /** Битый multipart (нет boundary, оборванное тело) — это запрос без файла. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> multipart(MultipartException e) {
        return apiError(new ApiError(400, "FILE_REQUIRED", "multipart body with field 'file' is required"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        if (e instanceof ErrorResponse response) { // неизвестный роут, не тот метод и т. п.
            HttpStatusCode status = response.getStatusCode();
            String code = status.value() == 404 ? "NOT_FOUND"
                    : status.is4xxClientError() ? "INVALID_PARAMETER" : "INTERNAL_ERROR";
            return apiError(new ApiError(status.value(), code, e.getMessage()));
        }
        log.error("unexpected error", e);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("type", e.getClass().getSimpleName());
        return apiError(new ApiError(500, "INTERNAL_ERROR", "unexpected error", details));
    }
}
