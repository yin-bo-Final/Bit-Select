"""Verify committed ranking provenance without models, API calls or third-party packages."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

DATASETS = (
    ("real-candidates.jsonl", ".", "catalog-target"),
    ("fixtures/synthetic-candidates.jsonl", "fixtures", "synthetic"),
)


def verify(directory: Path) -> None:
    for dataset_name, artifact_directory, label_source in DATASETS:
        raw = (directory / dataset_name).read_bytes()
        if not raw or b"\r" in raw or not raw.endswith(b"\n"):
            raise ValueError(f"{dataset_name}: expected nonempty UTF-8/LF JSONL with a final newline")
        rows = [json.loads(line) for line in raw.decode("utf-8").splitlines()]
        if any(row.get("labelSource") != label_source for row in rows):
            raise ValueError(f"{dataset_name}: label provenance changed")
        digest = hashlib.sha256(raw).hexdigest()
        artifact_path = directory / artifact_directory
        report = json.loads((artifact_path / "evaluation.json").read_text(encoding="utf-8"))
        model = json.loads((artifact_path / "model.json").read_text(encoding="utf-8"))
        for name, metadata in (("evaluation.json", report), ("model.json", model["bit_select_metadata"])):
            if metadata.get("datasetSha256") != digest:
                raise ValueError(f"{artifact_directory}/{name}: datasetSha256 does not match {dataset_name}")
            if metadata.get("labelSource") != label_source:
                raise ValueError(f"{artifact_directory}/{name}: labelSource does not match {dataset_name}")
        if sum(split["rows"] for split in report["splits"].values()) != len(rows):
            raise ValueError(f"{dataset_name}: evaluation split row counts do not cover the dataset")
        print(f"PASS: {dataset_name}: {len(rows)} rows, SHA-256 {digest}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    verify(args.directory)


if __name__ == "__main__":
    main()
