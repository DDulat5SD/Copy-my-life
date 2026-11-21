"""CopyMyLife lifestyle matching (US-26).

Texts are turned into sentence embeddings by a pretrained multilingual MiniLM model run with
ONNX Runtime on the CPU (no PyTorch): the tokenizer gives input_ids, attention_mask and
token_type_ids, the model gives last_hidden_state, and the token vectors are averaged over the
real tokens only (attention-mask-aware mean pooling) and L2-normalised, so that a dot product is
the cosine similarity.

A match score combines three parts, each between 0 and 1:
  semantic  60%  cosine similarity of the two people's lifestyle descriptions
  schedule  25%  how alike their timed activities are spread over the week and the day
  interests 15%  how many listed interests have a close counterpart on the other side
A part that one of the two has no data for is left out and the weights of the others are scaled
up to sum to 1; the result says which parts were used. The weights are starting values, not
validated ones, and the score is a similarity of what the two have written, not a probability
that two people get on.
"""

from __future__ import annotations

import os
import re
import threading
import unicodedata
from collections import OrderedDict
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

DEFAULT_MODEL_DIR = Path(__file__).resolve().parent / "models" / "multilingual-minilm"
MODEL_FILE = Path("onnx") / "model_quint8_avx2.onnx"
TOKENIZER_FILE = Path("tokenizer.json")
MAX_TOKENS = 128
# One text per model run: the quantised model scales its activations per batch, so a text run
# beside others came out slightly different (cosine 0.996). Alone, a text always gets the same
# vector; for 300 short texts this took 1.5 s instead of 1.0 s, and vectors are cached.
BATCH_SIZE = 1

WEIGHTS = {"semantic": 0.60, "schedule": 0.25, "interests": 0.15}

# An interest or an activity counts as shared with one of the other person's when their
# embeddings are at least this close. Set on labelled English/Kazakh/Russian pairs (see
# tests/test_engine.py): a lone word such as "java" lands next to unrelated words, so interests
# are compared inside a short sentence, where related pairs scored >= 0.63 and unrelated <= 0.59.
# Activity titles are phrases already; unrelated ones scored <= 0.28, so 0.45 keeps only close ones.
INTEREST_TEMPLATE = "I enjoy {}"
INTEREST_MATCH = 0.62
ACTIVITY_MATCH = 0.45

DAYS = ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
# The parts of the day an activity can start in, as minutes since midnight [start, end).
PARTS_OF_DAY = (
    ("morning", 4 * 60, 12 * 60),
    ("afternoon", 12 * 60, 17 * 60),
    ("evening", 17 * 60, 22 * 60),
    ("night", 22 * 60, 4 * 60),
)
_TIME = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)(:[0-5]\d)?$")
_INTEREST_SPLIT = re.compile(r"[,;\n\r|/•·]+")


def clean_text(value: str | None) -> str:
    """One line of text in NFC form with single spaces; '' for nothing."""
    if not value:
        return ""
    return " ".join(unicodedata.normalize("NFC", str(value)).split())


