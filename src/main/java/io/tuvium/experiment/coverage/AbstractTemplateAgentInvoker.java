package io.tuvium.experiment.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.journal.claude.PhaseCapture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for agent invokers with template-method workflow:
 * pre-invoke → agent invocation → post-invoke.
 */
public abstract class AbstractTemplateAgentInvoker implements AgentInvoker {

	private static final Logger logger = LoggerFactory.getLogger(AbstractTemplateAgentInvoker.class);

	@Nullable
	private final Path knowledgeSourceDir;

	@Nullable
	private final List<String> knowledgeFiles;

	protected AbstractTemplateAgentInvoker() {
		this(null, null);
	}

	protected AbstractTemplateAgentInvoker(@Nullable Path knowledgeSourceDir,
			@Nullable List<String> knowledgeFiles) {
		this.knowledgeSourceDir = knowledgeSourceDir;
		this.knowledgeFiles = knowledgeFiles;
	}

	@Override
	public final InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		long startTime = System.currentTimeMillis();
		Path workspace = context.workspacePath();

		String itemSlug = context.metadata().getOrDefault("itemId", workspace.getFileName().toString());
		logger.info("=== Agent Invocation: {} ===", itemSlug);

		Map<String, String> metrics = preInvoke(workspace, context);
		copyKnowledge(workspace);

		AgentResult agentResult;
		try {
			agentResult = invokeAgent(context);
		}
		catch (Exception ex) {
			logger.error("Agent execution failed", ex);
			return InvocationResult.error("Agent execution failed: " + ex.getMessage(),
					context.metadata());
		}

		Map<String, String> enrichedMetadata = new HashMap<>(context.metadata());
		enrichedMetadata.putAll(metrics);
		postInvoke(workspace, context, enrichedMetadata);

		long durationMs = System.currentTimeMillis() - startTime;

		return InvocationResult.fromPhases(agentResult.phases(), durationMs,
				agentResult.sessionId(), enrichedMetadata);
	}

	protected Map<String, String> preInvoke(Path workspace, InvocationContext context) {
		return Map.of();
	}

	protected abstract AgentResult invokeAgent(InvocationContext context) throws Exception;

	protected void postInvoke(Path workspace, InvocationContext context, Map<String, String> metadata) {
	}

	void copyKnowledge(Path workspace) {
		if (knowledgeSourceDir == null || knowledgeFiles == null || knowledgeFiles.isEmpty()) {
			return;
		}

		Path targetDir = workspace.resolve("knowledge");

		if (knowledgeFiles.contains("index.md")) {
			logger.info("Copying full knowledge tree from {}", knowledgeSourceDir);
			copyDirectoryRecursively(knowledgeSourceDir, targetDir);
		}
		else {
			logger.info("Copying {} targeted knowledge files", knowledgeFiles.size());
			for (String relativePath : knowledgeFiles) {
				Path source = knowledgeSourceDir.resolve(relativePath);
				Path target = targetDir.resolve(relativePath);
				try {
					Files.createDirectories(target.getParent());
					Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				}
				catch (IOException ex) {
					throw new UncheckedIOException("Failed to copy knowledge file: " + relativePath, ex);
				}
			}
		}
	}

	private void copyDirectoryRecursively(Path source, Path target) {
		try {
			Files.walkFileTree(source, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					Files.createDirectories(target.resolve(source.relativize(dir)));
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.copy(file, target.resolve(source.relativize(file)),
							StandardCopyOption.REPLACE_EXISTING);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to copy knowledge directory: " + source, ex);
		}
	}

	protected record AgentResult(List<PhaseCapture> phases, @Nullable String sessionId) {
	}

}
