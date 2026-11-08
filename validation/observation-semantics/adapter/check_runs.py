"""Check actual fixture run artifacts, without launching Java or making network calls."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


RUNNER = module("fixture_runner_for_checks", ROOT / "run.py")
SCORER = module("fixture_scorer_for_checks", ROOT.parent / "score.py")


def read(path):
    return SCORER.parse_json(path.read_text(encoding="utf-8"))


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def expected_counts(scenario):
    # valid / controlled failure / skipped / fixture attempts / received local HTTP.
    if scenario == "success":
        return 36, 0, 0, 36, 36
    if scenario == "second-sentinel-invalid":
        return 1, 1, 34, 2, 2
    if scenario == "third-sentinel-invalid":
        return 2, 1, 33, 3, 3
    if scenario == "call-budget":
        return 2, 1, 33, 2, 2
    if scenario == "byte-budget":
        return 0, 1, 35, 0, 1
    if scenario == "total-byte-budget":
        return 1, 1, 34, 1, 2
    return 0, 1, 35, 1, 1


def check_one(directory, scenario, frozen, cases, gold, config):
    run = read(directory / "run.json")
    results = read(directory / "results.json")
    report = SCORER.score(cases, gold, results, frozen["manifest_sha256"], config)
    valid, failed, skipped, attempts, local_requests = expected_counts(scenario)
    require(run["scenario"] == scenario and run["execution"] == results["execution"] == "synthetic_test", "Wrong evidence source")
    require(run["real_model_calls"] == run["database_calls"] == 0 and run["real_forwarding_implemented"] is False, "Non-fixture execution")
    require(run["listener_closed"] is True, "Listener leaked")
    require(run["manifest_sha256"] == frozen["manifest_sha256"], "Run manifest differs")
    require(report["counts"] == {"semantic": 36, "gate_excluded": 16, "valid": valid,
                                 "controlled_failure": failed, "skipped": skipped,
                                 "matched": report["counts"]["matched"]}, "Unexpected scenario counts")
    rows = results["records"]
    originals = {x["id"]: x for x in cases if x["split"] == "semantic"}
    expected_order = ["S01", "S13", "S25"] + [x for x in originals if x not in {"S01", "S13", "S25"}]
    require([x["id"] for x in rows] == run["execution_order"] == expected_order, "Wrong order or repeated sentinel")
    fixture = run["fixture_budget"]
    require(fixture["calls"] == attempts and fixture["loopback_requests"] == local_requests, "Unexpected attempts/retries")
    require(fixture["active"] == 0 and fixture["max_active"] <= 1, "Concurrency or release failure")
    require(not fixture["contract_errors"], "Wire request contract failed")
    require(fixture["real_upstream_requests"] == 0 and fixture["forwarding_implemented"] is False, "Upstream request forbidden")
    require(fixture["bytes"] == report["usage"]["request_bytes"], "Wire byte totals disagree")
    require(run["read_retries_setting"] == 1, "Did not exercise modelOnce against ordinary retry setting")
    require(run["budget_self_tests"]["pass"] == 5 and run["budget_self_tests"]["fail"] == 0, "Ledger self-test failure")
    completed = [x for x in rows if x["status"] == "COMPLETED"]
    require(len(run["raw_records"]) == len(completed), "Missing raw result")
    completed_ids = {x["id"] for x in completed}
    body_lengths = []
    distinct_accounting = False
    for entry in run["raw_records"]:
        require(entry["id"] in completed_ids and entry["raw_path"] == f"raw/{entry['id']}.json", "Unsafe raw path")
        raw_path = directory / entry["raw_path"]
        require(sha(raw_path.read_bytes()) == entry["raw_sha256"], "Raw evidence hash mismatch")
        raw = read(raw_path)
        require(raw["loopback_requests"] <= 1 and raw["tool_budget_calls"] == 0, "Unexpected retry or business tool")
        require(not raw["request_contract_errors"], "Invalid request was sent")
        if raw["request_body"] is not None:
            body = raw["request_body"].encode("utf-8")
            require(len(body) == raw["received_body_bytes"] <= 4096, "Full body bytes mismatch")
            request = SCORER.parse_json(raw["request_body"])
            messages = request["messages"]
            require(len(messages) == 2 and messages[0]["role"] == "system" and messages[1]["role"] == "user", "Unexpected messages")
            require(sha(messages[0]["content"].encode("utf-8")) == run["system_sha256"], "System differs")
            require(messages[1]["content"] == originals[entry["id"]]["text"], "Current USER was rewritten")
            require(request["model"] == config["model"]["requested_model"] and request["temperature"] == 0 and request["max_tokens"] == 128, "Wrong model settings")
            require(request["response_format"]["json_schema"]["schema"] == config["model"]["schema"], "Schema mismatch")
            require("tools" not in request and "tool_choice" not in request, "Tool request forbidden")
            body_lengths.append(len(body))
            distinct_accounting |= raw["production_input_bytes"] != len(body)
        require(raw["interpreter_reason"] == next(x["reason"] for x in completed if x["id"] == entry["id"]), "Production reason was changed")
    require(distinct_accounting, "Did not distinguish production accounting and wire body bytes")
    if scenario == "success":
        require({x["effective_kind"] for x in completed} == {"FACT", "QUESTION", "UNCLEAR"}, "Three response kinds not exercised")
        require(run["stop_reason"] is None and not fixture["rejections"], "Unexpected stop/rejection")
        require(len(body_lengths) == 36, "Not all fixed requests were captured")
    else:
        last = completed[-1]
        require(last["id"] == run["stop_id"] and last["reason"] == run["stop_reason"], "Failure was not preserved")
        require(all(x["skip_reason"] == "STOPPED_AFTER_" + last["reason"] for x in rows[len(completed):]), "Remaining SKIP reason differs")
        if scenario in {"invalid-json", "second-sentinel-invalid", "third-sentinel-invalid", "tool-response", "finish-length"}:
            require(last["reason"] == "OBSERVATION_INVALID_RESPONSE", "Invalid protocol became success")
        elif scenario == "call-budget":
            require(last["reason"] == "OBSERVATION_BUDGET_EXHAUSTED" and last["attempts"] == 0, "Pre-call cap failed")
        elif scenario == "timeout":
            require(last["reason"] in {"OBSERVATION_BUDGET_STOPPED", "OBSERVATION_UNAVAILABLE"}, "Timeout reason changed")
            require(last["finish_reason"] is None and last["input_tokens"] is None, "Timeout got invented usage")
        else:
            require(last["reason"] == "OBSERVATION_UNAVAILABLE", "HTTP failure reason changed")
        if scenario in {"byte-budget", "total-byte-budget"}:
            reason = "REQUEST_BYTES_LIMIT" if scenario == "byte-budget" else "TOTAL_BYTES_LIMIT"
            require(fixture["rejections"] == {reason: 1} and last["attempts"] == last["request_bytes"] == 0, "Body budget did not reject before fixture execution")
    return {"scenario": scenario, "status": "PASS", "valid_fixture_records": valid,
            "expected_controlled_failures": failed, "skipped_after_failure": skipped,
            "fixture_attempts": attempts, "loopback_requests": local_requests,
            "max_body_bytes": max(body_lengths, default=0), "real_model_calls": 0}


def check(directory):
    frozen = RUNNER.verify_frozen()
    cases = SCORER.read_jsonl(ROOT.parent / "cases.jsonl")
    gold = SCORER.read_jsonl(ROOT.parent / "gold.jsonl")
    config = read(ROOT.parent / "config.json")
    summary = read(directory / "summary.json")
    all_scenarios = summary.get("scenario") == "all"
    if all_scenarios:
        require(summary["scenario_count"] == len(RUNNER.SCENARIOS), "Missing suite scenarios")
        checks = [check_one(directory / name, name, frozen, cases, gold, config) for name in RUNNER.SCENARIOS]
    else:
        require(summary["scenario"] in RUNNER.SCENARIOS, "Unknown scenario")
        checks = [check_one(directory, summary["scenario"], frozen, cases, gold, config)]
    build = read(directory / "build.json")
    for name, expected in build["adapter_source_hashes"].items():
        require(sha((ROOT / name).read_bytes()) == expected, "Adapter changed since run: " + name)
    return {"status": "PASS", "pass": len(checks), "fail": 0, "skip": 0,
            "evidence": "loopback protocol fixture; not semantic quality or DB evidence",
            "distinct_ledger_self_checks": 5, "real_model_calls": 0, "scenarios": checks}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(check(args.run_dir.resolve()), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError) as failure:
        print(json.dumps({"status": "FAIL", "error": str(failure)}, ensure_ascii=False))
        sys.exit(1)
