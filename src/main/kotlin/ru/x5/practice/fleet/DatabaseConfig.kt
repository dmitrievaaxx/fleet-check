package ru.x5.practice.fleet

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import javax.sql.DataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

const val DEFAULT_URL = "postgresql://app:app@localhost:5432/app"

/**
 * Подключение к PostgreSQL по DATABASE_URL.
 *
 * На проверке платформа сама поднимает базу и передаёт её адрес; локально его задаёт
 * compose.yaml. Адрес записан так, как его понимает libpq:
 * `postgresql://пользователь:пароль@хост:порт/база`, а Spring нужен JDBC — поэтому подключение
 * собирается здесь. Пароля в адресе может не быть: на проверке база пускает без него.
 */
@Configuration
class DatabaseConfig {

    @Bean
    fun dataSource(env: Environment): DataSource {
        val url = URI.create(env.getProperty("DATABASE_URL", DEFAULT_URL))
        val config = HikariConfig()
        config.jdbcUrl = jdbcUrl(url)
        url.userInfo?.let { userInfo ->
            config.username = userInfo.substringBefore(':')
            if (':' in userInfo) {
                config.password = userInfo.substringAfter(':')
            }
        }
        config.connectionTimeout = 5_000 // недоступная база — ответ за 5 секунд, а не за 30
        return HikariDataSource(config) // база недоступна — сервис не стартует
    }
}

/** postgresql://app@127.0.0.1:5432/app → jdbc:postgresql://127.0.0.1:5432/app */
fun jdbcUrl(url: URI): String {
    val host = url.host ?: "localhost"
    val port = if (url.port < 0) 5432 else url.port
    val query = url.rawQuery?.let { "?$it" } ?: ""
    return "jdbc:postgresql://$host:$port${url.rawPath}$query"
}
