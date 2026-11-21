"""CopyMyLife routine assistant: semantic search with the same multilingual MiniLM model.

The routine assistant in the backend uses it for two things only: finding public routines whose
meaning is close to what the user wants (examples to take ideas from) and checking whether a
routine has an activity close to each of the user's goals. Everything else the assistant does
(scheduling, reviews, duration analysis, summaries) is fixed rules in the backend, so it keeps
working without this service.

The model is an embedding model: it measures how close two texts are in meaning (cosine
similarity of their sentence embeddings, -1..1, in practice 0..1 for these texts). It does not
write text, and a score is a closeness of meaning, not a probability or a recommendation.
"""

from __future__ import annotations

from ai_engine import LifestyleEncoder, clean_text


def rank(encoder: LifestyleEncoder, queries: list[str], items: list[tuple[int, str]],
         limit: int = 5, min_score: float = 0.0) -> list[list[dict]]:
    """For each query, the items closest in meaning, best first: [{"id", "score"}].

    Items with an empty text, and every item for an empty query, are left out; ties are broken by
    the smaller id so the same request always gives the same answer."""
    texts = [clean_text(text) for _, text in items]
    usable = [index for index, text in enumerate(texts) if text]
    results: list[list[dict]] = []
    if not usable:
        return [[] for _ in queries]

    item_vectors = encoder.encode([texts[index] for index in usable])
    query_vectors = encoder.encode([clean_text(query) for query in queries])

    for row, query in enumerate(queries):
        if not clean_text(query):
            results.append([])
            continue
        scores = item_vectors @ query_vectors[row]
        ranked = sorted(((float(scores[position]), items[index][0]) for position, index in enumerate(usable)),
                        key=lambda pair: (-pair[0], pair[1]))
        results.append([{"id": item_id, "score": round(max(-1.0, min(1.0, score)), 4)}
                        for score, item_id in ranked if score >= min_score][:limit])
    return results
