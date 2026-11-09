// Package apperr — ошибка, которая уходит клиенту в едином формате api-contract.md, 1.3:
// {"error": {"code", "message", "details"}}.
package apperr

import "fleet/internal/jsonx"

// Error — ошибка API с HTTP-статусом и кодом контракта.
type Error struct {
	Status  int
	Code    string
	Message string
	Details jsonx.Obj
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

// Body — тело ответа.
func (e *Error) Body() jsonx.Obj {
	details := e.Details
	if details == nil {
		details = jsonx.Obj{}
	}
	return jsonx.O("error", jsonx.O("code", e.Code, "message", e.Message, "details", details))
}

// New создаёт ошибку.
func New(status int, code, message string, details jsonx.Obj) *Error {
	return &Error{Status: status, Code: code, Message: message, Details: details}
}

// InvalidParameter — 400 INVALID_PARAMETER; value — исходная строка параметра или nil.
func InvalidParameter(name string, value any, message string) *Error {
	return New(400, "INVALID_PARAMETER", message, jsonx.O("parameter", name, "value", value))
}

// NotFound — 404 NOT_FOUND.
func NotFound(entity, key string) *Error {
	return New(404, "NOT_FOUND", entity+" "+key+" not found", jsonx.O("id", key))
}

// FuelNormMissing — 422: для расчёта нормы не хватает коэффициентов.
func FuelNormMissing(missing []jsonx.Obj) *Error {
	if missing == nil {
		missing = []jsonx.Obj{}
	}
	return New(422, "FUEL_NORM_MISSING", "fuel norm coefficients are missing", jsonx.O("missing", missing))
}
