"""E3 entry safety tests; all Java, build and network work is mocked."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("e3_runner_test_target", Path(__file__).with_name("run_e3.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
HASH = "a" * 64


class E3RunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.allowed = self.root / "runs"
        self.output = self.allowed / "new"
        self.patches = [patch.object(RUNNER.BASE, "OUTPUT_ROOT", self.allowed),
                        patch.object(RUNNER.BASE, "verify_frozen", return_value={"manifest_sha256": HASH})]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)

    def test_default_mode_is_fixture_and_real_is_disabled(self):
        args = RUNNER.parser().parse_args(["--output", "unused"])
        self.assertEqual(args.mode, "forward-fixture")
        self.assertEqual(args.scenario, "all")
        self.assertFalse(args.enable_real_model)
        self.assertIsNone(args.ack_manifest)

    def test_real_gates_fail_before_key_read_build_or_output(self):
        for enabled, ack in ((False, HASH), (True, None), (True, "b" * 64), (False, None)):
            with self.subTest(enabled=enabled, ack=ack), \
                    patch.object(RUNNER, "read_credential") as key, \
                    patch.object(RUNNER.BASE, "prepare") as build:
                with self.assertRaises(ValueError):
                    RUNNER.execute(self.output, "real", "success", enabled, ack)
                key.assert_not_called()
                build.assert_not_called()
                self.assertFalse(self.output.exists())

    def test_real_all_and_fault_scenarios_are_rejected_before_key_read(self):
        for scenario in ("all", "http-401", "credential-echo"):
            with self.subTest(scenario=scenario), patch.object(RUNNER, "read_credential") as key:
                with self.assertRaises(ValueError):
                    RUNNER.execute(self.output, "real", scenario, True, HASH)
                key.assert_not_called()
                self.assertFalse(self.output.exists())

    def test_fixture_mode_rejects_real_switches(self):
        for enabled, ack in ((True, None), (False, HASH), (True, HASH)):
            with self.subTest(enabled=enabled), self.assertRaises(ValueError):
                RUNNER.authorize("forward-fixture", "success", enabled, ack, {"manifest_sha256": HASH})

    def test_prepare_only_never_reads_credentials_or_runs_java(self):
        with patch.object(RUNNER.BASE, "prepare", return_value="cp"), \
                patch.object(RUNNER, "read_credential") as key, \
                patch.object(RUNNER, "run_one") as run:
            result = RUNNER.execute(self.output, "real", "success", True, HASH, True)
        key.assert_not_called()
        run.assert_not_called()
        self.assertEqual(result["status"], "PREPARED_ONLY")
        self.assertFalse(result["credential_read"])

    def test_fixture_all_shares_build_and_never_reads_key(self):
        with patch.object(RUNNER.BASE, "prepare", return_value="cp") as build, \
                patch.object(RUNNER, "read_credential") as key, \
                patch.object(RUNNER, "run_one", side_effect=lambda out, cp, mode, scenario, frozen: {"scenario": scenario}) as run:
            result = RUNNER.execute(self.output)
        build.assert_called_once()
        key.assert_not_called()
        self.assertEqual(run.call_count, len(RUNNER.SCENARIOS))
        self.assertEqual(result["real_model_calls"], 0)

    def test_real_credential_uses_only_dedicated_variable(self):
        with patch.dict(os.environ, {"AI_API_KEY": "ignored", "DASHSCOPE_API_KEY": "ignored"}, clear=True):
            with self.assertRaises(ValueError):
                RUNNER.read_credential()
        with patch.dict(os.environ, {RUNNER.CREDENTIAL_NAME: "synthetic-private-token"}, clear=True):
            self.assertEqual(RUNNER.read_credential(), "synthetic-private-token")
        for value in ("", " ", "short", "seven77", "contains space", "abc\tdefgh", "one\ntwo", "one\rtwo", "非ASCII密钥", "x" * 4097):
            with self.subTest(value_length=len(value)), patch.dict(os.environ, {RUNNER.CREDENTIAL_NAME: value}, clear=True):
                with self.assertRaises(ValueError):
                    RUNNER.read_credential()

    def test_real_key_is_stdin_only_and_not_in_child_environment(self):
        self.output.mkdir(parents=True)
        synthetic = "synthetic-private-token"
        with patch.object(RUNNER.BASE, "safe_environment", return_value={"PATH": "tools"}), \
                patch.object(RUNNER.shutil, "which", return_value="/tools/java"), \
                patch.object(RUNNER.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "COMPLETE", "")) as child:
            RUNNER.launch_java(self.output, "cp", "real", "success", HASH, synthetic)
        args, kwargs = child.call_args
        self.assertNotIn(synthetic, " ".join(args[0]))
        self.assertNotIn(synthetic, repr(kwargs["env"]))
        self.assertEqual(kwargs["input"], synthetic + "\n")
        self.assertEqual(kwargs["timeout"], 360)
        self.assertNotIn(synthetic, (self.output / "e3-java.stdout.log").read_text())

    def test_credential_echo_output_is_not_persisted(self):
        self.output.mkdir(parents=True)
        synthetic = "synthetic-private-token"
        with patch.object(RUNNER.BASE, "safe_environment", return_value={"PATH": "tools"}), \
                patch.object(RUNNER.shutil, "which", return_value="/tools/java"), \
                patch.object(RUNNER.subprocess, "run", return_value=subprocess.CompletedProcess([], 2, "echo " + synthetic, synthetic)):
            with self.assertRaisesRegex(RuntimeError, "echo"):
                RUNNER.launch_java(self.output, "cp", "real", "success", HASH, synthetic)
        self.assertFalse((self.output / "e3-java.stdout.log").exists())
        self.assertFalse((self.output / "e3-java.stderr.log").exists())

    def test_fixture_cannot_be_given_a_real_credential(self):
        with patch.object(RUNNER.shutil, "which", return_value="java"), \
                patch.object(RUNNER.subprocess, "run") as child:
            with self.assertRaises(ValueError):
                RUNNER.launch_java(self.output, "cp", "forward-fixture", "success", None, "synthetic")
        child.assert_not_called()

    def test_no_endpoint_or_inline_key_argument_exists(self):
        for args in (("--upstream", "https://example.invalid"), ("--api-key", "private"), ("--base-url", "http://127.0.0.1")):
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                RUNNER.parser().parse_args(["--output", "unused"] + list(args))

    def test_build_failure_preserves_evidence_and_uses_fixed_failure_message(self):
        def fail(output):
            (output / "partial.txt").write_text("keep", encoding="utf-8")
            raise RuntimeError("untrusted detail should not be logged")
        with patch.object(RUNNER.BASE, "prepare", side_effect=fail) as build, \
                patch.object(RUNNER, "read_credential") as key:
            with self.assertRaisesRegex(RuntimeError, "partial evidence"):
                RUNNER.execute(self.output)
        build.assert_called_once()
        key.assert_not_called()
        self.assertEqual((self.output / "partial.txt").read_text(), "keep")
        failure = (self.output / "failure.json").read_text(encoding="utf-8")
        self.assertNotIn("untrusted detail", failure)
        self.assertTrue(json.loads(failure)["evidence_retained"])

    def test_real_key_is_read_only_after_gates_and_successful_build(self):
        order = []
        with patch.object(RUNNER.BASE, "prepare", side_effect=lambda out: order.append("build") or "cp"), \
                patch.object(RUNNER, "read_credential", side_effect=lambda: order.append("credential") or "synthetic"), \
                patch.object(RUNNER, "run_one", side_effect=lambda *args: order.append("run") or {"status": "COMPLETED_WITH_RECORDS"}):
            RUNNER.execute(self.output, "real", "success", True, HASH)
        self.assertEqual(order, ["build", "credential", "run"])

    def test_result_source_and_external_accounting_cannot_be_mislabeled(self):
        self.output.mkdir(parents=True)
        scorer = SimpleNamespace(parse_json=json.loads, score=Mock(), read_jsonl=lambda path: [])
        variants = [
            ({"execution": "real_model"}, {"execution": "synthetic_test", "upstream_requests": 1, "real_model_calls": 0, "database_calls": 0}),
            ({"execution": "synthetic_test"}, {"execution": "synthetic_test", "upstream_requests": 1, "real_model_calls": 1, "database_calls": 0}),
            ({"execution": "synthetic_test"}, {"execution": "synthetic_test", "upstream_requests": True, "real_model_calls": 0, "database_calls": 0}),
            ({"execution": "synthetic_test"}, {"execution": "synthetic_test", "upstream_requests": 1, "real_model_calls": 0, "database_calls": 1}),
        ]
        for results, run in variants:
            with self.subTest(run=run):
                (self.output / "results.json").write_text(json.dumps(results), encoding="utf-8")
                (self.output / "run.json").write_text(json.dumps(run), encoding="utf-8")
                with patch.object(RUNNER.BASE, "load_module", return_value=scorer), self.assertRaises(ValueError):
                    RUNNER.score_output(self.output, "forward-fixture", "success", {"manifest_sha256": HASH})
        scorer.score.assert_not_called()
        self.assertFalse((self.output / "score.json").exists())

    def test_entry_error_output_never_prints_exception_payload(self):
        capture = io.StringIO()
        with patch.object(RUNNER, "execute", side_effect=RuntimeError("synthetic-private-token")), \
                contextlib.redirect_stdout(capture):
            code = RUNNER.main(["--output", "unused"])
        self.assertEqual(code, 1)
        self.assertNotIn("synthetic-private-token", capture.getvalue())
        self.assertEqual(json.loads(capture.getvalue())["error"], "E3_ENTRY_STOPPED")


if __name__ == "__main__":
    unittest.main()
