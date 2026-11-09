// Тесты HTTP-интерфейса на настоящей базе: DATABASE_URL, как в README (на проверке — база tests).
package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"fleet/internal/api"
	"fleet/internal/db"
)

const (
	vehiclesCSV = "vehicle_id,plate,brand,model,type,year,fuel_type,base_consumption_l_100km,odometer_km," +
		"last_service_odometer_km,service_interval_km,license_category,status\n" +
		"V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,32.5,214500,210000,15000,C,active\n" +
		"V-002,В456ЕК77,ПАЗ,320412,bus,2020,diesel,24.0,98200,,20000,D,active\n"
	driversCSV = "driver_id,full_name,license_number,license_categories,license_valid_until,hired_at,status\n" +
		"D-001,Иванов Пётр Сергеевич,77 12 345678,B;C,2027-05-20,2016-03-01,active\n"
	waybillsCSV = "waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end,route_description\n" +
		"WB-000001,V-001,D-001,2025-01-09T06:30:00,2025-01-09T17:45:00,214500,214812,\"Склад №2, Подольск\"\n" +
		"WB-000002,V-002,D-001,2025-01-09T10:00:00,2025-01-09T12:00:00,98200,98300,\n"
	normsCSV = "fuel_type,season,coefficient\ndiesel,winter,1.12\ndiesel,summer,1.00\n"
)

func newServer(t *testing.T) http.Handler {
	t.Helper()
	ctx := context.Background()
	pool, err := db.Connect(ctx)
	if err != nil {
		t.Skipf("нет базы по DATABASE_URL: %v", err)
	}
	t.Cleanup(pool.Close)
	require.NoError(t, db.Migrate(ctx, pool))
	require.NoError(t, db.Reset(ctx, pool))
	return api.NewRouter(db.NewStore(pool))
}

func call(t *testing.T, h http.Handler, method, path string, body []byte, contentType string) (int, map[string]any, string) {
	t.Helper()
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var parsed map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &parsed)
	return rec.Code, parsed, rec.Body.String()
}

func upload(t *testing.T, h http.Handler, dataset, content string) (int, map[string]any) {
	t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	part, err := w.CreateFormFile("file", dataset+".csv")
	require.NoError(t, err)
	_, _ = part.Write([]byte(content))
	require.NoError(t, w.Close())
	code, body, _ := call(t, h, http.MethodPost, "/import/"+dataset, buf.Bytes(), w.FormDataContentType())
	return code, body
}

func errorCode(body map[string]any) any {
	e, _ := body["error"].(map[string]any)
	return e["code"]
}

