package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.AcquisitionAssembly;
import io.github.markpollack.journal.ocsf.writer.FinalAssembly;
import io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition;

/** Select the qualified task artifacts through Experiment's public assembly interface. */
public final class JournalAssembly {
    private JournalAssembly() {}

    public static AcquisitionAssembly create() {
        return new AcquisitionAssembly((original, directory) -> {
            var qualified=ClaudeAcquisition.qualify(original,directory);
            return new AcquisitionAssembly.QualifiedPaths(qualified.manifest(),qualified.assemblyExpectation());
        },"journal-capture-tck:2.0.0-fresh-v3-sidecar3-SNAPSHOT",
                (manifest, output) -> FinalAssembly.assemble(manifest,output).name(),
                "journal-ocsf-writer:2.0.0-fresh-v3-sidecar3-SNAPSHOT");
    }
}
