// Package importer — импорт CSV по data-contract.md: разбор файла построчно, проверки строки
// в порядке 3.5 и решение, что сохранить. База сюда не ходит: уже сохранённые ключи приходят
// в State, принятые строки уходят вызывающему для записи. Поэтому всё здесь проверяется
// обычными юнит-тестами.
package importer

import (
	"fmt"
	"strings"
	"unicode/utf8"

	"fleet/internal/apperr"
	"fleet/internal/jsonx"
)

// Line — физическая строка файла с её номером (с 1).
type Line struct {
	No   int
	Text string
}

// File — файл, прошедший проверки уровня файла (3.3): заголовок и строки после него.
type File struct {
	Header []string
	Lines  []Line
}

// RowError — отклонённая строка.
type RowError struct {
	Row     int
	Field   string // "" — ошибка разбора строки целиком (field: null)
	Code    string
	Message string
}

// Obj — элемент списка errors в ответе.
func (e RowError) Obj() jsonx.Obj {
	var field any
	if e.Field != "" {
		field = e.Field
	}
	return jsonx.O("row", e.Row, "field", field, "code", e.Code, "message", e.Message)
}

// Result — ImportResult (3.2).
type Result struct {
	Dataset  string
	Total    int
	Accepted int
	Rejected int
	Skipped  int
	Errors   []RowError
}

// Obj — тело ответа.
func (r Result) Obj() jsonx.Obj {
	errs := make([]jsonx.Obj, 0, len(r.Errors))
	for _, e := range r.Errors {
		errs = append(errs, e.Obj())
	}
	return jsonx.O("dataset", r.Dataset, "total", r.Total, "accepted", r.Accepted,
		"rejected", r.Rejected, "skipped", r.Skipped, "errors", errs)
}

// State — что уже лежит в базе: первичные ключи набора, значения уникальных колонок и
// идентификаторы таблиц, на которые набор ссылается. Process дополняет PK и Unique
// принятыми строками, поэтому повтор внутри файла ведёт себя как повтор в базе.
type State struct {
	PK     map[string]bool
	Unique map[string]map[string]bool
	Refs   map[string]map[string]bool
}

// NewState — пустое состояние.
func NewState() *State {
	return &State{PK: map[string]bool{}, Unique: map[string]map[string]bool{}, Refs: map[string]map[string]bool{}}
}

// UnknownDataset — 400 для имени набора не из списка.
func UnknownDataset(name string) *apperr.Error {
	return apperr.New(400, "UNKNOWN_DATASET", "unknown dataset "+name, jsonx.O("dataset", name))
}

// splitLines делит текст на физические строки: перевод строки "\n" или "\r\n"
// (data-contract.md, 1.1).
func splitLines(text string) []Line {
	parts := strings.Split(text, "\n")
	if len(parts) > 0 && parts[len(parts)-1] == "" {
		parts = parts[:len(parts)-1]
	}
	lines := make([]Line, len(parts))
	for i, p := range parts {
		lines[i] = Line{No: i + 1, Text: strings.TrimSuffix(p, "\r")}
	}
	return lines
}

