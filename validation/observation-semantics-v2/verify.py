"""Verify current v2 assets and preserved v1 history, without services or secrets."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
V1 = ROOT.parent / "observation-semantics"
V1_MANIFEST_SHA256 = "afb4701fcdc28da8f3c9f8a77efda1eb20c9c8496d4e67d7340ccb871fe7187e"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fixed_path(folder, relative):
    path = (folder / relative).resolve()
    require(path.is_relative_to(folder.resolve()), "Path escapes frozen folder")
    require(path.is_file(), "Missing frozen file: " + relative)
    return path


def verify_historical_v1():
    original = V1 / "manifest.json"
    saved = ROOT / "baseline-v1" / "manifest-v1.json"
    require(digest(original) == digest(saved) == V1_MANIFEST_SHA256, "v1 manifest or historical manifest changed")
    manifest = json.loads(saved.read_text(encoding="utf-8"))
    for relative, expected in manifest["asset_files"].items():
        require(digest(fixed_path(V1, relative)) == expected, "Historical v1 asset changed: " + relative)
    require(len(manifest["source_files"]) == 14, "Historical source count changed")
    for relative, expected in manifest["source_files"].items():
        path = fixed_path(ROOT / "baseline-v1", relative)
        require(digest(path) == expected, "Historical v1 source changed: " + relative)
    return {"status": "PASS", "manifest_sha256": V1_MANIFEST_SHA256,
            "asset_files": len(manifest["asset_files"]), "source_snapshots": len(manifest["source_files"]),
            "scope": "Frozen v1 assets and saved old source bytes; does not assert v1 source matches current code"}


def verify_supplementary():
    reader = load("observation_v2_supplement_reader", V1 / "verify.py")
    rows = reader.read_rows(ROOT / "supplementary-pairs.jsonl")
    require([row["id"] for row in rows] == [f"X{i:02}" for i in range(1, 13)], "Supplementary IDs/count changed")
    pairs = {}
    for row in rows:
        require(set(row) == {"id", "pair_id", "context", "text", "expected_kind", "rationale", "risk_tags", "execution_policy"}, "Supplementary fields changed")
        require(row["context"] == "await_extra" and row["text"].strip() and row["rationale"], "Invalid supplementary context/text/rationale")
        require(row["expected_kind"] in {"FACT", "QUESTION", "UNCLEAR"} and row["risk_tags"], "Invalid supplementary gold")
        require(row["execution_policy"] == "FROZEN_NOT_SCHEDULED_NO_EXTRA_REAL_CALLS", "Supplementary real execution is not authorized")
        pairs.setdefault(row["pair_id"], []).append(row)
    require(set(pairs) == {f"P{i:02}" for i in range(1, 7)}, "Supplementary pair IDs changed")
    require(all(len(pair) == 2 and pair[0]["expected_kind"] != pair[1]["expected_kind"] for pair in pairs.values()), "Each contrast pair must have two distinct expected kinds")
    counts = dict(Counter(row["expected_kind"] for row in rows))
    require(counts == {"UNCLEAR": 6, "FACT": 5, "QUESTION": 1}, "Supplementary class counts changed")
    return {"pairs": len(pairs), "cases": len(rows), "counts": counts,
            "real_model_calls": 0, "included_in_primary_36_denominator": False}


def verify():
    history = verify_historical_v1()
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    require(manifest["asset_version"] == "observation-semantics-fixed-v2", "Incorrect v2 asset version")
    require(manifest["baseline_v1_manifest_sha256"] == V1_MANIFEST_SHA256, "Wrong v1 provenance")
    shared = manifest["shared_files"]
    for relative, expected in shared.items():
        require(digest(fixed_path(REPO, relative)) == expected, "Shared implementation changed: " + relative)
    common_adapter = V1 / "adapter"
    expected_common = {p.relative_to(REPO).as_posix() for p in common_adapter.iterdir() if p.suffix in {".java", ".py"}}
    require(expected_common.issubset(shared), "Shared adapter fingerprint coverage is incomplete")
    for name in ("cases.jsonl", "gold.jsonl"):
        require((ROOT / name).read_bytes() == (V1 / name).read_bytes(), "Primary v1 cases/gold must remain byte-identical")
    original_config = json.loads((V1 / "config.json").read_text(encoding="utf-8"))
    current_config = json.loads((ROOT / "config.json").read_text(encoding="utf-8"))
    require(current_config["asset_version"] == manifest["asset_version"], "v2 config version differs")
    metadata_only = {"asset_version", "status", "scope"}
    require({k: v for k, v in current_config.items() if k not in metadata_only}
            == {k: v for k, v in original_config.items() if k not in metadata_only}, "v2 model, budget or semantic contracts differ from v1")
    verifier = load("observation_v2_shared_core_verify", V1 / "verify.py")
    verifier.ROOT = ROOT
    verifier.REPO = REPO
    result = verifier.verify()
    result["historical_v1"] = history
    result["supplementary"] = verify_supplementary()
    result["shared_fingerprints"] = len(shared)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--historical-v1-only", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(verify_historical_v1() if args.historical_v1_only else verify(), ensure_ascii=False, indent=2))
    except (ValueError, KeyError, OSError, TypeError) as failure:
        raise SystemExit(f"FAIL: {failure}")
