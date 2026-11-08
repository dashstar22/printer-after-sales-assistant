"""V2 asset and wrapper boundary tests; no Java, database or model execution."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RUN = load("observation_v2_test_runner", ROOT / "run.py")
SCORE = load("observation_v2_test_scorer", ROOT / "score.py")
VERIFY = load("observation_v2_test_verify", ROOT / "verify.py")
CHECK = load("observation_v2_test_checker", ROOT / "check_runs.py")
ACK = "a" * 64


class V2BoundaryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.temporary = Path(temporary.name)

    def test_runner_uses_fixed_v2_assets_and_original_e3_implementation(self):
        self.assertEqual(RUN.E3.BASE.ASSETS, ROOT)
        self.assertEqual(Path(RUN.E3.__file__).resolve(), ROOT.parent / "observation-semantics/adapter/run_e3.py")
        args = RUN.E3.parser().parse_args(["--output", "unused"])
        self.assertEqual((args.mode, args.scenario, args.enable_real_model), ("forward-fixture", "all", False))
        for option in ("--assets", "--asset-root", "--upstream", "--base-url", "--api-key"):
            with self.subTest(option=option), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                RUN.E3.parser().parse_args(["--output", "unused", option, "forbidden"])

    def test_main_delegates_without_changing_arguments_or_defaults(self):
        args = ["--output", "unused", "--prepare-only"]
        with patch.object(RUN.E3, "main", return_value=7) as shared:
            self.assertEqual(RUN.main(args), 7)
        shared.assert_called_once_with(args)

    def test_real_gates_precede_output_build_and_key_read_for_v2(self):
        for enabled, acknowledged in ((False, ACK), (True, None), (True, "b" * 64)):
            output = self.temporary / (str(enabled) + str(acknowledged))
            with self.subTest(enabled=enabled, acknowledged=acknowledged), \
                    patch.object(RUN.E3.BASE, "verify_frozen", return_value={"manifest_sha256": ACK}), \
                    patch.object(RUN.E3.BASE, "prepare") as build, \
                    patch.object(RUN.E3, "read_credential") as key:
                with self.assertRaises(ValueError):
                    RUN.E3.execute(output, "real", "success", enabled, acknowledged)
                build.assert_not_called()
                key.assert_not_called()
                self.assertFalse(output.exists())

    def test_fixture_suite_keeps_shared_scenarios_without_key_reads(self):
        allowed = self.temporary / "runs"
        output = allowed / "suite"
        with patch.object(RUN.E3.BASE, "OUTPUT_ROOT", allowed), \
                patch.object(RUN.E3.BASE, "verify_frozen", return_value={"manifest_sha256": ACK}), \
                patch.object(RUN.E3.BASE, "prepare", return_value="offline-classpath") as build, \
                patch.object(RUN.E3, "read_credential") as key, \
                patch.object(RUN.E3, "run_one", side_effect=lambda out, cp, mode, scenario, frozen: {"scenario": scenario}) as run:
            summary = RUN.E3.execute(output)
        build.assert_called_once()
        key.assert_not_called()
        self.assertEqual(run.call_count, 16)
        self.assertEqual(summary["scenario_count"], 16)
        self.assertEqual(summary["real_model_calls"], 0)

    def test_scorer_reuses_exact_frozen_functions(self):
        self.assertIs(SCORE.score, SCORE.COMMON.score)
        self.assertIs(SCORE.read_jsonl, SCORE.COMMON.read_jsonl)
        self.assertIs(SCORE.parse_json, SCORE.COMMON.parse_json)

    def test_historical_sources_are_independent_and_corruption_is_rejected(self):
        shutil.copytree(ROOT / "baseline-v1", self.temporary / "baseline-v1")
        with patch.object(VERIFY, "ROOT", self.temporary):
            self.assertEqual(VERIFY.verify_historical_v1()["source_snapshots"], 14)
            interpreter = self.temporary / "baseline-v1/backend/src/main/java/local/printer/assistant/ObservationIntentInterpreter.java"
            interpreter.write_bytes(interpreter.read_bytes() + b"\ncorrupt historical bytes\n")
            with self.assertRaisesRegex(ValueError, "Historical v1 source changed"):
                VERIFY.verify_historical_v1()

    def test_supplementary_pairs_are_separate_and_cannot_enable_real_calls(self):
        shutil.copyfile(ROOT / "supplementary-pairs.jsonl", self.temporary / "supplementary-pairs.jsonl")
        with patch.object(VERIFY, "ROOT", self.temporary):
            checked = VERIFY.verify_supplementary()
            self.assertEqual((checked["pairs"], checked["cases"], checked["real_model_calls"]), (6, 12, 0))
            self.assertFalse(checked["included_in_primary_36_denominator"])
            rows = [json.loads(line) for line in (self.temporary / "supplementary-pairs.jsonl").read_text(encoding="utf-8").splitlines()]
            rows[0]["execution_policy"] = "CALL_REAL"
            (self.temporary / "supplementary-pairs.jsonl").write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "real execution is not authorized"):
                VERIFY.verify_supplementary()

    def test_checker_rejects_real_records_before_fixture_details(self):
        def read(path):
            return {"mode": "real"} if path.name == "summary.json" else {}
        with patch.object(CHECK.VERIFY, "verify", return_value={}), \
                patch.object(CHECK.CHECK, "read", side_effect=read), \
                patch.object(CHECK.CHECK.SCORER, "read_jsonl", return_value=[]), \
                patch.object(CHECK.CHECK, "check_one") as scenario:
            with self.assertRaisesRegex(ValueError, "Only local fixture"):
                CHECK.check(self.temporary)
        scenario.assert_not_called()

    def test_checker_independently_rejects_stale_common_build_hash(self):
        relative = "validation/observation-semantics/adapter/run.py"
        wrong = "0" * 64
        self.assertNotEqual(hashlib.sha256((CHECK.COMMON / "run.py").read_bytes()).hexdigest(), wrong)
        def read(path):
            if path.name == "manifest.json":
                return {"shared_files": {relative: wrong}}
            if path.name == "summary.json":
                return {"mode": "forward-fixture", "scenario": "success"}
            if path.name == "build.json":
                return {"adapter_source_hashes": {"run.py": wrong}}
            return {}
        with patch.object(CHECK.VERIFY, "verify", return_value={}), \
                patch.object(CHECK.CHECK, "read", side_effect=read), \
                patch.object(CHECK.CHECK.SCORER, "read_jsonl", return_value=[]), \
                patch.object(CHECK.CHECK, "check_one", return_value={"status": "PASS"}):
            with self.assertRaisesRegex(ValueError, "Common adapter changed since build"):
                CHECK.check(self.temporary)


if __name__ == "__main__":
    unittest.main()
