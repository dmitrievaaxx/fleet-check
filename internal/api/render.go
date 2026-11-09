package api

import (
	"strconv"
	"strings"

	"fleet/internal/jsonx"
)

// csvValue — значение ячейки CSV: null — пустая ячейка, объект и массив — JSON-строка
// (reports-contract.md, 1.2), дробные числа — как в JSON.
func csvValue(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case bool:
		if x {
			return "true"
		}
		return "false"
	case string:
		return x
	case *string:
		if x == nil {
			return ""
		}
		return *x
	case int:
		return strconv.Itoa(x)
	case int64:
		return strconv.FormatInt(x, 10)
	case float64:
		return jsonx.Float(x)
	default:
		return jsonx.Python(x)
	}
}

// writeCSVRow — строка CSV по RFC 4180: в кавычки берутся только значения с запятой,
// кавычкой или переводом строки, перевод строки "\n".
func writeCSVRow(b *strings.Builder, cells []string) {
	for i, cell := range cells {
		if i > 0 {
			b.WriteByte(',')
		}
		if strings.ContainsAny(cell, ",\"\r\n") {
			b.WriteByte('"')
			b.WriteString(strings.ReplaceAll(cell, `"`, `""`))
			b.WriteByte('"')
		} else {
			b.WriteString(cell)
		}
	}
	b.WriteByte('\n')
}
