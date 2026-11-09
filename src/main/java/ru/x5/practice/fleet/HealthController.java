package ru.x5.practice.fleet;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final Db db;

    public HealthController(Db db) {
        this.db = db;
    }

    /**
     * Платформа ждёт, пока сервис ответит здесь, и сверяет число записей до и после повторного
     * импорта и перезапуска.
     */
    @GetMapping("/health")
    public Health health() {
        return new Health("ok", db.recordCounts());
    }

    public record Health(String status, Map<String, Integer> records) {}
}
