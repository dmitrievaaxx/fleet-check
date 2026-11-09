package ru.x5.practice.fleet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.util.LinkedMultiValueMap

/** Импорт и ошибки через HTTP. Нужна запущенная база: DATABASE_URL, как в README. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ImportTest {

    @Autowired
    private lateinit var client: TestRestTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clean() {
        jdbc.execute("TRUNCATE ${TABLES.joinToString(", ")} CASCADE")
    }

    private fun upload(dataset: String, content: ByteArray): ResponseEntity<Map<*, *>> {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", object : ByteArrayResource(content) {
            override fun getFilename() = "$dataset.csv"
        })
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        return client.postForEntity("/import/$dataset", HttpEntity(body, headers), Map::class.java)
    }

    private fun upload(dataset: String, text: String) = upload(dataset, text.toByteArray())

    private fun codes(body: Map<*, *>): Map<Int, String> =
        (body["errors"] as List<*>).associate { e -> (e as Map<*, *>)["row"] as Int to e["code"] as String }

    private fun counters(body: Map<*, *>) = listOf(body["total"], body["accepted"], body["rejected"], body["skipped"])

    private fun errorCode(response: ResponseEntity<Map<*, *>>) = (response.body!!["error"] as Map<*, *>)["code"]

    @Test
    fun `row errors follow the order of the contract`() {
        val text = VEHICLES + listOf(
            "V-004,,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",
            "V17,Х901АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",
            "V-005,Х902АА77,ГАЗ,Валдай,van,2019,diesel,20.0,1000,,15000,C,active",
            "V-006,Х903АА77,ГАЗ,Валдай,truck,1985,diesel,20.0,1000,,15000,C,active",
            "V-007,Х904АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,2000,15000,C,active",
            "V-008,А123ВС77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",
            "V-009,Х905АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active,extra",
            "V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,30.0,100000,95000,10000,C,active",
            " V-010 , Х906АА77 ,ГАЗ,Валдай, truck , 2019 ,diesel, 20.0 ,1000,,15000,C,active",
            "V-011,Х907АА77,ГАЗ,Валдай,truck,٢٠١٩,diesel,20.0,1000,,15000,C,active",
        ).joinToString("\n") + "\n"
        val response = upload("vehicles", text)
        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(listOf(13, 4, 8, 1), counters(body))
        assertEquals(
            mapOf(
                5 to "REQUIRED_FIELD_MISSING", 6 to "INVALID_FORMAT", 7 to "INVALID_VALUE", 8 to "INVALID_VALUE",
                9 to "CROSS_FIELD_VIOLATION", 10 to "UNIQUE_VIOLATION", 11 to "PARSE_ERROR", 14 to "INVALID_FORMAT",
            ),
            codes(body),
        )
        val card = client.getForEntity("/vehicles/V-010", Map::class.java)
        assertEquals("Х906АА77", card.body!!["plate"])
    }

    @Test
    fun `repeated import changes nothing and references are checked`() {
        upload("vehicles", VEHICLES)
        upload("drivers", DRIVERS)
        val waybills = """
            waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end,route_description
            WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,100300,
            WB-000002,V-009,D-001,2025-01-11T06:00:00,2025-01-11T18:00:00,100300,100600,
            WB-000003,V-001,D-999,2025-01-12T06:00:00,2025-01-12T18:00:00,100600,100900,
            WB-000004,V-001,D-001,2025-01-13T18:00:00,2025-01-13T06:00:00,100900,101200,
            WB-000005,V-001,D-001,2025-01-14T06:00:00,2025-01-14T18:00:00,101200,101200,"Склад №2, Подольск"
        """.trimIndent() + "\n"
        val first = upload("waybills", waybills).body!!
        assertEquals(listOf(5, 2, 3, 0), counters(first))
        assertEquals(mapOf(3 to "REFERENCE_NOT_FOUND", 4 to "REFERENCE_NOT_FOUND", 5 to "CROSS_FIELD_VIOLATION"), codes(first))
        val second = upload("waybills", waybills).body!!
        assertEquals(listOf(5, 0, 3, 2), counters(second))

        val health = client.getForEntity("/health", Map::class.java).body!!
        assertEquals(2, (health["records"] as Map<*, *>)["waybills"])
        val card = client.getForEntity("/waybills/WB-000005", Map::class.java).body!!
        assertEquals(0, card["mileage_km"])
        assertEquals("Склад №2, Подольск", card["route_description"])
    }

    @Test
    fun `file level errors are 400 with a code and save nothing`() {
        val missing = upload("vehicles", "vehicle_id,plate\nV-001,А123ВС77\n")
        assertEquals(400, missing.statusCode.value())
        assertEquals("MISSING_COLUMNS", errorCode(missing))
        assertEquals("EMPTY_FILE", errorCode(upload("vehicles", "﻿\n\n")))
        assertEquals("INVALID_ENCODING", errorCode(upload("vehicles", byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 'b'.code.toByte()))))
        assertEquals("UNKNOWN_DATASET", errorCode(upload("unicorns", "a,b\n1,2\n")))
        val noFile = client.postForEntity("/import/vehicles", null, Map::class.java)
        assertEquals("FILE_REQUIRED", errorCode(noFile))
        val headerOnly = upload("vehicles", VEHICLES.lines().first() + "\n")
        assertEquals(listOf(0, 0, 0, 0), counters(headerOnly.body!!))

        val records = client.getForEntity("/health", Map::class.java).body!!["records"] as Map<*, *>
        assertEquals(0, records["vehicles"])
    }

    @Test
    fun `bad parameters are 400 INVALID_PARAMETER and never 500`() {
        val bad = listOf(
            "/reports/fuel?year=20x5&month=1", "/reports/fuel?year=2025", "/reports/fuel?year=2025&month=13",
            "/reports/fuel?year=2025&month=1&format=xml", "/reports/maintenance-due",
            "/reports/maintenance-due?as_of=2025-02-30", "/reports/violations?type=WHATEVER",
            "/reports/violations?from=yesterday", "/reports/violations?from=2025-05-01&to=2025-04-01",
            "/reports/utilization?year=1999&month=1", "/reports/drivers?year=2025&month=0", "/vehicles?limit=0",
            "/vehicles?limit=many", "/vehicles?offset=-1", "/vehicles?status=sold", "/waybills?from=01.01.2025",
        )
        for (path in bad) {
            val response = client.getForEntity(path, Map::class.java)
            assertEquals(400, response.statusCode.value(), path)
            assertEquals("INVALID_PARAMETER", errorCode(response), path)
        }
        assertEquals(404, client.getForEntity("/vehicles/V-999", Map::class.java).statusCode.value())
    }

    @Test
    fun `fuel report needs the norms and reports come as csv too`() {
        upload("vehicles", VEHICLES)
        upload("drivers", DRIVERS)
        upload(
            "waybills",
            "waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end\n" +
                "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,100100\n",
        )
        val refused = client.getForEntity("/reports/fuel?year=2025&month=1", Map::class.java)
        assertEquals(422, refused.statusCode.value())
        assertEquals("FUEL_NORM_MISSING", errorCode(refused))

        upload("fuel_norms", NORMS)
        val report = client.getForEntity("/reports/fuel?year=2025&month=1", Map::class.java).body!!
        assertEquals("fuel", report["report"])
        assertEquals(1, (report["rows"] as List<*>).size)
        val csv = client.getForEntity("/reports/violations?format=csv", String::class.java)
        assertEquals("csv", csv.headers.contentType!!.subtype)
        assertEquals(VIOLATION_COLUMNS.joinToString(","), csv.body!!.lines().first())
    }

    companion object {
        val VEHICLES = """
            vehicle_id,plate,brand,model,type,year,fuel_type,base_consumption_l_100km,odometer_km,last_service_odometer_km,service_interval_km,license_category,status
            V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,30.0,100000,95000,10000,C,active
            V-002,В456ЕК77,Lada,Granta,car,2021,petrol,8.0,50000,,15000,B,active
            V-003,Е789КМ77,ПАЗ,320412,bus,2020,diesel,24.0,20000,20000,20000,D,decommissioned
        """.trimIndent() + "\n"
        val DRIVERS = """
            driver_id,full_name,license_number,license_categories,license_valid_until,hired_at,status
            D-001,Иванов Пётр Сергеевич,77 12 345678,B;C,2027-05-20,2016-03-01,active
            D-002,Смирнова Анна Олеговна,77 09 112233,B,2025-03-10,2022-08-15,active
            D-003,Кузнецов Олег Иванович,50 14 998877,C;D,2030-01-01,2010-01-11,active
        """.trimIndent() + "\n"
        val NORMS = """
            fuel_type,season,coefficient
            diesel,winter,1.12
            diesel,summer,1.00
            petrol,winter,1.10
            petrol,summer,1.00
            gas,winter,1.15
            gas,summer,1.00
        """.trimIndent() + "\n"
    }
}
