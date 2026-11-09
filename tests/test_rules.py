from conftest import upload

WB_HEADER = "waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end,route_description\n"


def violations(client, **params):
    r = client.get("/reports/violations", params=params)
    assert r.status_code == 200, r.text
    return r.json()["rows"]


def by_type(rows, vtype):
    return [r for r in rows if r["type"] == vtype]


def test_overlap_touching_is_not_violation(seeded):
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,100300,\n"
           + "WB-000002,V-001,D-003,2025-01-10T18:00:00,2025-01-11T02:00:00,100300,100500,\n"      # касание концами
           + "WB-000003,V-001,D-001,2025-01-11T01:59:00,2025-01-11T12:00:00,100500,100700,\n")     # пересечение на минуту
    rows = by_type(violations(seeded), "VEHICLE_OVERLAP")
    assert [(r["subject_id"], r["related_id"]) for r in rows] == [("WB-000003", "WB-000002")]
    assert rows[0]["details"]["related_return_at"] == "2025-01-11T02:00:00"


def test_driver_overlap_and_category(seeded):
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,100300,\n"
           + "WB-000002,V-002,D-001,2025-01-10T07:00:00,2025-01-10T12:00:00,50000,50100,\n"    # тот же водитель, другое ТС
           + "WB-000003,V-002,D-003,2025-01-11T07:00:00,2025-01-11T12:00:00,50100,50200,\n")   # C;D на легковом
    rows = violations(seeded)
    assert [(r["subject_id"], r["related_id"]) for r in by_type(rows, "DRIVER_OVERLAP")] == [("WB-000002", "WB-000001")]
    mism = by_type(rows, "LICENSE_CATEGORY_MISMATCH")
    assert [r["subject_id"] for r in mism] == ["WB-000003"]
    assert mism[0]["details"] == {"required": "B", "driver_categories": ["C", "D"]}


def test_license_expiry_boundary(seeded):
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-002,D-002,2025-03-10T06:00:00,2025-03-10T18:00:00,50000,50100,\n"   # в день окончания допустимо
           + "WB-000002,V-002,D-002,2025-03-11T06:00:00,2025-03-11T18:00:00,50100,50200,\n")
    rows = by_type(violations(seeded), "LICENSE_EXPIRED")
    assert [r["subject_id"] for r in rows] == ["WB-000002"]


def test_odometer_gap_tolerance(seeded):
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100005,100300,\n"  # +5 от 100000: допуск
           + "WB-000002,V-001,D-001,2025-01-11T06:00:00,2025-01-11T18:00:00,100306,100500,\n"  # +6: нарушение
           + "WB-000003,V-001,D-001,2025-01-12T06:00:00,2025-01-12T18:00:00,100499,100700,\n"  # -1: нарушение
           + "WB-000004,V-001,D-001,2025-01-13T06:00:00,2025-01-13T18:00:00,100700,100900,\n")
    rows = by_type(violations(seeded), "ODOMETER_GAP")
    assert [(r["subject_id"], r["related_id"], r["details"]["delta"]) for r in rows] == [
        ("WB-000002", "WB-000001", 6), ("WB-000003", "WB-000002", -1)]


def test_maintenance_overdue_episode_and_exact_boundary(seeded):
    # интервал 10000, последнее ТО 95000: ровно 105000 не просрочка, 105001 просрочка, один эпизод, после ТО новый отсчёт
    upload(seeded, "maintenance", "maintenance_id,vehicle_id,type,started_at,finished_at,odometer_km,cost,description\n"
           + "M-00001,V-001,scheduled,2025-01-14T08:00:00,2025-01-14T18:00:00,105400,10000.00,ТО\n")
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,105000,\n"
           + "WB-000002,V-001,D-001,2025-01-11T06:00:00,2025-01-11T18:00:00,105000,105001,\n"
           + "WB-000003,V-001,D-001,2025-01-12T06:00:00,2025-01-12T18:00:00,105001,105400,\n"
           + "WB-000004,V-001,D-001,2025-01-15T06:00:00,2025-01-15T18:00:00,105400,115000,\n"
           + "WB-000005,V-001,D-001,2025-01-16T06:00:00,2025-01-16T18:00:00,115000,115500,\n")
    rows = by_type(violations(seeded), "MAINTENANCE_OVERDUE")
    assert [r["subject_id"] for r in rows] == ["WB-000002", "WB-000005"]
    assert rows[0]["details"] == {"service_odometer": 95000, "odometer_end": 105001, "km_since_service": 10001, "service_interval_km": 10000}
    due = seeded.get("/reports/maintenance-due", params={"as_of": "2025-01-16"}).json()["rows"]
    v1 = next(r for r in due if r["vehicle_id"] == "V-001")
    assert (v1["service_odometer_km"], v1["km_remaining"], v1["status"], v1["last_service_at"]) == (105400, -100, "overdue", "2025-01-14T08:00:00")
    assert all(r["vehicle_id"] != "V-003" for r in due)