class LifestyleEncoder:
    """Loads the ONNX model once and turns texts into L2-normalised sentence embeddings."""

    def __init__(self, model_dir: str | os.PathLike | None = None, cache_size: int = 4096):
        directory = Path(model_dir or os.environ.get("CML_AI_MODEL_DIR") or DEFAULT_MODEL_DIR)
        model_path = directory / MODEL_FILE
        tokenizer_path = directory / TOKENIZER_FILE
        for needed in (model_path, tokenizer_path):
            if not needed.is_file():
                raise FileNotFoundError(f"Model file not found: {needed} (set CML_AI_MODEL_DIR)")

        self.model_dir = directory
        self.tokenizer = Tokenizer.from_file(str(tokenizer_path))
        self.tokenizer.enable_truncation(MAX_TOKENS)
        pad_id = self.tokenizer.token_to_id("<pad>")
        self.tokenizer.enable_padding(pad_id=1 if pad_id is None else pad_id, pad_token="<pad>")

        options = ort.SessionOptions()
        # A container sees the host's CPUs, not its own share: CML_AI_THREADS sets it there.
        threads = os.environ.get("CML_AI_THREADS")
        options.intra_op_num_threads = int(threads) if threads else max(1, min(4, os.cpu_count() or 1))
        self.session = ort.InferenceSession(str(model_path), options, providers=["CPUExecutionProvider"])
        self.input_names = {item.name for item in self.session.get_inputs()}
        self.dimension = int(self.session.get_outputs()[0].shape[-1])

        self._cache: OrderedDict[str, np.ndarray] = OrderedDict()
        self._cache_size = cache_size
        self._lock = threading.Lock()
        # How many texts actually went through the model; lets tests see real inference.
        self.texts_inferred = 0

    def encode(self, texts: list[str]) -> np.ndarray:
        """One row per text; an empty text gets a zero row (similar to nothing)."""
        cleaned = [clean_text(text) for text in texts]
        result = np.zeros((len(cleaned), self.dimension), dtype=np.float32)

        with self._lock:
            missing = [text for text in dict.fromkeys(cleaned) if text and text not in self._cache]
            for start in range(0, len(missing), BATCH_SIZE):
                batch = missing[start:start + BATCH_SIZE]
                for text, vector in zip(batch, self._infer(batch)):
                    self._cache[text] = vector
                    if len(self._cache) > self._cache_size:
                        self._cache.popitem(last=False)
                self.texts_inferred += len(batch)
            for row, text in enumerate(cleaned):
                if text:
                    vector = self._cache.get(text)
                    if vector is None:  # pushed out of a small cache within this call
                        vector = self._infer([text])[0]
                    else:
                        self._cache.move_to_end(text)
                    result[row] = vector
        return result

    def _infer(self, texts: list[str]) -> np.ndarray:
        encodings = self.tokenizer.encode_batch(texts)
        input_ids = np.array([item.ids for item in encodings], dtype=np.int64)
        attention_mask = np.array([item.attention_mask for item in encodings], dtype=np.int64)
        token_type_ids = np.array([item.type_ids for item in encodings], dtype=np.int64)
        feeds = {"input_ids": input_ids, "attention_mask": attention_mask, "token_type_ids": token_type_ids}
        feeds = {name: value for name, value in feeds.items() if name in self.input_names}

        hidden = self.session.run(["last_hidden_state"], feeds)[0]
        mask = attention_mask[..., None].astype(np.float32)
        pooled = (hidden * mask).sum(axis=1) / np.clip(mask.sum(axis=1), 1e-9, None)
        norms = np.linalg.norm(pooled, axis=1, keepdims=True)
        return (pooled / np.clip(norms, 1e-12, None)).astype(np.float32)

    def similarity(self, first: str, second: str) -> float:
        """Cosine similarity of two texts; 0.0 when either is empty."""
        vectors = self.encode([first, second])
        return float(vectors[0] @ vectors[1])


@dataclass
class Activity:
    title: str = ""
    start_time: str | None = None
    repeat_days: list[str] = field(default_factory=list)


@dataclass
class Routine:
    title: str = ""
    category: str = ""
    description: str = ""
    activities: list[Activity] = field(default_factory=list)


@dataclass
class Person:
    id: int
    interests: str = ""
    goals: str = ""
    bio: str = ""
    profession: str = ""
    routines: list[Routine] = field(default_factory=list)

    def interest_list(self) -> list[str]:
        items = [clean_text(part).lower() for part in _INTEREST_SPLIT.split(self.interests or "")]
        return list(dict.fromkeys(item for item in items if item))[:30]

    def texts(self) -> list[str]:
        """The descriptions the semantic part compares: the profile, then one text per routine."""
        texts = []
        profile = ". ".join(part for part in map(clean_text, (self.interests, self.goals, self.bio, self.profession)) if part)
        if profile:
            texts.append(profile)
        for routine in self.routines:
            activities = ", ".join(title for title in (clean_text(a.title) for a in routine.activities) if title)
            parts = [clean_text(routine.title), clean_text(routine.category), clean_text(routine.description), activities]
            text = ". ".join(part for part in parts if part)
            if text:
                texts.append(text)
        return texts

    def activity_titles(self) -> list[str]:
        titles = (clean_text(a.title) for routine in self.routines for a in routine.activities)
        return list(dict.fromkeys(title for title in titles if title))[:100]

    def week(self) -> np.ndarray | None:
        """Timed activities per day and part of the day (7 x 4); None without any start time."""
        grid = np.zeros((len(DAYS), len(PARTS_OF_DAY)), dtype=np.float32)
        for routine in self.routines:
            for activity in routine.activities:
                minute = minutes(activity.start_time)
                if minute is None:
                    continue
                days = [day for day in (d.strip().upper() for d in activity.repeat_days) if day in DAYS]
                # Like CopyMyLife's own plan: an activity without repeat days is planned every day.
                for day in days or DAYS:
                    grid[DAYS.index(day), part_of_day(minute)] += 1
        return grid if grid.any() else None

    def has_data(self) -> bool:
        return bool(self.texts() or self.interest_list() or self.week() is not None)


def minutes(value: str | None) -> int | None:
    found = _TIME.match(value.strip()) if value else None
    return int(found.group(1)) * 60 + int(found.group(2)) if found else None


def part_of_day(minute: int) -> int:
    for index, (_, start, end) in enumerate(PARTS_OF_DAY):
        if (start <= minute < end) if start < end else (minute >= start or minute < end):
            return index
    return len(PARTS_OF_DAY) - 1


