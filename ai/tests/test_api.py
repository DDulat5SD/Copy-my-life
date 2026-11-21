"""US-26: the FastAPI service. Needs the model (CML_AI_MODEL_DIR)."""

import json

import pytest
from fastapi.testclient import TestClient

import app as service

LOCAL = ("127.0.0.1", 50000)

USER = {"id": 1, "interests": "Java, gym, reading",
        "routines": [{"title": "Morning routine", "activities": [
            {"title": "Gym workout", "startTime": "06:30", "repeatDays": ["MON", "WED", "FRI"]},
            {"title": "Read a book", "startTime": "07:30"}]}]}
CANDIDATES = [
    {"id": 3, "interests": "gaming, movies", "routines": [{"title": "Night", "activities": [{"title": "Play games", "startTime": "23:00"}]}]},
    {"id": 2, "interests": "fitness, books", "bio": "Утром спортзал и книги",
     "routines": [{"title": "Утро", "activities": [{"title": "Фитнес", "startTime": "06:00", "repeatDays": ["MON"]}]}]},
]


@pytest.fixture(scope="module")
def client():
    with TestClient(service.app, client=LOCAL) as test_client:
        yield test_client


def post(client, body, **headers):
    return client.post("/match", content=json.dumps(body), headers={"Content-Type": "application/json", **headers})


def test_health_reports_the_loaded_model(client):
    reply = client.get("/health")
    assert reply.status_code == 200
    body = reply.json()
    assert body["status"] == "ok" and body["dimension"] == 384
    assert body["weights"] == {"semantic": 0.6, "schedule": 0.25, "interests": 0.15}


def test_match_ranks_candidates(client):
    reply = post(client, {"user": USER, "candidates": CANDIDATES})
    assert reply.status_code == 200, reply.text
    body = reply.json()
    assert [m["userId"] for m in body["matches"]] == [2, 3]
    assert body["matches"][0]["score"] > body["matches"][1]["score"]
    assert set(body["matches"][0]["components"]) == {"semantic", "schedule", "interests"}
    assert body["weights"]["semantic"] == 0.6


def test_match_reflects_new_data_on_every_request(client):
    first = post(client, {"user": USER, "candidates": CANDIDATES}).json()["matches"][0]["userId"]
    gamer = {**USER, "interests": "gaming, movies", "routines": [{"title": "Night", "activities": [{"title": "Play games", "startTime": "23:30"}]}]}
    second = post(client, {"user": gamer, "candidates": CANDIDATES}).json()["matches"][0]["userId"]
    assert (first, second) == (2, 3)


def test_insufficient_data(client):
    reply = post(client, {"user": {"id": 1}, "candidates": CANDIDATES})
    assert reply.status_code == 200
    assert reply.json()["status"] == "insufficient_data"


@pytest.mark.parametrize("body,field", [
    ({"user": {**USER, "email": "a@b.c"}, "candidates": []}, "user.email"),
    ({"user": USER, "candidates": [{"id": 2, "routines": [{"activities": [{"title": "x", "startTime": "25:00"}]}]}]}, "startTime"),
    ({"user": USER, "candidates": [{"id": 2, "routines": [{"activities": [{"title": "x", "repeatDays": ["MONDAY"]}]}]}]}, "repeatDays"),
    ({"user": USER, "candidates": [{"id": 0}]}, "candidates.0.id"),
    ({"user": USER, "candidates": [{"id": 2}, {"id": 2}]}, ""),
    ({"user": USER, "candidates": [], "limit": 0}, "limit"),
    ({"user": {**USER, "bio": "x" * 1001}, "candidates": []}, "user.bio"),
    ({"candidates": []}, "user"),
])
def test_invalid_requests_are_refused_without_echoing_them(client, body, field):
    reply = post(client, body)
    assert reply.status_code == 422, reply.text
    assert reply.json()["message"] == "Invalid match request"
    assert field in json.dumps(reply.json()["errors"])
    assert "a@b.c" not in reply.text and "xxxx" not in reply.text


def test_not_json_is_refused(client):
    reply = client.post("/match", content="not json", headers={"Content-Type": "application/json"})
    assert reply.status_code == 422


def test_too_many_candidates_are_refused(client):
    reply = post(client, {"user": USER, "candidates": [{"id": i} for i in range(2, 503)]})
    assert reply.status_code == 422


def test_large_bodies_are_refused(client, monkeypatch):
    monkeypatch.setattr(service, "MAX_BODY_BYTES", 2000)
    reply = post(client, {"user": {**USER, "bio": "y" * 900}, "candidates": [{"id": 2, "bio": "z" * 900}, {"id": 3, "bio": "z" * 900}]})
    assert reply.status_code == 413


def test_only_local_clients_are_served(monkeypatch):
    monkeypatch.delenv("CML_AI_ALLOW_REMOTE", raising=False)
    with TestClient(service.app, client=("203.0.113.9", 4000)) as remote:
        assert remote.get("/health").status_code == 403
        assert post(remote, {"user": USER, "candidates": CANDIDATES}).status_code == 403


def test_a_configured_token_is_required(client, monkeypatch):
    monkeypatch.setenv("CML_AI_TOKEN", "s3cret")
    assert post(client, {"user": USER, "candidates": CANDIDATES}).status_code == 401
    assert post(client, {"user": USER, "candidates": CANDIDATES}, **{"X-AI-Token": "wrong"}).status_code == 401
    assert post(client, {"user": USER, "candidates": CANDIDATES}, **{"X-AI-Token": "s3cret"}).status_code == 200


def test_get_on_match_is_not_allowed(client):
    assert client.get("/match").status_code == 405
