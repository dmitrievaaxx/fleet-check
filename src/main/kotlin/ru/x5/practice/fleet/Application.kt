/*
 * Стартовый сервис: Spring Boot и PostgreSQL.
 *
 * Платформа запускает его так же, как вы локально: собирает `gradle bootJar` и запускает
 * `java -jar` на порту 8000, адрес базы — в переменной DATABASE_URL. Отсюда начинается решение
 * кейса: добавляйте свои классы в пакет ru.x5.practice.fleet и вложенные в него и эндпоинты по
 * контракту API из задания.
 */
package ru.x5.practice.fleet

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
