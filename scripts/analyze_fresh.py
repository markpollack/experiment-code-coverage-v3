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
    """Diagnostic projection; only admit_live selects a live population for the CLI."""
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
    journal_run_uid = attempt["invocationId"]
    if "journal_run_uid" in receipt:
        journal_run_uid = receipt["journal_run_uid"]
        originals = invocation.get("acquisitions", [])
        if len(originals) != 1:
            raise ValueError("Native mapping needs exactly one retained acquisition")
        original = originals[0]
        source = Path(original["path"])
        if sha(source) != original["sha256"] or original not in receipt["verified_files"]:
            raise ValueError("Native acquisition missing from independently verified inputs")
        manifest = json.loads(source.read_text())
        if (not manifest.get("complete") or manifest.get("acquisition_uid") != journal_run_uid
                or manifest.get("run", {}).get("run_uid") != journal_run_uid
                or not isinstance(journal_run_uid, str) or not journal_run_uid):
            raise ValueError("Native source/run identity mismatch")
    mode_path = evidence / "judges/judging-mode.json"
    deterministic = mode_path.exists()
    names = {"t0_build", "t1_coverage", "t2_quality"}
    if deterministic:
        mode = json.loads(mode_path.read_text())
        if (mode.get("mode") != "deterministic-coverage-validation/1"
                or mode.get("semanticQuality") != "NOT_ASSESSED" or mode.get("judgeInferenceCalls") != 0):
            raise ValueError("Unrecognized deterministic assessment mode")
        names = {"edit_surface", "t0_build", "t1_coverage"}
    observed = {}
    association, selected, details = selected_jury(row, result_path, evidence)
    if deterministic and ("t2_quality" in selected or association and association["quality_acquisition"] is not None):
        raise ValueError("Semantic inference is outside deterministic validation")
    for name in sorted(names):
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
    if set(selected) & names != set(observed):
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
    if deterministic:
        domain.update({"validation_mode": "deterministic-coverage-validation/1",
                       "semantic_quality": "NOT_ASSESSED", "judge_inference_calls": 0,
                       "judging_mode_sha256": sha(mode_path)})
    for name, value in observed.items():
        domain[name] = value["score"]
    if domain.get("t2_quality") is not None:
        domain["quality_convergence_met"] = domain["t2_quality"] >= .80
    return {"kind": "act/canonical-inputs", "version": "1", "inputs": [{
        "export_path": str(export), "export_sha256": sha(export),
        "journal_run_uid": journal_run_uid, "run_id": result["experimentId"],
        "execution_run_id": attempt["workflowRunId"] if "journal_run_uid" in receipt else identity["runId"], "invocation_id": attempt["invocationId"],
        "item_id": identity["itemId"], "variant": identity["variant"],
        "replicate_index": identity["replicateIndex"], "attempt_id": attempt["workflowAttemptId"],
        "attempt_index": attempt["attemptNumber"] - 1, "domain": domain, "judge_details": details,
        **({"expected_tool_calls": expected_tool_calls} if expected_tool_calls is not None else {})}]}


