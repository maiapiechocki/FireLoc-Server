# python-service/tests/test_localize_single_camera.py

import pytest
from app import app as flask_app
import json

@pytest.fixture
def client():
    flask_app.config['TESTING'] = True
    with flask_app.test_client() as client:
        yield client

def test_missing_fields(client):
    res = client.post("/localize_single_camera", json={})
    assert res.status_code == 400
    assert res.get_json()["status"] == "error"

def test_invalid_pixel_format(client):
    payload = {
        "algorithm": "TopoMono",
        "image_gcs_uri": "path/to/image.jpg",
        "detection_pixel": {"wrong": 1},
        "camera_params": {
            "lat": 34.07,
            "lon": -118.20,
            "elevation_m": 450,
            "heading_deg": 90,
            "tilt_deg": 10,
            "fov_h_deg": 60,
            "fov_v_deg": 45,
            "resolution": {"width": 640, "height": 480}
        },
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/localize_single_camera", json=payload)
    assert res.status_code == 400
    assert "detection_pixel" in res.get_json()["message"]

def test_invalid_camera_params(client):
    payload = {
        "algorithm": "TopoMono",
        "image_gcs_uri": "path/to/image.jpg",
        "detection_pixel": {"x": 100, "y": 100},
        "camera_params": {"lat": 34.07},  # missing fields
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }
    res = client.post("/localize_single_camera", json=payload)
    assert res.status_code == 400
    assert "camera_params" in res.get_json()["message"]

def test_valid_topomono_call_mocked(client, mocker):
    # Mock all functions to isolate logic
    mocker.patch("utils.dem.load_dem_by_id", return_value=([[1, 2], [3, 4]], (0, 1, 0, 0, 0, -1)))
    mocker.patch("algorithms.interface.run_topomono", return_value=(34.01, -118.02, True))

    payload = {
        "algorithm": "TopoMono",
        "image_gcs_uri": "path/to/image.jpg",
        "detection_pixel": {"x": 10, "y": 20},
        "camera_params": {
            "lat": 34.07,
            "lon": -118.20,
            "elevation_m": 450,
            "heading_deg": 90,
            "tilt_deg": 10,
            "fov_h_deg": 60,
            "fov_v_deg": 45,
            "resolution": {"width": 640, "height": 480}
        },
        "dem_identifier": "USGS_one_meter_x36y378_CA_LosAngeles_2016"
    }

    res = client.post("/localize_single_camera", data=json.dumps(payload), content_type='application/json')
    data = res.get_json()
    assert res.status_code == 200
    assert data["status"] == "success"
    assert data["latitude"] == 34.01
    assert data["longitude"] == -118.02
