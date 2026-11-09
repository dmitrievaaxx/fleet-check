package ru.x5.practice.fleet;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ошибка, которая уходит клиенту в едином формате api-contract.md, 1.3. */
public class ApiError extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiError(int status, String code, String message, Map<String, Object> details) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public ApiError(int status, String code, String message) {
        this(status, code, message, new LinkedHashMap<>());
    }

    public static ApiError invalidParameter(String name, Object value, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", name);
        details.put("value", value);
        return new ApiError(400, "INVALID_PARAMETER", message, details);
    }

    public static ApiError notFound(String entity, String id) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", id);
        return new ApiError(404, "NOT_FOUND", entity + " " + id + " not found", details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> body() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", getMessage());
        error.put("details", details);
        return Map.of("error", error);
    }
}
