package api

import (
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"fleet/internal/apperr"
)

// query — параметры запроса. Пустое значение равнозначно отсутствующему (api-contract.md, 1.5);
// при повторе параметра берётся последнее значение.
type query struct{ values url.Values }

// raw — значение параметра и было ли оно в запросе вообще (для details.value).
func (q query) raw(name string) (string, bool) {
	vs, ok := q.values[name]
	if !ok || len(vs) == 0 {
		return "", false
	}
	return vs[len(vs)-1], true
}

func (q query) shown(name string) any {
	v, ok := q.raw(name)
	if !ok {
		return nil
	}
	return v
}

// Целое — цифры с необязательным минусом (api-contract.md, 1.5): 2.0, 1e2, +5 — не целые.
var intParam = regexp.MustCompile(`^-?[0-9]+$`)

func (q query) int(name string, lo, hi, def int64, required bool) (int64, *apperr.Error) {
	raw, _ := q.raw(name)
	if raw == "" {
		if required {
			return 0, apperr.InvalidParameter(name, q.shown(name), name+" is required")
		}
		return def, nil
	}
	if !intParam.MatchString(raw) {
		return 0, apperr.InvalidParameter(name, raw, name+" must be an integer")
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value < lo || value > hi {
		return 0, apperr.InvalidParameter(name, raw, fmt.Sprintf("%s must be between %d and %d", name, lo, hi))
	}
	return value, nil
}

var dateParam = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}$`)

// date — календарно корректная дата YYYY-MM-DD; "" — параметра нет.
func (q query) date(name string, required bool) (string, *apperr.Error) {
	raw, _ := q.raw(name)
	if raw == "" {
		if required {
			return "", apperr.InvalidParameter(name, q.shown(name), name+" is required")
		}
		return "", nil
	}
	t, err := time.Parse("2006-01-02", raw)
	if !dateParam.MatchString(raw) || err != nil || t.Year() < 1 {
		return "", apperr.InvalidParameter(name, raw, name+" must be a date YYYY-MM-DD")
	}
	return raw, nil
}

func (q query) enum(name string, allowed []string, def string) (string, *apperr.Error) {
	raw, _ := q.raw(name)
	if raw == "" {
		return def, nil
	}
	for _, a := range allowed {
		if raw == a {
			return raw, nil
		}
	}
	return "", apperr.InvalidParameter(name, raw, name+" must be one of "+strings.Join(allowed, ", "))
}

func dateRange(from, to string) *apperr.Error {
	if from != "" && to != "" && from > to {
		return apperr.InvalidParameter("from", from, "from must not be after to")
	}
	return nil
}
