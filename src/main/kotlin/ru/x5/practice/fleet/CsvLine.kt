package ru.x5.practice.fleet

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Разбор CSV по data-contract.md, 1.1: одна физическая строка — одна запись, поэтому файл
 * разбирается построчно, и незакрытая кавычка портит только свою строку.
 */
object CsvLine {

    /** Текст файла: строго UTF-8 (иначе INVALID_ENCODING), без BOM в начале. */
    fun decode(content: ByteArray): String {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
        } catch (e: CharacterCodingException) {
            throw badRequest("INVALID_ENCODING", "file is not valid UTF-8")
        }
        return text.trimStart('﻿')
    }

    /** Физические строки файла: перевод строки \n или \r\n. Номер строки — индекс + 1. */
    fun lines(text: String): List<String> = text.split('\n').map { it.removeSuffix("\r") }

    /**
     * Одна строка как одна запись RFC 4180. null — незакрытая кавычка или кавычка внутри
     * незакавыченного поля (контракт требует такие строки отвергать).
     */
    fun parse(line: String): List<String>? {
        val fields = mutableListOf<String>()
        val n = line.length
        var i = 0
        while (true) {
            if (i < n && line[i] == '"') {
                i++
                val buf = StringBuilder()
                while (true) {
                    if (i >= n) return null
                    if (line[i] == '"') {
                        if (i + 1 < n && line[i + 1] == '"') {
                            buf.append('"')
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    buf.append(line[i])
                    i++
                }
                if (i < n && line[i] != ',') return null
                fields.add(buf.toString())
            } else {
                val end = line.indexOf(',', i).let { if (it < 0) n else it }
                val value = line.substring(i, end)
                if ('"' in value) return null
                fields.add(value)
                i = end
            }
            if (i >= n) return fields
            i++ // запятая
            if (i == n) {
                fields.add("")
                return fields
            }
        }
    }
}
