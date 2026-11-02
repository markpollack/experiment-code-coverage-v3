package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.Evidence;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Copies only the pinned tracked subject files, never ignored historical build artifacts. */
public final class PinnedCoverageTask {
    public static final String BEFORE = "dataset/items/spring-petclinic-partial/before";
    public static final String BEFORE_TREE = "6d9d5311952f7a022e5d30af8693d11d932186f9";
    public static final String ITEM = "dataset/items/spring-petclinic-partial/item.json";
    public static final String ITEM_SHA256 = "6b037a95607b76792d57c22ae20d4ea4ba9cc57e0895dc34e44520d7ff185b2a";
    public static final String PROMPT = "prompts/v0-simple.txt";
    public static final String PROMPT_SHA256 = "8084017e1d431000240d93568a975d9141f57b2d12553d3d8dad1086b6cffb15";
    public record Receipt(String beforeTree, String itemSha256, String promptSha256, int sourceFiles) {}
    private record Entry(String mode, String hash, String path, byte[] bytes) {}
    private PinnedCoverageTask() {}

    /** Verifies every selected byte before creating the new workspace. No provider is invoked. */
    public static Receipt prepare(Path project, Path destination) throws Exception {
        project = project.toRealPath();
        if (!git(project, "rev-parse", "HEAD:" + BEFORE).trim().equals(BEFORE_TREE))
            throw new IOException("Pinned before-tree differs");
        if (!Evidence.sha256(project.resolve(ITEM)).equals(ITEM_SHA256)
                || !Evidence.sha256(project.resolve(PROMPT)).equals(PROMPT_SHA256))
            throw new IOException("Pinned item or prompt bytes differ");
        Path subject = project.resolve(BEFORE).toRealPath();
        List<Entry> selected = new ArrayList<>();
        for (String row : git(project, "ls-tree", "-r", "-z", "HEAD:" + BEFORE).split("\0")) {
            if (row.isEmpty()) continue;
            String[] halves = row.split("\t", 2), metadata = halves[0].split(" ");
            if (halves.length != 2 || metadata.length != 3 || !metadata[1].equals("blob")
                    || !Set.of("100644", "100755").contains(metadata[0]))
                throw new IOException("Unsupported pinned source entry");
            Path relative = Path.of(halves[1]), path = subject.resolve(relative).normalize();
            if (relative.isAbsolute() || !path.startsWith(subject) || !path.toRealPath().equals(path))
                throw new IOException("Pinned source path is not an ordinary confined file");
            byte[] bytes = Files.readAllBytes(path);
            String blob = git(project, bytes, "hash-object", "--path=" + BEFORE + "/" + halves[1], "--stdin").trim();
            if (!blob.equals(metadata[2]))
                throw new IOException("Pinned source bytes differ: " + relative);
            selected.add(new Entry(metadata[0], metadata[2], halves[1], bytes));
        }
        if (selected.isEmpty()) throw new IOException("Empty pinned subject");
        Path requested = destination.toAbsolutePath().normalize();
        Path target = requested.getParent().toRealPath().resolve(requested.getFileName());
        if (target.startsWith(project)) throw new IOException("New workspace must be outside the source checkout");
        Files.createDirectory(target);
        for (Entry entry : selected) {
            Path file = target.resolve(entry.path());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.bytes(), StandardOpenOption.CREATE_NEW);
            if (entry.mode().equals("100755") && !file.toFile().setExecutable(true, true))
                throw new IOException("Could not restore executable source mode");
        }
        return new Receipt(BEFORE_TREE, ITEM_SHA256, PROMPT_SHA256, selected.size());
    }

    private static String git(Path project, String... args) throws Exception {
        return git(project, null, args);
    }

    private static String git(Path project, byte[] input, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", project.toString()));
        command.addAll(List.of(args));
        Path output = Files.createTempFile("v3-git-read-", ".txt");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try (var stdin = process.getOutputStream()) { if (input != null) stdin.write(input); }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Git read did not terminate");
                throw new IOException("Git read exceeded its allowance");
            }
            if (process.exitValue() != 0) throw new IOException("Git input inspection failed");
            return Files.readString(output);
        } finally { Files.deleteIfExists(output); }
    }
}
