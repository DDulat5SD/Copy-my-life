# CopyMyLife AI — Lifestyle Matching (US-26)

A small local service that ranks CopyMyLife members by how alike their lifestyle is to one
user's. The Spring Boot backend (`GET /users/me/lifestyle-matches`) collects the data it is
allowed to share and calls this service; the service never touches a database.

## Model

`paraphrase`-style multilingual MiniLM sentence-transformer (384 dimensions, XLM-R tokenizer,
English, Kazakh and Russian among its languages), run as the quantised ONNX file
`onnx/model_quint8_avx2.onnx` with ONNX Runtime on the CPU. No PyTorch, TensorFlow or
`sentence_transformers` is needed (they are blocked on the development PC).

Inference: tokenizer → `input_ids`, `attention_mask`, `token_type_ids` (128 tokens at most) →
`last_hidden_state` → mean of the real tokens only (attention mask) → L2 normalisation, so a dot
product is the cosine similarity. Each text is run on its own (the quantised model scales its
activations per batch) and its vector is cached in memory by its text.

The model files are **not** in Git. Point `CML_AI_MODEL_DIR` at a folder holding
`onnx/model_quint8_avx2.onnx` and `tokenizer.json` (default: `ai/models/multilingual-minilm`,
ignored by Git). On the development PC they are in
`C:\Users\ASUS\IdeaProjects\CopyMyLife-AI\models\multilingual-minilm`.

## Score

| part | weight | what it compares |
|---|---|---|
| semantic | 60% | cosine similarity of the two people's descriptions: the profile (interests, goals, bio, profession) and one text per routine (title, category, description, activity titles), averaged into one vector per person |
| schedule | 25% | cosine similarity of 7 × 4 counts of timed activities (day × morning 04–12, afternoon 12–17, evening 17–22, night 22–04); an activity without repeat days counts every day, as in CopyMyLife |
| interests | 15% | listed interests paired one-to-one with a close interest of the other person (cosine ≥ 0.62, compared as "I enjoy …"); score = 2 × pairs / all interests |

Semantic similarity below 0 counts as 0. A part one of the two has no data for (no timed
activities, no interests) is left out and the remaining weights are scaled up to 1; the reply says
which parts and weights were used. The weights are starting values, not validated ones, and the
score is how alike what the two wrote is — not a probability that they get on.

Reasons returned with each match: shared interests, similar activities (cosine ≥ 0.45), shared
parts of the day and days.

## API

* `GET /health` — model name, dimension, weights, texts inferred so far.
* `POST /match` — `{"user": Person, "candidates": [Person], "limit": 1-50, "minScore": 0-1}` where
  `Person = {id, interests, goals, bio, profession, routines: [{title, category, description,
  activities: [{title, startTime "HH:MM", repeatDays ["MON".."SUN"]}]}]}`. Unknown fields (such as
  an e-mail address) are refused with 422; errors name the field, never echo the value.
  Reply: `{status: "ok" | "insufficient_data", matches: [{userId, score, components, weightsUsed,
  factors}], considered, skipped, weights}`.
* `POST /similar` (routine assistant, `semantic_search.py`) — `{"queries": [text] (1-20),
  "items": [{id, text}] (up to 500, text up to 2000 characters), "limit": 1-50, "minScore": -1..1}`.
  Reply: `{status: "ok", results: [[{id, score}] per query, best first], considered}`; score is the
  cosine similarity of the two sentence embeddings. The backend uses it to find public routines
  close in meaning to the user's goals and activities, and to check whether a routine has an
  activity close to each goal. Everything else the routine assistant does (scheduling, reviews,
  duration analysis, summaries) is fixed rules in the Spring Boot backend
  (`com.copymylife.copymylife.assistant`), which keeps working when this service is down. The
  model only measures closeness of meaning; it does not write text.

Limits: 1 MB body, 500 candidates, 20 routines × 50 activities a person, text fields 1000
characters. Only clients on 127.0.0.1 are served unless `CML_AI_ALLOW_REMOTE=1`; if
`CML_AI_TOKEN` is set every request needs the header `X-AI-Token`.

## Run

```
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements-dev.txt
.venv\Scripts\python download_model.py models\multilingual-minilm   # the default model folder
.venv\Scripts\python app.py                 # http://127.0.0.1:8001
.venv\Scripts\python -m pytest tests        # uses the real model
.venv\Scripts\python test_similarity.py     # prints real similarities and a ranking
```

The backend finds the service at `AI_MATCHING_URL` (default `http://127.0.0.1:8001`), sends
`AI_MATCHING_TOKEN` if set and waits `AI_MATCHING_TIMEOUT_SECONDS` (default 20). If the service is
not running, Find Similar People says so and everything else in CopyMyLife works as before.

## Container and Railway

`Dockerfile` (build context: this folder) gives the same image on every build:

* base image `python:3.13.17-slim-bookworm` pinned by digest;
* `requirements.lock.txt`: all 30 packages pinned, every file hash listed, installed with
  `pip install --require-hashes` (regenerate it whenever `requirements.txt` changes);
* `download_model.py` fetches the two files in `model-manifest.json` from Hugging Face at the
  fixed commit `e8f8c211…` and stops the build unless size and SHA-256 match; it writes the
  manifest next to the model so `GET /health` reports the revision.

In the container the service listens on `::` (one socket for IPv6 and IPv4) and `PORT`
(default 8080), accepts other hosts (`CML_AI_ALLOW_REMOTE=1`) and refuses to start without a
`CML_AI_TOKEN` of at least 32 characters; every request except `GET /health` must send it as
`X-AI-Token`. It runs as a non-root user, one process, `CML_AI_THREADS=2` ONNX threads.

Measured locally (Windows, i5-13th gen, 2 threads, packages from the lock, model downloaded by
`download_model.py`): ready 1.6 s after start; a match against 300 members with 2 routines x
5 activities each (993 texts) took 8.0 s with an empty cache and 0.13 s after; resident memory
472 MB, peak 545 MB. Suggested Railway limits: 1 GB RAM, 1-2 vCPU. Image about 0.5 GB
(base, 53 MB of wheels, 127 MB of model).

`railway.json`: Dockerfile builder, healthcheck `GET /health` (120 s), restart on failure,
one replica, redeploys only for changes under `/ai/**`.

Railway service `copymylife-ai` (same project and environment as the backend):

| setting | value |
|---|---|
| Source | GitHub repo, branch `main` |
| Root Directory | `/ai` |
| Config file path | `/ai/railway.json` |
| Public networking | none (no domain) |
| Variables | `CML_AI_TOKEN` = a random secret of 48+ characters, `PORT` = `8080` |

Backend service variables:

```
AI_MATCHING_URL=http://${{copymylife-ai.RAILWAY_PRIVATE_DOMAIN}}:${{copymylife-ai.PORT}}
AI_MATCHING_TOKEN=${{copymylife-ai.CML_AI_TOKEN}}
AI_MATCHING_TIMEOUT_SECONDS=30
```
