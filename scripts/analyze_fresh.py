"""V3 mapping into the separately installed, private ACT canonical adapter.

No native parser, canonical writer, cost allocation or generic ACT implementation lives here.
The independent Journal reader must have accepted this exact source/canonical pair first.
"""
import argparse
import hashlib
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def selected_jury(row, result_path, evidence):
    """Keep producer bytes and bind every judgment to this immutable assessment revision."""
    result = json.loads(result_path.read_text())
    parents = [ref for ref in result.get("runtimeArtifacts", [])
               if ref.get("subject") == "selected-assessment-result"]
    assessment_path = result_path
    if parents:
        if len(parents) != 1:
            raise ValueError("Ambiguous selected assessment parent")
        assessment_path = Path(parents[0]["path"])
        if sha(assessment_path) != parents[0]["sha256"]:
            raise ValueError("Selected assessment parent hash mismatch")
        parent = json.loads(assessment_path.read_text())
        if len(parent["items"]) != 1:
            raise ValueError("Selected assessment parent cardinality mismatch")
        selected = parent["items"][0]
        if (parent["experimentId"] != result["experimentId"] or
                selected["identity"] != row["identity"] or
                selected["verdicts"] != row["verdicts"] or
                selected["invocations"][0]["attempt"] != row["invocations"][0]["attempt"]):
            raise ValueError("Selected assessment parent association mismatch")
    documents = row["verdicts"]
    if len(documents) != (1 if row["judgeState"] == "RETURNED" else 0):
        raise ValueError("Final Jury envelope cardinality mismatch")
    if not documents:
        return None, {}, []
    document = documents[0]
    if hashlib.sha256(document["producerJson"].encode("utf-8")).hexdigest() != document["sha256"]:
        raise ValueError("Final Jury producer hash mismatch")
    verdict = json.loads(document["producerJson"])
    if verdict.get("schemaVersion") != 6:
        raise ValueError("Expected full V6 Jury envelope")
    named = {}

    def collect(node):
        for name, judgment in node.get("individualByName", {}).items():
            if name in named and named[name] != judgment:
                raise ValueError("Conflicting named judgment in selected Jury")
            named[name] = judgment
        for attempt in node.get("compositeAttempts", []):
            if isinstance(attempt.get("verdict"), dict):
                collect(attempt["verdict"])

    collect(verdict)
    quality_path = evidence / "judges/quality/acquired/acquisition.json"
    quality = None
    if quality_path.exists():
        acquired = json.loads(quality_path.read_text())
        quality = {"path": str(quality_path), "sha256": sha(quality_path),
                   "invocation_id": acquired["acquisition_uid"]}
    association = {"assessment_identity": row["identity"],
                   "task_invocation_id": row["invocations"][0]["attempt"]["invocationId"],
                   "assessment_revision_path": str(assessment_path), "assessment_revision_sha256": sha(assessment_path),
                   "combined_result_path": str(result_path), "combined_result_sha256": sha(result_path),
                   "judge_evidence_root": str(evidence), "quality_acquisition": quality}
    final = {"judge_name": "final_jury", "status": document["conclusion"],
             "producer_document": document, "source_path": str(result_path),
             "source_sha256": sha(result_path), **association}
    return association, named, [final]


