package ru.x5.practice.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Сервис целиком через HTTP. Нужна база PostgreSQL: DATABASE_URL, как в README. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@SuppressWarnings({"rawtypes", "unchecked"})
class ApiTest {

    static final String VEHICLES = """
            vehicle_id,plate,brand,model,type,year,fuel_type,base_consumption_l_100km,odometer_km,last_service_odometer_km,service_interval_km,license_category,status
            V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,32.5,214500,210000,15000,C,active
            V-002,В456ЕК77,ПАЗ,320412,van,2020,diesel,24.0,98200,,20000,D,active
            V-003,Е789КМ77,Lada,Granta,car,2021,petrol,7.8,45100,40000,15000,B,active
            V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,32.5,214500,210000,15000,C,active
            V-004,Е789КМ77,Lada,Granta,car,2021,petrol,7.8,45100,40000,15000,B,active
            """;
    static final String DRIVERS = """
            driver_id,full_name,license_number,license_categories,license_valid_until,hired_at,status
            D-001,Иванов Пётр Сергеевич,77 12 345678,B;C,2027-05-20,2016-03-01,active
            """;
    static final String WAYBILLS = """
            waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end,route_description
            WB-000001,V-001,D-001,2025-01-09T06:30:00,2025-01-09T17:45:00,214500,214812,"Склад №2, Подольск"
            WB-000002,V-001,D-999,2025-01-10T06:30:00,2025-01-10T17:45:00,214812,215000,
            """;
    static final String NORMS = """
            fuel_type,season,coefficient
            diesel,winter,1.12
            """;

    @Autowired
    private TestRestTemplate client;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("TRUNCATE maintenance, fuel_records, waybills, fuel_norms, drivers, vehicles");
    }

    private ResponseEntity<Map> upload(String dataset, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return dataset + ".csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return client.postForEntity("/import/" + dataset, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> upload(String dataset, String content) {
        return upload(dataset, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String code(ResponseEntity<Map> response) {
        return (String) ((Map) response.getBody().get("error")).get("code");
    }

    private Map records() {
        return (Map) client.getForEntity("/health", Map.class).getBody().get("records");
    }

    @Test
    void healthCountsRecordsOfEveryDataset() {
        ResponseEntity<Map> response = client.getForEntity("/health", Map.class);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("ok", response.getBody().get("status"));
        assertEquals(Map.of("vehicles", 0, "drivers", 0, "fuel_norms", 0, "waybills", 0, "fuel_records", 0,
                "maintenance", 0), records());
    }

    @Test
    void importChecksRowsAndRepeatChangesNothing() {
        Map first = upload("vehicles", VEHICLES).getBody();
        assertEquals(5, first.get("total"));
        assertEquals(2, first.get("accepted"));
        assertEquals(2, first.get("rejected"));
        assertEquals(1, first.get("skipped"));
        List<Map> errors = (List<Map>) first.get("errors");
        assertEquals(List.of(3, 6), errors.stream().map(e -> e.get("row")).toList());
        assertEquals(List.of("INVALID_VALUE", "UNIQUE_VIOLATION"), errors.stream().map(e -> e.get("code")).toList());

        Map before = records();
        Map repeat = upload("vehicles", VEHICLES).getBody();
        assertEquals(0, repeat.get("accepted"));
        assertEquals(3, repeat.get("skipped"));
        assertEquals(before, records());
    }

    @Test
    void fileErrorsSaveNothing() {
        assertEquals("UNKNOWN_DATASET", code(upload("unicorns", "a,b\n1,2\n")));
        assertEquals("EMPTY_FILE", code(upload("vehicles", "﻿")));
        assertEquals("INVALID_ENCODING", code(upload("vehicles", new byte[] {(byte) 0xff, (byte) 0xfe, 0, 'b'})));
        ResponseEntity<Map> missing = upload("vehicles", "vehicle_id,plate\nV-001,А123ВС77\n");
        assertEquals(400, missing.getStatusCode().value());
        assertEquals("MISSING_COLUMNS", code(missing));
        ResponseEntity<Map> noFile = client.postForEntity("/import/vehicles", null, Map.class);
        assertEquals(400, noFile.getStatusCode().value());
        assertEquals("FILE_REQUIRED", code(noFile));
        assertEquals(0, records().get("vehicles"));
    }

    @Test
    void badParametersAreInvalidParameter() {
        for (String path : List.of("/reports/fuel?year=20x5&month=1", "/reports/fuel?year=2025",
                "/reports/maintenance-due?as_of=2025-02-30", "/reports/violations?type=WHATEVER",
                "/reports/violations?from=2025-05-01&to=2025-04-01", "/vehicles?limit=0", "/vehicles?offset=-1",
                "/vehicles?status=sold", "/waybills?from=01.01.2025")) {
            ResponseEntity<Map> response = client.getForEntity(path, Map.class);
            assertEquals(400, response.getStatusCode().value(), path);
            assertEquals("INVALID_PARAMETER", code(response), path);
        }
        ResponseEntity<Map> missing = client.getForEntity("/vehicles/V-999", Map.class);
        assertEquals(404, missing.getStatusCode().value());
        assertEquals("NOT_FOUND", code(missing));
    }

    @Test
    void fuelReportWaitsForNormsAndHasACsvTwin() {
        upload("vehicles", VEHICLES);
        upload("drivers", DRIVERS);
        Map waybills = upload("waybills", WAYBILLS).getBody();
        assertEquals("REFERENCE_NOT_FOUND", ((List<Map>) waybills.get("errors")).get(0).get("code"));

        ResponseEntity<Map> before = client.getForEntity("/reports/fuel?year=2025&month=1", Map.class);
        assertEquals(422, before.getStatusCode().value());
        assertEquals("FUEL_NORM_MISSING", code(before));

        upload("fuel_norms", NORMS);
        Map report = client.getForEntity("/reports/fuel?year=2025&month=1", Map.class).getBody();
        assertEquals("fuel", report.get("report"));
        assertEquals(1, ((List) report.get("rows")).size());

        ResponseEntity<String> csv = client.getForEntity("/reports/fuel?year=2025&month=1&format=csv", String.class);
        assertTrue(csv.getHeaders().getContentType().toString().startsWith("text/csv"));
        assertTrue(csv.getBody().startsWith("vehicle_id,plate,fuel_type,mileage_km,norm_liters,actual_liters,"
                + "deviation_liters,deviation_pct,overrun\n"));

        Map card = client.getForEntity("/waybills/WB-000001", Map.class).getBody();
        assertEquals(312, card.get("mileage_km"));
        assertEquals("Склад №2, Подольск", card.get("route_description"));
    }
}
