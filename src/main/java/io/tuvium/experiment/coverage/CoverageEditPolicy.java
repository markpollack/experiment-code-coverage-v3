package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.Evidence;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Selected tool policy for the pinned test-only task; native installation is a separate gate. */
public final class CoverageEditPolicy {
    public static final String BUILD_COMMAND = "./mvnw clean test jacoco:report";
    public record Decision(boolean allowed, String reason) {}
    public record Snapshot(Map<String, String> protectedFiles) {
        public Snapshot { protectedFiles = Map.copyOf(protectedFiles); }
    }

    private final Path workspace;

    public CoverageEditPolicy(Path workspace) throws IOException {
        this.workspace = workspace.toRealPath();
        if (!Files.isDirectory(this.workspace)) throw new IOException("Workspace directory required");
    }

    public Path workspace() { return workspace; }

    /** Invoke before tool execution. An unevaluated/unknown tool is refused. */
    public Decision beforeTool(String tool, Map<String, ?> input) {
        if (tool == null || input == null) return deny("Missing tool or input");
        return switch (tool) {
            case "Write", "Edit", "MultiEdit" -> pathDecision(input.get("file_path"), true, false);
            case "Read" -> pathDecision(input.get("file_path"), false, false);
            case "Glob" -> globDecision(input);
            case "Grep" -> pathDecision(input.get("path"), false, true);
            case "Bash" -> BUILD_COMMAND.equals(input.get("command"))
                    && (input.get("run_in_background") == null || Boolean.FALSE.equals(input.get("run_in_background")))
                    ? new Decision(true, "Exact pinned Maven command; native wall/spend caps remain required")
                    : deny("Only the exact foreground Maven command is permitted");
            default -> deny("Tool is outside the selected coverage policy");
        };
    }

    private Decision globDecision(Map<String, ?> input) {
        Decision root = pathDecision(input.get("path"), false, true);
        if (!root.allowed()) return root;
        if (!(input.get("pattern") instanceof String pattern) || pattern.isBlank())
            return deny("Explicit relative glob pattern required");
        // Keep ordinary relative * and ? patterns. Reject alternate-root/escape syntaxes.
        for (String segment : pattern.split("/", -1))
            if (segment.equals("..") || !segment.matches("[A-Za-z0-9._?*\\-]+"))
                return deny("Glob must use a relative path without parent traversal or expansions");
        Path selected = input.get("path") == null ? workspace
                : workspace.resolve((String) input.get("path")).normalize();
        try (var paths = Files.walk(selected)) {
            if (paths.anyMatch(Files::isSymbolicLink))
                return deny("Symbolic search trees are not permitted");
        } catch (IOException failure) { return deny("Search root cannot be checked"); }
        return new Decision(true, "Confined relative glob");
    }

    private Decision pathDecision(Object value, boolean write, boolean defaultRoot) {
        if (value == null && defaultRoot) return new Decision(true, "Workspace search");
        if (!(value instanceof String text) || text.isBlank()) return deny("Explicit path required");
        try {
            Path selected = workspace.resolve(text).normalize();
            if (!selected.startsWith(workspace)) return deny("Path escapes workspace");
            Path relative = workspace.relativize(selected);
            Path current = workspace;
            for (Path segment : relative) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) return deny("Symbolic paths are not permitted");
            }
            if (write && !isTest(relative)) return deny("Only src/test files may be edited");
            return new Decision(true, write ? "Selected test edit" : "Workspace read");
        } catch (InvalidPathException failure) {
            return deny("Invalid path");
        }
    }

    private static Decision deny(String reason) { return new Decision(false, reason); }
    private static boolean isTest(Path relative) {
        return relative.startsWith(Path.of("src", "test")) && relative.getNameCount() > 2;
    }

    /** Capture before provider work. Generated target files and selected tests are excluded. */
    public Snapshot snapshot() throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = workspace.relativize(path);
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic path in workspace: " + relative);
                if (!Files.isRegularFile(path) || relative.startsWith("target") || isTest(relative)) continue;
                files.put(relative.toString(), Evidence.sha256(path) + ":executable=" + Files.isExecutable(path));
            }
        }
        return new Snapshot(files);
    }

    /** Independent post-run check; it does not establish that a native hook was installed. */
    public List<String> violations(Snapshot before) throws IOException {
        Map<String, String> after = snapshot().protectedFiles();
        Set<String> names = new TreeSet<>(before.protectedFiles().keySet()); names.addAll(after.keySet());
        List<String> violations = new ArrayList<>();
        for (String name : names)
            if (!Objects.equals(before.protectedFiles().get(name), after.get(name))) violations.add(name);
        return List.copyOf(violations);
    }
}