def admit_live(mapped, manifest_path, evidence):
    """Select live rows before ACT; diagnostic PASS/coverage is not live provenance."""
    if manifest_path is None:
        raise ValueError("Exact launch manifest required for live admission")
    manifest_path, evidence = Path(manifest_path), Path(evidence)
    manifest = json.loads(manifest_path.read_text())
    root = evidence.parent
    launch = json.loads((evidence / "launch.json").read_text())
    mode = manifest.get("mode")
    if mode not in ("NATIVE", "FIXTURE", "SYNTHETIC_FIXTURE"):
        raise ValueError("Missing or ambiguous launch population")
    rows = mapped["inputs"]
    if len(rows) != 1:
        raise ValueError("Exactly one mapped attempt required")
    if manifest.get("kind") == "v3-codex-first-launch/1":
        reservation = json.loads((root / "candidate-reservation.json").read_text())
        run_id, source = manifest["runId"], manifest["sourceCommit"]
        if (reservation.get("manifestSha256") != sha(manifest_path)
                or reservation.get("runId") != run_id
                or manifest.get("outputDirectory") != str(root)):
            raise ValueError("Launch reservation/output provenance differs")
    elif manifest.get("kind") == "v3-native-coverage-launch/1":
        review = json.loads((root / "launch-review.json").read_text())
        run_id = review["runId"]
        source = manifest.get("sourceRefs", {}).get("v3", manifest.get("sourceCommit", manifest.get("source")))
        if review.get("sha256") != sha(manifest_path):
            raise ValueError("Launch review provenance differs")
    else:
        raise ValueError("Unsupported launch provenance")
    observed_source = launch.get("sourceCommit", "")
    source_matches = observed_source == source or (mode == "SYNTHETIC_FIXTURE" and isinstance(source, str)
                     and len(source) >= 7 and len(observed_source) == 40 and observed_source.startswith(source))
    if (not source or not source_matches
            or rows[0]["run_id"] != run_id or launch.get("identity", {}).get("runId") != run_id):
        raise ValueError("Launch/result/source association differs")
    summary = {"population": "LIVE", "trials": 0, "successes": 0,
               "spendContributionUsd": 0, "lineCoverage": None, "J": None,
               "measuredCostUsd": None, "excludedFixtures": 0}
    provenance = {"path": str(manifest_path.resolve()), "sha256": sha(manifest_path), "mode": mode}
    if mode != "NATIVE":
        summary["excludedFixtures"] = 1
        return {"kind": "act/canonical-inputs", "version": "1", "inputs": [],
                "liveAdmission": {"status": "EXCLUDED_SYNTHETIC_FIXTURE", "launch": provenance, **summary}}
    # A relabelled fixture cannot pass with its accepted archive or numeric result.
    if manifest.get("kind") == "v3-codex-first-launch/1":
        # The installed mapper is invoked with Python -I; import only its pinned sibling.
        import importlib.util
        guard_path = Path(__file__).resolve().with_name('run_codex_candidate.py')
        if not any(ref.get('path') == str(guard_path) and ref.get('sha256') == sha(guard_path)
                   for ref in manifest['files']):
            raise ValueError('Unpinned live admission guard')
        spec = importlib.util.spec_from_file_location('v3_live_guard', guard_path)
        guard = importlib.util.module_from_spec(spec); spec.loader.exec_module(guard)
        guard.require_fixture_free(manifest)
        selected = {ref["path"]: ref["sha256"] for ref in manifest["files"]}
        observed = {ref["path"]: ref["sha256"] for ref in launch.get("inputs", [])}
        for path in [manifest["codexExecutable"], *manifest["runtimeClasspath"]]:
            if observed.get(path) != selected.get(path) or path not in selected:
                raise ValueError("Observed native launch artifacts differ")
        activation = json.loads((root / "owner-activation.json").read_text())
        # Readback validates expiry at the recorded reservation, not at a later offline read.
        # Launch and application-claim authorize() calls still use current time.
        guard.authorize(manifest, sha(manifest_path), activation, at=reservation['reservedAt'])
        if reservation.get("activationSha256") != sha(root / "owner-activation.json"):
            raise ValueError("Owner activation differs from reservation")
        claim = json.loads(Path(manifest["attemptLedger"] + ".claimed").read_text())
        if (claim.get("manifestSha256") != sha(manifest_path) or claim.get("runId") != run_id
                or not reservation['reservedAt'] <= claim.get('claimedAt', -1) < activation['expiresEpochSeconds']):
            raise ValueError("Native application claim differs")
        original_ref = json.loads((evidence / "codex-original-acquisition-reference.json").read_text())
        original_path = Path(original_ref["path"])
        if sha(original_path) != original_ref["sha256"]:
            raise ValueError("Changed original acquisition")
        original = json.loads(original_path.read_text())
        argv = original.get("process", {}).get("argv", [])
        if (not argv or argv[0] != manifest["codexExecutable"] or "--model" not in argv
                or argv[argv.index("--model") + 1] != manifest["model"]
                or original.get("acquisition_uid") != rows[0]["journal_run_uid"]
                or original.get("process", {}).get("cwd") != str(evidence / "workspace")):
            raise ValueError("Native executable/model/execution identity differs")
    elif (manifest.get("approvedRunId") != run_id or manifest.get("plannedEvidenceRoot") != str(root)
          or not manifest.get("ownerSpendApproval", {}).get("reference")
          or manifest.get("prelaunchReview", {}).get("status") != "ACCEPTED"):
        raise ValueError("Native launch authority missing")
    summary.update(trials=1, successes=int(rows[0]["domain"]["passed"] is True),
                   spendContributionUsd=None, lineCoverage=rows[0]["domain"].get("line_coverage"))
    for row in rows:
        row["domain"]["evidence_population"] = "LIVE"
        row["domain"]["launch_manifest_sha256"] = sha(manifest_path)
    return {**mapped, "liveAdmission": {"status": "ADMITTED_NATIVE", "launch": provenance, **summary}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--export", required=True, type=Path)
    parser.add_argument("--reader-receipt", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-tool-calls", type=int)
    parser.add_argument("--mapping-only", choices=["true"])
    parser.add_argument("--launch-manifest", required=True, type=Path,
                        help="Exact original launch provenance; fixture rows are excluded before ACT")
    args = parser.parse_args()
    for name in ("result", "export", "reader_receipt", "evidence", "output"):
        setattr(args, name, getattr(args, name).resolve())
    inputs = mapping(args.result, args.export, args.reader_receipt, args.evidence, args.expected_tool_calls)
    inputs = admit_live(inputs, args.launch_manifest, args.evidence)
    inputs_path = args.output if args.mapping_only else args.output.with_name(args.output.name + "-inputs.json")
    with inputs_path.open("x") as stream:
        json.dump(inputs, stream, indent=2)
    if args.mapping_only or not inputs["inputs"]:
        return
    from agent_control_theory.canonical import analyze_inputs
    # Use owning ACT's default provisional full-command classifier for first vertical proof.
    analyze_inputs(inputs_path, args.output)


if __name__ == "__main__":
    main()
