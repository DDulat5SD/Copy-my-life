"""US-26 prototype: runs the real multilingual MiniLM ONNX model on fictional lifestyles and prints
embeddings facts, cosine similarities and rankings in English, Kazakh and Russian.
Usage: python test_similarity.py   (CML_AI_MODEL_DIR may point at the model folder)
"""

import time

import numpy as np

from ai_engine import Activity, LifestyleEncoder, LifestyleMatcher, Person, Routine

LIFESTYLES = {
    "A (en)": "Java, gym, reading, morning routine",
    "B (en)": "coding, fitness, books, morning routine",
    "C (en)": "gaming, movies, night routine",
    "D (en)": "studying programming, exercise, productivity",
    "B (kk)": "бағдарламалау, фитнес, кітап оқу, таңғы режим",
    "C (kk)": "компьютер ойындары, фильмдер, түнгі режим",
    "B (ru)": "программирование, фитнес, книги, утренний режим",
    "C (ru)": "компьютерные игры, фильмы, ночной режим",
    "empty": "",
}


def person(person_id, interests, routine_title, activities):
    return Person(id=person_id, interests=interests, routines=[Routine(
        title=routine_title, activities=[Activity(title, time, days) for title, time, days in activities])])


def main():
    started = time.perf_counter()
    encoder = LifestyleEncoder()
    print(f"model loaded from {encoder.model_dir} in {time.perf_counter() - started:.2f}s, dimension {encoder.dimension}")

    names = list(LIFESTYLES)
    started = time.perf_counter()
    vectors = encoder.encode([LIFESTYLES[name] for name in names])
    print(f"{encoder.texts_inferred} texts through the model in {time.perf_counter() - started:.3f}s")
    print("norms:", " ".join(f"{name}={np.linalg.norm(v):.3f}" for name, v in zip(names, vectors)))
    print("first 5 values of A:", np.round(vectors[0][:5], 4).tolist())

    print("\ncosine similarity to A (en):")
    scores = sorted(((float(vectors[0] @ v), name) for name, v in zip(names[1:], vectors[1:])), reverse=True)
    for score, name in scores:
        print(f"  {score:7.4f}  {name:7s}  {LIFESTYLES[name]!r}")

    print("\nmultilingual: the same lifestyle in other languages vs a different one")
    for language in ("kk", "ru"):
        same = float(vectors[names.index("B (en)")] @ vectors[names.index(f"B ({language})")])
        other = float(vectors[names.index("B (en)")] @ vectors[names.index(f"C ({language})")])
        print(f"  B(en)~B({language}) {same:.4f}   B(en)~C({language}) {other:.4f}")

    print("\ninterest pairs:")
    for first, second in [("gym", "фитнес"), ("reading", "кітап оқу"), ("books", "книги"),
                          ("coding", "программирование"), ("gym", "movies"), ("reading", "gaming")]:
        print(f"  {first!r} ~ {second!r}: {encoder.similarity(first, second):.4f}")

    print("\nhybrid ranking for A (semantic 60%, schedule 25%, interests 15%):")
    me = person(1, "Java, gym, reading", "Morning routine",
                [("Gym workout", "06:30", ["MON", "WED", "FRI"]), ("Read a book", "07:30", []), ("Java practice", "08:00", [])])
    candidates = [
        person(2, "coding, fitness, books", "Утренний режим",
               [("Фитнес", "06:00", ["MON", "WED", "FRI"]), ("Чтение книг", "07:00", []), ("Программирование", "08:30", [])]),
        person(3, "компьютер ойындары, фильмдер", "Түнгі режим",
               [("Ойын ойнау", "23:00", []), ("Фильм көру", "01:00", [])]),
        person(4, "programming, exercise, productivity", "Study day",
               [("Study programming", "14:00", []), ("Exercise", "18:00", ["TUE", "THU"])]),
        Person(id=5),  # nothing written: left out, not given a made-up score
        person(6, "", "", []),
    ]
    result = LifestyleMatcher(encoder).match(me, candidates)
    print(f"  status={result['status']} considered={result['considered']} skipped={result['skipped']}")
    for item in result["matches"]:
        c = item["components"]
        print(f"  user {item['userId']}: score {item['score']:.4f}  semantic={c['semantic']} schedule={c['schedule']} "
              f"interests={c['interests']}  weights={item['weightsUsed']}")
        f = item["factors"]
        print(f"      interests {[(p['mine'], p['theirs'], p['similarity']) for p in f['sharedInterests']]}")
        print(f"      activities {[(p['mine'], p['theirs'], p['similarity']) for p in f['similarActivities']]}")
        print(f"      times {f['sharedTimesOfDay']} days {len(f['sharedDays'])}")

    print("\nempty description:", LifestyleMatcher(encoder).match(Person(id=9), candidates)["status"],
          "| similarity('', 'gym') =", encoder.similarity("", "gym"))


if __name__ == "__main__":
    main()
