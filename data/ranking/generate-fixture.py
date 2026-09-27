"""Generate explicitly synthetic candidates only to test the offline training/export pipeline."""
import json
from pathlib import Path
import random

random.seed(20260927)
destination = Path(__file__).parent / "fixtures" / "synthetic-candidates.jsonl"
destination.parent.mkdir(parents=True, exist_ok=True)
rows = []
for family in range(20):
    for query in range(3):
        for candidate in range(12):
            bm25, cosine, rerank = random.uniform(0, 9), random.random(), random.random()
            exact = int(candidate == 0)
            utility = 0.3 * (bm25 / 9) + 0.2 * cosine + 0.4 * rerank + 0.1 * exact
            label = 0 if utility < 0.3 else 1 if utility < 0.48 else 2 if utility < 0.66 else 3
            rows.append({"queryId": f"synthetic-q-{family}-{query}", "productGroup": f"synthetic-family-{family}",
                         "candidateId": f"synthetic-c-{family}-{query}-{candidate}",
                         "features": [bm25, cosine, rerank, exact], "label": label,
                         "labelSource": "synthetic", "featureSource": "synthetic"})
destination.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8", newline="\n")
print(f"Wrote {len(rows)} SYNTHETIC fixture candidates. This is not a real ranking dataset.")
