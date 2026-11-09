// Package api — HTTP-интерфейс по api-contract.md.
package api

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"

	"fleet/internal/apperr"
	"fleet/internal/db"
	"fleet/internal/importer"
	"fleet/internal/jsonx"
	"fleet/internal/reports"
	"fleet/internal/rules"
)

// maxUpload — файл набора не больше 4 МБ по контракту; берём с запасом.
const maxUpload = 64 << 20

type server struct{ store *db.Store }

// csvBody — ответ отчёта в CSV.
type csvBody []byte

type handler func(r *http.Request) (any, error)

// NewRouter собирает маршруты сервиса.
func NewRouter(store *db.Store) http.Handler {
	s := &server{store: store}
	r := chi.NewRouter()
	r.Use(recoverer)
	r.Get("/health", s.wrap(s.health))
	r.Post("/import/{dataset}", s.wrap(s.importDataset))
	r.Get("/vehicles", s.wrap(s.listVehicles))
	r.Get("/vehicles/{id}", s.wrap(s.card("vehicle", store.Vehicle)))
	r.Get("/drivers", s.wrap(s.listDrivers))
	r.Get("/drivers/{id}", s.wrap(s.card("driver", store.Driver)))
	r.Get("/waybills", s.wrap(s.listWaybills))
	r.Get("/waybills/{id}", s.wrap(s.card("waybill", store.Waybill)))
	r.Get("/reports/fuel", s.wrap(s.reportFuel))
	r.Get("/reports/maintenance-due", s.wrap(s.reportMaintenanceDue))
	r.Get("/reports/violations", s.wrap(s.reportViolations))
	r.Get("/reports/utilization", s.wrap(s.reportUtilization))
	r.Get("/reports/drivers", s.wrap(s.reportDrivers))
	return r
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(jsonx.Marshal(body))
}

func internalError(w http.ResponseWriter, kind string) {
	e := apperr.New(500, "INTERNAL_ERROR", "unexpected error", jsonx.O("type", kind))
	writeJSON(w, e.Status, e.Body())
}

// recoverer: паника обработчика — 500 в формате контракта, а не оборванное соединение.
func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if p := recover(); p != nil {
				if p == http.ErrAbortHandler {
					panic(p)
				}
				log.Printf("panic: %v", p)
				internalError(w, "panic")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func (s *server) wrap(h handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := h(r)
		if err != nil {
			var ae *apperr.Error
			if errors.As(err, &ae) {
				writeJSON(w, ae.Status, ae.Body())
				return
			}
			log.Printf("%s %s: %v", r.Method, r.URL.Path, err)
			internalError(w, fmt.Sprintf("%T", err))
			return
		}
		if data, ok := body.(csvBody); ok {
			w.Header().Set("Content-Type", "text/csv; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(data)
			return
		}
		writeJSON(w, http.StatusOK, body)
	}
}

// --- служебные роуты ---

func (s *server) health(r *http.Request) (any, error) {
	records, err := s.store.Records(r.Context())
	if err != nil {
		return nil, err
	}
	return jsonx.O("status", "ok", "records", records), nil
}

// readFile — содержимое файловой части "file" multipart-запроса (часть с атрибутом filename).
func readFile(r *http.Request) ([]byte, *apperr.Error) {
	noFile := apperr.New(400, "FILE_REQUIRED", "multipart field 'file' is required", nil)
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "multipart/form-data" {
		return nil, noFile
	}
	r.Body = http.MaxBytesReader(nil, r.Body, maxUpload)
	if err := r.ParseMultipartForm(maxUpload); err != nil {
		return nil, apperr.New(400, "FILE_REQUIRED", "multipart body with field 'file' is required", nil)
	}
	defer func() { _ = r.MultipartForm.RemoveAll() }()
	files := r.MultipartForm.File["file"]
	if len(files) == 0 {
		return nil, noFile
	}
	f, err := files[len(files)-1].Open()
	if err != nil {
		return nil, noFile
	}
	defer f.Close()
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, f); err != nil {
		return nil, noFile
	}
	return buf.Bytes(), nil
}

