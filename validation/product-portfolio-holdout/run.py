"""One-use holdout publication and real assistant run, bound to an explicitly frozen JAR.

The authored package is separate from the original 12 questions. No SQL, no
response injection, no edits to runtime.sample, and no automatic model retries.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import math
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import portfolio_runtime as runtime


class HoldoutClient(runtime.Client):
    """Keep the ordinary session/CSRF protocol; pace turns without retrying them."""
    def __init__(self, base):
        self.last_message_sent = None
        super().__init__(base)

    def request(self, method, path, body=None, form=None, raw=None, content_type=None):
        message = method == "POST" and path.startswith("/api/conversations/") and path.endswith("/messages")
        if message:
            if self.last_message_sent is not None:
                time.sleep(max(0, 6.0 - (time.monotonic() - self.last_message_sent)))
            self.last_message_sent = time.monotonic()
        data = raw if raw is not None else urllib.parse.urlencode(form).encode() if form is not None else json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        headers = {"Content-Type": content_type or ("application/x-www-form-urlencoded" if form is not None else "application/json")}
        if self.token:
            headers["X-CSRF-TOKEN"] = self.token
        try:
            with self.opener.open(urllib.request.Request(self.base + path, data=data, headers=headers, method=method), timeout=195 if message else 40) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as error:
            try:
                code = json.loads(error.read()).get("code", "UNKNOWN")
            except (ValueError, AttributeError):
                code = "UNKNOWN"
            raise runtime.Failure(f"HTTP_{error.code}_{code}: {method} {path}") from None


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify(package):
    freeze = runtime.read(package / "freeze.json")
    assert set(freeze["files"]) == {"PRINT-HOLDOUT-02.md", "questions.json", "LICENSE.txt"}
    assert all(sha(package / name) == checksum for name, checksum in freeze["files"].items()), "HOLDOUT_CHANGED"
    questions = runtime.read(package / "questions.json")
    assert questions["model"] == "PRINT-HOLDOUT-02" and len(questions["cases"]) == 8
    assert sum(c["answerable"] for c in questions["cases"]) == 6
    assert len({c["id"] for c in questions["cases"]}) == 8
    return freeze, questions


def bound_runtime(instance, expected):
    assert expected and len(expected) == 64, "explicit final JAR hash required before external execution"
    c = runtime.load(instance)
    assert c["mode"] == "real" and c["jar_sha256"] == expected and sha(c["jar"]) == expected, "FINAL_PACKAGE_MISMATCH"
    state = runtime.read(Path(c["folder"]) / "state.json")
    assert state.get("java") and runtime.owned(state["java"]), "FINAL_INSTANCE_NOT_RUNNING"
    assert state["base"] == "http://127.0.0.1:" + str(c["ports"]["app"])
    account = c["roles"]["admin"]
    admin = runtime.Client(state["base"]).login(account["email"], account["password"])
    assert admin.request("GET", "/api/service-status")["deploymentMode"] == "PORTFOLIO_REAL"
    return c, state["base"], admin


def publish(package, c, admin, fixture, folder):
    model = "PRINT-HOLDOUT-02"
    if not fixture.get("model_id"):
        value = admin.request("POST", "/api/admin/models", dict(modelCode=model, brand="纸序合成保留集设备", aliases=[], isDemo=True, requestId=fixture["requests"]["model"]))
        fixture["model_id"] = value["id"]
        runtime.write(folder / "fixture.json", fixture)
    if not fixture.get("order_id"):
        value = admin.request("POST", "/api/admin/orders", dict(orderNo="DEMO-HOLDOUT-" + fixture["freeze_sha256"][:12].upper(), customerId=c["roles"]["customer"]["id"],
                            modelId=fixture["model_id"], serialNo="SYNTHETIC-HOLDOUT-02", purchaseDate="2026-11-03", isDemo=True,
                            verificationNote="独立保留集合成订单；不代表真实购买或客户", requestId=fixture["requests"]["order"]))
        fixture["order_id"] = value["id"]
        runtime.write(folder / "fixture.json", fixture)
    source = package / "PRINT-HOLDOUT-02.md"
    if not fixture.get("document_id"):
        metadata = dict(requestId=fixture["requests"]["upload"], documentKey="portfolio-holdout-02-" + fixture["freeze_sha256"][:12],
                        title="PRINT-HOLDOUT-02 合成记录箱附录", version="HOLDOUT-2026-11-03-v1", sourceType="self_authored_simulation", isSimulated=True,
                        modelScope="MODELS", models=[model], source={"description": "CC0-1.0 AI自编并核对的合成保留集；不是厂商资料", "checkedOn": "2026-11-03"},
                        useCondition="仅用于虚构型号PRINT-HOLDOUT-02与合成订单；允许本次配置处理，不适用于真实设备", distributionAllowed=True,
                        processingPermissions={p: {"allowed": True, "configurationId": c["profile"]} for p in ("embedding", "rerank", "chat")}, chunkSize=500, chunkOverlap=50)
        boundary = "holdout" + uuid.uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n' + json.dumps(metadata, ensure_ascii=False)
                + f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="PRINT-HOLDOUT-02.md"\r\nContent-Type: text/markdown\r\n\r\n').encode() + source.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        value = admin.request("POST", "/api/admin/knowledge", raw=body, content_type="multipart/form-data; boundary=" + boundary)
        fixture["document_id"] = value["id"]
        runtime.write(folder / "fixture.json", fixture)
    doc_id = fixture["document_id"]
    if not fixture.get("publication_requested"):
        preview = admin.request("GET", f"/api/admin/knowledge/{doc_id}/preview?revision=1&pageSize=100")
        assert preview["reviewComplete"] and preview.get("nextCursor") is None, "PREVIEW_INCOMPLETE"
        runtime.write(folder / "preview.json", preview)
        request = {key: preview[key] for key in ("revision", "previewToken", "contentHash", "layoutHash", "configHash")}
        request.update(requestId=fixture["requests"]["publish"], expectedVersion=preview["version"])
        value = admin.request("POST", f"/api/admin/knowledge/{doc_id}/publish", request)
        runtime.write(folder / "publication-request.json", value)
        fixture["publication_requested"] = True
        runtime.write(folder / "fixture.json", fixture)
    def ready():
        value = admin.request("GET", f"/api/admin/knowledge/{doc_id}")
        if value.get("active") is True and value.get("activeGeneration", {}).get("revision") == 1:
            return value
    detail = runtime.wait(ready, 180)
    runtime.write(folder / "published-detail.json", detail)
    fixture["published"] = True
    fixture["review_type"] = "AI_AUTHORED_SOURCE_AND_AUTOMATED_PREVIEW_NOT_HUMAN_REVIEW"
    runtime.write(folder / "fixture.json", fixture)
    return fixture


def execute(package, questions, c, base, admin, fixture, folder, exposure, baseline=None):
    path = folder / "run.json"
    assert not path.exists(), "HOLDOUT_ALREADY_CONSUMED: inspect prior results; no automatic rerun"
    assert fixture.get("published")
    if exposure == "FIRST_FROZEN_RUN":
        assert fixture["jar_sha256"] == c["jar_sha256"]
    account = c["roles"]["customer"]
    customer = HoldoutClient(base).login(account["email"], account["password"])
    operations = admin.request("GET", "/api/admin/operations")
    budget = operations["budget"]
    daily = next((row for row in budget["counters"] if row["budget_key"].count(":") == 1), {"attempts": 0, "input_bytes": 0})
    assert budget["dailyCalls"] - daily["attempts"] >= 8 * 12, "INSUFFICIENT_CONSERVATIVE_CALL_HEADROOM"
    assert budget["dailyInputBytes"] - daily["input_bytes"] >= 8 * 120000, "INSUFFICIENT_CONSERVATIVE_BYTE_HEADROOM"
    report = dict(dataset=questions["dataset"], jar_sha256=c["jar_sha256"], freeze_sha256=fixture["freeze_sha256"], status="RUNNING", attempts_per_case=1,
                  exposure=exposure, source_publication_jar_sha256=fixture["jar_sha256"], baseline_run=baseline,
                  authorship=questions["authorship"], operator="AUTOMATED_REAL_HTTP_NOT_CUSTOMER_TRIAL", operations_before=operations,
                  source_document_id=fixture["document_id"], order_id=fixture["order_id"], answers=[], started_at=datetime.now(timezone.utc).isoformat(),
                  request_protection={"initial_quiet_seconds": 60, "minimum_message_start_interval_seconds": 6, "http_turn_timeout_seconds": 195, "automatic_retries": 0})
    # Atomic creation is the execution claim: concurrent starts cannot both send turns.
    with path.open("x", encoding="utf-8") as target:
        json.dump(report, target, ensure_ascii=False, indent=2)
        target.write("\n")
    try:
        print("Waiting 60 seconds for prior request-protection windows to expire; no model call is sent.", flush=True)
        time.sleep(60)
        report["quiet_period_finished_at"] = datetime.now(timezone.utc).isoformat()
        runtime.write(path, report)
        for case in questions["cases"]:
            row = dict(case_id=case["id"], question=case["question"], expected_answerable=case["answerable"], status="STARTED", turn_id=str(uuid.uuid4()), ai_semantic_review="PENDING", human_reviewed=False)
            report["answers"].append(row)
            runtime.write(path, report)
            started = time.monotonic()
            try:
                created = customer.request("POST", "/api/conversations", {"orderId": fixture["order_id"]})
                row["conversation_id"] = created["id"]
                runtime.write(path, report)
                reply = customer.request("POST", f'/api/conversations/{created["id"]}/messages', {"turnId": row["turn_id"], "content": case["question"]})
                runtime.write(folder / (case["id"] + "-raw.json"), reply)
                answer = reply["assistantMessage"]["response"]
                row.update(status="EXECUTED", answer=answer, assistant_message_id=reply["assistantMessage"]["id"])
                citations = answer.get("citations", [])
                row["citation_scope_valid"] = all(s.get("simulated") is True and s.get("models") == ["PRINT-HOLDOUT-02"] and s.get("documentId") == fixture["document_id"] for s in citations)
                points = answer.get("points", [])
                row["point_indices_valid"] = (isinstance(points, list) and (answer.get("status") != "ANSWERED" or bool(points))
                    and all(isinstance(point, dict) and isinstance(point.get("citations"), list) and bool(point["citations"])
                            and all(type(i) is int and 1 <= i <= len(citations) for i in point["citations"]) for point in points))
                row["status_contract_passed"] = (answer.get("status") == "ANSWERED" and bool(citations)) if case["answerable"] else (answer.get("status") == "NO_EVIDENCE" and not citations)
                print(case["id"] + " " + answer.get("status", "UNKNOWN"), flush=True)
            except Exception as exc:
                row.update(status="FAILED_OR_UNKNOWN", failure=str(exc), retry="NOT_PERFORMED")
                print(case["id"] + " FAILED_OR_UNKNOWN", flush=True)
            row["latency_ms"] = round((time.monotonic() - started) * 1000)
            runtime.write(path, report)
        report["operations_after"] = admin.request("GET", "/api/admin/operations")
        latencies = sorted(row["latency_ms"] for row in report["answers"])
        report["latency"] = {"population": "all 8 attempted turns including failures", "p50_ms": latencies[math.ceil(len(latencies) * .5) - 1], "p95_ms": latencies[math.ceil(len(latencies) * .95) - 1]}
        report["status"] = "EXECUTION_COMPLETE_SUPPORT_REVIEW_PENDING" if all(row["status"] == "EXECUTED" for row in report["answers"]) else "EXECUTION_HAS_FAILURES"
        report["automated_contract_passed"] = sum(bool(row.get("status_contract_passed") and row.get("citation_scope_valid") and row.get("point_indices_valid")) for row in report["answers"])
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        runtime.write(path, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["verify", "publish", "run"])
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--instance")
    parser.add_argument("--expected-jar-sha")
    parser.add_argument("--exposure", choices=["FIRST_FROZEN_RUN", "SEEN_REPAIR_REGRESSION"], default="FIRST_FROZEN_RUN")
    parser.add_argument("--regression-label", help="Required unique directory label for an explicit seen-data regression")
    args = parser.parse_args()
    if args.exposure == "SEEN_REPAIR_REGRESSION":
        if args.action != "run" or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", args.regression_label or ""):
            parser.error("seen regression requires run and a unique lowercase --regression-label")
    elif args.regression_label:
        parser.error("regression label requires SEEN_REPAIR_REGRESSION")
    package = args.package.resolve()
    freeze, questions = verify(package)
    if args.action == "verify":
        print(json.dumps({"status": "PASS_OFFLINE_FROZEN_HOLDOUT", "freeze_sha256": sha(package / "freeze.json"), "cases": 8, "external_calls": 0}))
        return
    c, base, admin = bound_runtime(args.instance, args.expected_jar_sha)
    key = sha(package / "freeze.json")
    folder = Path(c["folder"]) / ("holdout-" + key[:12])
    folder.mkdir(exist_ok=True)
    fixture_path = folder / "fixture.json"
    if fixture_path.exists():
        fixture = runtime.read(fixture_path)
        assert fixture["freeze_sha256"] == key, "HOLDOUT_BINDING_CHANGED"
        if args.exposure == "FIRST_FROZEN_RUN":
            assert fixture["jar_sha256"] == c["jar_sha256"], "HOLDOUT_JAR_BINDING_CHANGED"
    else:
        assert args.exposure == "FIRST_FROZEN_RUN", "SEEN_REGRESSION_REQUIRES_EXISTING_FIXTURE"
        fixture = dict(freeze_sha256=key, jar_sha256=c["jar_sha256"], package=str(package), requests={k: str(uuid.uuid4()) for k in ("model", "order", "upload", "publish")})
        runtime.write(fixture_path, fixture)
    if args.action == "publish":
        publish(package, c, admin, fixture, folder)
        print(json.dumps({"status": "PUBLISHED", "fixture": str(fixture_path)}))
    else:
        baseline = None
        if args.exposure == "SEEN_REPAIR_REGRESSION":
            original_path = folder / "run.json"
            original = runtime.read(original_path)
            assert original["freeze_sha256"] == key and original["dataset"] == questions["dataset"], "BASELINE_MISMATCH"
            assert len(original["answers"]) == 8 and all(row["status"] != "STARTED" for row in original["answers"]), "BASELINE_NOT_FINISHED"
            baseline = {"path": str(original_path), "sha256": sha(original_path), "jar_sha256": original["jar_sha256"]}
            folder = folder / "seen-regressions" / args.regression_label
            folder.mkdir(parents=True, exist_ok=True)
        report = execute(package, questions, c, base, admin, fixture, folder, args.exposure, baseline)
        print(json.dumps({"status": report["status"], "report": str(folder / "run.json"), "automated_contract_passed": report["automated_contract_passed"]}))
        if report["status"] != "EXECUTION_COMPLETE_SUPPORT_REVIEW_PENDING" or report["automated_contract_passed"] != 8:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