def test_vehicle_in_maintenance_and_decommissioned(seeded):
    upload(seeded, "maintenance", "maintenance_id,vehicle_id,type,started_at,finished_at,odometer_km,cost,description\n"
           + "M-00001,V-001,repair,2025-02-01T09:00:00,,100300,0.00,Открытый ремонт\n")
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-001,D-001,2025-01-31T06:00:00,2025-02-01T09:00:00,100000,100300,\n"   # касание начала ремонта
           + "WB-000002,V-001,D-001,2025-02-05T06:00:00,2025-02-05T18:00:00,100300,100600,\n"   # во время открытого ремонта
           + "WB-000003,V-003,D-003,2025-02-06T06:00:00,2025-02-06T18:00:00,20000,20100,\n")    # списанное ТС
    rows = violations(seeded)
    inm = by_type(rows, "VEHICLE_IN_MAINTENANCE")
    assert [(r["subject_id"], r["related_id"]) for r in inm] == [("WB-000002", "M-00001")]
    assert inm[0]["details"]["finished_at"] is None
    assert [r["subject_id"] for r in by_type(rows, "VEHICLE_DECOMMISSIONED")] == ["WB-000003"]


def test_fuel_overrun_threshold_and_missing_norms(seeded):
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,100100,\n")  # норма 100*30*1.12/100 = 33.6
    fuel_header = "record_id,vehicle_id,driver_id,filled_at,liters,price_per_liter,station\n"
    upload(seeded, "fuel_records", fuel_header + "F-000001,V-001,D-001,2025-01-10T18:30:00,36.96,65.00,АЗС\n")  # ровно +10 %
    assert by_type(violations(seeded), "FUEL_OVERRUN") == []
    upload(seeded, "fuel_records", fuel_header + "F-000002,V-001,,2025-01-20T18:30:00,0.05,65.00,\n")
    rows = by_type(violations(seeded), "FUEL_OVERRUN")
    assert len(rows) == 1 and rows[0]["details"]["actual_liters"] == 37.01 and rows[0]["occurred_at"] == "2025-01-01T00:00:00"
    report = seeded.get("/reports/fuel", params={"year": 2025, "month": 1}).json()["rows"]
    assert report[0]["overrun"] is True and report[0]["deviation_pct"] == 10.1
    assert seeded.get("/reports/fuel", params={"year": 2025, "month": 2}).json()["rows"] == []

    fresh_client = seeded
    # без справочника норм отчёт по топливу и нарушения без фильтра отвечают 422, фильтр по другому типу работает
    import sqlite3, os
    with sqlite3.connect(os.environ["ATP_DB_PATH"]) as conn:
        conn.execute("DELETE FROM fuel_norms WHERE fuel_type = 'diesel' AND season = 'winter'")
    r = fresh_client.get("/reports/fuel", params={"year": 2025, "month": 1})
    assert r.status_code == 422 and r.json()["error"]["details"]["missing"] == [{"fuel_type": "diesel", "season": "winter"}]
    assert fresh_client.get("/reports/violations").status_code == 422
    assert fresh_client.get("/reports/violations", params={"type": "ODOMETER_GAP"}).status_code == 200
