package io.tuvium.experiment.coverage;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable conservative submission reservations. An uncertain admission consumes its slot. */
final class NativeCoverageLedger {
    static void reserve(Path path, String runId) throws Exception {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
            throw new IllegalArgumentException("Existing coordinator-selected attempt ledger required");
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (channel.size() > 1024 * 1024) throw new IllegalStateException("Oversized attempt ledger");
            var bytes = ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
            var ledger = CoverageEvidence.JSON.readTree(bytes.array());
            if (!ledger.path("kind").asText().equals("v3-native-coverage-attempts/1") || ledger.path("limit").asInt() != 6
                    || !ledger.path("attempts").isArray()) throw new IllegalStateException("Wrong attempt ledger");
            var attempts = (com.fasterxml.jackson.databind.node.ArrayNode) ledger.path("attempts");
            if (attempts.size() >= 6) throw new IllegalStateException("All six coverage submissions are reserved");
            for (var entry : attempts) if (entry.path("runId").asText().equals(runId))
                throw new IllegalStateException("Run already reserved; implicit replay refused");
            attempts.addObject().put("runId", runId).put("reservedAt", Instant.now().toString())
                    .put("nativeCapUsd", 5).put("actualCostState", "UNKNOWN")
                    .put("state", "SUBMISSION_RESERVED");
            byte[] encoded = CoverageEvidence.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(ledger);
            channel.position(0); channel.truncate(0);
            var out = ByteBuffer.wrap(encoded); while (out.hasRemaining()) channel.write(out);
            channel.force(true);
        }
    }
}
