"""Offline validation and scoring only: no model, HTTP, database, or secret access."""

import argparse
from collections import Counter
import json
from pathlib import Path
import sys


KINDS = ("FACT", "QUESTION", "UNCLEAR")
FAILURES = frozenset({
    "OBSERVATION_INVALID_RESPONSE", "OBSERVATION_UNAVAILABLE",
    "OBSERVATION_BUDGET_STOPPED", "OBSERVATION_BUDGET_EXHAUSTED",
    "OBSERVATION_DISABLED", "OBSERVATION_CONFIG_INVALID", "OBSERVATION_INPUT_INVALID",
})
OUTCOMES = KINDS + ("CONTROLLED_FAILURE", "SKIPPED")
RECORD_FIELDS = frozenset({
    "id", "status", "attempts", "request_bytes", "input_tokens", "output_tokens",
    "elapsed_ms", "response_model", "finish_reason", "response_text", "tool_call_count",
    "effective_kind", "reason", "skip_reason",
})
RESULT_FIELDS = frozenset({
    "schema_version", "execution", "run_id", "manifest_sha256", "requested_model", "records",
})
DEFAULT_BUDGET = {
    "max_attempts": 36, "max_request_bytes": 4096,
    "max_total_request_bytes": 147456, "max_output_tokens": 128,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key: " + key)
        result[key] = value
    return result


def parse_json(text):
    def reject_constant(value):
        raise ValueError("non-finite JSON number: " + value)
    try:
        return json.loads(text, object_pairs_hook=unique_object, parse_constant=reject_constant)
    except json.JSONDecodeError as exc:
        raise ValueError("invalid JSON: " + exc.msg) from exc


def read_jsonl(path):
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        require(bool(line.strip()), f"{path.name}:{number}: blank JSONL row")
        rows.append(parse_json(line))
    return rows


def integer(value, name, maximum=None, nullable=False):
    if value is None and nullable:
        return
    require(type(value) is int and value >= 0, name + " must be a nonnegative integer")
    if maximum is not None:
        require(value <= maximum, name + " exceeds budget")


def optional_text(value, name):
    require(value is None or isinstance(value, str), name + " must be text or null")


def rate(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator,
            "value": numerator / denominator if denominator else None}


def score(cases, gold, results, manifest_sha256, config=None):
    """Reject malformed evidence; controlled failures never become correct UNCLEAR."""
    config = config or {}
    budget = DEFAULT_BUDGET | config.get("budget", {})
    for key in DEFAULT_BUDGET:
        integer(budget[key], "budget." + key)
    if "max_concurrency" in budget:
        require(budget["max_concurrency"] == 1, "only concurrency 1 is planned")
    if "retries" in budget:
        require(budget["retries"] == 0, "retries must be zero")

    case_map = {}
    for row in cases:
        require(isinstance(row, dict), "case must be an object")
        case_id = row.get("id")
        require(isinstance(case_id, str) and bool(case_id.strip()), "case id must be nonempty")
        require(case_id not in case_map, "duplicate case id: " + case_id)
        require(row.get("split") in ("semantic", "gate"), "unknown case split: " + case_id)
        require(isinstance(row.get("text"), str) and bool(row["text"].strip()), "empty case text: " + case_id)
        case_map[case_id] = row
    expected = {}
    gold_ids = set()
    for row in gold:
        require(isinstance(row, dict), "gold must be an object")
        case_id = row.get("id")
        require(isinstance(case_id, str) and case_id in case_map, "unknown gold id")
        require(case_id not in gold_ids, "duplicate gold id: " + case_id)
        gold_ids.add(case_id)
        kind = row.get("expected_kind")
        require("expected_kind" in row, "gold missing expected_kind: " + case_id)
        if case_map[case_id]["split"] == "semantic":
            require(kind in KINDS, "invalid semantic gold: " + case_id)
            expected[case_id] = kind
        else:
            require(kind is None, "gate gold must have null kind: " + case_id)
    require(gold_ids == set(case_map), "gold must cover every case exactly once")
    require(bool(expected), "semantic set cannot be empty")
    if "semantic_cases" in budget:
        require(budget["semantic_cases"] == len(expected), "semantic case count differs from frozen budget")

    require(isinstance(results, dict) and set(results) == RESULT_FIELDS, "invalid result envelope fields")
    require(type(results["schema_version"]) is int and results["schema_version"] == 1, "unsupported schema_version")
    require(results["execution"] in ("real_model", "synthetic_test"), "invalid execution source")
    require(isinstance(results["run_id"], str) and bool(results["run_id"].strip()), "run_id must be nonempty")
    require(results["manifest_sha256"] == manifest_sha256, "manifest SHA256 mismatch")
    model = config.get("model", {}).get("requested_model", "qwen-plus")
    require(results["requested_model"] == model, "requested model differs from frozen config")
    require(isinstance(results["records"], list), "records must be a list")

    matrix = {kind: {outcome: 0 for outcome in OUTCOMES} for kind in KINDS}
    seen = set()
    reasons = Counter()
    skip_reasons = Counter()
    response_models = Counter()
    attempts = request_bytes = valid = matched = failures = skipped = 0
    input_known = output_known = input_unknown = output_unknown = 0
    for row in results["records"]:
        require(isinstance(row, dict) and set(row) == RECORD_FIELDS, "invalid record fields")
        case_id = row["id"]
        require(isinstance(case_id, str) and case_id in expected, "unknown or gate result id")
        require(case_id not in seen, "duplicate result id: " + case_id)
        seen.add(case_id)
        require(row["status"] in ("COMPLETED", "SKIPPED"), "invalid status: " + case_id)
        integer(row["attempts"], case_id + ".attempts", maximum=1)
        integer(row["request_bytes"], case_id + ".request_bytes", maximum=budget["max_request_bytes"])
        integer(row["input_tokens"], case_id + ".input_tokens", nullable=True)
        integer(row["output_tokens"], case_id + ".output_tokens", maximum=budget["max_output_tokens"], nullable=True)
        integer(row["elapsed_ms"], case_id + ".elapsed_ms", nullable=True)
        integer(row["tool_call_count"], case_id + ".tool_call_count", nullable=True)
        for key in ("response_model", "finish_reason", "response_text", "effective_kind", "reason", "skip_reason"):
            optional_text(row[key], case_id + "." + key)
        attempts += row["attempts"]
        request_bytes += row["request_bytes"]
        if row["status"] == "SKIPPED":
            require(row["attempts"] == row["request_bytes"] == 0, "SKIPPED cannot consume a request")
            require(all(row[key] is None for key in RECORD_FIELDS - {"id", "status", "attempts", "request_bytes", "skip_reason"}), "SKIPPED cannot contain response or usage")
            require(isinstance(row["skip_reason"], str) and bool(row["skip_reason"].strip()), "SKIPPED needs skip_reason")
            skipped += 1
            skip_reasons[row["skip_reason"]] += 1
            outcome = "SKIPPED"
        else:
            require(row["skip_reason"] is None, "COMPLETED cannot have skip_reason")
            if row["attempts"] == 0:
                require(row["request_bytes"] == 0, "zero attempts cannot consume request bytes")
                require(all(row[key] is None for key in ("input_tokens", "output_tokens", "response_model", "finish_reason", "response_text", "tool_call_count")), "zero attempts cannot contain provider response")
            else:
                require(row["request_bytes"] > 0, "attempt needs measured request bytes")
                if row["input_tokens"] is None:
                    input_unknown += 1
                else:
                    input_known += row["input_tokens"]
                if row["output_tokens"] is None:
                    output_unknown += 1
                else:
                    output_known += row["output_tokens"]
                response_models[row["response_model"] or "UNKNOWN"] += 1
            if row["reason"] in FAILURES:
                require(row["effective_kind"] == "UNCLEAR", "controlled failure must fall back to UNCLEAR")
                failures += 1
                reasons[row["reason"]] += 1
                outcome = "CONTROLLED_FAILURE"
            else:
                kind = row["effective_kind"]
                require(kind in KINDS and row["reason"] == "OBSERVATION_" + kind, "invalid successful classifier reason")
                require(row["attempts"] == 1 and row["finish_reason"] == "STOP" and row["tool_call_count"] == 0, "successful classification needs one STOP response without tools")
                require(isinstance(row["response_text"], str), "successful classification needs raw response text")
                raw = parse_json(row["response_text"])
                require(isinstance(raw, dict) and set(raw) == {"kind"} and raw["kind"] in KINDS, "invalid successful raw response")
                require(raw["kind"] == kind, "raw kind differs from recorded effective kind")
                valid += 1
                matched += kind == expected[case_id]
                outcome = kind
        matrix[expected[case_id]][outcome] += 1

    require(seen == set(expected), "results must include every semantic id, using SKIPPED for unexecuted cases")
    require(attempts <= budget["max_attempts"], "total attempts exceed budget")
    require(request_bytes <= budget["max_total_request_bytes"], "total request bytes exceed budget")
    n = len(expected)
    counts = Counter(expected.values())
    dangerous_question = matrix["QUESTION"]["FACT"]
    dangerous_unclear = matrix["UNCLEAR"]["FACT"]
    fact_proposals = sum(matrix[kind]["FACT"] for kind in KINDS)
    metrics = {
        "strict_match_rate": rate(matched, n),
        "valid_response_match_rate": rate(matched, valid),
        "dangerous_fact_proposal_rate": rate(dangerous_question + dangerous_unclear, counts["QUESTION"] + counts["UNCLEAR"]),
        "question_to_fact_rate": rate(dangerous_question, counts["QUESTION"]),
        "unclear_to_fact_rate": rate(dangerous_unclear, counts["UNCLEAR"]),
        "fact_proposal_precision": rate(matrix["FACT"]["FACT"], fact_proposals),
        "fact_miss_rate": rate(counts["FACT"] - matrix["FACT"]["FACT"], counts["FACT"]),
        "valid_response_rate": rate(valid, n),
        "controlled_failure_rate": rate(failures, n),
        "skip_rate": rate(skipped, n),
    }
    return {
        "schema_version": 1, "run_id": results["run_id"], "execution": results["execution"],
        "evidence_label": "real model run (declared by input; offline scorer cannot attest provenance)" if results["execution"] == "real_model" else "synthetic scorer test; no real model quality evidence",
        "manifest_sha256": manifest_sha256, "requested_model": model,
        "counts": {"semantic": n, "gate_excluded": len(case_map) - n, "valid": valid,
                   "controlled_failure": failures, "skipped": skipped, "matched": matched},
        "classification_evidence_complete": valid == n,
        "metrics": metrics, "confusion_matrix": matrix,
        "controlled_failure_reasons": dict(sorted(reasons.items())),
        "skip_reasons": dict(sorted(skip_reasons.items())),
        "usage": {"attempts": attempts, "request_bytes": request_bytes,
                  "input_tokens_known_sum": input_known, "input_tokens_unknown_records": input_unknown,
                  "output_tokens_known_sum": output_known, "output_tokens_unknown_records": output_unknown,
                  "input_tokens_total": None if input_unknown else input_known,
                  "output_tokens_total": None if output_unknown else output_known,
                  "response_models": dict(sorted(response_models.items()))},
        "actual_field_misfill_rate": {"status": "unmeasured", "reason": "classifier records do not exercise service state or database writes"},
        "gate_mis_call_rate": {"status": "unmeasured", "reason": "gate cases must not be sent to a real model; verify Router/Service separately"},
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parent
    try:
        # Refuse to score silently edited cases, gold, config, scripts, or source.
        from verify import verify
        manifest_hash = verify()["manifest_sha256"]
        report = score(read_jsonl(root / "cases.jsonl"), read_jsonl(root / "gold.jsonl"),
                       parse_json(args.results.read_text(encoding="utf-8")), manifest_hash,
                       parse_json((root / "config.json").read_text(encoding="utf-8")))
    except (ValueError, OSError, TypeError, KeyError) as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
