package io.tuvium.experiment.coverage;

import java.nio.file.Path;
import java.util.List;

import io.github.markpollack.experiment.agent.InvocationContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-phase AgentInvoker for code coverage experiments.
 * Invokes Claude Code against a Spring Boot project workspace to add tests.
 *
 * <p>TODO: Wire AgentClient for real invocation. Currently a placeholder
 * that logs the invocation and returns an empty result.</p>
 */
public class CoverageAgentInvoker extends AbstractTemplateAgentInvoker {

	private static final Logger logger = LoggerFactory.getLogger(CoverageAgentInvoker.class);

	public CoverageAgentInvoker() {
		super();
	}

	public CoverageAgentInvoker(@Nullable Path knowledgeSourceDir, @Nullable List<String> knowledgeFiles) {
		super(knowledgeSourceDir, knowledgeFiles);
	}

	@Override
	protected AgentResult invokeAgent(InvocationContext context) {
		logger.info("CoverageAgentInvoker invoked for workspace: {}", context.workspacePath());
		logger.info("Prompt length: {} chars", context.prompt().length());
		logger.info("Model: {}", context.model());
		logger.warn("Placeholder — wire AgentClient for real invocation");

		return new AgentResult(List.of(), null);
	}

}
