from conftest import DRIVERS, VEHICLES, upload


def codes(result):
    return {e["row"]: e["code"] for e in result["errors"]}


def test_row_errors_follow_contract_order(client):
    text = VEHICLES + "\n".join([
        "V-004,,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",          # 5: пустое обязательное
        "V17,Х901АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",     # 6: формат идентификатора
        "V-005,Х902АА77,ГАЗ,Валдай,van,2019,diesel,20.0,1000,,15000,C,active",     # 7: перечисление
        "V-006,Х903АА77,ГАЗ,Валдай,truck,1985,diesel,20.0,1000,,15000,C,active",   # 8: диапазон
        "V-007,Х904АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,2000,15000,C,active",  # 9: межполевое
        "V-008,А123ВС77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",   # 10: госномер занят
        "V-009,Х905АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active,extra",  # 11: лишнее поле
        "V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,30.0,100000,95000,10000,C,active",  # 12: дубликат
    ]) + "\n"
    r = upload(client, "vehicles", text)
    assert r.status_code == 200
    body = r.json()
    assert (body["total"], body["accepted"], body["rejected"], body["skipped"]) == (11, 3, 7, 1)
    assert codes(body) == {5: "REQUIRED_FIELD_MISSING", 6: "INVALID_FORMAT", 7: "INVALID_VALUE", 8: "INVALID_VALUE",
                           9: "CROSS_FIELD_VIOLATION", 10: "UNIQUE_VIOLATION", 11: "PARSE_ERROR"}
    assert client.get("/health").json()["records"]["vehicles"] == 3


def test_decimal_places_and_lists(client):
    bad = VEHICLES.replace("30.0,100000", "30.05,100000")
    assert codes(upload(client, "vehicles", bad).json()) == {2: "INVALID_FORMAT"}
    r = upload(client, "drivers", DRIVERS + "D-004,Тест,11 11 111111,B;E,2030-01-01,2020-01-01,active\n"
               + "D-005,Тест,11 11 111112,B;;C,2030-01-01,2020-01-01,active\n")
    assert codes(r.json()) == {5: "INVALID_VALUE", 6: "INVALID_FORMAT"}


def test_references_and_idempotency(seeded):
    wb = ("waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end,route_description\n"
          "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,100300,\n"
          "WB-000002,V-009,D-001,2025-01-11T06:00:00,2025-01-11T18:00:00,100300,100600,\n"
          "WB-000003,V-001,D-999,2025-01-12T06:00:00,2025-01-12T18:00:00,100600,100900,\n"
          "WB-000004,V-001,D-001,2025-01-13T18:00:00,2025-01-13T06:00:00,100900,101200,\n"
          "WB-000005,V-001,D-001,2025-01-14T06:00:00,2025-01-14T18:00:00,101200,101200,Нулевой пробег допустим\n")
    first = upload(seeded, "waybills", wb).json()
    assert (first["accepted"], first["rejected"], first["skipped"]) == (2, 3, 0)
    assert codes(first) == {3: "REFERENCE_NOT_FOUND", 4: "REFERENCE_NOT_FOUND", 5: "CROSS_FIELD_VIOLATION"}
    second = upload(seeded, "waybills", wb).json()
    assert (second["accepted"], second["rejected"], second["skipped"]) == (0, 3, 2)
    assert seeded.get("/health").json()["records"]["waybills"] == 2


def test_file_level_errors(client):
    r = upload(client, "vehicles", "vehicle_id,plate\nV-001,А123ВС77\n")
    assert r.status_code == 400 and r.json()["error"]["code"] == "MISSING_COLUMNS"
    assert "brand" in r.json()["error"]["details"]["columns"]
    assert upload(client, "vehicles", "").json()["error"]["code"] == "EMPTY_FILE"
    assert upload(client, "unicorns", VEHICLES).json()["error"]["code"] == "UNKNOWN_DATASET"
    r = client.post("/import/vehicles", files={"file": ("v.csv", "\xff\xfe".encode("latin-1"), "text/csv")})
    assert r.json()["error"]["code"] == "INVALID_ENCODING"
    assert client.post("/import/vehicles").json()["error"]["code"] == "FILE_REQUIRED"
    header_only = upload(client, "vehicles", VEHICLES.splitlines()[0] + "\n").json()
    assert header_only["total"] == 0


