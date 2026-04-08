package io.tuvium.experiment.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.experiment.agent.AgentInvocationException;
import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationContext;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.journal.claude.PhaseCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agents.client.AgentClient;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.claude.ClaudeAgentModel;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.model.AgentModel;
import org.springaicommunity.judge.coverage.JaCoCoReportParser;
import org.springaicommunity.judge.coverage.JaCoCoReportParser.CoverageMetrics;
import org.springaicommunity.judge.exec.util.MavenBuildRunner;
import org.springaicommunity.judge.exec.util.MavenBuildRunner.BuildResult;

/**
 * Single-phase coverage agent invoker. Runs the full workflow:
 * compile check, JaCoCo injection, baseline measurement, agent invocation,
 * final coverage measurement.
 */
public class CoverageAgentInvoker implements AgentInvoker {

	private static final Logger logger = LoggerFactory.getLogger(CoverageAgentInvoker.class);

	// Excludes application bootstrap classes (main() methods) which agents must not test.
	private static final String JACOCO_PLUGIN_SNIPPET = """
			<plugin>
				<groupId>org.jacoco</groupId>
				<artifactId>jacoco-maven-plugin</artifactId>
				<version>0.8.14</version>
				<configuration>
					<excludes>
						<exclude>**/*Application.class</exclude>
						<exclude>**/*Main.class</exclude>
					</excludes>
				</configuration>
				<executions>
					<execution>
						<id>default</id>
						<goals><goal>prepare-agent</goal></goals>
					</execution>
					<execution>
						<id>report</id>
						<phase>test</phase>
						<goals><goal>report</goal></goals>
					</execution>
				</executions>
			</plugin>
			""";

	@Override
	public InvocationResult invoke(InvocationContext context) throws AgentInvocationException {
		long startTime = System.currentTimeMillis();
		Path workspace = context.workspacePath();

		String itemSlug = context.metadata().getOrDefault("itemId", workspace.getFileName().toString());
		logger.info("=== Coverage Agent: {} ===", itemSlug);

		// 1. Verify project compiles
		logger.info("Step 1: Verifying project compiles");
		BuildResult compileResult = MavenBuildRunner.runBuild(workspace, 5,
				"clean", "compile", "-Dspring-javaformat.skip=true");
		if (!compileResult.success()) {
			return InvocationResult.error("Project does not compile: " + compileResult.output(),
					context.metadata());
		}

		// 2. Ensure JaCoCo plugin
		ensureJaCoCoPlugin(workspace);

		// 3. Measure baseline coverage
		CoverageMetrics baseline;
		if (hasTestFiles(workspace)) {
			logger.info("Step 3: Measuring baseline coverage");
			baseline = measureCoverage(workspace);
			logger.info("Baseline coverage: line={}%, branch={}%",
					baseline.lineCoverage(), baseline.branchCoverage());
		}
		else {
			logger.info("Step 3: No test files found — baseline is 0%");
			baseline = new CoverageMetrics(0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, "No tests");
		}

		// 4. Invoke agent
		logger.info("Step 4: Invoking agent (model={})", context.model());
		List<PhaseCapture> phases;
		String sessionId;
		try {
			AgentModel agentModel = ClaudeAgentModel.builder()
					.workingDirectory(workspace)
					.defaultOptions(ClaudeAgentOptions.builder()
							.model(context.model())
							.yolo(true)
							.build())
					.build();

			AgentClient client = AgentClient.create(agentModel);
			String prompt = buildPrompt(context.prompt(), baseline);
			AgentClientResponse response = client.goal(prompt).workingDirectory(workspace).run();

			PhaseCapture capture = response.getPhaseCapture();
			if (capture != null) {
				logger.info("Agent exhaust: {} turns, {} in + {} out tokens, ${}",
						capture.numTurns(), capture.inputTokens(), capture.outputTokens(),
						String.format("%.4f", capture.totalCostUsd()));
			}

			phases = capture != null ? List.of(capture) : List.of();
			sessionId = capture != null ? capture.sessionId() : null;
		}
		catch (Exception ex) {
			logger.error("Agent execution failed", ex);
			phases = List.of();
			sessionId = null;
		}

		// 5. Measure final coverage
		logger.info("Step 5: Measuring final coverage");
		CoverageMetrics finalCov = measureCoverage(workspace);
		double improvement = finalCov.lineCoverage() - baseline.lineCoverage();
		logger.info("Final coverage: line={}%, branch={}% (improvement: {}pp)",
				finalCov.lineCoverage(), finalCov.branchCoverage(),
				String.format("%.1f", improvement));

		long durationMs = System.currentTimeMillis() - startTime;

		// 6. Build enriched metadata and return
		Map<String, String> enrichedMetadata = new HashMap<>(context.metadata());
		enrichedMetadata.put("baselineCoverage", String.valueOf(baseline.lineCoverage()));
		enrichedMetadata.put("finalCoverage", String.valueOf(finalCov.lineCoverage()));
		enrichedMetadata.put("baselineBranchCoverage", String.valueOf(baseline.branchCoverage()));
		enrichedMetadata.put("finalBranchCoverage", String.valueOf(finalCov.branchCoverage()));
		enrichedMetadata.put("coverageImprovement", String.valueOf(improvement));
		enrichedMetadata.put("jacocoReportExists",
				String.valueOf(Files.isRegularFile(workspace.resolve("target/site/jacoco/jacoco.xml"))));

		return InvocationResult.fromPhases(phases, durationMs, sessionId, enrichedMetadata);
	}

