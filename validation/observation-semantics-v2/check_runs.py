"""Offline v2 fixture checking using the common E3 checker, no model execution."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
COMMON = ROOT.parent / "observation-semantics" / "adapter"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECK = load("observation_v2_shared_checker", COMMON / "check_e3_runs.py")
VERIFY = load("observation_v2_fixture_verify", ROOT / "verify.py")


def check(directory):
    frozen = VERIFY.verify()
    manifest = CHECK.read(ROOT / "manifest.json")
    config = CHECK.read(ROOT / "config.json")
    cases = CHECK.SCORER.read_jsonl(ROOT / "cases.jsonl")
    gold = CHECK.SCORER.read_jsonl(ROOT / "gold.jsonl")
    suite = CHECK.read(directory / "summary.json")
    CHECK.require(suite["mode"] == "forward-fixture", "Only local fixture evidence is accepted")
    if suite["scenario"] == "all":
        CHECK.require(suite["scenario_count"] == len(CHECK.E3.SCENARIOS), "Missing scenarios")
        results = [CHECK.check_one(directory / scenario, scenario, frozen, cases, gold, config)
                   for scenario in CHECK.E3.SCENARIOS]
    else:
        CHECK.require(suite["scenario"] in CHECK.E3.SCENARIOS, "Unknown scenario")
        results = [CHECK.check_one(directory, suite["scenario"], frozen, cases, gold, config)]
    build = CHECK.read(directory / "build.json")
    expected_names = {Path(name).name for name in manifest["shared_files"] if name.startswith("validation/observation-semantics/adapter/")}
    CHECK.require(set(build["adapter_source_hashes"]) == expected_names, "Common adapter build fingerprint coverage differs")
    for name, expected in build["adapter_source_hashes"].items():
        actual = hashlib.sha256((COMMON / name).read_bytes()).hexdigest()
        CHECK.require(actual == expected, "Common adapter changed since build: " + name)
        relative = (COMMON / name).relative_to(VERIFY.REPO).as_posix()
        CHECK.require(manifest["shared_files"][relative] == expected, "Common build differs from frozen v2 adapter: " + name)
    return {"status": "PASS", "pass": len(results), "fail": 0, "skip": 0, "real_model_calls": 0,
            "asset_version": "observation-semantics-fixed-v2", "historical_v1": frozen["historical_v1"],
            "evidence": "Local forwarding fixtures only; not real semantic quality", "scenarios": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(check(args.run_dir.resolve()), ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, TypeError) as failure:
        print(json.dumps({"status": "FAIL", "error": str(failure)}, ensure_ascii=False))
        sys.exit(1)
