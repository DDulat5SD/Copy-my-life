"""US-26: the engine with the real ONNX model. Needs the model (CML_AI_MODEL_DIR)."""

import numpy as np
import pytest

from ai_engine import (ACTIVITY_MATCH, INTEREST_MATCH, INTEREST_TEMPLATE, Activity, LifestyleEncoder,
                       LifestyleMatcher, Person, Routine, part_of_day)


@pytest.fixture(scope="session")
def encoder():
    return LifestyleEncoder()


@pytest.fixture()
def matcher(encoder):
    return LifestyleMatcher(encoder)


def person(person_id, interests="", activities=(), title="", **profile):
    return Person(id=person_id, interests=interests, routines=[Routine(
        title=title, activities=[Activity(name, time, list(days)) for name, time, days in activities])], **profile)


A = "Java, gym, reading, morning routine"
B = "coding, fitness, books, morning routine"
C = "gaming, movies, night routine"
D = "studying programming, exercise, productivity"


# ---- embeddings ----

def test_embeddings_are_normalised_and_come_from_the_model(encoder):
    before = encoder.texts_inferred
    vectors = encoder.encode(["morning yoga and tea " + str(np.random.rand()), "late night gaming", ""])
    assert encoder.texts_inferred - before == 2, "two new texts went through the model"
    assert vectors.shape == (3, 384)
    assert np.allclose(np.linalg.norm(vectors[:2], axis=1), 1.0, atol=1e-5)
    assert not vectors[2].any(), "an empty text is a zero vector"
    assert abs(float(vectors[0] @ vectors[1])) < 0.99


def test_mean_pooling_ignores_padding(encoder):
    alone = encoder._infer(["gym"])[0]
    long = "a much longer sentence about reading books every evening after work"
    padded = encoder._infer(["gym", long])[0]
    # Averaging the padding positions too would move the vector far away (cosine 0.88); with the
    # mask only the quantisation of the bigger batch is left (0.996).
    encoding = encoder.tokenizer.encode_batch(["gym", long])
    feeds = {name: np.array([getattr(item, attr) for item in encoding], dtype=np.int64) for name, attr in
             (("input_ids", "ids"), ("attention_mask", "attention_mask"), ("token_type_ids", "type_ids"))}
    naive = encoder.session.run(["last_hidden_state"], feeds)[0][0].mean(axis=0)
    naive /= np.linalg.norm(naive)
    assert float(alone @ padded) > 0.99
    assert float(alone @ padded) > float(alone @ naive) + 0.05


def test_a_text_gets_the_same_vector_whatever_else_is_encoded(encoder):
    text = "evening swimming and jazz " + str(np.random.rand())
    alone = encoder.encode([text])[0]
    encoder._cache.clear()
    together = encoder.encode(["a much longer sentence about reading books every evening after work", text])[1]
    assert np.allclose(alone, together, atol=1e-6)


def test_same_text_gives_the_same_vector(encoder):
    first = encoder._infer([B])[0]
    assert np.allclose(first, encoder.encode([B])[0], atol=1e-6)


def test_empty_text_is_similar_to_nothing(encoder):
    assert encoder.similarity("", A) == 0.0
    assert encoder.similarity("   ", "") == 0.0


# ---- semantic and multilingual ----

def test_similar_lifestyles_score_higher_than_different_ones(encoder):
    assert encoder.similarity(A, B) > encoder.similarity(A, D) > encoder.similarity(A, C)


@pytest.mark.parametrize("same,different", [
    ("бағдарламалау, фитнес, кітап оқу, таңғы режим", "компьютер ойындары, фильмдер, түнгі режим"),
    ("программирование, фитнес, книги, утренний режим", "компьютерные игры, фильмы, ночной режим"),
])
def test_the_same_lifestyle_in_kazakh_and_russian_is_recognised(encoder, same, different):
    assert encoder.similarity(B, same) > encoder.similarity(B, different) + 0.25


INTEREST_PAIRS = [("gym", "фитнес"), ("gym", "fitness"), ("reading", "кітап оқу"), ("books", "книги"),
                  ("coding", "программирование"), ("java", "programming"), ("running", "бег"),
                  ("music", "музыка"), ("cooking", "тамақ пісіру"), ("coding", "бағдарламалау"),
                  ("movies", "фильмдер"), ("travel", "саяхат"), ("chess", "шахмат")]
UNRELATED_INTERESTS = [("java", "фильмдер"), ("gym", "movies"), ("reading", "gaming"), ("java", "football"),
                       ("coding", "cooking"), ("yoga", "программирование"), ("books", "gym"),
                       ("music", "chess"), ("python", "фильмдер"), ("chess", "фитнес")]


def test_interest_threshold_separates_the_labelled_pairs(encoder):
    def score(a, b):
        return encoder.similarity(INTEREST_TEMPLATE.format(a), INTEREST_TEMPLATE.format(b))

    assert min(score(a, b) for a, b in INTEREST_PAIRS) >= INTEREST_MATCH
    assert max(score(a, b) for a, b in UNRELATED_INTERESTS) < INTEREST_MATCH


def test_activity_threshold_keeps_unrelated_activities_apart(encoder):
    unrelated = [("Gym workout", "Фильм көру"), ("Read a book", "Ойын ойнау"), ("Java practice", "Breakfast"),
                 ("Meditation", "Football match"), ("Morning run", "Watch movies"), ("Sleep", "Programming")]
    related = [("Read a book", "Чтение книг"), ("Meditation", "Медитация"), ("Gym workout", "Exercise")]
    assert max(encoder.similarity(a, b) for a, b in unrelated) < ACTIVITY_MATCH
    assert min(encoder.similarity(a, b) for a, b in related) >= ACTIVITY_MATCH


