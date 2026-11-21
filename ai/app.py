"""CopyMyLife lifestyle matching service (US-26) and routine assistant search.

POST /match takes one user's lifestyle and the candidates' public lifestyle, as chosen by the
Spring Boot backend, and returns the candidates ranked by ai_engine.LifestyleMatcher.
POST /similar (routine assistant) ranks texts by closeness of meaning to each query with the
same model (semantic_search.rank). The service never reads a database and keeps nothing but an
in-memory cache of text embeddings.

By default it answers only on 127.0.0.1 and only to local clients. CML_AI_TOKEN, when set,
must be sent as the X-AI-Token header on every request but GET /health. In a container
(CML_AI_ALLOW_REMOTE=1, see Dockerfile) it listens on CML_AI_HOST=:: and PORT, and refuses to
start without a token of at least MIN_TOKEN_LENGTH characters.
Run: python app.py   (PORT or CML_AI_PORT, CML_AI_HOST, CML_AI_MODEL_DIR, CML_AI_TOKEN)
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import socket
from contextlib import asynccontextmanager
from typing import Literal, Mapping

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

import semantic_search
from ai_engine import WEIGHTS, Activity, LifestyleEncoder, LifestyleMatcher, Person, Routine

log = logging.getLogger("copymylife.ai")

MAX_BODY_BYTES = int(os.environ.get("CML_AI_MAX_BODY_BYTES", "1000000"))
MAX_CANDIDATES = 500
MAX_ITEMS = 500
LOCAL_CLIENTS = {"127.0.0.1", "::1", "localhost"}
MIN_TOKEN_LENGTH = 32
# Railway's healthcheck sends no token; it only learns that the model is loaded.
OPEN_PATHS = {"/health"}
Day = Literal["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]


def listen_address(env: Mapping[str, str]) -> tuple[str, int]:
    """Where to listen: PORT (set by Railway) wins over CML_AI_PORT; 127.0.0.1:8001 by default."""
    host = env.get("CML_AI_HOST") or "127.0.0.1"
    port = int(env.get("PORT") or env.get("CML_AI_PORT") or "8001")
    return host, port


def listening_socket(host: str, port: int) -> socket.socket | None:
    """For "::" a socket that takes IPv6 and IPv4 alike (Railway's private network and
    healthcheck may use either), whatever the system's default; None for any other host."""
    if host != "::":
        return None
    sock = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
    if os.name != "nt":  # on Windows SO_REUSEADDR would let another process share the port
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("::", port))
    return sock


def check_settings(env: Mapping[str, str]) -> None:
    """A service other machines can reach must demand a strong token."""
    if env.get("CML_AI_ALLOW_REMOTE") == "1" and len(env.get("CML_AI_TOKEN", "")) < MIN_TOKEN_LENGTH:
        raise RuntimeError(f"CML_AI_ALLOW_REMOTE=1 needs CML_AI_TOKEN of at least {MIN_TOKEN_LENGTH} characters")


class Strict(BaseModel):
    # Anything the backend should not send (an e-mail address, notes, ...) is refused, not ignored.
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ActivityIn(Strict):
    title: str = Field("", max_length=200)
    startTime: str | None = Field(None, pattern=r"^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$")
    repeatDays: list[Day] = Field(default_factory=list, max_length=7)


class RoutineIn(Strict):
    title: str = Field("", max_length=200)
    category: str = Field("", max_length=100)
    description: str = Field("", max_length=1000)
    activities: list[ActivityIn] = Field(default_factory=list, max_length=50)


class PersonIn(Strict):
    id: int = Field(ge=1)
    interests: str = Field("", max_length=1000)
    goals: str = Field("", max_length=1000)
    bio: str = Field("", max_length=1000)
    profession: str = Field("", max_length=200)
    routines: list[RoutineIn] = Field(default_factory=list, max_length=20)

    def to_person(self) -> Person:
        return Person(
            id=self.id, interests=self.interests, goals=self.goals, bio=self.bio, profession=self.profession,
            routines=[Routine(r.title, r.category, r.description,
                              [Activity(a.title, a.startTime, list(a.repeatDays)) for a in r.activities])
                      for r in self.routines])


class SimilarItemIn(Strict):
    id: int = Field(ge=-(2 ** 53), le=2 ** 53)
    text: str = Field("", max_length=2000)


class SimilarRequest(Strict):
    # Routine assistant: rank items by closeness of meaning to each query (semantic_search.rank).
    queries: list[str] = Field(min_length=1, max_length=20)
    items: list[SimilarItemIn] = Field(max_length=MAX_ITEMS)
    limit: int = Field(5, ge=1, le=50)
    minScore: float = Field(0.0, ge=-1.0, le=1.0)

    @model_validator(mode="after")
    def short_distinct(self):
        if any(len(query) > 2000 for query in self.queries):
            raise ValueError("a query may have at most 2000 characters")
        ids = [item.id for item in self.items]
        if len(ids) != len(set(ids)):
            raise ValueError("item ids must be distinct")
        return self


