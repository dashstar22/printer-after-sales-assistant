"""Reuse the frozen v1 scoring implementation with fixed v2 assets, offline only."""
import argparse
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("observation_v2_shared_scorer", ROOT.parent / "observation-semantics" / "score.py")
COMMON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMMON)
parse_json = COMMON.parse_json
read_jsonl = COMMON.read_jsonl
score = COMMON.score


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        spec = importlib.util.spec_from_file_location("observation_v2_score_verify", ROOT / "verify.py")
        verifier = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verifier)
        frozen = verifier.verify()
        report = score(read_jsonl(ROOT / "cases.jsonl"), read_jsonl(ROOT / "gold.jsonl"),
                       parse_json(args.results.read_text(encoding="utf-8")), frozen["manifest_sha256"],
                       parse_json((ROOT / "config.json").read_text(encoding="utf-8")))
    except (ValueError, OSError, TypeError, KeyError) as failure:
        print(json.dumps({"error": str(failure)}, ensure_ascii=False))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
