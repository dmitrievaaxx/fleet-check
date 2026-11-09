# Стартовый шаблон сервиса на Java

Каркас бэкенда для кейса: Spring Boot, PostgreSQL, тесты, запуск одной командой. Скачайте шаблон,
создайте из него свой репозиторий на GitHub и пишите решение здесь.

## Как платформа запускает решение

Так же, как вы можете запустить его у себя, только без Docker:

- язык платформа узнаёт по файлу `build.gradle.kts` в корне репозитория: если есть папка
  `src/main/kotlin`, решение считается написанным на Kotlin, иначе — на Java;
- сервис собирает установленный на платформе Gradle 8 командой `gradle bootJar`; `gradlew` и
  папка `gradle/wrapper` на проверке не используются;
- сервис стартует командой `java -jar` на единственном jar из `build/libs` и слушает порт 8000;
- базу PostgreSQL поднимает сама платформа, её адрес — в переменной `DATABASE_URL`;
- для своих файлов (например, SQLite) есть папка из переменной `DATA_DIR`;
- проверка начинается, когда сервис ответит на `GET /health`, — на это есть 60 секунд;
- сети на проверке нет, а плагины и библиотеки — только те, что в `build.gradle.kts` этого
  шаблона, и только этих версий: свои зависимости платформа не скачивает.

Поэтому не меняйте версии плагинов и библиотек в `build.gradle.kts` и не добавляйте новые. Не
подключайте и toolchain (`java { toolchain { … } }`): без сети Gradle не сможет скачать JDK.

## Запуск у себя

Одной командой, нужен Docker:

    docker compose up --build

Сервис будет на http://localhost:8000, проверка: http://localhost:8000/health.

Без Docker — нужны JDK 21 и PostgreSQL; Gradle ставить не нужно, его скачает `gradlew`:

    export DATABASE_URL=postgresql://app:app@localhost:5432/app
    ./gradlew bootRun

В Windows вместо `./gradlew` — `gradlew.bat`. Адрес базы записывается так же, как для
PostgreSQL: `postgresql://пользователь:пароль@хост:порт/база`, — JDBC-адрес шаблон соберёт сам.

## Тесты

Нужна запущенная база, адрес — в `DATABASE_URL`, как выше:

    ./gradlew test

Или `gradle test`, если Gradle 8 у вас уже установлен.

## Что здесь лежит

| файл | зачем |
|---|---|
| `src/main/java/ru/x5/practice/fleet/Application.java` | точка входа Spring Boot |
| `src/main/java/ru/x5/practice/fleet/HealthController.java` | `/health` |
| `src/main/java/ru/x5/practice/fleet/DatabaseConfig.java` | подключение к базе по `DATABASE_URL` |
| `src/main/java/ru/x5/practice/fleet/Db.java` | создание схемы и проверка, что база отвечает |
| `src/main/resources/application.properties` | настройки Spring: порт из `PORT` |
| `src/test/` | тесты; добавляйте свои |
| `build.gradle.kts`, `settings.gradle.kts` | сборка: плагины, библиотеки и их версии |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle для запуска у себя; платформа их не использует |
| `compose.yaml`, `Dockerfile` | запуск одной командой у себя; платформа их не запускает, но проверяет, что они описывают сервис и базу |
