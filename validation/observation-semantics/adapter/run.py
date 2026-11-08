"""Build and run the observation evaluator against a loopback protocol fixture only.

No live mode, upstream URL, credential option, database, or network downloader exists.
All files produced by a failed run are retained for inspection.
"""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import zipfile


ADAPTER = Path(__file__).resolve().parent
ASSETS = ADAPTER.parent
REPO = ASSETS.parents[1]
OUTPUT_ROOT = REPO / ".local-validation" / "observation-semantics-eval"
APP_JAR = REPO / "backend" / "target" / "printer-after-sales-0.0.1-SNAPSHOT.jar"
SCENARIOS = (
    "success", "invalid-json", "second-sentinel-invalid", "third-sentinel-invalid",
    "tool-response", "finish-length", "timeout", "http-401", "http-429", "http-503",
    "call-budget", "byte-budget", "total-byte-budget",
)
SAFE_ENV_NAMES = (
    "PATH", "PATHEXT", "SystemRoot", "SYSTEMROOT", "WINDIR", "COMSPEC",
    "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "HOME", "APPDATA", "LOCALAPPDATA",
    "TEMP", "TMP", "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "LANG", "LC_ALL",
)


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify_frozen():
    return load_module("observation_frozen_verify", ASSETS / "verify.py").verify()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def reserve_output(path):
    """Resolve symlinks before accepting an entirely new, ignored run directory."""
    path = Path(path).resolve()
    allowed = OUTPUT_ROOT.resolve()
    if allowed != OUTPUT_ROOT.absolute():
        raise ValueError("Ignored output root must not redirect through a symlink or junction")
    if path == allowed or not path.is_relative_to(allowed):
        raise ValueError("Output must be a new child of .local-validation/observation-semantics-eval/")
    if path.exists():
        raise ValueError("Output already exists; use a new directory. Existing evidence is never overwritten.")
    path.mkdir(parents=True, exist_ok=False)
    return path


def safe_environment():
    # Explicit names only: do not enumerate or forward private model/proxy/JVM options.
    result = {}
    seen = set()
    for name in SAFE_ENV_NAMES:
        if name.lower() not in seen and name in os.environ:
            result[name] = os.environ[name]
            seen.add(name.lower())
    return result


def command(args, output, name):
    environment = safe_environment()
    executable = shutil.which(args[0], path=environment.get("PATH", os.defpath))
    if executable is None:
        raise RuntimeError(f"Required local tool not found: {args[0]}; no retry or download is attempted")
    result = subprocess.run([executable] + list(args[1:]), cwd=REPO, env=environment,
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    (output / f"{name}.stdout.log").write_text(result.stdout, encoding="utf-8")
    (output / f"{name}.stderr.log").write_text(result.stderr, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"{name} failed with exit {result.returncode}; retained logs: {output}")
    return result


def require_java17(output):
    result = command(["java", "-version"], output, "java-version")
    match = re.search(r'(?:openjdk|java) version "(\d+)', result.stdout + result.stderr)
    if not match or int(match.group(1)) != 17:
        raise ValueError("Java 17 is required; no application or fixture was started.")
    return (result.stdout + result.stderr).strip()


def extract_dependencies(jar_path, target):
    """Extract only flat BOOT-INF/lib JARs; reject traversal, symlinks and duplicates."""
    with zipfile.ZipFile(jar_path) as archive:
        members = []
        names = set()
        for member in archive.infolist():
            if not member.filename.startswith("BOOT-INF/lib/") or not member.filename.endswith(".jar"):
                continue
            path = PurePosixPath(member.filename)
            mode = member.external_attr >> 16
            if (len(path.parts) != 3 or path.parts[:2] != ("BOOT-INF", "lib")
                    or "\\" in member.filename or path.name in names
                    or stat.S_ISLNK(mode) or member.is_dir()):
                raise ValueError("Unsafe or duplicate dependency member in local application JAR")
            names.add(path.name)
            members.append((member, path.name))
        if not members:
            raise ValueError("No BOOT-INF/lib dependencies found; build the application JAR locally first.")
        target.mkdir(parents=True, exist_ok=False)
        result = {}
        for member, name in members:
            path = target / name
            with path.open("xb") as stream:
                stream.write(archive.read(member))
            result[name] = sha256(path)
        return result


def prepare(output):
    runtime = require_java17(output)
    command(["mvn", "-o", "-B", "-ntp", "-f", "backend/pom.xml", "-DskipTests", "compile"],
            output, "compile-production")
    if not APP_JAR.is_file():
        raise ValueError("Local application JAR is required for dependency JARs; no download is attempted.")
    build = output / ".build"
    build.mkdir()
    libs = build / "lib"
    dependencies = extract_dependencies(APP_JAR, libs)
    classes = build / "classes"
    classes.mkdir()
    sources = sorted(ADAPTER.glob("*.java"))
    if not sources:
        raise ValueError("Adapter Java source is missing")
    classpath = os.pathsep.join((str(REPO / "backend/target/classes"), str(libs / "*")))
    command(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes)]
            + [str(path) for path in sources], output, "compile-adapter")
    info = {
        "mode": "offline_build_only", "real_model_calls": 0,
        "java_version": runtime, "business_classes": "backend/target/classes",
        "dependency_source_jar_sha256": sha256(APP_JAR), "dependency_hashes": dependencies,
        "adapter_source_hashes": {path.name: sha256(path)
                                  for path in sources + sorted(ADAPTER.glob("*.py"))},
    }
    write_json(output / "build.json", info)
    return os.pathsep.join((str(classes), classpath))


