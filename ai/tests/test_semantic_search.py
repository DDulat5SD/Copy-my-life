"""Routine assistant: POST /similar and semantic_search.rank with the real model (CML_AI_MODEL_DIR)."""

import ast
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app as service
import semantic_search
from ai_engine import LifestyleEncoder

AI = Path(__file__).resolve().parent.parent
LOCAL = ("127.0.0.1", 50001)

ROUTINES = [
    {"id": 11, "text": "Evening cooking. Wellness. Cook dinner, wash dishes"},
    {"id": 12, "text": "Coding practice. Study. Java exercises, read Spring documentation"},
    {"id": 13, "text": "Morning yoga. Fitness. Stretching, breathing, yoga flow"},
]


@pytest.fixture(scope="module")
def client():
    with TestClient(service.app, client=LOCAL) as test_client:
        yield test_client


@pytest.fixture(scope="module")
def encoder():
    return LifestyleEncoder()


def post(client, body, **headers):
    return client.post("/similar", content=json.dumps(body), headers={"Content-Type": "application/json", **headers})


def test_the_closest_meaning_comes_first(client):
    reply = post(client, {"queries": ["learn Java programming"], "items": ROUTINES, "limit": 3})
    assert reply.status_code == 200, reply.text
    body = reply.json()
    ranked = body["results"][0]
    assert [item["id"] for item in ranked][0] == 12
    assert ranked[0]["score"] > ranked[1]["score"] >= ranked[2]["score"]
    assert body["considered"] == 3


@pytest.mark.parametrize("query,expected", [
    ("изучать программирование на Java", 12),   # Russian
    ("таңертең йога және созылу жаттығулары", 13),  # Kazakh
    ("приготовить ужин", 11),
])
def test_queries_in_other_languages_find_the_same_meaning(client, query, expected):
    ranked = post(client, {"queries": [query], "items": ROUTINES}).json()["results"][0]
    assert ranked[0]["id"] == expected, ranked


def test_one_result_list_per_query_with_limit_and_min_score(client):
    reply = post(client, {"queries": ["yoga", "", "cooking"], "items": ROUTINES, "limit": 1, "minScore": 0.2}).json()
    assert len(reply["results"]) == 3
    assert reply["results"][0][0]["id"] == 13 and len(reply["results"][0]) == 1
    assert reply["results"][1] == [], "an empty query finds nothing"
    assert reply["results"][2][0]["id"] == 11
    high = post(client, {"queries": ["yoga"], "items": ROUTINES, "minScore": 0.99}).json()["results"][0]
    assert high == []


def test_empty_items_and_empty_texts_are_left_out(client):
    assert post(client, {"queries": ["yoga"], "items": []}).json()["results"] == [[]]
    ranked = post(client, {"queries": ["yoga"], "items": [{"id": 1, "text": ""}, {"id": 2, "text": "yoga"}]}).json()["results"][0]
    assert [item["id"] for item in ranked] == [2]


def test_the_same_request_gives_the_same_answer(encoder):
    items = [(item["id"], item["text"]) for item in ROUTINES]
    first = semantic_search.rank(encoder, ["daily Java study"], items)
    second = semantic_search.rank(encoder, ["daily Java study"], list(reversed(items)))
    assert first == second


@pytest.mark.parametrize("body,field", [
    ({"queries": [], "items": ROUTINES}, "queries"),
    ({"queries": ["x"], "items": [{"id": 1, "text": "a", "email": "a@b.c"}]}, "items.0.email"),
    ({"queries": ["x"], "items": [{"id": 1, "text": "a"}, {"id": 1, "text": "b"}]}, ""),
    ({"queries": ["x"], "items": ROUTINES, "limit": 0}, "limit"),
    ({"queries": ["x"], "items": [{"id": 1, "text": "y" * 2001}]}, "items.0.text"),
    ({"queries": ["x" * 2001], "items": ROUTINES}, ""),
    ({"items": ROUTINES}, "queries"),
])
def test_invalid_requests_are_refused_without_echoing_them(client, body, field):
    reply = post(client, body)
    assert reply.status_code == 422, reply.text
    assert reply.json()["message"] == "Invalid similarity request"
    assert field in json.dumps(reply.json()["errors"])
    assert "a@b.c" not in reply.text and "yyyy" not in reply.text and "xxxx" not in reply.text


def test_too_many_items_are_refused(client):
    reply = post(client, {"queries": ["x"], "items": [{"id": i, "text": "t"} for i in range(501)]})
    assert reply.status_code == 422


def test_a_configured_token_is_required(client, monkeypatch):
    monkeypatch.setenv("CML_AI_TOKEN", "s3cret")
    assert post(client, {"queries": ["x"], "items": ROUTINES}).status_code == 401
    assert post(client, {"queries": ["x"], "items": ROUTINES}, **{"X-AI-Token": "s3cret"}).status_code == 200


def test_only_local_clients_are_served(monkeypatch):
    monkeypatch.delenv("CML_AI_ALLOW_REMOTE", raising=False)
    with TestClient(service.app, client=("203.0.113.9", 4000)) as remote:
        assert post(remote, {"queries": ["x"], "items": ROUTINES}).status_code == 403


def test_the_matching_endpoint_still_answers(client):
    reply = client.post("/match", content=json.dumps({"user": {"id": 1, "interests": "yoga"},
                                                      "candidates": [{"id": 2, "interests": "yoga"}]}),
                        headers={"Content-Type": "application/json"})
    assert reply.status_code == 200 and reply.json()["matches"][0]["userId"] == 2


def test_the_image_contains_every_module_the_service_imports():
    dockerfile = (AI / "Dockerfile").read_text(encoding="utf-8")
    copied = {word for line in dockerfile.splitlines() if line.startswith("COPY") for word in line.split()[1:-1]}
    local = {path.stem for path in AI.glob("*.py")}
    imported = set()
    for module in ("app.py", "semantic_search.py", "ai_engine.py"):
        tree = ast.parse((AI / module).read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.add(node.module)
    needed = {name + ".py" for name in imported if name in local}
    assert needed <= copied, needed - copied
