"""Train LambdaMART on exported retrieval candidates; never mix queries/products across splits."""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import random

FEATURES = ["bm25Score", "cosineScore", "rerankScore", "exactProductMatch"]
GAINS = [0, 1, 3, 7]

def load_rows(path: Path, label_source: str):
    raw = path.read_bytes()
    assert b"\r" not in raw, "Ranking datasets must use LF newlines so Git checkout preserves their SHA-256"
    rows = []
    for number, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        assert row.get("queryId") and row.get("productGroup"), f"line {number}: queryId/productGroup required"
        assert row.get("labelSource") == label_source, f"line {number}: labelSource must explicitly be {label_source}"
        if label_source != "synthetic":
            assert row.get("featureSource") == "retrieval", f"line {number}: only real retrieval features are accepted"
        f = row.get("features")
        assert isinstance(f, list) and len(f) == 4 and all(isinstance(x, (int, float)) and math.isfinite(x) for x in f), f"line {number}: four finite numeric features required"
        assert isinstance(row.get("label"), int) and 0 <= row["label"] <= 3, f"line {number}: label must be an integer 0..3"
        rows.append(row)
    assert rows, "Dataset is empty"
    by_query = defaultdict(list)
    for row in rows:
        by_query[str(row["queryId"])].append(row)
    assert all(len(group) >= 2 for group in by_query.values()), "Each query needs at least two candidates"
    assert all(len({row['label'] for row in group}) >= 2 for group in by_query.values()), "Each query needs at least two relevance levels"
    return rows

def split_rows(rows, seed: int):
    # A query may touch several product families. All of its families are joined,
    # so neither query IDs nor product families can leak across the three splits.
    parent = {}
    def find(item):
        parent.setdefault(item, item)
        if parent[item] != item:
            parent[item] = find(parent[item])
        return parent[item]
    def union(a, b):
        parent[find(a)] = find(b)
    for row in rows:
        union("q:" + str(row["queryId"]), "p:" + str(row["productGroup"]))
    components = defaultdict(list)
    for row in rows:
        components[find("q:" + str(row["queryId"]))].append(row)
    keys = sorted(components)
    assert len(keys) >= 6, ("Need at least six independent query/product components. "
                            "Partition product families before collecting retrieval candidates; "
                            "do not randomly split rows from overlapping products.")
    random.Random(seed).shuffle(keys)
    test_count = max(1, round(len(keys) * 0.2))
    validation_count = max(1, round(len(keys) * 0.2))
    selected = {"test": keys[:test_count], "validation": keys[test_count:test_count + validation_count], "train": keys[test_count + validation_count:]}
    splits = {name: sorted([row for key in group for row in components[key]], key=lambda row: (str(row["queryId"]), str(row.get("candidateId", "")))) for name, group in selected.items()}
    for left, right in (("train", "validation"), ("train", "test"), ("validation", "test")):
        for field in ("queryId", "productGroup"):
            assert not ({str(r[field]) for r in splits[left]} & {str(r[field]) for r in splits[right]}), f"{field} leakage detected"
    return splits

def grouped(rows):
    sizes = []
    previous = None
    for row in rows:
        if row["queryId"] != previous:
            sizes.append(0)
            previous = row["queryId"]
        sizes[-1] += 1
    return sizes

def ndcg(rows, scores, k):
    queries = defaultdict(list)
    for row, score in zip(rows, scores):
        queries[str(row["queryId"])].append((float(score), int(row["label"])))
    metrics = []
    for pairs in queries.values():
        labels = [label for _, label in sorted(pairs, key=lambda pair: pair[0], reverse=True)[:k]]
        ideal = sorted((label for _, label in pairs), reverse=True)[:k]
        dcg = sum(GAINS[label] / math.log2(index + 2) for index, label in enumerate(labels))
        idcg = sum(GAINS[label] / math.log2(index + 2) for index, label in enumerate(ideal))
        metrics.append(dcg / idcg if idcg else 0.0)
    return sum(metrics) / len(metrics)

def predict_dump(model, features):
    total = 0.0
    for tree in model["tree_info"]:
        node = tree["tree_structure"]
        while "leaf_value" not in node:
            assert node["decision_type"] == "<=", "Java scorer supports numeric splits only"
            node = node["left_child" if features[node["split_feature"]] <= node["threshold"] else "right_child"]
        total += node["leaf_value"]
    return total

