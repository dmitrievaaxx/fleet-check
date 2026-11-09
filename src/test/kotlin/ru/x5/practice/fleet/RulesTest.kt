package ru.x5.practice.fleet

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Правила и отчёты на мини-наборе в памяти, без базы и Spring. */
class RulesTest {

    private val truck = Vehicle(
        "V-001", "А123ВС77", "КамАЗ", "65115", "truck", 2018, "diesel", 30.0, 100_000, 95_000, 10_000, "C", "active",
    )
    private val old = truck.copy(vehicleId = "V-002", plate = "В456ЕК77", status = "decommissioned")
    private val ivanov = Driver(
        "D-001", "Иванов Пётр Сергеевич", "77 12 345678", listOf("B", "C"),
        LocalDate.parse("2027-05-20"), LocalDate.parse("2016-03-01"), "active",
    )
    private val smirnova = Driver(
        "D-002", "Смирнова Анна Олеговна", "77 09 112233", listOf("B"),
        LocalDate.parse("2025-03-10"), LocalDate.parse("2022-08-15"), "active",
    )
    private val norms = mapOf(("diesel" to "winter") to 1.12, ("diesel" to "summer") to 1.0)

    private fun at(s: String) = LocalDateTime.parse(s)

    private fun waybill(id: String, departure: String, ret: String, start: Int, end: Int, vehicle: String = "V-001", driver: String = "D-001") =
        Waybill(id, vehicle, driver, at(departure), at(ret), start, end, null)

    private fun domain(
        waybills: List<Waybill>,
        fuel: List<FuelRecord> = emptyList(),
        maintenance: List<Maintenance> = emptyList(),
        fuelNorms: Map<Pair<String, String>, Double> = norms,
    ) = Domain(listOf(truck, old), listOf(ivanov, smirnova), fuelNorms, waybills, fuel, maintenance)

    @Test
    fun `overlap and license category are reported on the later waybill`() {
        val rows = Rules(
            domain(
                listOf(
                    waybill("WB-000001", "2025-01-10T06:00:00", "2025-01-10T18:00:00", 100_000, 100_300),
                    waybill("WB-000002", "2025-01-10T12:00:00", "2025-01-10T20:00:00", 100_300, 100_600, driver = "D-002"),
                ),
            ),
        ).violations("VEHICLE_OVERLAP")
        assertEquals(1, rows.size)
        assertEquals("WB-000002", rows[0].subjectId)
        assertEquals("WB-000001", rows[0].relatedId)

        val mismatch = Rules(
            domain(listOf(waybill("WB-000003", "2025-01-11T06:00:00", "2025-01-11T18:00:00", 100_000, 100_100, driver = "D-002"))),
        ).violations("LICENSE_CATEGORY_MISMATCH")
        assertEquals(listOf("WB-000003"), mismatch.map { it.subjectId })
        assertEquals(mapOf("required" to "C", "driver_categories" to listOf("B")), mismatch[0].details)
    }

    @Test
    fun `expired license and decommissioned vehicle`() {
        val rows = Rules(
            domain(
                listOf(
                    waybill("WB-000001", "2025-03-11T06:00:00", "2025-03-11T18:00:00", 100_000, 100_100, driver = "D-002"),
                    waybill("WB-000002", "2025-03-12T06:00:00", "2025-03-12T18:00:00", 100_000, 100_100, vehicle = "V-002"),
                ),
            ),
        ).violations()
        val types = rows.map { it.type to it.subjectId }.toSet()
        assert(("LICENSE_EXPIRED" to "WB-000001") in types)
        assert(("VEHICLE_DECOMMISSIONED" to "WB-000002") in types)
    }

    @Test
    fun `odometer gap beyond five kilometres names the previous waybill`() {
        val rows = Rules(
            domain(
                listOf(
                    waybill("WB-000001", "2025-01-10T06:00:00", "2025-01-10T18:00:00", 100_000, 100_300),
                    waybill("WB-000002", "2025-01-11T06:00:00", "2025-01-11T18:00:00", 100_305, 100_400),
                    waybill("WB-000003", "2025-01-12T06:00:00", "2025-01-12T18:00:00", 100_410, 100_500),
                ),
            ),
        ).violations("ODOMETER_GAP")
        assertEquals(1, rows.size)
        assertEquals("WB-000003", rows[0].subjectId)
        assertEquals("WB-000002", rows[0].relatedId)
        assertEquals(mapOf("expected" to 100_400, "actual" to 100_410, "delta" to 10), rows[0].details)
    }