func (s *server) importDataset(r *http.Request) (any, error) {
	dataset := chi.URLParam(r, "dataset")
	spec, ok := importer.Specs[dataset]
	if !ok {
		return nil, importer.UnknownDataset(dataset)
	}
	content, ferr := readFile(r)
	if ferr != nil {
		return nil, ferr
	}
	file, ferr := importer.Prepare(spec, content)
	if ferr != nil {
		return nil, ferr
	}
	res, err := s.store.Import(r.Context(), spec, file)
	if err != nil {
		return nil, err
	}
	return res.Obj(), nil
}

// --- сущности ---

func page(p db.Page, limit, offset int64) jsonx.Obj {
	return jsonx.O("items", p.Items, "total", p.Total, "limit", limit, "offset", offset)
}

const maxOffset = 1<<62 - 1

func paging(q query) (int64, int64, *apperr.Error) {
	limit, err := q.int("limit", 1, 1000, 100, false)
	if err != nil {
		return 0, 0, err
	}
	offset, err := q.int("offset", 0, maxOffset, 0, false)
	return limit, offset, err
}

func (s *server) listVehicles(r *http.Request) (any, error) {
	q := query{r.URL.Query()}
	var filters []db.Filter
	for _, f := range []struct {
		name    string
		allowed []string
	}{
		{"status", []string{"active", "decommissioned"}},
		{"type", []string{"truck", "bus", "car"}},
		{"fuel_type", []string{"diesel", "petrol", "gas"}},
	} {
		value, err := q.enum(f.name, f.allowed, "")
		if err != nil {
			return nil, err
		}
		if value != "" {
			filters = append(filters, db.Filter{SQL: f.name + " = $%d", Value: value})
		}
	}
	limit, offset, perr := paging(q)
	if perr != nil {
		return nil, perr
	}
	p, err := s.store.Vehicles(r.Context(), filters, limit, offset)
	if err != nil {
		return nil, err
	}
	return page(p, limit, offset), nil
}

func (s *server) listDrivers(r *http.Request) (any, error) {
	q := query{r.URL.Query()}
	status, perr := q.enum("status", []string{"active", "dismissed"}, "")
	if perr != nil {
		return nil, perr
	}
	var filters []db.Filter
	if status != "" {
		filters = append(filters, db.Filter{SQL: "status = $%d", Value: status})
	}
	limit, offset, perr := paging(q)
	if perr != nil {
		return nil, perr
	}
	p, err := s.store.Drivers(r.Context(), filters, limit, offset)
	if err != nil {
		return nil, err
	}
	return page(p, limit, offset), nil
}

func (s *server) listWaybills(r *http.Request) (any, error) {
	q := query{r.URL.Query()}
	from, perr := q.date("from", false)
	if perr != nil {
		return nil, perr
	}
	to, perr := q.date("to", false)
	if perr != nil {
		return nil, perr
	}
	if perr := dateRange(from, to); perr != nil {
		return nil, perr
	}
	limit, offset, perr := paging(q)
	if perr != nil {
		return nil, perr
	}
	var filters []db.Filter
	if v, _ := q.raw("vehicle_id"); v != "" {
		filters = append(filters, db.Filter{SQL: "vehicle_id = $%d", Value: v})
	}
	if v, _ := q.raw("driver_id"); v != "" {
		filters = append(filters, db.Filter{SQL: "driver_id = $%d", Value: v})
	}
	if from != "" {
		filters = append(filters, db.Filter{SQL: "substr(departure_at, 1, 10) >= $%d", Value: from})
	}
	if to != "" {
		filters = append(filters, db.Filter{SQL: "substr(departure_at, 1, 10) <= $%d", Value: to})
	}
	p, err := s.store.Waybills(r.Context(), filters, limit, offset)
	if err != nil {
		return nil, err
	}
	return page(p, limit, offset), nil
}

func (s *server) card(entity string, get func(ctx context.Context, id string) (jsonx.Obj, error)) handler {
	return func(r *http.Request) (any, error) {
		id := chi.URLParam(r, "id")
		// PostgreSQL не принимает NUL и неверный UTF-8 в тексте: такого идентификатора точно нет
		if strings.IndexByte(id, 0) >= 0 || !utf8.ValidString(id) {
			return nil, apperr.NotFound(entity, id)
		}
		obj, err := get(r.Context(), id)
		if err != nil {
			return nil, err
		}
		if obj == nil {
			return nil, apperr.NotFound(entity, id)
		}
		return obj, nil
	}
}

