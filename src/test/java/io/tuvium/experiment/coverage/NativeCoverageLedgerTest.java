package io.tuvium.experiment.coverage;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class NativeCoverageLedgerTest {
    @TempDir Path root;
    @Test void sixthReservationExhaustsSharedCeilingAndDuplicateDoesNotReplay() throws Exception {
        Path ledger = root.resolve("attempts.json");
        Files.writeString(ledger, "{\"kind\":\"v3-native-coverage-attempts/1\",\"limit\":6,\"attempts\":[]}");
        NativeCoverageLedger.reserve(ledger, "run-0");
        assertThatThrownBy(() -> NativeCoverageLedger.reserve(ledger, "run-0")).hasMessageContaining("replay");
        for (int n=1; n<6; n++) NativeCoverageLedger.reserve(ledger, "run-"+n);
        assertThatThrownBy(() -> NativeCoverageLedger.reserve(ledger, "run-6")).hasMessageContaining("six");
        var data = CoverageEvidence.JSON.readTree(ledger.toFile());
        assertThat(data.path("attempts").size()).isEqualTo(6);
        assertThat(data.path("attempts").get(0).path("actualCostState").asText()).isEqualTo("UNKNOWN");
    }
}
