package ru.x5.practice.fleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Стартовый сервис: Spring Boot и PostgreSQL.
 *
 * <p>Платформа запускает его так же, как вы локально: собирает {@code gradle bootJar} и запускает
 * {@code java -jar} на порту 8000, адрес базы — в переменной DATABASE_URL. Отсюда начинается
 * решение кейса: добавляйте свои классы в пакет {@code ru.x5.practice.fleet} и вложенные в него
 * и эндпоинты по контракту API из задания.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
