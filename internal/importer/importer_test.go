package importer

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParseLine(t *testing.T) {
	cases := []struct {
		line string
		want []string
		ok   bool
	}{
		{"a,b,c", []string{"a", "b", "c"}, true},
		{`"Склад №2, Подольск",x`, []string{"Склад №2, Подольск", "x"}, true},
		{`"say ""hi""",`, []string{`say "hi"`, ""}, true},
		{"a,", []string{"a", ""}, true},
		{`"unterminated,b`, nil, false},
		{`Вал"дай,b`, nil, false},
		{`"a"b,c`, nil, false},
	}
	for _, c := range cases {
		got, ok := ParseLine(c.line)
		assert.Equal(t, c.ok, ok, c.line)
		if c.ok {
			assert.Equal(t, c.want, got, c.line)
		}
	}
}

func TestFileLevelErrors(t *testing.T) {
	spec := Specs["vehicles"]
	_, err := Prepare(spec, []byte{0xff, 0xfe, 0x00})
	require.NotNil(t, err)
	assert.Equal(t, "INVALID_ENCODING", err.Code)

	_, err = Prepare(spec, []byte("\ufeff\n\n"))
	require.NotNil(t, err)
	assert.Equal(t, "EMPTY_FILE", err.Code)

	_, err = Prepare(spec, []byte("vehicle_id,plate\nV-001,А123ВС77\n"))
	require.NotNil(t, err)
	assert.Equal(t, "MISSING_COLUMNS", err.Code)

	_, err = Prepare(spec, []byte("vehicle_id;plate\n"))
	require.NotNil(t, err)
	assert.Equal(t, "MISSING_COLUMNS", err.Code)
}

const vehiclesHeader = "vehicle_id,plate,brand,model,type,year,fuel_type,base_consumption_l_100km,odometer_km," +
	"last_service_odometer_km,service_interval_km,license_category,status\r\n"

func TestVehicleRowsInContractOrder(t *testing.T) {
	content := vehiclesHeader +
		"V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,32.5,214500,210000,15000,C,active\r\n" + // 2 ok
		"V-002,В456ЕК77,ПАЗ,320412,bus,2020,diesel,24.0,98200,,20000,D,active\r\n" + // 3 ok
		"\r\n" + // 4 пустая строка: не считается
		"V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,32.5,214500,210000,15000,C,active\r\n" + // 5 повтор ключа
		"V-003,А123ВС77,Lada,Granta,car,2021,petrol,7.8,45100,40000,15000,B,active\r\n" + // 6 госномер занят
		"V17,Е789КМ77,Lada,Granta,car,2021,petrol,7.8,45100,40000,15000,B,active\r\n" + // 7 формат
		"V-004,Е789КМ77,Lada,Granta,van,2021,petrol,7.8,45100,40000,15000,B,active\r\n" + // 8 значение
		"V-005,Е789КМ77,Lada,Granta,car,2021,petrol,7.8,45100,50000,15000,B,active\r\n" + // 9 межполевое
		"V-006,,Lada,Granta,car,2021,petrol,7.8,45100,40000,15000,B,active\r\n" + // 10 пусто
		"V-007,Е789КМ77,Lada\r\n" + // 11 число полей
		"V-008,Е789КМ77,Lada,Granta,car,2021,petrol,7.80,45100,40000,15000,B,active\r\n" // 12 decimal(1)
	file, ferr := Prepare(Specs["vehicles"], []byte(content))
	require.Nil(t, ferr)
	res, rows := Process(Specs["vehicles"], file, NewState())
	assert.Equal(t, 10, res.Total)
	assert.Equal(t, 2, res.Accepted)
	assert.Equal(t, 1, res.Skipped)
	assert.Equal(t, 7, res.Rejected)
	assert.Len(t, rows, 2)
	codes := map[int]string{}
	for _, e := range res.Errors {
		codes[e.Row] = e.Code
	}
	assert.Equal(t, map[int]string{
		6: "UNIQUE_VIOLATION", 7: "INVALID_FORMAT", 8: "INVALID_VALUE", 9: "CROSS_FIELD_VIOLATION",
		10: "REQUIRED_FIELD_MISSING", 11: "PARSE_ERROR", 12: "INVALID_FORMAT",
	}, codes)
}

func TestRepeatImportSkipsEverything(t *testing.T) {
	content := "fuel_type,season,coefficient\n" +
		"diesel,winter,1.12\n" +
		"diesel,winter,1.20\n" + // повтор ключа после принятой строки: skipped
		"petrol,summer,9.99\n" + // вне диапазона: rejected, ключ не занимает
		"petrol,summer,1.00\n"
	spec := Specs["fuel_norms"]
	file, ferr := Prepare(spec, []byte(content))
	require.Nil(t, ferr)
	st := NewState()
	first, _ := Process(spec, file, st)
	assert.Equal(t, []int{4, 2, 1, 1}, []int{first.Total, first.Accepted, first.Rejected, first.Skipped})
	again, rows := Process(spec, file, st)
	assert.Equal(t, []int{4, 0, 1, 3}, []int{again.Total, again.Accepted, again.Rejected, again.Skipped})
	assert.Empty(t, rows)
}

func TestReferencesAndCategories(t *testing.T) {
	st := NewState()
	st.Refs["vehicles"] = map[string]bool{"V-001": true}
	st.Refs["drivers"] = map[string]bool{"D-001": true}
	content := "waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end\n" +
		"WB-000001,V-001,D-001,2025-01-09T06:30:00,2025-01-09T17:45:00,100,200\n" +
		"WB-000002,V-001,D-999,2025-01-09T06:30:00,2025-01-09T17:45:00,100,200\n" +
		"WB-000003,V-001,D-001,2025-01-09T06:30:00,2025-01-09T06:30:00,100,200\n" +
		"WB-000004,V-001,D-001,2025-01-09 06:30:00,2025-01-09T17:45:00,100,200\n"
	spec := Specs["waybills"]
	file, ferr := Prepare(spec, []byte(content))
	require.Nil(t, ferr)
	res, _ := Process(spec, file, st)
	require.Len(t, res.Errors, 3)
	assert.Equal(t, "REFERENCE_NOT_FOUND", res.Errors[0].Code)
	assert.Equal(t, "CROSS_FIELD_VIOLATION", res.Errors[1].Code)
	assert.Equal(t, "INVALID_FORMAT", res.Errors[2].Code)

	spec = Specs["drivers"]
	header := []string{"driver_id", "full_name", "license_number", "license_categories", "license_valid_until", "hired_at", "status"}
	for cats, code := range map[string]string{"B;C": "", "B; C": "INVALID_FORMAT", "B;B": "INVALID_FORMAT", "B;c": "INVALID_VALUE"} {
		fields := []string{"D-001", "Иванов Пётр", "77 12 345678", cats, "2027-05-20", "2016-03-01", "active"}
		err, _ := ValidateRow(spec, header, fields, 2)
		if code == "" {
			assert.Nil(t, err, cats)
		} else if assert.NotNil(t, err, cats) {
			assert.Equal(t, code, err.Code, cats)
		}
	}
}
