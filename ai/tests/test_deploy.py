"""US-26 deployment: how the service listens and protects itself in a container, and that the
build can only ever install the pinned packages and the pinned model."""

import json
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app as service
import download_model
from ai_engine import LifestyleEncoder

AI = Path(__file__).resolve().parent.parent
MANIFEST = json.loads((AI / "model-manifest.json").read_text(encoding="utf-8"))
TOKEN = "t" * 40


# ---- where it listens ----

def test_railway_port_wins_over_the_local_default():
    assert service.listen_address({}) == ("127.0.0.1", 8001)
    assert service.listen_address({"CML_AI_PORT": "9000"}) == ("127.0.0.1", 9000)
    assert service.listen_address({"PORT": "8080", "CML_AI_PORT": "9000", "CML_AI_HOST": "::"}) == ("::", 8080)


# ---- a reachable service demands a strong token ----

@pytest.mark.parametrize("token", ["", "short"])
def test_remote_access_without_a_strong_token_does_not_start(token):
    with pytest.raises(RuntimeError, match="CML_AI_TOKEN"):
        service.check_settings({"CML_AI_ALLOW_REMOTE": "1", "CML_AI_TOKEN": token})


def test_strong_token_or_local_only_starts():
    service.check_settings({"CML_AI_ALLOW_REMOTE": "1", "CML_AI_TOKEN": TOKEN})
    service.check_settings({})


def test_the_service_refuses_to_start_with_unsafe_settings(monkeypatch):
    monkeypatch.setenv("CML_AI_ALLOW_REMOTE", "1")
    monkeypatch.delenv("CML_AI_TOKEN", raising=False)
    with pytest.raises(RuntimeError):
        with TestClient(service.app, client=("10.0.0.5", 4000)):
            pass


def test_in_a_container_health_is_open_and_matching_needs_the_token(monkeypatch):
    monkeypatch.setenv("CML_AI_ALLOW_REMOTE", "1")
    monkeypatch.setenv("CML_AI_TOKEN", TOKEN)
    body = json.dumps({"user": {"id": 1, "interests": "gym"}, "candidates": [{"id": 2, "interests": "fitness"}]})
    headers = {"Content-Type": "application/json"}
    # A neighbour on the private network, as Railway's healthcheck and the backend are.
    with TestClient(service.app, client=("fd12:3456::7", 4000)) as remote:
        assert remote.get("/health").status_code == 200
        assert remote.post("/match", content=body, headers=headers).status_code == 401
        assert remote.post("/match", content=body, headers={**headers, "X-AI-Token": "x" * 40}).status_code == 401
        reply = remote.post("/match", content=body, headers={**headers, "X-AI-Token": TOKEN})
        assert reply.status_code == 200, reply.text
        assert reply.json()["matches"][0]["userId"] == 2


def test_health_reports_the_model_revision_when_the_build_wrote_it():
    with TestClient(service.app, client=("127.0.0.1", 4000)) as local:
        revision = local.get("/health").json()["revision"]
    manifest = service.app.state.matcher.encoder.model_dir / "model-manifest.json"
    assert revision == (MANIFEST["revision"] if manifest.is_file() else None)


# ---- the model is exactly the pinned one ----

def test_the_manifest_pins_a_commit_not_a_branch():
    assert re.fullmatch(r"[0-9a-f]{40}", MANIFEST["revision"])
    assert {item["path"] for item in MANIFEST["files"]} == {"onnx/model_quint8_avx2.onnx", "tokenizer.json"}
    assert all(re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) for item in MANIFEST["files"])
    assert download_model.url_for(MANIFEST, "tokenizer.json") == (
        "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/"
        "e8f8c211226b894fcb81acc59f3b34ba3efd5f42/tokenizer.json")


def test_the_model_in_use_is_the_pinned_one():
    directory = LifestyleEncoder().model_dir
    for item in MANIFEST["files"]:
        assert download_model.verify(directory / item["path"], item["size"], item["sha256"]) is None, item["path"]


