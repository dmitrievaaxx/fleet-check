package ru.x5.practice.fleet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpStatus

/** Нужна запущенная база: DATABASE_URL, как в README. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HealthTest {

    @Autowired
    private lateinit var client: TestRestTemplate

    @Test
    fun `health answers and counts records of all six entities`() {
        val response = client.getForEntity("/health", Map::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("ok", response.body!!["status"])
        assertEquals(TABLES, (response.body!!["records"] as Map<*, *>).keys.toList())
    }
}
