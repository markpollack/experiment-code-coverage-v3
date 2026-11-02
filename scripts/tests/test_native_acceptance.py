import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from accept_native_coverage import Evidence, GateError, evaluate, freeze_receipt_inputs, jury_verdict


class NativeAcceptanceTest(unittest.TestCase):
    def test_failed_full_jury_refuses_even_when_every_individual_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            passing = {"producerStatus": "pass"}
            envelope = {"schemaVersion": 6, "judgment": {"producerStatus": "fail"},
                        "individualByName": {name: passing for name in ("edit_surface", "t0_build", "t1_coverage")}}
            producer = json.dumps(envelope)
            row = {"identity": {"runId": "one"}, "judgeState": "RETURNED",
                   "invocations": [{"attempt": {"invocationId": "original"}}],
                   "verdicts": [{"producerJson": producer, "sha256": hashlib.sha256(producer.encode()).hexdigest(),
                                 "conclusion": "FAIL"}]}
            result = root / "run.json"
            result.write_text(json.dumps({"items": [row]}))
            with self.assertRaises(GateError) as error:
                jury_verdict(row, result, root, Evidence())
            self.assertEqual("TASK_FAILED", error.exception.state)

    def test_missing_full_jury_is_incomplete_not_pass(self):
        with self.assertRaises(GateError) as error:
            jury_verdict({"judgeState": "NOT_ASSESSED", "verdicts": []}, Path("unused"), Path("unused"), Evidence())
        self.assertEqual("EVIDENCE_INCOMPLETE", error.exception.state)

    def test_changed_required_evidence_is_a_contract_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.json"
            source.write_text('{"complete":true}')
            original = hashlib.sha256(source.read_bytes()).hexdigest()
            source.write_text('{"complete":false}')
            with self.assertRaises(GateError) as error:
                Evidence().json(source, original)
            self.assertEqual("CONTRACT_FAILED", error.exception.state)

    def test_missing_batch_never_turns_green_or_creates_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = evaluate(root / "missing-run", root / "missing-manifest.json")
            self.assertEqual("EVIDENCE_INCOMPLETE", receipt["outcome"])
            self.assertEqual(10, len(receipt["gates"]))
            self.assertFalse((root / "missing-run").exists())
            self.assertTrue(all(gate["state"] == "EVIDENCE_INCOMPLETE" for gate in receipt["gates"].values()))

    def test_receipt_snapshots_survive_later_ledger_and_source_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            live = root / "live"
            live.mkdir()
            ledger = live / "ledger.json"
            ledger.write_text('{"attempts": [{"runId": "one"}]}')
            script = live / "accept_native_coverage.py"
            script.write_text("original evaluator\n")
            script.with_name("analyze_fresh.py").write_text("original mapping\n")
            snapshot = root / "receipt.inputs"
            freeze_receipt_inputs(snapshot, ledger, script)
            ledger.write_text('{"attempts": [{"runId": "one"}, {"runId": "two"}]}')
            script.write_text("later evaluator\n")
            script.with_name("analyze_fresh.py").write_text("later mapping\n")
            for ref in json.loads((snapshot / "provenance.json").read_text())["files"]:
                Evidence().read(ref["snapshotPath"], ref["sha256"])
            self.assertEqual(1, len(json.loads((snapshot / "coverage-attempts.json").read_text())["attempts"]))


if __name__ == "__main__":
    unittest.main()