def mapping(result_path, export, reader_receipt, evidence, expected_tool_calls):
    result = json.loads(result_path.read_text())
    if result.get("recordingSchemaVersion") != 4 or result.get("kind") != "AGENT_CANONICAL":
        raise ValueError("Expected current Experiment canonical result")
    if len(result["items"]) != 1:
        raise ValueError("The bounded V3 path expects one item")
    row = result["items"][0]
    if len(row["invocations"]) != 1:
        raise ValueError("Expected exactly one retained task invocation")
    invocation = row["invocations"][0]
    attempt = invocation["attempt"]
    identity = row["identity"]
    if identity != attempt["row"] or identity["runId"] != result["experimentId"]:
        raise ValueError("Wrong full run/item/variant/replicate association")
    # Receipt is V3's separately retained binding to the actual packaged verifier invocation.
    receipt = json.loads(reader_receipt.read_text())
    if receipt["verifier_exit"] != 0 or receipt["invocation_id"] != attempt["invocationId"]:
        raise ValueError("Independent reader did not accept this invocation")
    if receipt["export_sha256"] != sha(export):
        raise ValueError("Changed canonical export")
    if not receipt["verified_files"]:
        raise ValueError("Reader receipt lacks checked source/canonical hashes")
    for ref in receipt["verified_files"]:
        if sha(Path(ref["path"])) != ref["sha256"]:
            raise ValueError("Changed independently checked source/canonical artifact")
    observed = {}
    association, selected, details = selected_jury(row, result_path, evidence)
    for name in ("t0_build", "t1_coverage", "t2_quality"):
        path = evidence / "judges" / f"{name}.json"
        if not path.exists():
            continue
        judgment = json.loads(path.read_text())
        if selected.get(name) != judgment:
            raise ValueError("Individual judgment differs from selected Jury revision: " + name)
        status = judgment["producerStatus"].upper()
        numeric = (judgment.get("finding") or {}).get("numeric") or {}
        observed[name] = {"status": status, "score": numeric.get("value")}
        details.append({"judge_name": name, "status": status, "judgment": judgment,
                        "source_path": str(path), "source_sha256": sha(path), **association})
    if set(selected) & {"t0_build", "t1_coverage", "t2_quality"} != set(observed):
        raise ValueError("Selected Jury is missing its individual judgment artifacts")
    states = [j["status"] for j in observed.values()]
    conclusion = row["verdicts"][0]["conclusion"] if row["verdicts"] else None
    if "ERROR" in states or row["judgeState"] != "RETURNED":
        outcome, passed = "INSTRUMENT_ERROR", None
    elif "FAIL" in states or conclusion == "FAIL":
        outcome, passed = "FAIL", False
    elif conclusion == "PASS" and len(states) == 3 and all(s == "PASS" for s in states):
        outcome, passed = "PASS", True
    else:
        outcome, passed = "NOT_EVALUATED", None
    metrics = {m["name"]: m["values"][0] if m["state"] == "MEASURED" else None
               for m in row["domain"]["measurements"]}
    domain = {"passed": passed, "outcome": outcome, "cost_usd": None,
              "duration_ms": invocation["elapsedMs"], "line_coverage": metrics.get("lineCoverage"),
              "artifact_refs": row["domain"]["artifacts"], "workflow_state": row["workflowState"],
              "response_state": invocation["responseState"], "capture_state": invocation["captureState"],
              "reader_receipt": str(reader_receipt), "reader_receipt_sha256": sha(reader_receipt),
              "experiment_result": str(result_path), "experiment_result_sha256": sha(result_path)}
    for name, value in observed.items():
        domain[name] = value["score"]
    if domain.get("t2_quality") is not None:
        domain["quality_convergence_met"] = domain["t2_quality"] >= .80
    return {"kind": "act/canonical-inputs", "version": "1", "inputs": [{
        "export_path": str(export), "export_sha256": sha(export),
        "journal_run_uid": attempt["invocationId"], "run_id": result["experimentId"],
        "execution_run_id": identity["runId"], "invocation_id": attempt["invocationId"],
        "item_id": identity["itemId"], "variant": identity["variant"],
        "replicate_index": identity["replicateIndex"], "attempt_id": attempt["workflowAttemptId"],
        "attempt_index": attempt["attemptNumber"] - 1, "domain": domain, "judge_details": details,
        "expected_tool_calls": expected_tool_calls}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--export", required=True, type=Path)
    parser.add_argument("--reader-receipt", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-tool-calls", required=True, type=int)
    args = parser.parse_args()
    for name in ("result", "export", "reader_receipt", "evidence", "output"):
        setattr(args, name, getattr(args, name).resolve())
    inputs = mapping(args.result, args.export, args.reader_receipt, args.evidence, args.expected_tool_calls)
    inputs_path = args.output.with_name(args.output.name + "-inputs.json")
    with inputs_path.open("x") as stream:
        json.dump(inputs, stream, indent=2)
    from agent_control_theory.canonical import analyze_inputs
    # Use owning ACT's default provisional full-command classifier for first vertical proof.
    analyze_inputs(inputs_path, args.output)


if __name__ == "__main__":
    main()