func TestImportIsIdempotentAndReportsWork(t *testing.T) {
	h := newServer(t)

	code, body, _ := call(t, h, http.MethodGet, "/health", nil, "")
	require.Equal(t, http.StatusOK, code)
	assert.Equal(t, float64(0), body["records"].(map[string]any)["waybills"])

	for _, step := range []struct{ dataset, content string }{
		{"vehicles", vehiclesCSV}, {"drivers", driversCSV}, {"waybills", waybillsCSV},
	} {
		code, body = upload(t, h, step.dataset, step.content)
		require.Equal(t, http.StatusOK, code, step.dataset)
		assert.Equal(t, float64(0), body["rejected"], step.dataset)
	}

	// норм ещё нет: отчёт по топливу не формируется
	code, body, _ = call(t, h, http.MethodGet, "/reports/fuel?year=2025&month=1", nil, "")
	assert.Equal(t, http.StatusUnprocessableEntity, code)
	assert.Equal(t, "FUEL_NORM_MISSING", errorCode(body))

	code, _ = upload(t, h, "fuel_norms", normsCSV)
	require.Equal(t, http.StatusOK, code)

	// повтор: всё пропущено, записей столько же
	code, body = upload(t, h, "waybills", waybillsCSV)
	require.Equal(t, http.StatusOK, code)
	assert.Equal(t, float64(0), body["accepted"])
	assert.Equal(t, float64(2), body["skipped"])

	code, body, _ = call(t, h, http.MethodGet, "/health", nil, "")
	require.Equal(t, http.StatusOK, code)
	assert.Equal(t, map[string]any{"vehicles": 2.0, "drivers": 1.0, "fuel_norms": 2.0, "waybills": 2.0,
		"fuel_records": 0.0, "maintenance": 0.0}, body["records"])

	// D-001 без категории D ведёт автобус V-002
	code, body, _ = call(t, h, http.MethodGet, "/reports/violations?type=LICENSE_CATEGORY_MISMATCH", nil, "")
	require.Equal(t, http.StatusOK, code)
	rows := body["rows"].([]any)
	require.Len(t, rows, 1)
	assert.Equal(t, "WB-000002", rows[0].(map[string]any)["subject_id"])

	code, body, _ = call(t, h, http.MethodGet, "/reports/fuel?year=2025&month=1", nil, "")
	require.Equal(t, http.StatusOK, code)
	assert.Len(t, body["rows"], 2)

	code, _, text := call(t, h, http.MethodGet, "/reports/violations?format=csv", nil, "")
	require.Equal(t, http.StatusOK, code)
	assert.True(t, strings.HasPrefix(text, "type,occurred_at,vehicle_id,driver_id,subject_type,subject_id,related_id,details\n"))

	code, body, _ = call(t, h, http.MethodGet, "/waybills/WB-000001", nil, "")
	require.Equal(t, http.StatusOK, code)
	assert.Equal(t, float64(312), body["mileage_km"])
	assert.Equal(t, "Склад №2, Подольск", body["route_description"])

	code, body, _ = call(t, h, http.MethodGet, "/vehicles?limit=1&offset=1", nil, "")
	require.Equal(t, http.StatusOK, code)
	assert.Equal(t, float64(2), body["total"])
	assert.Equal(t, "V-002", body["items"].([]any)[0].(map[string]any)["vehicle_id"])
}

func TestBadInputIsRejectedWithoutServerErrors(t *testing.T) {
	h := newServer(t)
	for _, path := range []string{
		"/reports/fuel?year=20x5&month=1", "/reports/fuel?year=2025", "/reports/fuel?year=2025&month=13",
		"/reports/fuel?year=2025&month=1&format=xml", "/reports/maintenance-due", "/reports/maintenance-due?as_of=2025-02-30",
		"/reports/violations?type=WHATEVER", "/reports/violations?from=yesterday",
		"/reports/violations?from=2025-05-01&to=2025-04-01", "/reports/utilization?year=1999&month=1",
		"/reports/drivers?year=2025&month=0", "/vehicles?limit=0", "/vehicles?limit=many", "/vehicles?offset=-1",
		"/vehicles?status=sold", "/waybills?from=01.01.2025", "/reports/fuel?year=2025.0&month=1",
	} {
		code, body, _ := call(t, h, http.MethodGet, path, nil, "")
		assert.Equal(t, http.StatusBadRequest, code, path)
		assert.Equal(t, "INVALID_PARAMETER", errorCode(body), path)
	}

	code, body, _ := call(t, h, http.MethodGet, "/vehicles/abc", nil, "")
	assert.Equal(t, http.StatusNotFound, code)
	assert.Equal(t, "NOT_FOUND", errorCode(body))

	code, body, _ = call(t, h, http.MethodPost, "/import/unicorns", nil, "")
	assert.Equal(t, http.StatusBadRequest, code)
	assert.Equal(t, "UNKNOWN_DATASET", errorCode(body))

	code, body, _ = call(t, h, http.MethodPost, "/import/vehicles", []byte("vehicle_id"), "text/plain")
	assert.Equal(t, http.StatusBadRequest, code)
	assert.Equal(t, "FILE_REQUIRED", errorCode(body))

	code, body = upload(t, h, "vehicles", "vehicle_id;plate\nV-001;А123ВС77\n")
	assert.Equal(t, http.StatusBadRequest, code)
	assert.Equal(t, "MISSING_COLUMNS", errorCode(body))
}
