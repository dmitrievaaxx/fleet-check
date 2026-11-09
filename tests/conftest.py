import os
import tempfile
from pathlib import Path

import pytest


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setenv("ATP_DB_PATH", str(Path(tempfile.mkdtemp()) / "atp.db"))
    from fastapi.testclient import TestClient
    from app.main import app
    with TestClient(app) as c:
        yield c


def upload(client, dataset: str, text: str):
    return client.post(f"/import/{dataset}", files={"file": (f"{dataset}.csv", text.encode("utf-8"), "text/csv")})


VEHICLES = """vehicle_id,plate,brand,model,type,year,fuel_type,base_consumption_l_100km,odometer_km,last_service_odometer_km,service_interval_km,license_category,status
V-001,А123ВС77,КамАЗ,65115,truck,2018,diesel,30.0,100000,95000,10000,C,active
V-002,В456ЕК77,Lada,Granta,car,2021,petrol,8.0,50000,,15000,B,active
V-003,Е789КМ77,ПАЗ,320412,bus,2020,diesel,24.0,20000,20000,20000,D,decommissioned
"""
DRIVERS = """driver_id,full_name,license_number,license_categories,license_valid_until,hired_at,status
D-001,Иванов Пётр Сергеевич,77 12 345678,B;C,2027-05-20,2016-03-01,active
D-002,Смирнова Анна Олеговна,77 09 112233,B,2025-03-10,2022-08-15,active
D-003,Кузнецов Олег Иванович,50 14 998877,C;D,2030-01-01,2010-01-11,active
"""
NORMS = """fuel_type,season,coefficient
diesel,winter,1.12
diesel,summer,1.00
petrol,winter,1.10
petrol,summer,1.00
gas,winter,1.15
gas,summer,1.00
"""


@pytest.fixture()
def seeded(client):
    for name, text in (("vehicles", VEHICLES), ("drivers", DRIVERS), ("fuel_norms", NORMS)):
        assert upload(client, name, text).status_code == 200
    return client
