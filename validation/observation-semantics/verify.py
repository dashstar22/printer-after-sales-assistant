"""Verify frozen evaluation assets and source provenance, entirely offline."""
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
KINDS = {"FACT", "QUESTION", "UNCLEAR"}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)


def read_rows(path):
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            raise ValueError(f"Blank row: {path.name}:{number}")
        rows.append(json.loads(line, object_pairs_hook=unique_object))
    if not rows or len({row["id"] for row in rows}) != len(rows):
        raise ValueError(f"Empty data or duplicate IDs: {path.name}")
    return rows


def require(value, message):
    if not value:
        raise ValueError(message)


def verify():
    manifest = read_json(ROOT / "manifest.json")
    require(manifest["status"] == "FROZEN_ASSETS_NOT_MODEL_RESULTS", "Incorrect manifest status")
    for folder, hashes in ((ROOT, manifest["asset_files"]), (REPO, manifest["source_files"])):
        for relative, expected in hashes.items():
            path = folder / relative
            require(path.resolve().is_relative_to(folder.resolve()), "Path escapes frozen root")
            require(path.is_file() and sha256(path) == expected, f"Hash mismatch: {relative}")

    cases = read_rows(ROOT / "cases.jsonl")
    gold = read_rows(ROOT / "gold.jsonl")
    config = read_json(ROOT / "config.json")
    require([x["id"] for x in cases] == [x["id"] for x in gold], "Case/gold IDs or order differ")
    require([x["id"] for x in cases] == [f"S{i:02}" for i in range(1, 37)]
            + [f"G{i:02}" for i in range(1, 17)], "Unexpected fixed IDs/order")
    counts = Counter()
    policies = {"NORMALIZED_CURRENT_USER_IF_VALID_FACT_AND_SERVER_RECHECK_PASSES", "PRESERVE", "DETERMINISTIC_LITERAL"}
    for case, expected in zip(cases, gold):
        require(set(case) == {"id", "split", "text", "context"}, f"Unexpected input fields: {case['id']}")
        require(isinstance(case["text"], str) and case["text"].strip(), "Blank case text")
        require(case["context"] in config["contexts"], "Unknown context")
        require(expected["rationale"] and expected["risk_tags"], "Missing gold rationale/tags")
        require(expected["expected_extra_policy"] in policies, "Invalid extra policy")
        require(type(expected["expected_router_semantic"]) is bool, "Invalid semantic flag")
        require(type(expected["expected_classifier_calls"]) is int, "Invalid call expectation")
        if case["split"] == "semantic":
            require(expected["expected_kind"] in KINDS, "Invalid semantic gold")
            require(case["context"] == "await_extra" and expected["expected_classifier_calls"] == 1
                    and expected["expected_router_semantic"]
                    and expected["expected_router_reason"] == "OBSERVATION_SEMANTIC_PROPOSAL", "Ineligible semantic gold")
            counts[expected["expected_kind"]] += 1
        else:
            require(case["split"] == "gate" and expected["expected_kind"] is None
                    and expected["expected_classifier_calls"] == 0, "Invalid gate gold")
            counts["gate"] += 1
    require(dict(counts) == {"FACT": 12, "QUESTION": 12, "UNCLEAR": 12, "gate": 16}, "Class counts differ")
    budget = config["budget"]
    require(budget["semantic_cases"] == budget["max_attempts"] == 36 and budget["gate_cases"] == 16, "Call budget differs")
    require(budget["max_request_bytes"] == 4096 and budget["max_total_request_bytes"] == 36 * 4096, "Byte budget differs")
    require(budget["max_output_tokens"] == 128 and budget["max_configured_output_tokens_total"] == 36 * 128, "Output budget differs")
    require(budget["max_concurrency"] == 1 and budget["retries"] == 0, "Concurrency/retry budget differs")
    model = config["model"]
    require(model["requested_model"] == "qwen-plus" and model["temperature"] == 0
            and model["max_tokens"] == 128 and model["timeout_ms"] == 5000
            and model["strict_json_schema"] and model["sdk_retries"] == model["application_retries"] == 0,
            "Model configuration differs")
    require(model["schema"]["properties"]["kind"]["enum"] == ["FACT", "QUESTION", "UNCLEAR"]
            and model["schema"]["required"] == ["kind"] and model["schema"]["additionalProperties"] is False,
            "Output schema differs")
    require(len(config["execution_order"]["compatibility_sentinel_ids"]) == 3
            and set(config["execution_order"]["compatibility_sentinel_ids"]) == {"S01", "S13", "S25"}, "Invalid sentinel IDs")
    lengths = [len(x["text"].encode("utf-8")) for x in cases if x["split"] == "semantic"]
    return {
        "status": "PASS", "asset_version": config["asset_version"], "cases": len(cases),
        "counts": dict(counts), "frozen_asset_files": len(manifest["asset_files"]),
        "source_fingerprints": len(manifest["source_files"]),
        "manifest_sha256": sha256(ROOT / "manifest.json"),
        "semantic_user_text_bytes_total": sum(lengths), "semantic_user_text_bytes_max": max(lengths),
        "full_serialized_request_bytes": "NOT_MEASURED_REQUIRES_FUTURE_ADAPTER_PREFLIGHT",
        "model_execution": "NOT_PERFORMED", "real_model_calls": 0,
    }


if __name__ == "__main__":
    try:
        print(json.dumps(verify(), ensure_ascii=False, indent=2))
    except (ValueError, KeyError, OSError, TypeError) as failure:
        raise SystemExit(f"FAIL: {failure}")