class LifestyleMatcher:
    """Ranks candidates by how alike their lifestyle is to one person's."""

    def __init__(self, encoder: LifestyleEncoder, weights: dict[str, float] | None = None):
        self.encoder = encoder
        self.weights = dict(weights or WEIGHTS)

    def centroid(self, person: Person) -> np.ndarray | None:
        texts = person.texts()
        if not texts:
            return None
        mean = self.encoder.encode(texts).mean(axis=0)
        norm = float(np.linalg.norm(mean))
        return mean / norm if norm > 0 else None

    def interest_vectors(self, interests: list[str]) -> np.ndarray:
        return self.encoder.encode([INTEREST_TEMPLATE.format(item) for item in interests])

    def match(self, me: Person, candidates: list[Person], limit: int = 10, min_score: float = 0.0) -> dict:
        if not me.has_data():
            return {"status": "insufficient_data", "matches": [], "considered": 0, "skipped": len(candidates)}

        my_vector = self.centroid(me)
        my_week = me.week()
        my_interests = me.interest_list()
        my_activities = me.activity_titles()
        my_interest_vectors = self.interest_vectors(my_interests) if my_interests else None
        my_activity_vectors = self.encoder.encode(my_activities) if my_activities else None

        results, skipped = [], 0
        for other in candidates:
            if other.id == me.id or not other.has_data():
                skipped += 1
                continue

            components = {"semantic": None, "schedule": None, "interests": None}
            factors = {"sharedInterests": [], "similarActivities": [], "sharedTimesOfDay": [], "sharedDays": []}

            other_vector = self.centroid(other)
            if my_vector is not None and other_vector is not None:
                components["semantic"] = max(0.0, float(my_vector @ other_vector))

            other_week = other.week()
            if my_week is not None and other_week is not None:
                components["schedule"] = cosine(my_week.ravel(), other_week.ravel())
                # Only slots both use count: the same part of the same day.
                shared = (my_week > 0) & (other_week > 0)
                factors["sharedTimesOfDay"] = [PARTS_OF_DAY[i][0] for i in np.flatnonzero(shared.any(axis=0))]
                factors["sharedDays"] = [DAYS[i] for i in np.flatnonzero(shared.any(axis=1))]

            other_interests = other.interest_list()
            if my_interest_vectors is not None and other_interests:
                score, pairs = soft_overlap(my_interests, my_interest_vectors, other_interests,
                                            self.interest_vectors(other_interests), INTEREST_MATCH)
                components["interests"] = score
                factors["sharedInterests"] = pairs[:5]

            other_activities = other.activity_titles()
            if my_activity_vectors is not None and other_activities:
                _, pairs = soft_overlap(my_activities, my_activity_vectors, other_activities,
                                        self.encoder.encode(other_activities), ACTIVITY_MATCH)
                factors["similarActivities"] = pairs[:3]

            used = {name: weight for name, weight in self.weights.items() if components[name] is not None}
            if not used:
                skipped += 1
                continue
            total = sum(used.values())
            score = sum(weight * components[name] for name, weight in used.items()) / total
            results.append({
                "userId": other.id,
                "score": round(score, 4),
                "components": {name: None if value is None else round(value, 4) for name, value in components.items()},
                "weightsUsed": {name: round(weight / total, 4) for name, weight in used.items()},
                "factors": factors,
            })

        results.sort(key=lambda item: (-item["score"], item["userId"]))
        ranked = [item for item in results if item["score"] >= min_score][:limit]
        return {"status": "ok", "matches": ranked, "considered": len(results), "skipped": skipped}


def cosine(first: np.ndarray, second: np.ndarray) -> float:
    norms = float(np.linalg.norm(first) * np.linalg.norm(second))
    return float(first @ second) / norms if norms > 0 else 0.0


def soft_overlap(mine: list[str], my_vectors: np.ndarray, theirs: list[str], their_vectors: np.ndarray,
                 threshold: float) -> tuple[float, list[dict]]:
    """Pairs each item with at most one close item of the other list, closest pairs first; the score
    is the share of both lists that got a partner (2 * pairs / all items), so it is exactly the
    pairs reported."""
    similarity = my_vectors @ their_vectors.T
    for i, text in enumerate(mine):  # the same words always count, whatever the model says
        for j, other in enumerate(theirs):
            if text.lower() == other.lower():
                similarity[i, j] = 1.0

    pairs, used_mine, used_theirs = [], set(), set()
    for flat in np.argsort(-similarity, axis=None):
        i, j = divmod(int(flat), len(theirs))
        if similarity[i, j] < threshold:
            break
        if i in used_mine or j in used_theirs:
            continue
        used_mine.add(i)
        used_theirs.add(j)
        pairs.append({"mine": mine[i], "theirs": theirs[j], "similarity": round(float(similarity[i, j]), 4)})
    return 2 * len(pairs) / (len(mine) + len(theirs)), pairs
