package ru.x5.practice.fleet.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Порядок проверок строки (data-contract.md, 3.5) без базы: разбор, обязательность, формат, значения. */
class RowChecksTest {

    static final DatasetSpec DRIVERS = Datasets.ALL.get("drivers");
    static final List<String> HEADER = List.of("driver_id", "full_name", "license_number", "license_categories",
            "license_valid_until", "hired_at", "status");

    static String code(String line) {
        ImportService.Row row = ImportService.validate(DRIVERS, HEADER, CsvLine.parse(line), 2);
        return row.error == null ? null : (String) row.error.get("code");
    }

    @Test
    void acceptsAValidRowWithSpacesAroundValues() {
        assertNull(code("D-001, Иванов Пётр ,77 12 345678,B;C,2027-05-20,2016-03-01, active"));
    }

    @Test
    void checksInContractOrder() {
        assertEquals("PARSE_ERROR", code("D-001,Иванов,77 12 345678,B;C,2027-05-20,2016-03-01"));
        assertEquals("REQUIRED_FIELD_MISSING", code("D-1,Иванов,77 12 345678,B;C,2027-05-20,2016-03-01,  "));
        assertEquals("INVALID_FORMAT", code("D-1,Иванов,77 12 345678,B;C,2027-05-20,2016-03-01,fired"));
        assertEquals("INVALID_VALUE", code("D-001,Иванов,77 12 345678,B;C,2027-05-20,2016-03-01,fired"));
        assertEquals("INVALID_FORMAT", code("D-001,Иванов,77 12 345678,B;C,2025-02-30,2016-03-01,active"));
    }

    @Test
    void categoryListStructureIsFormatAndUnknownCategoryIsValue() {
        assertEquals("INVALID_FORMAT", code("D-001,Иванов,77 12 345678,B;;C,2027-05-20,2016-03-01,active"));
        assertEquals("INVALID_FORMAT", code("D-001,Иванов,77 12 345678,B; C,2027-05-20,2016-03-01,active"));
        assertEquals("INVALID_FORMAT", code("D-001,Иванов,77 12 345678,B C,2027-05-20,2016-03-01,active"));
        assertEquals("INVALID_FORMAT", code("D-001,Иванов,77 12 345678,B;B,2027-05-20,2016-03-01,active"));
        assertEquals("INVALID_VALUE", code("D-001,Иванов,77 12 345678,B;CD,2027-05-20,2016-03-01,active"));
        assertEquals("INVALID_VALUE", code("D-001,Иванов,77 12 345678,B;c,2027-05-20,2016-03-01,active"));
    }
}