// --- отчёты ---

func report(name string, params jsonx.Obj, rows []jsonx.Obj, columns []string, format string) any {
	if format != "csv" {
		return jsonx.O("report", name, "params", params, "rows", rows)
	}
	var b strings.Builder
	writeCSVRow(&b, columns)
	for _, row := range rows {
		cells := make([]string, len(columns))
		for i, c := range columns {
			cells[i] = csvValue(row.Get(c))
		}
		writeCSVRow(&b, cells)
	}
	return csvBody(b.String())
}

var formats = []string{"json", "csv"}

func yearMonth(q query) (int, int, string, *apperr.Error) {
	year, err := q.int("year", 2000, 2100, 0, true)
	if err != nil {
		return 0, 0, "", err
	}
	month, err := q.int("month", 1, 12, 0, true)
	if err != nil {
		return 0, 0, "", err
	}
	format, err := q.enum("format", formats, "json")
	return int(year), int(month), format, err
}

func (s *server) reportFuel(r *http.Request) (any, error) {
	year, month, format, perr := yearMonth(query{r.URL.Query()})
	if perr != nil {
		return nil, perr
	}
	d, err := s.store.Domain(r.Context())
	if err != nil {
		return nil, err
	}
	rows, rerr := reports.Fuel(d, year, month)
	if rerr != nil {
		return nil, rerr
	}
	return report("fuel", jsonx.O("year", year, "month", month), rows, reports.FuelColumns, format), nil
}

func (s *server) reportMaintenanceDue(r *http.Request) (any, error) {
	q := query{r.URL.Query()}
	asOf, perr := q.date("as_of", true)
	if perr != nil {
		return nil, perr
	}
	format, perr := q.enum("format", formats, "json")
	if perr != nil {
		return nil, perr
	}
	d, err := s.store.Domain(r.Context())
	if err != nil {
		return nil, err
	}
	rows := reports.MaintenanceDue(d, asOf)
	return report("maintenance-due", jsonx.O("as_of", asOf), rows, reports.MaintenanceDueColumns, format), nil
}

func (s *server) reportViolations(r *http.Request) (any, error) {
	q := query{r.URL.Query()}
	vtype, perr := q.enum("type", rules.Types, "")
	if perr != nil {
		return nil, perr
	}
	from, perr := q.date("from", false)
	if perr != nil {
		return nil, perr
	}
	to, perr := q.date("to", false)
	if perr != nil {
		return nil, perr
	}
	if perr := dateRange(from, to); perr != nil {
		return nil, perr
	}
	format, perr := q.enum("format", formats, "json")
	if perr != nil {
		return nil, perr
	}
	d, err := s.store.Domain(r.Context())
	if err != nil {
		return nil, err
	}
	rows, rerr := reports.Violations(d, vtype, from, to)
	if rerr != nil {
		return nil, rerr
	}
	params := jsonx.O("type", orNil(vtype), "from", orNil(from), "to", orNil(to))
	return report("violations", params, rows, rules.Columns, format), nil
}

func (s *server) reportUtilization(r *http.Request) (any, error) {
	year, month, format, perr := yearMonth(query{r.URL.Query()})
	if perr != nil {
		return nil, perr
	}
	d, err := s.store.Domain(r.Context())
	if err != nil {
		return nil, err
	}
	rows := reports.Utilization(d, year, month)
	return report("utilization", jsonx.O("year", year, "month", month), rows, reports.UtilizationColumns, format), nil
}

func (s *server) reportDrivers(r *http.Request) (any, error) {
	year, month, format, perr := yearMonth(query{r.URL.Query()})
	if perr != nil {
		return nil, perr
	}
	d, err := s.store.Domain(r.Context())
	if err != nil {
		return nil, err
	}
	rows := reports.Drivers(d, year, month)
	return report("drivers", jsonx.O("year", year, "month", month), rows, reports.DriversColumns, format), nil
}

func orNil(s string) any {
	if s == "" {
		return nil
	}
	return s
}