    @Test
    fun `overdue maintenance is one violation per episode`() {
        val service = Maintenance("M-00001", "V-001", "scheduled", at("2025-02-01T08:00:00"), at("2025-02-01T17:00:00"), 107_000)
        val rows = Rules(
            domain(
                listOf(
                    waybill("WB-000001", "2025-01-10T06:00:00", "2025-01-10T18:00:00", 100_000, 104_000),
                    waybill("WB-000002", "2025-01-11T06:00:00", "2025-01-11T18:00:00", 104_000, 106_000),
                    waybill("WB-000003", "2025-01-12T06:00:00", "2025-01-12T18:00:00", 106_000, 107_000),
                    waybill("WB-000004", "2025-02-02T06:00:00", "2025-02-02T18:00:00", 107_000, 118_000),
                ),
                maintenance = listOf(service),
            ),
        ).violations("MAINTENANCE_OVERDUE")
        assertEquals(listOf("WB-000002", "WB-000004"), rows.map { it.subjectId })
        assertEquals(107_000, rows[1].details["service_odometer"])
    }

    @Test
    fun `fuel overrun over ten percent of the norm`() {
        val dom = domain(
            listOf(waybill("WB-000001", "2025-01-10T06:00:00", "2025-01-10T18:00:00", 100_000, 100_100)),
            fuel = listOf(FuelRecord("F-000001", "V-001", at("2025-01-10T19:00:00"), 40.0)),
        )
        val rows = Rules(dom).violations("FUEL_OVERRUN")
        assertEquals(1, rows.size)
        assertEquals("2025-01-01T00:00:00", rows[0].occurredAt)
        assertEquals(33.6, rows[0].details["norm_liters"])
        assertEquals(19.0, rows[0].details["deviation_pct"])

        val report = Reports(dom).fuel(2025, 1).single()
        assertEquals(true, report["overrun"])
        assertEquals(6.4, report["deviation_liters"])
    }

    @Test
    fun `missing norms are 422 only where norms are needed`() {
        val dom = domain(
            listOf(waybill("WB-000001", "2025-07-10T06:00:00", "2025-07-10T18:00:00", 100_000, 100_100)),
            fuelNorms = mapOf(("diesel" to "winter") to 1.12),
        )
        val error = assertThrows<ApiException> { Rules(dom).violations() }
        assertEquals("FUEL_NORM_MISSING", error.code)
        assertEquals(listOf(mapOf("fuel_type" to "diesel", "season" to "summer")), error.details["missing"])
        assertEquals(emptyList<Violation>(), Rules(dom).violations("ODOMETER_GAP"))
        assertEquals(emptyList<Row>(), Reports(dom).fuel(2025, 1))
    }

    @Test
    fun `utilization counts days on line, in maintenance and idle`() {
        val repair = Maintenance("M-00001", "V-001", "repair", at("2025-01-30T08:00:00"), null, 100_500)
        val dom = domain(
            listOf(
                waybill("WB-000001", "2025-01-10T06:00:00", "2025-01-10T18:00:00", 100_000, 100_300),
                waybill("WB-000002", "2025-01-11T22:00:00", "2025-01-12T02:00:00", 100_300, 100_400),
            ),
            maintenance = listOf(repair),
        )
        val row = Reports(dom).utilization(2025, 1).single()
        assertEquals(31, row["days_in_month"])
        assertEquals(3, row["days_on_line"])
        assertEquals(2, row["days_in_maintenance"])
        assertEquals(26, row["days_idle"])
        assertEquals(0.097, row["utilization"])
    }

    @Test
    fun `maintenance due takes the last scheduled service before the date`() {
        val dom = domain(listOf(waybill("WB-000001", "2025-01-10T06:00:00", "2025-01-10T18:00:00", 100_000, 104_200)))
        val row = Reports(dom).maintenanceDue(LocalDate.parse("2025-01-31")).single()
        assertEquals(104_200, row["current_odometer_km"])
        assertEquals(95_000, row["service_odometer_km"])
        assertNull(row["last_service_at"])
        assertEquals(800, row["km_remaining"])
        assertEquals("due_soon", row["status"])
    }

    @Test
    fun `csv line parsing follows rfc 4180 per physical line`() {
        assertEquals(listOf("a", "b,c", "d\"e", ""), CsvLine.parse("a,\"b,c\",\"d\"\"e\","))
        assertNull(CsvLine.parse("\"unterminated,b"))
        assertNull(CsvLine.parse("Вал\"дай,b"))
    }
}