class MatchRequest(Strict):
    user: PersonIn
    candidates: list[PersonIn] = Field(max_length=MAX_CANDIDATES)
    limit: int = Field(10, ge=1, le=50)
    minScore: float = Field(0.0, ge=0.0, le=1.0)

    @model_validator(mode="after")
    def distinct_candidates(self):
        ids = [candidate.id for candidate in self.candidates]
        if len(ids) != len(set(ids)):
            raise ValueError("candidate ids must be distinct")
        return self


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Unsafe settings or a missing model stop the service from starting; the model is loaded once.
    check_settings(os.environ)
    app.state.matcher = LifestyleMatcher(LifestyleEncoder())
    manifest = app.state.matcher.encoder.model_dir / "model-manifest.json"
    app.state.revision = json.loads(manifest.read_text(encoding="utf-8"))["revision"] if manifest.is_file() else None
    log.info("model loaded from %s (revision %s)", app.state.matcher.encoder.model_dir, app.state.revision)
    yield


app = FastAPI(title="CopyMyLife AI", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


def refuse(status: int, message: str, **extra) -> JSONResponse:
    return JSONResponse(status_code=status, content={"message": message, **extra})


@app.middleware("http")
async def guard(request: Request, call_next):
    host = request.client.host if request.client else ""
    if os.environ.get("CML_AI_ALLOW_REMOTE") != "1" and host not in LOCAL_CLIENTS:
        return refuse(403, "Only local clients may use this service")

    token = os.environ.get("CML_AI_TOKEN", "")
    if token and request.url.path not in OPEN_PATHS \
            and not hmac.compare_digest(request.headers.get("X-AI-Token", ""), token):
        return refuse(401, "Missing or wrong X-AI-Token")

    length = request.headers.get("content-length")
    if length and (not length.isdigit() or int(length) > MAX_BODY_BYTES):
        return refuse(413, "Request too large")
    return await call_next(request)


@app.get("/health")
def health(request: Request):
    encoder = request.app.state.matcher.encoder
    return {"status": "ok", "model": encoder.model_dir.name, "revision": request.app.state.revision,
            "dimension": encoder.dimension, "weights": WEIGHTS, "textsInferred": encoder.texts_inferred}


@app.post("/match")
async def match(request: Request):
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:  # also when no Content-Length was sent
        return refuse(413, "Request too large")
    try:
        payload = MatchRequest.model_validate_json(body)
    except ValidationError as error:
        # Where and why only: the values may be someone's profile text.
        problems = [{"field": ".".join(str(part) for part in item["loc"]), "problem": item["msg"]}
                    for item in error.errors(include_input=False, include_url=False)][:20]
        return refuse(422, "Invalid match request", errors=problems)

    try:
        # The model runs on a worker thread, so /health keeps answering during a long match.
        result = await run_in_threadpool(
            request.app.state.matcher.match,
            payload.user.to_person(), [candidate.to_person() for candidate in payload.candidates],
            limit=payload.limit, min_score=payload.minScore)
    except Exception:
        log.exception("matching failed")
        return refuse(500, "Matching failed")
    return {**result, "weights": WEIGHTS}


@app.post("/similar")
async def similar(request: Request):
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:
        return refuse(413, "Request too large")
    try:
        payload = SimilarRequest.model_validate_json(body)
    except ValidationError as error:
        problems = [{"field": ".".join(str(part) for part in item["loc"]), "problem": item["msg"]}
                    for item in error.errors(include_input=False, include_url=False)][:20]
        return refuse(422, "Invalid similarity request", errors=problems)

    try:
        results = await run_in_threadpool(
            semantic_search.rank, request.app.state.matcher.encoder, payload.queries,
            [(item.id, item.text) for item in payload.items], payload.limit, payload.minScore)
    except Exception:
        log.exception("similarity search failed")
        return refuse(500, "Similarity search failed")
    return {"status": "ok", "results": results, "considered": len(payload.items)}


if __name__ == "__main__":
    import uvicorn

    logging.basicConfig(level=logging.INFO)
    check_settings(os.environ)
    host, port = listen_address(os.environ)
    # One process: the model is held in memory once; requests share it.
    server = uvicorn.Server(uvicorn.Config(app, host=host, port=port, workers=1, proxy_headers=False))
    sock = listening_socket(host, port)
    server.run(sockets=[sock] if sock else None)
