"""Boundary tests for the fixture-only runner; no Java or external services are run."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("fixture_runner", Path(__file__).with_name("run.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class RunnerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.allowed = self.root / "ignored-runs"
        self.patches = patch.object(RUNNER, "OUTPUT_ROOT", self.allowed)
        self.patches.start()
        self.addCleanup(self.patches.stop)

    def test_output_must_be_new_child_and_existing_evidence_is_preserved(self):
        for candidate in (self.allowed, self.root / "outside", self.allowed / ".." / "outside"):
            with self.subTest(path=candidate), self.assertRaises(ValueError):
                RUNNER.reserve_output(candidate)
        accepted = RUNNER.reserve_output(self.allowed / "new-run")
        evidence = accepted / "result.json"
        evidence.write_text("preserve me", encoding="utf-8")
        with self.assertRaises(ValueError):
            RUNNER.reserve_output(accepted)
        self.assertEqual(evidence.read_text(encoding="utf-8"), "preserve me")

    def test_symlink_parent_cannot_escape_allowed_root(self):
        self.allowed.mkdir()
        outside = self.root / "outside"
        outside.mkdir()
        try:
            (self.allowed / "link").symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("OS does not allow directory symlinks for this user")
        with self.assertRaises(ValueError):
            RUNNER.reserve_output(self.allowed / "link" / "run")

    def test_redirected_output_root_is_rejected_before_creation(self):
        with patch.object(Path, "resolve", return_value=self.root / "redirected"), \
                self.assertRaisesRegex(ValueError, "redirect"):
            RUNNER.reserve_output(self.allowed / "run")
        self.assertFalse(self.allowed.exists())

    def make_jar(self, entries):
        path = self.root / "app.jar"
        with zipfile.ZipFile(path, "w") as jar:
            for name, value in entries:
                jar.writestr(name, value)
        return path

    def test_extracts_flat_dependencies_without_business_classes(self):
        jar = self.make_jar([
            ("BOOT-INF/lib/library.jar", b"library"),
            ("BOOT-INF/classes/local/Production.class", b"old business class"),
        ])
        target = self.root / "lib"
        result = RUNNER.extract_dependencies(jar, target)
        self.assertEqual(list(result), ["library.jar"])
        self.assertEqual(list(path.name for path in target.iterdir()), ["library.jar"])

    def test_dependency_traversal_or_duplicate_is_rejected_before_extraction(self):
        for bad in ("BOOT-INF/lib/../../escape.jar", "BOOT-INF/lib/nested/library.jar", "BOOT-INF/lib/bad\\name.jar"):
            with self.subTest(member=bad):
                jar = self.make_jar([("BOOT-INF/lib/good.jar", b"ok"), (bad, b"bad")])
                target = self.root / "lib"
                with self.assertRaises(ValueError):
                    RUNNER.extract_dependencies(jar, target)
                self.assertFalse(target.exists())
        with contextlib.redirect_stderr(io.StringIO()):
            jar = self.make_jar([("BOOT-INF/lib/a.jar", b"one"), ("BOOT-INF/lib/a.jar", b"two")])
        with self.assertRaises(ValueError):
            RUNNER.extract_dependencies(jar, self.root / "lib")

    def test_no_live_mode_or_url_option_can_be_requested(self):
        for args in (("--mode", "real"), ("--upstream", "https://example.invalid"), ("--scenario", "real")):
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as stop:
                RUNNER.parser().parse_args(["--output", "unused"] + list(args))
            self.assertEqual(stop.exception.code, 2)

    def test_subprocess_environment_excludes_keys_proxy_and_jvm_injection(self):
        values = {"PATH": "tool-path", "SystemRoot": "system-path", "DASHSCOPE_API_KEY": "private",
                  "AI_API_KEY": "private", "HTTPS_PROXY": "private", "JAVA_TOOL_OPTIONS": "private"}
        with patch.dict(os.environ, values, clear=True):
            actual = RUNNER.safe_environment()
        self.assertEqual(actual, {"PATH": "tool-path", "SystemRoot": "system-path"})

    def test_command_resolves_windows_maven_cmd_before_launch_without_shell(self):
        output = self.root / "command-output"
        output.mkdir()
        completed = RUNNER.subprocess.CompletedProcess([], 0, "offline build", "")
        with patch.object(RUNNER, "safe_environment", return_value={"PATH": "fixture-path"}), \
                patch.object(RUNNER.shutil, "which", return_value="D:/maven/bin/mvn.CMD") as which, \
                patch.object(RUNNER.subprocess, "run", return_value=completed) as launch:
            RUNNER.command(["mvn", "-o", "compile"], output, "maven")
        which.assert_called_once_with("mvn", path="fixture-path")
        launch.assert_called_once()
        self.assertEqual(launch.call_args.args[0], ["D:/maven/bin/mvn.CMD", "-o", "compile"])
        self.assertNotIn("shell", launch.call_args.kwargs)
        self.assertEqual(launch.call_args.kwargs["env"], {"PATH": "fixture-path"})
        self.assertEqual((output / "maven.stdout.log").read_text(encoding="utf-8"), "offline build")

    def test_missing_tool_is_reported_without_process_or_retry(self):
        with patch.object(RUNNER, "safe_environment", return_value={"PATH": "fixture-path"}), \
                patch.object(RUNNER.shutil, "which", return_value=None) as which, \
                patch.object(RUNNER.subprocess, "run") as launch, \
                self.assertRaisesRegex(RuntimeError, "Required local tool not found: mvn"):
            RUNNER.command(["mvn", "-o", "compile"], self.root, "maven")
        which.assert_called_once_with("mvn", path="fixture-path")
        launch.assert_not_called()

    def test_freeze_failure_prevents_build_and_output_creation(self):
        output = self.allowed / "run"
        with patch.object(RUNNER, "verify_frozen", side_effect=ValueError("hash mismatch")), \
                patch.object(RUNNER, "prepare") as prepare:
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                RUNNER.execute(output, "success")
        self.assertFalse(output.exists())
        prepare.assert_not_called()

    def test_build_failure_is_not_retried_and_partial_evidence_is_retained(self):
        output = self.allowed / "run"
        def fail(directory):
            (directory / "build.log").write_text("evidence", encoding="utf-8")
            raise RuntimeError("compile failed")
        with patch.object(RUNNER, "verify_frozen", return_value={"manifest_sha256": "a" * 64}), \
                patch.object(RUNNER, "prepare", side_effect=fail) as prepare:
            with self.assertRaisesRegex(RuntimeError, "compile failed"):
                RUNNER.execute(output, "success")
        prepare.assert_called_once()
        self.assertEqual((output / "build.log").read_text(encoding="utf-8"), "evidence")
        self.assertTrue(json.loads((output / "failure.json").read_text(encoding="utf-8"))["evidence_retained"])

    def test_prepare_only_does_not_start_java_fixture(self):
        output = self.allowed / "run"
        with patch.object(RUNNER, "verify_frozen", return_value={"manifest_sha256": "a" * 64}), \
                patch.object(RUNNER, "prepare", return_value="class-path"), \
                patch.object(RUNNER, "command") as command:
            result = RUNNER.execute(output, "success", prepare_only=True)
        self.assertEqual(result["status"], "PREPARED")
        self.assertEqual(result["real_model_calls"], 0)
        command.assert_not_called()
        self.assertFalse((output / "results.json").exists())

    def test_all_scenarios_share_one_build_and_have_separate_new_outputs(self):
        output = self.allowed / "all-run"
        def scenario_summary(directory, name, classpath, frozen):
            self.assertTrue(directory.is_dir())
            self.assertEqual(classpath, "shared-classes")
            return {"scenario": name, "execution": "synthetic_test", "real_model_calls": 0}
        with patch.object(RUNNER, "verify_frozen", return_value={"manifest_sha256": "a" * 64}), \
                patch.object(RUNNER, "prepare", return_value="shared-classes") as prepare, \
                patch.object(RUNNER, "run_fixture", side_effect=scenario_summary) as run:
            summary = RUNNER.execute(output, "all")
        prepare.assert_called_once_with(output.resolve())
        self.assertEqual(run.call_count, len(RUNNER.SCENARIOS))
        self.assertEqual(summary["scenario_count"], len(RUNNER.SCENARIOS))
        self.assertEqual([x["scenario"] for x in summary["scenario_runs"]], list(RUNNER.SCENARIOS))

    def test_unknown_library_scenario_is_rejected_before_any_setup(self):
        with patch.object(RUNNER, "verify_frozen") as verify, \
                self.assertRaisesRegex(ValueError, "Unknown"):
            RUNNER.execute(self.allowed / "run", "https://example.invalid")
        verify.assert_not_called()


if __name__ == "__main__":
    unittest.main()