def run_fixture(output, scenario, classpath, frozen):
    command(["java", "-cp", classpath, "local.printer.assistant.ObservationEvalAdapter",
             str(ASSETS), str(output), scenario], output, "fixture")
    frozen_after = verify_frozen()
    if frozen_after["manifest_sha256"] != frozen["manifest_sha256"]:
        raise ValueError("Frozen manifest changed during this run")
    scorer = load_module("observation_offline_scorer", ASSETS / "score.py")
    results = scorer.parse_json((output / "results.json").read_text(encoding="utf-8"))
    run = scorer.parse_json((output / "run.json").read_text(encoding="utf-8"))
    if (results.get("execution") != "synthetic_test" or run.get("real_model_calls") != 0
            or run.get("execution") != "synthetic_test"):
        raise ValueError("Fixture adapter produced a non-fixture evidence declaration")
    report = scorer.score(scorer.read_jsonl(ASSETS / "cases.jsonl"),
                          scorer.read_jsonl(ASSETS / "gold.jsonl"), results,
                          frozen["manifest_sha256"],
                          scorer.parse_json((ASSETS / "config.json").read_text(encoding="utf-8")))
    write_json(output / "score.json", report)
    summary = {
        "status": "COMPLETED", "scenario": scenario, "execution": "synthetic_test",
        "evidence_label": "Protocol fixture validation only; not model-quality evidence",
        "real_model_calls": 0, "counts": report["counts"], "output": str(output),
    }
    write_json(output / "summary.json", summary)
    return summary


def execute(output, scenario, prepare_only=False):
    if scenario not in SCENARIOS + ("all",):
        raise ValueError("Unknown protocol fixture scenario")
    frozen = verify_frozen()
    output = reserve_output(output)
    try:
        classpath = prepare(output)
        if prepare_only:
            verify_frozen()
            summary = {"status": "PREPARED", "mode": "loopback_protocol_fixture_only",
                       "real_model_calls": 0, "output": str(output)}
            write_json(output / "prepared.json", summary)
            return summary
        if scenario == "all":
            runs = [run_fixture(reserve_output(output / name), name, classpath, frozen)
                    for name in SCENARIOS]
            summary = {
                "status": "COMPLETED", "scenario": "all", "execution": "synthetic_test",
                "evidence_label": "Protocol fixture validation only; not model-quality evidence",
                "real_model_calls": 0, "scenario_count": len(runs), "scenario_runs": runs,
                "output": str(output),
            }
            write_json(output / "summary.json", summary)
            return summary
        return run_fixture(output, scenario, classpath, frozen)
    except Exception as failure:
        write_json(output / "failure.json", {
            "status": "FAILED", "mode": "loopback_protocol_fixture_only", "real_model_calls": 0,
            "error_type": type(failure).__name__, "error": str(failure), "evidence_retained": True,
        })
        raise


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--scenario", choices=SCENARIOS + ("all",), default="success")
    result.add_argument("--output", required=True, type=Path,
                        help="New run directory below .local-validation/observation-semantics-eval/")
    result.add_argument("--prepare-only", action="store_true", help="Compile locally without starting a fixture")
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        summary = execute(args.output, args.scenario, args.prepare_only)
    except (OSError, ValueError, RuntimeError, KeyError, zipfile.BadZipFile) as failure:
        print(json.dumps({"status": "FAILED", "error": str(failure)}, ensure_ascii=False))
        return 1
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
