"""Synthetic unit tests for the offline scorer; never model-quality evidence."""

import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("observation_score", Path(__file__).with_name("score.py"))
SCORER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCORER)


def completed(case_id, kind):
    return {"id": case_id, "status": "COMPLETED", "attempts": 1, "request_bytes": 512,
            "input_tokens": None, "output_tokens": None, "elapsed_ms": 12,
            "response_model": None, "finish_reason": "STOP", "response_text": '{"kind":"' + kind + '"}',
            "tool_call_count": 0, "effective_kind": kind, "reason": "OBSERVATION_" + kind, "skip_reason": None}


def skipped(case_id):
    row = {key: None for key in SCORER.RECORD_FIELDS}
    row.update(id=case_id, status="SKIPPED", attempts=0, request_bytes=0, skip_reason="STOPPED_AFTER_PROTOCOL_FAILURE")
    return row


class ScoreTest(unittest.TestCase):
    def setUp(self):
        self.cases = [{"id": key, "split": "semantic", "text": key, "context": {}} for key in ("F", "Q", "U")]
        self.cases.append({"id": "G", "split": "gate", "text": "gate", "context": {}})
        self.gold = [{"id": key, "expected_kind": kind} for key, kind in zip(("F", "Q", "U", "G"), ("FACT", "QUESTION", "UNCLEAR", None))]
        self.result = {"schema_version": 1, "execution": "synthetic_test", "run_id": "unit-test",
                       "manifest_sha256": "a" * 64, "requested_model": "qwen-plus",
                       "records": [completed("F", "FACT"), completed("Q", "QUESTION"), completed("U", "UNCLEAR")]}

    def evaluate(self, config=None):
        return SCORER.score(self.cases, self.gold, self.result, "a" * 64, config)

    def test_perfect_synthetic_run_preserves_unknown_usage(self):
        report = self.evaluate()
        self.assertEqual(report["metrics"]["strict_match_rate"]["value"], 1)
        self.assertEqual(report["usage"]["input_tokens_unknown_records"], 3)
        self.assertIsNone(report["usage"]["input_tokens_total"])
        self.assertEqual(report["counts"]["gate_excluded"], 1)
        self.assertEqual(report["execution"], "synthetic_test")
        self.assertEqual(report["actual_field_misfill_rate"]["status"], "unmeasured")

    def test_dangerous_fact_proposal_uses_non_fact_denominator(self):
        self.result["records"][1] = completed("Q", "FACT")
        report = self.evaluate()
        self.assertEqual(report["metrics"]["dangerous_fact_proposal_rate"], {"numerator": 1, "denominator": 2, "value": 0.5})
        self.assertEqual(report["metrics"]["question_to_fact_rate"]["value"], 1)
        self.assertEqual(report["metrics"]["fact_proposal_precision"]["value"], 0.5)

    def test_protocol_failure_cannot_count_as_correct_unclear(self):
        row = self.result["records"][2]
        row.update(reason="OBSERVATION_INVALID_RESPONSE", response_text="broken")
        report = self.evaluate()
        self.assertEqual(report["metrics"]["strict_match_rate"]["value"], 2 / 3)
        self.assertEqual(report["metrics"]["valid_response_match_rate"]["value"], 1)
        self.assertEqual(report["confusion_matrix"]["UNCLEAR"]["CONTROLLED_FAILURE"], 1)

    def test_duplicate_missing_unknown_and_gate_records_rejected(self):
        variants = [self.result["records"] + [completed("F", "FACT")],
                    self.result["records"][:2],
                    [completed("unknown", "FACT")] + self.result["records"][1:],
                    [completed("G", "FACT")] + self.result["records"][1:]]
        for records in variants:
            with self.subTest(ids=[row["id"] for row in records]):
                self.result["records"] = records
                with self.assertRaises(ValueError):
                    self.evaluate()

    def test_malformed_success_raw_is_rejected_not_repaired(self):
        for raw in ('{"kind":"FACT","kind":"UNCLEAR"}', '{"kind":"FACT"} trailing',
                    '{"kind":"FACT","extra":"x"}', '```json\n{"kind":"FACT"}\n```',
                    '{"kind":"fact"}', '{"kind":null}', '[]', '{"kind":NaN}'):
            with self.subTest(raw=raw):
                self.result["records"][0]["response_text"] = raw
                with self.assertRaises(ValueError):
                    self.evaluate()

    def test_response_kind_finish_and_tool_mismatch_rejected(self):
        for key, value in (("response_text", '{"kind":"QUESTION"}'), ("finish_reason", "LENGTH"), ("tool_call_count", 1), ("tool_call_count", None)):
            with self.subTest(key=key, value=value):
                self.result["records"][0] = completed("F", "FACT")
                self.result["records"][0][key] = value
                with self.assertRaises(ValueError):
                    self.evaluate()

    def test_failure_can_have_valid_text_when_finish_or_tools_invalid(self):
        row = self.result["records"][2]
        row.update(reason="OBSERVATION_INVALID_RESPONSE", tool_call_count=1)
        self.assertEqual(self.evaluate()["counts"]["controlled_failure"], 1)

    def test_each_production_failure_is_distinct(self):
        for reason in SCORER.FAILURES:
            with self.subTest(reason=reason):
                self.result["records"][2]["reason"] = reason
                self.assertEqual(self.evaluate()["controlled_failure_reasons"], {reason: 1})

    def test_unknown_failure_reason_and_non_unclear_fallback_rejected(self):
        self.result["records"][2]["reason"] = "MODEL_ERROR"
        with self.assertRaises(ValueError):
            self.evaluate()
        self.result["records"][2].update(reason="OBSERVATION_UNAVAILABLE", effective_kind="FACT")
        with self.assertRaises(ValueError):
            self.evaluate()

    def test_request_output_and_total_budget_rejected(self):
        for key, value in (("request_bytes", 4097), ("output_tokens", 129), ("attempts", 2), ("request_bytes", -1), ("attempts", True)):
            with self.subTest(key=key, value=value):
                self.result["records"][0] = completed("F", "FACT")
                self.result["records"][0][key] = value
                with self.assertRaises(ValueError):
                    self.evaluate()
        self.result["records"][0] = completed("F", "FACT")
        for budget in ({"max_total_request_bytes": 1535}, {"max_attempts": 2}):
            with self.subTest(budget=budget):
                with self.assertRaises(ValueError):
                    self.evaluate({"budget": budget})

    def test_skipped_denominator_and_zero_valid_denominator(self):
        self.result["records"] = [skipped(key) for key in ("F", "Q", "U")]
        report = self.evaluate()
        self.assertEqual(report["metrics"]["strict_match_rate"]["value"], 0)
        self.assertIsNone(report["metrics"]["valid_response_match_rate"]["value"])
        self.assertIsNone(report["metrics"]["fact_proposal_precision"]["value"])
        self.assertEqual(report["metrics"]["skip_rate"]["value"], 1)
        self.assertEqual(report["confusion_matrix"]["FACT"]["SKIPPED"], 1)

    def test_skip_cannot_hide_usage_or_response(self):
        for key, value in (("attempts", 1), ("request_bytes", 1), ("input_tokens", 0), ("effective_kind", "UNCLEAR"), ("skip_reason", "")):
            with self.subTest(key=key):
                self.result["records"][0] = skipped("F")
                self.result["records"][0][key] = value
                with self.assertRaises(ValueError):
                    self.evaluate()

    def test_preflight_failure_without_attempt(self):
        row = skipped("U")
        row.update(status="COMPLETED", effective_kind="UNCLEAR", reason="OBSERVATION_CONFIG_INVALID", skip_reason=None)
        self.result["records"][2] = row
        report = self.evaluate()
        self.assertEqual(report["usage"]["attempts"], 2)
        self.assertEqual(report["counts"]["controlled_failure"], 1)

    def test_manifest_model_envelope_and_gold_rejected(self):
        original = copy.deepcopy(self.result)
        for key, value in (("manifest_sha256", "b" * 64), ("requested_model", "other"), ("schema_version", True), ("execution", "unknown")):
            with self.subTest(key=key):
                self.result = copy.deepcopy(original)
                self.result[key] = value
                with self.assertRaises(ValueError):
                    self.evaluate()
        self.result = original
        self.gold[3]["expected_kind"] = "FACT"
        with self.assertRaises(ValueError):
            self.evaluate()

    def test_json_envelope_duplicate_keys_are_rejected(self):
        with self.assertRaises(ValueError):
            SCORER.parse_json('{"records":[],"records":[]}')

    def test_frozen_count_and_record_extension_rejected(self):
        with self.assertRaises(ValueError):
            self.evaluate({"budget": {"semantic_cases": 36}})
        self.result["records"][0]["unexpected"] = "not part of evidence schema"
        with self.assertRaises(ValueError):
            self.evaluate()


if __name__ == "__main__":
    unittest.main()