def test_a_changed_or_partial_file_is_refused(tmp_path):
    file = tmp_path / "tokenizer.json"
    file.write_bytes(b"not the tokenizer")
    item = next(i for i in MANIFEST["files"] if i["path"] == "tokenizer.json")
    assert "size" in download_model.verify(file, item["size"], item["sha256"])
    assert "sha256" in download_model.verify(file, file.stat().st_size, item["sha256"])
    assert download_model.verify(tmp_path / "missing", 1, item["sha256"]) == "missing"
    assert download_model.verify(file, file.stat().st_size, download_model.sha256_of(file)) is None


def test_files_already_present_are_kept_without_downloading(tmp_path, monkeypatch):
    content = b"pinned bytes"
    import hashlib
    manifest = {"repo": "x/y", "revision": "0" * 40,
                "files": [{"path": "a/b.bin", "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}]}
    (tmp_path / "a").mkdir()
    (tmp_path / "a" / "b.bin").write_bytes(content)
    manifest_file = tmp_path / "manifest.json"
    manifest_file.write_text(json.dumps(manifest), encoding="utf-8")
    monkeypatch.setattr(download_model, "MANIFEST", manifest_file)
    monkeypatch.setattr(download_model, "download", lambda *args: pytest.fail("must not download"))
    assert download_model.main(["download_model.py", str(tmp_path)]) == 0
    assert json.loads((tmp_path / "model-manifest.json").read_text())["revision"] == "0" * 40


# ---- the build installs only pinned packages ----

def test_every_locked_package_is_pinned_with_hashes():
    text = (AI / "requirements.lock.txt").read_text(encoding="utf-8")
    entries = re.findall(r"^([A-Za-z0-9_.-]+)==(\S+) \\$", text, re.M)
    assert len(entries) >= 25
    blocks = re.split(r"^(?=[A-Za-z0-9_.-]+==)", text, flags=re.M)[1:]
    assert all("--hash=sha256:" in block for block in blocks)
    locked = {name.lower().replace("_", "-"): version for name, version in entries}
    for line in (AI / "requirements.txt").read_text(encoding="utf-8").splitlines():
        if "==" in line and not line.startswith("#"):
            name, version = line.split("==")
            assert locked[name.lower()] == version.strip(), name


def test_the_dockerfile_uses_the_pins():
    dockerfile = (AI / "Dockerfile").read_text(encoding="utf-8")
    assert re.search(r"^FROM python:3\.13\.\d+-slim-bookworm@sha256:[0-9a-f]{64}$", dockerfile, re.M)
    assert "pip install --require-hashes -r requirements.lock.txt" in dockerfile
    assert "python download_model.py /models/multilingual-minilm" in dockerfile
    assert "CML_AI_HOST=::" in dockerfile and "CML_AI_ALLOW_REMOTE=1" in dockerfile
    assert "USER copymylife" in dockerfile
    config = json.loads((AI / "railway.json").read_text(encoding="utf-8"))
    assert config["build"]["builder"] == "DOCKERFILE"
    assert config["deploy"]["healthcheckPath"] == "/health"
    assert "models/" in (AI / ".dockerignore").read_text(encoding="utf-8")


def test_listening_on_all_interfaces_takes_ipv4_and_ipv6():
    # IPv4 is accepted on the same socket because IPV6_V6ONLY is off before binding; whether a
    # connection then arrives depends on the host's network stack, so only the socket is checked.
    import socket
    sock = service.listening_socket("::", 0)
    try:
        assert sock.family == socket.AF_INET6
        assert sock.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY) == 0
        assert sock.getsockname()[1] > 0
    finally:
        sock.close()
    assert service.listening_socket("127.0.0.1", 0) is None
    assert service.listening_socket("0.0.0.0", 0) is None
