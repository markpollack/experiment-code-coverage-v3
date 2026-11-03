import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.journal.tck.ocsf.*;
import java.nio.file.*;
import java.util.*;

/** Separate installed reader: no V3 application, Client, SDK or Journal writer classes. */
class ReadNativeCoverage {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("results-directory run-id new-output-directory");
        for (String name : List.of("io.tuvium.experiment.coverage.NativeCoverageClient",
                "io.github.markpollack.agents.client.AgentClient",
                "io.github.markpollack.agents.codex.CodexClientProvider",
                "io.github.markpollack.claude.agent.sdk.DefaultClaudeSyncClient",
                "io.github.markpollack.journal.ocsf.writer.TerminalReceiver")) {
            try { Class.forName(name); throw new IllegalStateException("Reader contains forbidden class: " + name); }
            catch (ClassNotFoundException expected) { }
        }
        var result = new FileSystemResultStore(Path.of(args[0])).load(args[1]).orElseThrow();
        if (result.items().size() != 1) throw new IllegalStateException("Expected one result row");
        var row = result.items().getFirst();
        if (row.invocations().size() != 1 || !row.judgeState().equals("RETURNED"))
            throw new IllegalStateException("Missing native invocation/full Jury");
        Evidence.verify(row);
        var invocation = row.invocations().getFirst();
        var canonical = invocation.canonical().getFirst();
        var qualification = invocation.qualifications().getFirst();
        Path events = Path.of(canonical.events().path()), source = events.getParent().resolve("sources");
        var pkg = PackageReader.open(events);
        var binding = TerminalBindingReader.read(pkg.declarations);
        var json = new ObjectMapper();
        var decision = json.readTree(binding.decision());
        var original = json.readTree(Path.of(invocation.acquisitions().getFirst().path()).toFile());
        String execution = decision.required("executionId").asText();
        if (!execution.equals(original.required("acquisition_uid").asText())
                || !execution.equals(original.required("run").required("run_uid").asText())
                || !invocation.attempt().invocationId().equals(decision.required("correlation").required("experimentInvocationId").asText()))
            throw new IllegalStateException("Source/decision/Experiment mismatch");
        Path output = Files.createDirectory(Path.of(args[2]));
        int code = OcsfReaderTool.run(new String[]{"verify", events.toString(), source.toString(),
                output.resolve("oracle.json").toString(), "--acquisition", source.resolve("acquisition.json").toString(),
                "--assembly", qualification.assemblyExpectations().getFirst().path()}, System.out, System.err);
        if (code != 0) throw new IllegalStateException("Independent native verification refused: " + code);
        Path export = output.resolve("canonical.jsonl");
        if (OcsfReaderTool.run(new String[]{"canonical", events.toString(), export.toString()}, System.out, System.err) != 0)
            throw new IllegalStateException("Canonical export failed");
        var refs = new ArrayList<AgentRecords.FileRef>();
        refs.addAll(invocation.acquisitions()); refs.addAll(canonical.retainedFiles()); refs.addAll(qualification.retainedFiles());
        var receipt = new LinkedHashMap<String,Object>();
        receipt.put("verifier_exit", 0); receipt.put("invocation_id", invocation.attempt().invocationId());
        receipt.put("journal_run_uid", execution); receipt.put("decision_id", decision.required("decisionId").asText());
        receipt.put("verified_files", refs); receipt.put("export_sha256", Evidence.sha256(export));
        receipt.put("full_jury", row.verdicts());
        Files.write(output.resolve("reader-receipt.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
        System.out.println("INDEPENDENT_NATIVE_COVERAGE_READER_PASS " + execution);
    }
}