	private String buildPrompt(String basePrompt, CoverageMetrics baseline) {
		StringBuilder sb = new StringBuilder(basePrompt);
		if (baseline.lineCoverage() == 0.0 && baseline.linesTotal() == 0) {
			sb.append("\n\n## Current State\n");
			sb.append("No tests exist yet. Coverage is 0%.\n");
			sb.append("JaCoCo is already configured. Run `./mvnw clean test jacoco:report` to generate reports.\n");
		}
		else {
			sb.append("\n\n## Current Coverage Metrics\n");
			sb.append("- Line coverage: ").append(String.format("%.1f", baseline.lineCoverage())).append("%\n");
			sb.append("- Branch coverage: ").append(String.format("%.1f", baseline.branchCoverage())).append("%\n");
			sb.append("- Lines covered: ").append(baseline.linesCovered())
					.append("/").append(baseline.linesTotal()).append("\n");
			sb.append("\nNote: JaCoCo is already configured. Run `./mvnw clean test jacoco:report` to regenerate.\n");
		}
		return sb.toString();
	}

	void ensureJaCoCoPlugin(Path workspace) {
		Path pomPath = workspace.resolve("pom.xml");
		if (!Files.isRegularFile(pomPath)) {
			logger.warn("No pom.xml found in workspace — skipping JaCoCo injection");
			return;
		}
		try {
			String pom = Files.readString(pomPath);
			if (pom.contains("jacoco-maven-plugin")) {
				logger.info("Step 2: JaCoCo plugin already present");
				return;
			}
			logger.info("Step 2: Injecting JaCoCo plugin into pom.xml");
			String updated;
			if (pom.contains("</plugins>")) {
				updated = pom.replace("</plugins>", JACOCO_PLUGIN_SNIPPET + "    </plugins>");
			}
			else if (pom.contains("</build>")) {
				updated = pom.replace("</build>",
						"    <plugins>\n" + JACOCO_PLUGIN_SNIPPET + "    </plugins>\n  </build>");
			}
			else {
				updated = pom.replace("</project>",
						"  <build>\n    <plugins>\n" + JACOCO_PLUGIN_SNIPPET + "    </plugins>\n  </build>\n</project>");
			}
			Files.writeString(pomPath, updated);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to inject JaCoCo plugin", ex);
		}
	}

	private CoverageMetrics measureCoverage(Path workspace) {
		BuildResult result = MavenBuildRunner.runBuild(workspace, 10,
				"clean", "test", "jacoco:report", "-Dspring-javaformat.skip=true");
		if (result.success()) {
			return JaCoCoReportParser.parse(workspace);
		}
		logger.warn("Test execution failed during coverage measurement: {}",
				result.output().substring(0, Math.min(500, result.output().length())));
		return new CoverageMetrics(0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, "Tests failed");
	}

	private boolean hasTestFiles(Path workspace) {
		Path testJavaDir = workspace.resolve("src/test/java");
		if (!Files.isDirectory(testJavaDir)) {
			return false;
		}
		try (var stream = Files.walk(testJavaDir)) {
			return stream.anyMatch(p -> p.toString().endsWith(".java"));
		}
		catch (IOException ex) {
			return false;
		}
	}

}