def test_entities_and_pagination(seeded):
    r = seeded.get("/vehicles", params={"limit": 2, "offset": 1})
    assert [v["vehicle_id"] for v in r.json()["items"]] == ["V-002", "V-003"] and r.json()["total"] == 3
    assert seeded.get("/vehicles/V-002").json()["last_service_odometer_km"] is None
    assert seeded.get("/vehicles/V-999").status_code == 404
    assert seeded.get("/drivers/D-001").json()["license_categories"] == ["B", "C"]
    assert seeded.get("/vehicles", params={"limit": "abc"}).json()["error"]["details"]["parameter"] == "limit"
    assert seeded.get("/vehicles", params={"status": "sold"}).status_code == 400


def test_blank_lines_bom_spaces_and_lengths(client):
    text = "﻿" + VEHICLES.rstrip("\n") + "\n\n" + "\n".join([
        " V-004 , Х901АА77 ,ГАЗ,Валдай, truck , 2019 ,diesel, 20.0 ,1000,,15000,C,active",  # 6: пробелы у всех типов
        "V-005,Х902АА77," + "Б" * 51 + ",Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",  # 7: длина строки
        "V-006,Х903АА77,ГАЗ,Валдай,truck,2019,diesel,20.,1000,,15000,C,active",            # 8: пустая дробная часть
        "V-007,Х904АА77,ГАЗ,Валдай,truck,2019,diesel,-20.0,1000,,15000,C,active",          # 9: знак
    ]) + "\n\n"
    r = upload(client, "vehicles", text)
    assert r.status_code == 200
    body = r.json()
    assert (body["total"], body["accepted"], body["rejected"], body["skipped"]) == (7, 4, 3, 0)
    assert codes(body) == {7: "INVALID_FORMAT", 8: "INVALID_FORMAT", 9: "INVALID_FORMAT"}
    assert client.get("/vehicles/V-004").json()["plate"] == "Х901АА77"


def test_digits_header_position_and_file_field(client):
    text = "\n\n" + VEHICLES.rstrip("\n") + "\n" + "\n".join([
        "V-004,Х901АА77,ГАЗ,Валдай,truck,٢٠١٩,diesel,20.0,1000,,15000,C,active",            # 7: цифры другой письменности
        "V-005,Х902АА77,ГАЗ,Валдай,truck,2019,diesel,20.0," + "9" * 5000 + ",,15000,C,active",  # 8: слишком длинное целое
        "V-006,Х903АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active",            # 9: валидная
    ]) + "\n"
    body = upload(client, "vehicles", text).json()
    assert (body["total"], body["accepted"], body["rejected"], body["skipped"]) == (6, 4, 2, 0)
    assert codes(body) == {7: "INVALID_FORMAT", 8: "INVALID_FORMAT"}
    r = upload(client, "drivers", DRIVERS + "D-004,Тест,11 11 111111,B; C,2030-01-01,2020-01-01,active\n")
    assert codes(r.json()) == {5: "INVALID_FORMAT"}
    r = client.post("/import/vehicles", data={"file": "text instead of upload"})
    assert (r.status_code, r.json()["error"]["code"]) == (400, "FILE_REQUIRED")
    r = client.post("/import/unicorns")
    assert r.json()["error"]["code"] == "UNKNOWN_DATASET"


def test_unterminated_quote_affects_one_row_only(client):
    text = VEHICLES + "\n".join([
        '"V-004,Х901АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active',   # 5: незакрытая кавычка
        'V-005,Х902АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active',    # 6: валидная
        'V-006,Х903АА77,ГАЗ,Вал"дай,truck,2019,diesel,20.0,1000,,15000,C,active',   # 7: кавычка внутри поля
        'V-007,Х904АА77,ГАЗ,Валдай,truck,2019,diesel,20.0,1000,,15000,C,active',    # 8: валидная
    ]) + "\n"
    body = upload(client, "vehicles", text).json()
    assert (body["total"], body["accepted"], body["rejected"], body["skipped"]) == (7, 5, 2, 0)
    assert codes(body) == {5: "PARSE_ERROR", 7: "PARSE_ERROR"}
