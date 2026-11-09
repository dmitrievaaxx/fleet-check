import csv
import io

from conftest import upload

WB_HEADER = "waybill_id,vehicle_id,driver_id,departure_at,return_at,odometer_start,odometer_end,route_description\n"


def test_utilization_days(seeded):
    upload(seeded, "maintenance", "maintenance_id,vehicle_id,type,started_at,finished_at,odometer_km,cost,description\n"
           + "M-00001,V-001,repair,2025-02-10T09:00:00,2025-02-12T18:00:00,100600,500.00,Ремонт\n"
           + "M-00002,V-002,repair,2025-02-25T09:00:00,,50000,0.00,Открытый\n")
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-001,D-001,2025-02-01T06:00:00,2025-02-02T00:00:00,100000,100300,\n"   # ровно до полуночи: один день
           + "WB-000002,V-001,D-001,2025-02-03T06:00:00,2025-02-04T10:00:00,100300,100600,\n"   # два дня
           + "WB-000003,V-001,D-001,2025-02-12T19:00:00,2025-02-12T22:00:00,100600,100700,\n")  # день и на линии, и в ремонте
    rows = {r["vehicle_id"]: r for r in seeded.get("/reports/utilization", params={"year": 2025, "month": 2}).json()["rows"]}
    assert set(rows) == {"V-001", "V-002"}
    v1 = rows["V-001"]
    assert (v1["days_in_month"], v1["days_on_line"], v1["days_in_maintenance"], v1["days_idle"], v1["utilization"]) == (28, 4, 3, 22, 0.143)
    v2 = rows["V-002"]
    assert (v2["days_on_line"], v2["days_in_maintenance"], v2["days_idle"]) == (0, 4, 24)


def test_drivers_summary_counts_driver_violations_only(seeded):
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-002,D-002,2025-03-11T06:00:00,2025-03-11T18:00:00,50000,50100,\n"   # права истекли 03-10
           + "WB-000002,V-003,D-003,2025-03-12T06:00:00,2025-03-12T18:00:00,20000,20100,\n"   # списанное ТС: не водительское
           + "WB-000003,V-001,D-001,2025-04-01T06:00:00,2025-04-01T18:00:00,100000,100100,\n")
    rows = seeded.get("/reports/drivers", params={"year": 2025, "month": 3}).json()["rows"]
    assert rows == [
        {"driver_id": "D-002", "full_name": "Смирнова Анна Олеговна", "trips": 1, "mileage_km": 100, "violations": 1},
        {"driver_id": "D-003", "full_name": "Кузнецов Олег Иванович", "trips": 1, "mileage_km": 100, "violations": 0},
    ]


def test_csv_format_and_param_validation(seeded):
    upload(seeded, "waybills", WB_HEADER + "WB-000001,V-001,D-001,2025-01-10T06:00:00,2025-01-10T18:00:00,100000,100100,\n")
    r = seeded.get("/reports/fuel", params={"year": 2025, "month": 1, "format": "csv"})
    assert r.headers["content-type"].startswith("text/csv")
    rows = list(csv.DictReader(io.StringIO(r.text)))
    assert rows[0]["vehicle_id"] == "V-001" and rows[0]["overrun"] == "false" and rows[0]["deviation_pct"] != ""
    r = seeded.get("/reports/violations", params={"type": "ODOMETER_GAP", "format": "csv"})
    assert r.status_code == 200 and r.text.splitlines()[0] == "type,occurred_at,vehicle_id,driver_id,subject_type,subject_id,related_id,details"
    for params in ({"year": 2025}, {"year": 2025, "month": 13}, {"year": "abc", "month": 1}, {"year": 2025, "month": 1, "format": "xml"}):
        r = seeded.get("/reports/fuel", params=params)
        assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_PARAMETER", params
    assert seeded.get("/reports/violations", params={"from": "2025-05-01", "to": "2025-04-01"}).status_code == 400
    assert seeded.get("/reports/violations", params={"type": "SOMETHING"}).status_code == 400
    assert seeded.get("/reports/maintenance-due", params={"as_of": "2025-02-30"}).status_code == 400
    assert seeded.get("/reports/maintenance-due", params={"as_of": "2025-02-28"}).status_code == 200


def test_violations_date_filter(seeded):
    upload(seeded, "waybills", WB_HEADER
           + "WB-000001,V-003,D-003,2025-02-06T06:00:00,2025-02-06T18:00:00,20000,20100,\n"
           + "WB-000002,V-003,D-003,2025-03-06T06:00:00,2025-03-06T18:00:00,20100,20200,\n")
    rows = seeded.get("/reports/violations", params={"from": "2025-03-01", "to": "2025-03-31"}).json()["rows"]
    assert [r["subject_id"] for r in rows] == ["WB-000002"]
    rows = seeded.get("/reports/violations", params={"from": "2025-02-06", "to": "2025-02-06"}).json()["rows"]
    assert [r["subject_id"] for r in rows] == ["WB-000001"]
