"""Offline assertions over E3 forwarding-fixture evidence. Never executes real mode."""
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


E3 = module("e3_check_runner", ROOT / "run_e3.py")
E2 = module("e2_check_records", ROOT / "check_runs.py")
SCORER = E2.SCORER


def read(path):
    return SCORER.parse_json(path.read_text(encoding="utf-8"))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(body):
    return hashlib.sha256(body).hexdigest()


def check_one(directory, scenario, frozen, cases, gold, config):
    run = read(directory / "run.json")
    results = read(directory / "results.json")
    upstream = read(directory / "upstream.json")
    report = SCORER.score(cases, gold, results, frozen["manifest_sha256"], config)
    valid, failed, skipped, attempts, local = E2.expected_counts(scenario)
    require(run["mode"] == "forward-fixture" and run["execution"] == results["execution"] == "synthetic_test", "Non-fixture evidence")
    require(run["real_model_calls"] == run["database_calls"] == upstream["real_model_calls"] == 0, "Real service access forbidden")
    require(run["listener_closed"] is True and run["real_forwarding_implemented"] is True, "Forwarding lifecycle mismatch")
    require(report["counts"]["valid"] == valid and report["counts"]["controlled_failure"] == failed
            and report["counts"]["skipped"] == skipped, "Unexpected result counts")
    original = {c["id"]: c["text"] for c in cases if c["split"] == "semantic"}
    order = ["S01", "S13", "S25"] + [i for i in original if i not in {"S01", "S13", "S25"}]
    rows = results["records"]
    require([r["id"] for r in rows] == run["execution_order"] == order, "Order/duplicates/gates mismatch")
    budget = run["fixture_budget"]
    require(budget["calls"] == budget["upstream_requests"] == run["upstream_requests"] == upstream["requests"] == attempts,
            "Unexpected upstream retries or budget attempts")
    require(budget["loopback_requests"] == local and budget["active"] == 0 and budget["max_active"] <= 1, "Local retry or concurrency failure")
    require(upstream["active"] == 0 and upstream["max_active"] <= 1, "Upstream fixture not stopped")
    require(upstream["authorization_correct"] and upstream["headers_correct"] and upstream["redirect_follow_count"] == 0, "Credential/header/redirect failure")
    require(not budget["contract_errors"] and budget["real_upstream_requests"] == 0, "Request contract failed")
    require(budget["bytes"] == report["usage"]["request_bytes"] <= 147456, "Body bytes mismatch")
    require(run["read_retries_setting"] == 1, "modelOnce retry guard not exercised")
    require(run["budget_self_tests"]["pass"] == 5 and run["budget_self_tests"]["fail"] == 0, "Budget self-test failed")
    require(run["forwarding_policy_tests"]["pass"] == 18 and run["forwarding_policy_tests"]["fail"] == 0
            and run["forwarding_policy_tests"]["upstream_requests"] == 0, "Forwarding policy test failed")
    completed = [r for r in rows if r["status"] == "COMPLETED"]
    require(len(run["raw_records"]) == len(completed), "Raw record coverage mismatch")
    forwarded_hashes = []
    body_sizes = []
    different_units = False
    for index, item in enumerate(run["raw_records"]):
        require(item["id"] == completed[index]["id"] and item["raw_path"] == f"raw/{item['id']}.json", "Unsafe raw metadata")
        raw_path = directory / item["raw_path"]
        require(digest(raw_path.read_bytes()) == item["raw_sha256"], "Raw evidence changed")
        raw = read(raw_path)
        require(raw["loopback_requests"] <= 1 and raw["tool_budget_calls"] == 0 and not raw["request_contract_errors"], "Unexpected SDK retry/tool/contract")
        require(raw["interpreter_reason"] == completed[index]["reason"], "Production reason altered")
        body_text = raw["request_body"]
        if body_text is not None:
            body = body_text.encode("utf-8")
            body_sizes.append(len(body))
            require(len(body) == raw["received_body_bytes"] <= 4096, "Serialized body byte error")
            request = SCORER.parse_json(body_text)
            require(request["messages"][1]["content"] == original[item["id"]], "USER original changed")
            require(len(request["messages"]) == 2 and request["messages"][0]["role"] == "system"
                    and request["messages"][1]["role"] == "user", "Context or history leakage")
            require(digest(request["messages"][0]["content"].encode("utf-8")) == run["system_sha256"], "System differs")
            require(request["model"] == "qwen-plus" and request["temperature"] == 0 and request["max_tokens"] == 128, "Model configuration changed")
            require(request["response_format"]["json_schema"]["schema"] == config["model"]["schema"], "Schema differs")
            require("tools" not in request and "tool_choice" not in request, "Tools leaked")
            different_units |= raw["production_input_bytes"] != len(body)
            if raw["supplier_attempts"]:
                require(raw["forwarded_body_sha256"] == digest(body), "Forwarded body was changed")
                forwarded_hashes.append(digest(body))
    require(different_units, "Wire/production byte units were not separated")
    require(forwarded_hashes == upstream["body_sha256"], "Upstream did not receive exact same bodies")
    require(len(upstream["request_bodies"]) == attempts, "Unexpected fixture receipt count")
    if failed:
        last = completed[-1]
        require(last["reason"] == run["stop_reason"] and last["id"] == run["stop_id"], "Stop reason lost")
        require(all(r["skip_reason"] == "STOPPED_AFTER_" + last["reason"] for r in rows[len(completed):]), "Missing failure SKIP records")
        if scenario in {"invalid-json", "second-sentinel-invalid", "third-sentinel-invalid", "tool-response", "finish-length"}:
            require(last["reason"] == "OBSERVATION_INVALID_RESPONSE", "Protocol failure accepted")
        elif scenario == "call-budget":
            require(last["reason"] == "OBSERVATION_BUDGET_EXHAUSTED" and last["attempts"] == 0, "Call budget not preempted")
        elif scenario == "timeout":
            require(last["reason"] in {"OBSERVATION_BUDGET_STOPPED", "OBSERVATION_UNAVAILABLE"}, "Timeout misclassified")
            require(last["input_tokens"] is None and last["finish_reason"] is None, "Timeout usage fabricated")
        else:
            require(last["reason"] == "OBSERVATION_UNAVAILABLE", "HTTP error was repaired")
        if scenario in {"byte-budget", "total-byte-budget"}:
            reason = "REQUEST_BYTES_LIMIT" if scenario == "byte-budget" else "TOTAL_BYTES_LIMIT"
            require(budget["rejections"] == {reason: 1}, "Wrong body budget rejection")
    else:
        require(not budget["rejections"] and run["stop_reason"] is None, "Unexpected normal stop")
    if scenario == "credential-echo":
        raw = read(directory / run["raw_records"][0]["raw_path"])
        require(raw["upstream_failure"] == "CREDENTIAL_ECHO_REJECTED" and raw["raw_response_body"] is None,
                "Credential echo not suppressed")
    # This known fake token is test data. Its absence checks the same persistence boundary
    # without ever retrieving or comparing a real credential.
    fake = "e3-synthetic-key-never-real"
    for path in directory.rglob("*"):
        if path.is_file() and path.suffix in {".json", ".log"}:
            require(fake not in path.read_text(encoding="utf-8"), "Synthetic credential leaked to evidence")
    return {"scenario": scenario, "status": "PASS", "valid_fixture_records": valid,
            "expected_controlled_failure": failed, "expected_skipped": skipped,
            "upstream_fixture_receipts": attempts, "local_sdk_requests": local,
            "max_body_bytes": max(body_sizes, default=0), "real_model_calls": 0}


