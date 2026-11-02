"""Read-only consolidation of one retained native coverage attempt; never launches or retries."""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys

from analyze_fresh import mapping, selected_jury


class GateError(Exception):
    def __init__(self, state, message):
        super().__init__(message)
        self.state = state


def require(condition, message, state="CONTRACT_FAILED"):
    if not condition:
        raise GateError(state, message)


class Evidence:
    def __init__(self):
        self.files = {}

    def read(self, path, expected=None):
        path = Path(path).resolve(strict=True)
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        self.files[str(path)] = {"path": str(path), "sha256": digest, "bytes": len(data)}
        require(expected is None or digest == expected, "Changed evidence: " + str(path))
        return data

    def json(self, path, expected=None):
        return json.loads(self.read(path, expected))

    def references(self, value):
        if isinstance(value, dict):
            if isinstance(value.get("path"), str) and isinstance(value.get("sha256"), str):
                self.read(value["path"], value["sha256"])
            for child in value.values():
                self.references(child)
        elif isinstance(value, list):
            for child in value:
                self.references(child)


def jury_verdict(row, result_path, app, evidence):
    require(row["judgeState"] == "RETURNED" and len(row["verdicts"]) == 1,
            "Full persisted Jury is missing", "EVIDENCE_INCOMPLETE")
    _, selected, _ = selected_jury(row, result_path, app)
    document = row["verdicts"][0]
    envelope = json.loads(document["producerJson"])
    require(document["conclusion"] == "PASS" and envelope["judgment"]["producerStatus"] == "pass",
            "Full persisted Jury did not pass", "TASK_FAILED")
    for name in ("edit_surface", "t0_build", "t1_coverage"):
        saved = evidence.json(app / "judges" / (name + ".json"))
        require(selected.get(name) == saved, "Judge artifact differs from full Jury: " + name)
        require(saved["producerStatus"] == "pass", "Independent judge failed: " + name, "TASK_FAILED")
    coverage = selected["t1_coverage"]["metadata"]
    current, baseline = coverage["currentLineCoverage"], coverage["baselineLineCoverage"]
    require(math.isfinite(current) and math.isfinite(baseline) and current >= 85 and current > baseline,
            "Independent coverage threshold/improvement failed", "TASK_FAILED")
    require(abs(coverage["improvementPp"] - (current - baseline)) < 1e-8,
            "Coverage improvement is inconsistent")
    mode = evidence.json(app / "judges/judging-mode.json")
    require(mode["mode"] == "deterministic-coverage-validation/1"
            and mode["semanticQuality"] == "NOT_ASSESSED" and mode["judgeInferenceCalls"] == 0,
            "Semantic inference is outside this acceptance route")
    return {"fullJurySha256": document["sha256"], "lineCoverage": current,
            "baselineLineCoverage": baseline, "improvementPp": current - baseline,
            "semanticQuality": "NOT_ASSESSED"}


