package ru.x5.practice.fleet.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvLineTest {

    @Test
    void splitsPlainAndQuotedFields() {
        assertEquals(List.of("WB-000001", "Склад №2, Подольск", ""),
                CsvLine.parse("WB-000001,\"Склад №2, Подольск\","));
        assertEquals(List.of("a", "say \"hi\"", "c"), CsvLine.parse("a,\"say \"\"hi\"\"\",c"));
        assertEquals(List.of("", ""), CsvLine.parse(","));
    }

    @Test
    void rejectsBrokenQuotes() {
        assertNull(CsvLine.parse("\"unterminated,V-001"));
        assertNull(CsvLine.parse("Вал\"дай,1"));
        assertNull(CsvLine.parse("\"closed\"x,1"));
    }

    @Test
    void stripsUnicodeSpaces() {
        assertEquals("B", Text.strip(" \tB  "));
        assertEquals("", Text.strip("   "));
    }
}