def check(directory):
    frozen = E3.BASE.verify_frozen()
    config = read(ROOT.parent / "config.json")
    cases = SCORER.read_jsonl(ROOT.parent / "cases.jsonl")
    gold = SCORER.read_jsonl(ROOT.parent / "gold.jsonl")
    suite = read(directory / "summary.json")
    require(suite["mode"] == "forward-fixture", "This checker only accepts local fixture runs")
    if suite["scenario"] == "all":
        require(suite["scenario_count"] == len(E3.SCENARIOS), "Missing scenarios")
        checks = [check_one(directory / s, s, frozen, cases, gold, config) for s in E3.SCENARIOS]
    else:
        require(suite["scenario"] in E3.SCENARIOS, "Unknown scenario")
        checks = [check_one(directory, suite["scenario"], frozen, cases, gold, config)]
    build = read(directory / "build.json")
    for name, expected in build["adapter_source_hashes"].items():
        require(digest((ROOT / name).read_bytes()) == expected, "Adapter source changed since build: " + name)
    return {"status": "PASS", "pass": len(checks), "fail": 0, "skip": 0, "real_model_calls": 0,
            "evidence": "Local forwarding fixtures only; not real provider or semantic quality evidence", "scenarios": checks}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(check(args.run_dir.resolve()), ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, TypeError) as failure:
        print(json.dumps({"status": "FAIL", "error": str(failure)}, ensure_ascii=False))
        sys.exit(1)