def freeze_receipt_inputs(directory, ledger, script=Path(__file__)):
    """Retain mutable observations and the exact executable evaluator beside one receipt."""
    directory.mkdir()
    refs = []
    for original, name in ((Path(ledger), "coverage-attempts.json"), (script, "accept_native_coverage.py"),
                           (script.with_name("analyze_fresh.py"), "analyze_fresh.py")):
        original = original.resolve(strict=True)
        target = directory / name
        data = original.read_bytes()
        target.write_bytes(data)
        refs.append({"originalPath": str(original), "snapshotPath": str(target),
                     "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
    (directory / "provenance.json").write_text(json.dumps({"kind": "v3-acceptance-input-snapshot/1", "files": refs}, indent=2) + "\n")


def evaluate(root, manifest_path, snapshot=None):
    root, manifest_path = Path(root).resolve(), Path(manifest_path).resolve()
    app = root / "application"
    evidence, gates, state = Evidence(), {}, {}

    def gate(name, operation):
        try:
            detail = operation()
            gates[name] = {"state": "PASS", **(detail or {})}
        except GateError as error:
            gates[name] = {"state": error.state, "reason": str(error)}
        except (OSError, KeyError, IndexError, TypeError, ValueError) as error:
            gates[name] = {"state": "EVIDENCE_INCOMPLETE", "reason": str(error)}

    def launch():
        manifest = evidence.json(manifest_path)
        state["manifest"] = manifest
        review = evidence.json(root / "launch-review.json")
        require(review["sha256"] == hashlib.sha256(evidence.read(manifest_path)).hexdigest(),
                "Manifest differs from actual launch receipt")
        require(manifest["mode"] == "NATIVE" and manifest["prelaunchReview"]["status"] == "ACCEPTED"
                and manifest["ownerSpendApproval"]["reference"], "Launch authority is missing")
        run_id = manifest["approvedRunId"]
        require(review["runId"] == run_id and str(root) == manifest["plannedEvidenceRoot"],
                "Reviewed run/output identity differs")
        evidence.references(manifest["files"])
        actual = evidence.json(app / "launch.json")
        evidence.references(actual["inputs"])
        pins = manifest["pins"]
        require(actual["sourceCommit"] == manifest["sourceRefs"]["v3"], "Launch source differs")
        require(actual["pinned"] == {"beforeTree": pins["beforeTree"], "itemSha256": pins["itemSha256"],
                "promptSha256": pins["basePromptSha256"], "sourceFiles": pins["copiedSourceFiles"]},
                "Task pins differ")
        prepared = evidence.json(app / "prepared.json")
        require(prepared["task"]["identity"]["runId"] == run_id
                and prepared["task"]["model"] == pins["model"], "Prepared task identity/model differs")
        prompt = evidence.json(app / "expanded-prompt-provenance.json")
        require(prompt["basePromptSha256"] == pins["basePromptSha256"]
                and prompt["expandedPromptSha256"] == pins["deterministicBaselineExpandedPromptSha256"]
                and hashlib.sha256(prepared["task"]["prompt"].encode()).hexdigest() == pins["basePromptSha256"]
                and hashlib.sha256(evidence.read(app / "expanded-prompt.txt")).hexdigest() == prompt["expandedPromptSha256"],
                "Prompt provenance differs")
        outer = evidence.json(root / "outer-receipt.json")
        require(not outer["remainingOwnedGroupObserved"], "Owned process group remained at wrapper return")
        require(outer["elapsedSeconds"] <= 1800, "Observed pipeline exceeded declared hard bound")
        return {"runId": run_id, "outerExit": outer["exit"], "elapsedSeconds": outer["elapsedSeconds"],
                "scope": "Reviewed enforcement and observed owned-group cleanup; escaped containment unqualified"}

    def client():
        custody = evidence.json(app / "native-attempt.json")
        state["custody"] = custody
        require(custody["handleAvailable"] and custody["outcomeType"] == "Success",
                "Original Client outcome was not successful", "EVIDENCE_INCOMPLETE")
        decision, termination = custody["decision"], custody["termination"]
        require(custody["associationObservation"] == "MATCHED" and decision["executionId"] == custody["executionId"],
                "Client association differs")
        require(custody["terminationState"] == "OBSERVED" and termination["cleanupConfirmed"]
                and not termination["unconfirmedResources"] and termination["settledAt"]
                and termination["providerTurnDispatched"] and not termination["reconnected"]
                and termination["executionId"] == custody["executionId"]
                and termination["sessionId"] == custody["sessionId"], "Client cleanup/one-shot settlement incomplete")
        return {"executionId": custody["executionId"], "decisionId": decision["decisionId"],
                "originalFailure": custody["failure"], "cleanupScope": "Observed resources only"}

    def sources():
        custody = state["custody"]
        base = app / "sources" / custody["executionId"]
        acquired = evidence.json(base / "acquisition.json")
        state.update(acquired=acquired, sourceBase=base)
        require(acquired["complete"] and not acquired["capture_errors"]
                and acquired["acquisition_uid"] == custody["executionId"]
                and acquired["run"]["run_uid"] == custody["executionId"], "Native acquisition incomplete")
        settings, bounds = acquired["run"]["launch_settings"], state["manifest"]["bounds"]
        argv = acquired["process"]["argv"]
        require(settings["model"] == state["manifest"]["pins"]["model"]
                and settings["timeout_ms"] <= bounds["maximumClientRequestSeconds"] * 1000
                and argv[argv.index("--model") + 1] == settings["model"]
                and float(argv[argv.index("--max-budget-usd") + 1]) == bounds["nativeGenerationCapUsd"],
                "Observed native model/budget/deadline differs")
        observed = evidence.json(root / "observed-native-process.json")
        require(len(observed) == 1 and observed[0]["pid"] == acquired["process"]["pid"]
                and observed[0]["argv"] == argv, "Observed native process differs from acquired process")
        roles = {}
        for ref in acquired["sources"]:
            path = (base / ref["path"]).resolve(strict=True)
            require(path.is_relative_to(base.resolve()), "Acquisition source escapes root")
            data = evidence.read(path, ref["sha256"])
            require(len(data) == ref["bytes"], "Acquisition byte count differs")
            roles.setdefault(ref["role"], []).append(path)
        require(all(len(roles.get(role, [])) == 1 for role in ("stdin", "stream", "stderr", "transcript")),
                "Required native source cardinality differs")
        state["roles"] = roles
        terminal = []
        for number, line in enumerate(evidence.read(roles["stream"][0]).decode().splitlines(), 1):
            row = json.loads(line)
            if row.get("type") == "result":
                terminal.append((number, row))
        require(len(terminal) == 1 and terminal[0][1].get("subtype") == "success"
                and terminal[0][1].get("is_error") is False, "Native terminal result is not one success")
        state["terminal"] = terminal[0]
        return {"complete": True, "sourceCount": len(acquired["sources"]), "terminalLine": terminal[0][0]}

    def policy():
        rows = [json.loads(line) for line in evidence.read(app / "tool-policy.jsonl").decode().splitlines()]
        require(len({row["toolUseId"] for row in rows}) == len(rows), "Duplicate policy tool identities")
        denied = {row["toolUseId"]: row for row in rows if not row["allowed"]}
        results = {uid: [] for uid in denied}
        for role in ("stream", "transcript"):
            path = state["roles"][role][0]
            for number, line in enumerate(evidence.read(path).decode().splitlines(), 1):
                row = json.loads(line)
                if row.get("type") != "user":
                    continue  # Exclude embedded request copies from native authored result observations.
                content = row.get("message", {}).get("content")
                for item in content if isinstance(content, list) else []:
                    uid = item.get("tool_use_id")
                    if item.get("type") == "tool_result" and uid in denied:
                        expected = "PreToolUse:" + denied[uid]["tool"] + " hook error: " + denied[uid]["reason"]
                        require(item.get("is_error") is True and str(item.get("content")).startswith(expected),
                                "Denied action has a nonblocked native result: " + uid)
                        results[uid].append({"source": str(path), "line": number})
        require(all(results.values()), "Denied action is missing its native blocked result", "EVIDENCE_INCOMPLETE")
        terminal_ids = {row["tool_use_id"] for row in state["terminal"][1].get("permission_denials", [])}
        require(terminal_ids == set(denied), "Terminal denial population differs from required hook")
        stderr = evidence.read(state["roles"]["stderr"][0]).decode(errors="replace")
        require(not any(error in stderr for error in ("Error in hook callback", "ZodError", "Invalid control response")),
                "Native hook schema error observed")
        return {"calls": len(rows), "deniedActions": results,
                "denialObservation": "OBSERVED_BLOCKED" if denied else "NOT_OBSERVED",
                "scope": "Exact original-source blocked/error outcomes; archive qualification is a separate gate"}

    def integrity():
        require(evidence.json(app / "preparation-edit-surface.json")["violations"] == [],
                "Preparation modified protected inputs")
        before = evidence.json(app / "prepared-protected-snapshot.json")["protectedFiles"]
        current = {}
        for path in (app / "workspace").rglob("*"):
            relative = path.relative_to(app / "workspace").as_posix()
            if relative == "target" or relative.startswith("target/") or relative == "src/test" or relative.startswith("src/test/"):
                continue
            require(not path.is_symlink(), "Protected symbolic path appeared: " + relative)
            if path.is_file():
                current[relative] = hashlib.sha256(evidence.read(path)).hexdigest() + ":executable=" + str(os.access(path, os.X_OK)).lower()
        require(before == current, "Protected input content, population or executable flags changed")
        return {"protectedFiles": len(before), "violations": []}

    def archive():
        custody = state["custody"]
        markers = []
        for path in (app / "journal/prepared").glob("*/marker.json"):
            marker = evidence.json(path)
            if marker["ack"]["executionId"] == custody["executionId"]:
                markers.append((path, marker))
        require(len(markers) == 1, "Ambiguous/missing original terminal ACK")
        path, marker = markers[0]
        ack = marker["ack"]
        require(ack["decisionId"] == custody["decision"]["decisionId"]
                and "bindingId=" + ack["bindingId"] in custody["recording"], "ACK differs from original recording")
        for ref in ack["artifacts"]:
            target = (app / "journal" / ref["relativePath"]).resolve(strict=True)
            require(target.is_relative_to((app / "journal").resolve()), "ACK path escapes archive")
            require(len(evidence.read(target, ref["sha256"])) == ref["size"], "ACK artifact size differs")
        receipt = evidence.json(root / "independent-reader/reader-receipt.json")
        require(receipt["verifier_exit"] == 0 and receipt["journal_run_uid"] == custody["executionId"]
                and receipt["decision_id"] == custody["decision"]["decisionId"], "Detached reader did not accept original decision")
        evidence.references(receipt["verified_files"])
        evidence.read(root / "independent-reader/canonical.jsonl", receipt["export_sha256"])
        state["reader"] = receipt
        return {"bindingId": ack["bindingId"], "sourceAssemblyVerifierExit": 0}

    def result():
        run_id = state["manifest"]["approvedRunId"]
        encoded = base64.urlsafe_b64encode(run_id.encode()).decode().rstrip("=")
        path = app / "results" / encoded / "run.json"
        saved = evidence.json(path)
        require(saved["experimentId"] == run_id and len(saved["items"]) == 1, "Result run/cardinality differs")
        evidence.references(saved)
        row = saved["items"][0]
        state.update(resultPath=path, row=row)
        require(row["workflowState"] == "SUCCEEDED" and len(row["invocations"]) == 1,
                "Workflow/Invocation is not accepted; original failed workflow remains failed", "EVIDENCE_INCOMPLETE")
        invocation = row["invocations"][0]
        require(invocation["attempt"] == state["custody"]["attempt"], "Original subject Attempt changed")
        require(len(row["workflowProof"]) == 1 and row["workflowProof"][0]["lifecycle"] == "DRAINED",
                "Workflow drain proof missing")
        return {"workflow": "SUCCEEDED", "lifecycle": "DRAINED", "attempt": invocation["attempt"]}

    def jury():
        return jury_verdict(state["row"], state["resultPath"], app, evidence)

    def act():
        exported = root / "independent-reader/canonical.jsonl"
        expected = mapping(state["resultPath"], exported, root / "independent-reader/reader-receipt.json", app, None)
        inputs = evidence.json(root / "act-inputs.json")
        require(inputs == expected, "ACT mapping differs from retained original source/Attempt/full Jury")
        manifest = evidence.json(root / "act/manifest.json")
        require(manifest["inputs_sha256"] == hashlib.sha256(evidence.read(root / "act-inputs.json")).hexdigest(),
                "ACT input document differs")
        names = {"tool_uses", "item_results", "runs", "judge_details", "cores", "events", "observations", "edges", "state_sequence"}
        require(set(manifest["tables"]) == names and len(manifest["inputs"]) == 1, "ACT table/input population differs")
        require(manifest["inputs"][0]["mapping"] == inputs["inputs"][0]
                and manifest["inputs"][0]["export_sha256"] == state["reader"]["export_sha256"], "ACT source differs")
        for relative, digest in manifest["files"].items():
            target = (root / "act" / relative).resolve(strict=True)
            require(target.is_relative_to((root / "act").resolve()), "ACT output escapes root")
            evidence.read(target, digest)
        require(all(name + ".parquet" in manifest["files"] for name in names), "Missing ACT table artifact")
        return {"tables": {name: manifest["tables"][name]["rows"] for name in sorted(names)},
                "accounting": "Owning ACT projection retained; cumulative observations are not summed"}

    def accounting():
        manifest = state["manifest"]
        ledger = evidence.json(Path(snapshot) / "coverage-attempts.json" if snapshot else manifest["attemptLedger"])
        attempts = ledger["attempts"]
        require(ledger["limit"] == 6 and len(attempts) <= 6 and len({a["runId"] for a in attempts}) == len(attempts),
                "Cumulative attempt allowance/identity differs")
        require(sum(a["runId"] == manifest["approvedRunId"] for a in attempts) == 1
                and all(a["nativeCapUsd"] == 5 for a in attempts), "Missing/count-mismatched reservation")
        value = state.get("terminal", (None, {}))[1].get("total_cost_usd")
        require(value is None or isinstance(value, (int, float)) and math.isfinite(value) and 0 <= value <= 5,
                "Unexpected reported terminal spending; escalate to coordinator")
        qualified = gates.get("archive_reader", {}).get("state") == "PASS"
        return {"coverageUsed": len(attempts), "coverageCeiling": 6,
                "cumulativeReservedCapsUsd": sum(a["nativeCapUsd"] for a in attempts),
                "thisSingleTerminalReportedUsd": value, "costQualification": "SOURCE_VERIFIED" if qualified else "UNQUALIFIED",
                "priorAttemptCosts": [{"runId": a["runId"], "state": a["actualCostState"],
                                       "reservedCapUsd": a["nativeCapUsd"]} for a in attempts if a["runId"] != manifest["approvedRunId"]],
                "actualizeUsedAtLaunch": manifest["counterState"]["actualizeUsed"],
                "source": "One original native final result; no cumulative sum or per-tool allocation"}

    for name, operation in (("launch_integrity", launch), ("client_cleanup", client), ("native_sources", sources),
                            ("required_policy", policy), ("protected_inputs", integrity), ("archive_reader", archive),
                            ("workflow_result", result), ("full_jury", jury), ("act", act), ("accounting", accounting)):
        gate(name, operation)
    states = {value["state"] for value in gates.values()}
    outcome = next((s for s in ("CONTRACT_FAILED", "EVIDENCE_INCOMPLETE", "TASK_FAILED") if s in states), "ACCEPTED")
    evidence.read(Path(__file__))
    evidence.read(Path(__file__).with_name("analyze_fresh.py"))
    if snapshot:
        provenance = evidence.json(Path(snapshot) / "provenance.json")
        for ref in provenance["files"]:
            evidence.read(ref["snapshotPath"], ref["sha256"])
    return {"kind": "v3-native-coverage-acceptance/1", "outcome": outcome,
            "runId": state.get("manifest", {}).get("approvedRunId"), "gates": gates,
            "semanticQuality": "NOT_ASSESSED", "manualChecks": [],
            "reviewBoundary": "Coordinator independently accepts the receipt; no retry or launch authority follows",
            "scopeLimits": ["Observed resource cleanup, not escaped-process containment",
                            "Native terminal reports are not a provider invoice", "Historical unknown charges remain unknown"],
            "inputs": list(evidence.files.values())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--snapshot", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.snapshot is None:
        require(not args.output.exists(), "Refusing existing acceptance receipt")
        manifest = json.loads(args.manifest.read_text())
        directory = args.output.absolute().with_suffix(".inputs")
        freeze_receipt_inputs(directory, manifest["attemptLedger"])
        command = [sys.executable, str(directory / "accept_native_coverage.py"), "--root", str(args.root),
                   "--manifest", str(args.manifest), "--output", str(args.output), "--snapshot", str(directory)]
        raise SystemExit(subprocess.call(command))
    require(Path(__file__).resolve().parent == args.snapshot.resolve(), "Evaluator must execute its frozen snapshot")
    result = evaluate(args.root, args.manifest, args.snapshot)
    with args.output.open("x") as stream:
        json.dump(result, stream, indent=2)
        stream.write("\n")
    print(result["outcome"])
    raise SystemExit(0 if result["outcome"] == "ACCEPTED" else 1)


if __name__ == "__main__":
    main()
