"""Controlled E3 entry point. Default: local forwarding protocol fixtures only.

Real execution additionally needs --enable-real-model, the exact frozen manifest
acknowledgement and the dedicated OBSERVATION_EVAL_API_KEY process variable.
Credentials are sent to Java via stdin only, never arguments, child environment or logs.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parent
import importlib.util

SPEC = importlib.util.spec_from_file_location("e3_base_runner", ROOT / "run.py")
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)

SCENARIOS = (
    "success", "invalid-json", "second-sentinel-invalid", "third-sentinel-invalid",
    "tool-response", "finish-length", "timeout", "http-401", "http-429", "http-503",
    "redirect", "disconnect", "call-budget", "byte-budget", "total-byte-budget", "credential-echo",
)
CREDENTIAL_NAME = "OBSERVATION_EVAL_API_KEY"


def authorize(mode, scenario, enabled, acknowledged, frozen):
    """Check explicit gates without inspecting any credential or creating output."""
    if mode not in ("forward-fixture", "real") or scenario not in SCENARIOS + ("all",):
        raise ValueError("Unknown E3 mode or scenario")
    if mode == "real":
        if scenario != "success":
            raise ValueError("Real mode requires the explicit --scenario success; fixture scenarios and all are forbidden")
        if enabled is not True or acknowledged != frozen["manifest_sha256"]:
            raise ValueError("Real mode is disabled without --enable-real-model and exact --ack-manifest")
    elif enabled or acknowledged is not None:
        raise ValueError("Real execution switches cannot be combined with fixture mode")


def read_credential():
    value = os.environ.get(CREDENTIAL_NAME)
    if (not value or not 8 <= len(value) <= 4096
            or any(ord(character) < 0x21 or ord(character) > 0x7e for character in value)):
        raise ValueError("Dedicated evaluation credential is missing or invalid")
    return value


def launch_java(output, classpath, mode, scenario, acknowledged, credential=None):
    environment = BASE.safe_environment()
    executable = shutil.which("java", path=environment.get("PATH", os.defpath))
    if executable is None:
        raise RuntimeError("Required local tool not found: java")
    args = [executable, "-cp", classpath, "local.printer.assistant.ObservationE3Adapter",
            str(BASE.ASSETS), str(output), mode, scenario, acknowledged if mode == "real" else "-"]
    # A second independent check prevents accidental key delivery from library callers.
    if mode == "real" and not credential:
        raise ValueError("Real launch requires a dedicated credential")
    if mode != "real" and credential is not None:
        raise ValueError("Fixture launch cannot accept a real credential")
    result = subprocess.run(args, cwd=BASE.REPO, env=environment,
                            input=(credential + "\n") if credential is not None else "",
                            capture_output=True, text=True, encoding="utf-8", errors="replace",
                            timeout=360)
    # Never write process output that echoes the credential, including failure output.
    if credential and (credential in result.stdout or credential in result.stderr):
        raise RuntimeError("Credential echo detected; child stdout and stderr were not persisted")
    (output / "e3-java.stdout.log").write_text(result.stdout, encoding="utf-8")
    (output / "e3-java.stderr.log").write_text(result.stderr, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError("E3 Java adapter failed; sanitized diagnostics retained")


def score_output(output, mode, scenario, frozen):
    after = BASE.verify_frozen()
    if after["manifest_sha256"] != frozen["manifest_sha256"]:
        raise ValueError("Frozen manifest changed during E3 run")
    scorer = BASE.load_module("e3_offline_scorer", BASE.ASSETS / "score.py")
    results = scorer.parse_json((output / "results.json").read_text(encoding="utf-8"))
    run = scorer.parse_json((output / "run.json").read_text(encoding="utf-8"))
    expected = "real_model" if mode == "real" else "synthetic_test"
    if run.get("execution") != expected or results.get("execution") != expected:
        raise ValueError("E3 evidence source does not match selected mode")
    upstream = run.get("upstream_requests")
    actual = run.get("real_model_calls")
    if type(upstream) is not int or upstream < 0 or type(actual) is not int:
        raise ValueError("E3 upstream accounting is missing")
    if actual != (upstream if mode == "real" else 0) or run.get("database_calls") != 0:
        raise ValueError("E3 external-call accounting does not match selected mode")
    report = scorer.score(scorer.read_jsonl(BASE.ASSETS / "cases.jsonl"),
                          scorer.read_jsonl(BASE.ASSETS / "gold.jsonl"), results,
                          frozen["manifest_sha256"],
                          scorer.parse_json((BASE.ASSETS / "config.json").read_text(encoding="utf-8")))
    BASE.write_json(output / "score.json", report)
    summary = {
        "status": "COMPLETED_WITH_RECORDS", "mode": mode, "scenario": scenario,
        "execution": expected, "real_model_calls": actual, "upstream_requests": upstream,
        "evidence_label": ("Real model records; inspect failures, skipped cases and risk metrics before any quality conclusion"
                           if mode == "real" else "Local forwarding fixture records only; not real model quality evidence"),
        "counts": report["counts"], "output": str(output),
    }
    BASE.write_json(output / "summary.json", summary)
    return summary


def run_one(output, classpath, mode, scenario, frozen, credential=None):
    launch_java(output, classpath, mode, scenario,
                frozen["manifest_sha256"] if mode == "real" else None, credential)
    return score_output(output, mode, scenario, frozen)


def execute(output, mode="forward-fixture", scenario="all", enabled=False,
            acknowledged=None, prepare_only=False):
    frozen = BASE.verify_frozen()
    authorize(mode, scenario, enabled, acknowledged, frozen)
    output = BASE.reserve_output(output)
    try:
        classpath = BASE.prepare(output)
        if prepare_only:
            BASE.verify_frozen()
            summary = {"status": "PREPARED_ONLY", "mode": mode, "real_model_calls": 0,
                       "credential_read": False, "output": str(output)}
            BASE.write_json(output / "prepared.json", summary)
            return summary
        # No private environment variable is read until both gates and the build pass.
        credential = read_credential() if mode == "real" else None
        if scenario == "all":
            runs = [run_one(BASE.reserve_output(output / name), classpath, mode, name, frozen)
                    for name in SCENARIOS]
            summary = {"status": "COMPLETED_WITH_RECORDS", "mode": mode, "scenario": "all",
                       "execution": "synthetic_test", "real_model_calls": 0,
                       "evidence_label": "Local forwarding protocol fixtures only; not real model quality evidence",
                       "scenario_count": len(runs), "scenario_runs": runs, "output": str(output)}
            BASE.write_json(output / "summary.json", summary)
            return summary
        return run_one(output, classpath, mode, scenario, frozen, credential)
    except Exception as failure:
        # Exception messages may originate from HTTP or subprocess code: keep fixed diagnostics.
        BASE.write_json(output / "failure.json", {"status": "FAILED", "mode": mode,
                        "error_type": type(failure).__name__, "error": "E3 execution stopped; inspect sanitized local logs",
                        "evidence_retained": True, "real_model_calls": None if mode == "real" else 0})
        raise RuntimeError("E3 execution stopped; partial evidence retained") from None


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--mode", choices=("forward-fixture", "real"), default="forward-fixture")
    result.add_argument("--scenario", choices=SCENARIOS + ("all",), default="all")
    result.add_argument("--output", required=True, type=Path)
    result.add_argument("--enable-real-model", action="store_true", default=False)
    result.add_argument("--ack-manifest")
    result.add_argument("--prepare-only", action="store_true")
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        summary = execute(args.output, args.mode, args.scenario, args.enable_real_model,
                          args.ack_manifest, args.prepare_only)
    except Exception as failure:
        # Fixed code and class name only; no exception payload or credential.
        print(json.dumps({"status": "FAILED", "error": "E3_ENTRY_STOPPED",
                          "error_type": type(failure).__name__}, ensure_ascii=False))
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