def train(args):
    import lightgbm as lgb
    import numpy as np

    if args.label_source == "synthetic":
        assert args.allow_synthetic, "Synthetic training requires --allow-synthetic and must remain a test fixture"
        assert "fixtures" in args.output_dir.parts, "Synthetic models may only be written under a fixtures directory"
    rows = load_rows(args.input, args.label_source)
    splits = split_rows(rows, args.seed)
    datasets = {}
    for name in ("train", "validation"):
        part = splits[name]
        datasets[name] = lgb.Dataset(np.asarray([r["features"] for r in part]), label=[r["label"] for r in part], group=grouped(part), feature_name=FEATURES, reference=datasets.get("train"))
    params = {"objective": "lambdarank", "metric": "ndcg", "ndcg_eval_at": [5, 10], "label_gain": GAINS,
              "learning_rate": 0.05, "num_leaves": 7, "max_depth": 3, "min_data_in_leaf": 4,
              "lambda_l2": 1.0, "lambdarank_truncation_level": 13, "num_threads": 2,
              "verbosity": -1, "seed": args.seed, "deterministic": True, "force_col_wise": True,
              "use_missing": False, "zero_as_missing": False}
    booster = lgb.train(params, datasets["train"], num_boost_round=args.rounds,
                        valid_sets=[datasets["validation"]], valid_names=["validation"],
                        callbacks=[lgb.early_stopping(20, verbose=False)])
    model = booster.dump_model(num_iteration=booster.best_iteration)
    metadata = {"labelSource": args.label_source, "featureSource": "synthetic" if args.label_source == "synthetic" else "retrieval",
                "datasetSha256": hashlib.sha256(args.input.read_bytes()).hexdigest(), "featureOrder": FEATURES,
                "trainedAt": datetime.now(timezone.utc).isoformat(), "lightgbmVersion": lgb.__version__,
                "qualityClaim": "held_out_human_evaluation" if args.label_source == "human" else "pipeline_validation_only"}
    model["bit_select_metadata"] = metadata
    report = {**metadata, "bestIteration": booster.best_iteration, "seed": args.seed,
              "splitMethod": "query/product bipartite connected components; test never used for early stopping", "splits": {}, "testMetrics": {}}
    for name, part in splits.items():
        report["splits"][name] = {"rows": len(part), "queries": len({r["queryId"] for r in part}),
                                 "queryIds": sorted({str(r["queryId"]) for r in part}), "productGroups": sorted({str(r["productGroup"]) for r in part})}
    test = splits["test"]
    predictions = booster.predict(np.asarray([r["features"] for r in test]), num_iteration=booster.best_iteration)
    for k in (5, 10):
        learned = ndcg(test, predictions, k)
        baseline = ndcg(test, [r["features"][2] for r in test], k)
        report["testMetrics"][f"ndcg@{k}"] = {"lambdaMART": learned, "rerankerBaseline": baseline, "delta": learned - baseline}
    if args.label_source != "human":
        report["limitations"] = ["No human relevance labels. These metrics do not establish user-facing quality improvement."]
        if args.label_source == "reranker-weak":
            report["limitations"].append("Reranker-derived labels can favor the teacher; the reranker score is also an input feature.")
        elif args.label_source == "catalog-target":
            report["limitations"].append("Catalog identity rules are weak supervision. Product-specific generated queries and exactProductMatch may make the benchmark easier than open-ended shopping conversations.")
        else:
            report["limitations"].append("Synthetic features and labels validate only serialization and training mechanics.")
    probes = [r["features"] for r in test[:20]] + [[0.0, 0.0, 0.0, 0.0], [1.0, 1.0, 1.0, 1.0]]
    for tree in model["tree_info"][:3]:
        node = tree["tree_structure"]
        if "split_feature" in node:
            for value in (np.nextafter(node["threshold"], -np.inf), node["threshold"], np.nextafter(node["threshold"], np.inf)):
                probe = [1.0, 0.5, 0.5, 0.0]
                probe[node["split_feature"]] = float(value)
                probes.append(probe)
    expected = booster.predict(np.asarray(probes), num_iteration=booster.best_iteration)
    assert all(abs(predict_dump(model, f) - float(value)) < 1e-10 for f, value in zip(probes, expected)), "Tree dump predictions differ from native LightGBM"
    fixtures = {"featureOrder": FEATURES, "modelFile": "model.json", "labelSource": args.label_source,
                "tolerance": 1e-10, "cases": [{"features": f, "expectedScore": float(value)} for f, value in zip(probes, expected)]}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, content in (("model.json", model), ("evaluation.json", report), ("equivalence-fixtures.json", fixtures)):
        (args.output_dir / name).write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"model": str(args.output_dir / "model.json"), "labelSource": args.label_source,
                      "qualityClaim": metadata["qualityClaim"], "testMetrics": report["testMetrics"]}, ensure_ascii=False, indent=2))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--label-source", choices=("human", "reranker-weak", "catalog-target", "synthetic"), required=True)
    parser.add_argument("--allow-synthetic", action="store_true")
    parser.add_argument("--seed", type=int, default=20260927)
    parser.add_argument("--rounds", type=int, default=200)
    train(parser.parse_args())

if __name__ == "__main__":
    main()
