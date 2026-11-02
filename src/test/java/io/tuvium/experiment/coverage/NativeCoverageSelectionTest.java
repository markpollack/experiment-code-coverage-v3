package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.Evidence;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class NativeCoverageSelectionTest {
    @TempDir Path root;
    @Test void actualAckSelectsOneExactArchiveAndRejectsWrongIdentityOrChangedBytes() throws Exception {
        Path selected=fixture("selected","binding","execution","decision");
        fixture("later","different","execution","decision");
        assertThat(NativeCoverageApp.selectPrepared(root,"binding","execution","decision")).isEqualTo(selected);
        assertThatThrownBy(()->NativeCoverageApp.selectPrepared(root,"binding","wrong","decision")).hasMessageContaining("identity");
        Files.writeString(selected,"changed");
        assertThatThrownBy(()->NativeCoverageApp.selectPrepared(root,"binding","execution","decision")).hasMessageContaining("one actual");
    }
    private Path fixture(String name,String binding,String execution,String decision) throws Exception {
        Path directory=Files.createDirectories(root.resolve("prepared").resolve(name));
        Path events=directory.resolve("events.jsonl");Files.writeString(events,"fixture events");
        CoverageEvidence.write(directory.resolve("marker.json"),Map.of("ack",Map.of("bindingId",binding,"executionId",execution,
                "decisionId",decision,"artifacts",List.of(Map.of("relativePath",root.relativize(events).toString(),
                        "sha256",Evidence.sha256(events),"size",Files.size(events))))));
        return events;
    }
}