# ---- schedule ----

def test_parts_of_day_wrap_around_midnight():
    assert [part_of_day(m) for m in (4 * 60, 11 * 60 + 59, 12 * 60, 17 * 60, 22 * 60, 23 * 60, 60, 3 * 60 + 59)] == \
           [0, 0, 1, 2, 3, 3, 3, 3]


def test_an_activity_without_repeat_days_is_planned_every_day():
    week = person(1, activities=[("Run", "06:00", [])]).week()
    assert week[:, 0].tolist() == [1] * 7 and week.sum() == 7
    assert person(1, activities=[("Run", "06:00", ["MON", "FRI"])]).week().sum() == 2
    assert person(1, activities=[("Run", None, [])]).week() is None, "untimed activities have no schedule"


# ---- ranking and the hybrid score ----

ME = dict(interests="Java, gym, reading", title="Morning routine",
          activities=[("Gym workout", "06:30", ["MON", "WED", "FRI"]), ("Read a book", "07:30", []), ("Java practice", "08:00", [])])


def candidates():
    return [
        person(3, "компьютер ойындары, фильмдер", [("Ойын ойнау", "23:00", []), ("Фильм көру", "01:00", [])], "Түнгі режим"),
        person(2, "coding, fitness, books", [("Фитнес", "06:00", ["MON", "WED", "FRI"]), ("Чтение книг", "07:00", []),
                                             ("Программирование", "08:30", [])], "Утренний режим"),
        person(4, "programming, exercise, productivity", [("Study programming", "14:00", []), ("Exercise", "18:00", ["TUE"])],
               "Study day"),
    ]


def test_ranks_people_by_lifestyle_with_scores_and_factors(matcher):
    result = matcher.match(person(1, **ME), candidates())
    assert result["status"] == "ok"
    assert [m["userId"] for m in result["matches"]] == [2, 4, 3]
    scores = [m["score"] for m in result["matches"]]
    assert scores == sorted(scores, reverse=True) and all(0 <= s <= 1 for s in scores)
    best = result["matches"][0]
    assert best["components"]["schedule"] == 1.0
    assert best["factors"]["sharedTimesOfDay"] == ["morning"]
    assert {p["theirs"] for p in best["factors"]["sharedInterests"]} >= {"books", "fitness"}
    assert result["matches"][2]["factors"]["sharedInterests"] == []


def test_the_score_is_the_weighted_sum_of_its_parts(matcher):
    for item in matcher.match(person(1, **ME), candidates())["matches"]:
        parts, weights = item["components"], item["weightsUsed"]
        assert abs(sum(weights.values()) - 1) < 1e-3
        assert abs(sum(weights[k] * parts[k] for k in weights) - item["score"]) < 1e-3


def test_missing_parts_are_left_out_not_invented(matcher):
    me = person(1, "reading, chess")  # no routine at all
    other = person(2, "books, шахмат", activities=[("Chess club", "19:00", [])])
    item = matcher.match(me, [other])["matches"][0]
    assert item["components"]["schedule"] is None
    assert set(item["weightsUsed"]) == {"semantic", "interests"}
    assert item["weightsUsed"]["semantic"] == pytest.approx(0.8, abs=1e-3)


def test_people_without_any_data_are_skipped(matcher):
    result = matcher.match(person(1, **ME), [Person(id=5), person(6), candidates()[0]])
    assert [m["userId"] for m in result["matches"]] == [3]
    assert result["skipped"] == 2


def test_the_user_is_never_matched_with_themself(matcher):
    result = matcher.match(person(1, **ME), [person(1, **ME), *candidates()])
    assert 1 not in [m["userId"] for m in result["matches"]]


def test_a_user_without_data_is_told_so(matcher):
    result = matcher.match(Person(id=9, bio="   "), candidates())
    assert result == {"status": "insufficient_data", "matches": [], "considered": 0, "skipped": 3}


def test_limit_and_minimum_score(matcher):
    result = matcher.match(person(1, **ME), candidates(), limit=1)
    assert [m["userId"] for m in result["matches"]] == [2]
    assert all(m["score"] >= 0.4 for m in matcher.match(person(1, **ME), candidates(), min_score=0.4)["matches"])


# ---- updates change the result ----

def test_changed_interests_change_the_ranking(matcher):
    gamers = person(7, "computer games, films")
    sporty = person(8, "fitness, running")
    assert matcher.match(person(1, "gym, jogging"), [gamers, sporty])["matches"][0]["userId"] == 8
    assert matcher.match(person(1, "video games, cinema"), [gamers, sporty])["matches"][0]["userId"] == 7


def test_changed_routine_times_change_the_ranking(matcher):
    early = person(7, activities=[("Workout", "06:00", [])])
    late = person(8, activities=[("Workout", "23:00", [])])
    assert matcher.match(person(1, activities=[("Workout", "06:30", [])]), [early, late])["matches"][0]["userId"] == 7
    assert matcher.match(person(1, activities=[("Workout", "23:30", [])]), [early, late])["matches"][0]["userId"] == 8


def test_one_interest_is_shared_with_one_other_at_most(matcher):
    me = person(1, "gym, fitness, workout")
    other = person(2, "спорт")
    item = matcher.match(me, [other])["matches"][0]
    assert len(item["factors"]["sharedInterests"]) == 1
    assert item["components"]["interests"] == pytest.approx(2 * 1 / 4)