// Prepare — проверки уровня файла в порядке контракта: INVALID_ENCODING, EMPTY_FILE,
// MISSING_COLUMNS (UNKNOWN_DATASET проверяет вызывающий раньше чтения файла).
func Prepare(spec *Spec, content []byte) (*File, *apperr.Error) {
	if !utf8.Valid(content) {
		return nil, apperr.New(400, "INVALID_ENCODING", "file is not valid UTF-8", nil)
	}
	text := strings.TrimLeft(string(content), "\ufeff")
	lines := splitLines(text)
	start := -1
	for i, l := range lines {
		if l.Text != "" {
			start = i
			break
		}
	}
	if start < 0 {
		return nil, apperr.New(400, "EMPTY_FILE", "file has no header row", nil)
	}
	// Заголовок, который не разбирается как CSV, — заголовок без обязательных колонок (3.3).
	fields, _ := ParseLine(lines[start].Text)
	header := make([]string, len(fields))
	for i, h := range fields {
		header[i] = strings.TrimSpace(h)
	}
	present := make(map[string]bool, len(header))
	for _, h := range header {
		present[h] = true
	}
	missing := []string{}
	for _, name := range spec.RequiredNames() {
		if !present[name] {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return nil, apperr.New(400, "MISSING_COLUMNS", "required columns are missing", jsonx.O("columns", missing))
	}
	return &File{Header: header, Lines: lines[start+1:]}, nil
}

// ParseLine разбирает одну физическую строку как запись RFC 4180. false — незакрытая кавычка
// или кавычка внутри незакавыченного поля.
func ParseLine(line string) ([]string, bool) {
	var fields []string
	i, n := 0, len(line)
	for {
		if i < n && line[i] == '"' {
			i++
			var buf strings.Builder
			for {
				if i >= n {
					return nil, false
				}
				if line[i] == '"' {
					if i+1 < n && line[i+1] == '"' {
						buf.WriteByte('"')
						i += 2
						continue
					}
					i++
					break
				}
				buf.WriteByte(line[i])
				i++
			}
			if i < n && line[i] != ',' {
				return nil, false
			}
			fields = append(fields, buf.String())
		} else {
			end := strings.IndexByte(line[i:], ',')
			if end < 0 {
				end = n
			} else {
				end += i
			}
			if strings.IndexByte(line[i:end], '"') >= 0 {
				return nil, false
			}
			fields = append(fields, line[i:end])
			i = end
		}
		if i >= n {
			return fields, true
		}
		i++ // запятая
		if i == n {
			return append(fields, ""), true
		}
	}
}

// ValidateRow — проверки 1–5 из 3.5 для одной строки; при успехе — разобранные значения
// (nil для пустых).
func ValidateRow(spec *Spec, header, fields []string, rowNo int) (*RowError, map[string]any) {
	if len(fields) != len(header) {
		return &RowError{Row: rowNo, Code: "PARSE_ERROR",
			Message: fmt.Sprintf("expected %d fields, got %d", len(header), len(fields))}, nil
	}
	raw := make(map[string]string, len(spec.Columns))
	for i, name := range header {
		if spec.has(name) {
			raw[name] = strings.TrimSpace(fields[i])
		}
	}
	for _, c := range spec.Columns {
		if c.Required && raw[c.Name] == "" {
			return &RowError{Row: rowNo, Field: c.Name, Code: "REQUIRED_FIELD_MISSING", Message: c.Name + " is required"}, nil
		}
	}
	parsed := make(map[string]any, len(spec.Columns))
	for _, c := range spec.Columns {
		value := raw[c.Name]
		if value == "" {
			parsed[c.Name] = nil
			continue
		}
		if c.Parse == nil {
			parsed[c.Name] = value
			continue
		}
		v, ok := c.Parse(value)
		if !ok {
			return &RowError{Row: rowNo, Field: c.Name, Code: "INVALID_FORMAT",
				Message: fmt.Sprintf("%s has invalid format: %s", c.Name, value)}, nil
		}
		parsed[c.Name] = v
	}
	for _, c := range spec.Columns {
		if c.Check != nil && parsed[c.Name] != nil && !c.Check(parsed[c.Name]) {
			return &RowError{Row: rowNo, Field: c.Name, Code: "INVALID_VALUE",
				Message: fmt.Sprintf("%s is out of allowed values: %s", c.Name, raw[c.Name])}, nil
		}
	}
	if spec.Cross != nil {
		if bad := spec.Cross(parsed); bad != "" {
			return &RowError{Row: rowNo, Field: bad, Code: "CROSS_FIELD_VIOLATION", Message: bad + " conflicts with another field"}, nil
		}
	}
	return nil, parsed
}

// Key — первичный ключ строки.
func Key(spec *Spec, parsed map[string]any) string {
	parts := make([]string, len(spec.PK))
	for i, name := range spec.PK {
		parts[i] = fmt.Sprint(parsed[name])
	}
	return strings.Join(parts, "\x1f")
}

// Process — строки файла по порядку: проверки 1–8 из 3.5. Возвращает результат импорта
// и принятые строки (значения в порядке Spec.Columns) для записи в базу.
func Process(spec *Spec, file *File, st *State) (Result, [][]any) {
	res := Result{Dataset: spec.Name, Errors: []RowError{}}
	var rows [][]any
	for _, line := range file.Lines {
		if line.Text == "" {
			continue
		}
		res.Total++
		var rowErr *RowError
		var parsed map[string]any
		fields, ok := ParseLine(line.Text)
		if !ok {
			rowErr = &RowError{Row: line.No, Code: "PARSE_ERROR", Message: "unterminated quoted field"}
		} else {
			rowErr, parsed = ValidateRow(spec, file.Header, fields, line.No)
		}
		if rowErr == nil {
			for _, ref := range spec.Refs {
				id, isSet := parsed[ref.Field].(string)
				if isSet && !st.Refs[ref.Table][id] {
					rowErr = &RowError{Row: line.No, Field: ref.Field, Code: "REFERENCE_NOT_FOUND",
						Message: fmt.Sprintf("%s %s not found", ref.Field, id)}
					break
				}
			}
		}
		key := ""
		if rowErr == nil {
			key = Key(spec, parsed)
			if st.PK[key] {
				res.Skipped++
				continue
			}
			for _, col := range spec.Unique {
				value := parsed[col].(string)
				if st.Unique[col][value] {
					rowErr = &RowError{Row: line.No, Field: col, Code: "UNIQUE_VIOLATION",
						Message: fmt.Sprintf("%s %s already exists", col, value)}
					break
				}
			}
		}
		if rowErr != nil {
			res.Rejected++
			res.Errors = append(res.Errors, *rowErr)
			continue
		}
		row := make([]any, len(spec.Columns))
		for i, c := range spec.Columns {
			v := parsed[c.Name]
			if list, isList := v.([]string); isList {
				v = strings.Join(list, ";")
			}
			row[i] = v
		}
		rows = append(rows, row)
		st.PK[key] = true
		for _, col := range spec.Unique {
			if st.Unique[col] == nil {
				st.Unique[col] = map[string]bool{}
			}
			st.Unique[col][parsed[col].(string)] = true
		}
		res.Accepted++
	}
	return res, rows
}
