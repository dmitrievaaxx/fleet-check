package ru.x5.practice.fleet;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Подключение к PostgreSQL по DATABASE_URL.
 *
 * <p>На проверке платформа сама поднимает базу и передаёт её адрес; локально его задаёт
 * compose.yaml. Адрес записан так, как его понимает libpq: {@code
 * postgresql://пользователь:пароль@хост:порт/база}, а Spring нужен JDBC — поэтому подключение
 * собирается здесь. Пароля в адресе может не быть: на проверке база пускает без него.
 */
@Configuration
public class DatabaseConfig {

    static final String DEFAULT_URL = "postgresql://app:app@localhost:5432/app";

    @Bean
    public DataSource dataSource(Environment env) {
        URI url = URI.create(env.getProperty("DATABASE_URL", DEFAULT_URL));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl(url));
        String userInfo = url.getUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            config.setUsername(colon < 0 ? userInfo : userInfo.substring(0, colon));
            if (colon >= 0) {
                config.setPassword(userInfo.substring(colon + 1));
            }
        }
        config.setConnectionTimeout(5_000); // недоступная база — ответ за 5 секунд, а не за 30
        return new HikariDataSource(config); // база недоступна — сервис не стартует
    }

    /** postgresql://app@127.0.0.1:5432/app → jdbc:postgresql://127.0.0.1:5432/app */
    static String jdbcUrl(URI url) {
        String host = url.getHost() == null ? "localhost" : url.getHost();
        int port = url.getPort() < 0 ? 5432 : url.getPort();
        String query = url.getRawQuery() == null ? "" : "?" + url.getRawQuery();
        return "jdbc:postgresql://" + host + ":" + port + url.getRawPath() + query;
    }
}
