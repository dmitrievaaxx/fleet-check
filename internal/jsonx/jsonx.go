// Package jsonx — JSON с фиксированным порядком ключей.
//
// Ответы сервиса и колонка details в CSV пишутся объектами Obj: порядок полей — как в контракте,
// пустые массивы — [], а не null (api-contract.md, 1.2). Дробные числа выводятся так же, как их
// пишет Python (32.0, а не 32), чтобы CSV совпадал с эталонной реализацией.
package jsonx

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"strings"
)

// KV — одно поле объекта.
type KV struct {
	Key   string
	Value any
}

// Obj — объект JSON с порядком ключей.
type Obj []KV

// O собирает объект из пар ключ, значение: O("a", 1, "b", "x").
func O(pairs ...any) Obj {
	o := make(Obj, 0, len(pairs)/2)
	for i := 0; i+1 < len(pairs); i += 2 {
		o = append(o, KV{Key: pairs[i].(string), Value: pairs[i+1]})
	}
	return o
}

// Get возвращает значение поля или nil.
func (o Obj) Get(key string) any {
	for _, kv := range o {
		if kv.Key == key {
			return kv.Value
		}
	}
	return nil
}

// MarshalJSON позволяет отдавать Obj и через encoding/json.
func (o Obj) MarshalJSON() ([]byte, error) { return Marshal(o), nil }

// Marshal — компактный JSON для ответов API.
func Marshal(v any) []byte {
	var b bytes.Buffer
	encode(&b, v, ",", ":")
	return b.Bytes()
}

// Python — JSON так, как его пишет json.dumps по умолчанию (разделители ", " и ": "):
// так выглядит колонка details в CSV-представлении отчёта о нарушениях.
func Python(v any) string {
	var b bytes.Buffer
	encode(&b, v, ", ", ": ")
	return b.String()
}

// Float — дробное число в записи Python repr: 12.0, 0.1, 1e+16.
func Float(f float64) string {
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return "null"
	}
	if f == 0 {
		if math.Signbit(f) {
			return "-0.0"
		}
		return "0.0"
	}
	abs := math.Abs(f)
	if abs >= 1e16 || abs < 1e-4 {
		return strconv.FormatFloat(f, 'e', -1, 64)
	}
	s := strconv.FormatFloat(f, 'f', -1, 64)
	if !strings.Contains(s, ".") {
		s += ".0"
	}
	return s
}

// Round — округление как round(x, n) в Python: по точному двоичному значению, половина — к чётному.
func Round(x float64, places int) float64 {
	r, err := strconv.ParseFloat(strconv.FormatFloat(x, 'f', places, 64), 64)
	if err != nil {
		return x
	}
	return r
}

func encode(b *bytes.Buffer, v any, itemSep, keySep string) {
	switch x := v.(type) {
	case nil:
		b.WriteString("null")
	case bool:
		if x {
			b.WriteString("true")
		} else {
			b.WriteString("false")
		}
	case int:
		b.WriteString(strconv.Itoa(x))
	case int64:
		b.WriteString(strconv.FormatInt(x, 10))
	case float64:
		b.WriteString(Float(x))
	case string:
		quote(b, x)
	case *string:
		if x == nil {
			b.WriteString("null")
		} else {
			quote(b, *x)
		}
	case *int:
		if x == nil {
			b.WriteString("null")
		} else {
			b.WriteString(strconv.Itoa(*x))
		}
	case []string:
		b.WriteByte('[')
		for i, s := range x {
			if i > 0 {
				b.WriteString(itemSep)
			}
			quote(b, s)
		}
		b.WriteByte(']')
	case []any:
		b.WriteByte('[')
		for i, item := range x {
			if i > 0 {
				b.WriteString(itemSep)
			}
			encode(b, item, itemSep, keySep)
		}
		b.WriteByte(']')
	case Obj:
		b.WriteByte('{')
		for i, kv := range x {
			if i > 0 {
				b.WriteString(itemSep)
			}
			quote(b, kv.Key)
			b.WriteString(keySep)
			encode(b, kv.Value, itemSep, keySep)
		}
		b.WriteByte('}')
	case []Obj:
		b.WriteByte('[')
		for i, item := range x {
			if i > 0 {
				b.WriteString(itemSep)
			}
			encode(b, item, itemSep, keySep)
		}
		b.WriteByte(']')
	default:
		data, err := json.Marshal(x)
		if err != nil {
			b.WriteString("null")
			return
		}
		b.Write(data)
	}
}

// quote пишет строку как json.dumps(..., ensure_ascii=False): экранируются только кавычка,
// обратная косая черта и управляющие символы.
func quote(b *bytes.Buffer, s string) {
	b.WriteByte('"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch c {
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		case '\b':
			b.WriteString(`\b`)
		case '\f':
			b.WriteString(`\f`)
		default:
			if c < 0x20 {
				fmt.Fprintf(b, `\u%04x`, c)
			} else {
				b.WriteByte(c)
			}
		}
	}
	b.WriteByte('"')
}
