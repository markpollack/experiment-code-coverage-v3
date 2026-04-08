package io.tuvium.experiment.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.comparison.ComparisonEngine;
import io.github.markpollack.experiment.comparison.ComparisonResult;
import io.github.markpollack.experiment.comparison.DefaultComparisonEngine;
import io.github.markpollack.experiment.dataset.DatasetManager;
import io.github.markpollack.experiment.dataset.FileSystemDatasetManager;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.runner.ExperimentConfig;
import io.github.markpollack.experiment.runner.ExperimentRunner;
import io.github.markpollack.experiment.store.ActiveSession;
import io.github.markpollack.experiment.store.FileSystemResultStore;
import io.github.markpollack.experiment.store.FileSystemSessionStore;
import io.github.markpollack.experiment.store.FileSystemSweepStore;
import io.github.markpollack.experiment.store.ResultStore;
import io.github.markpollack.experiment.store.RunSessionStatus;
import io.github.markpollack.experiment.store.SessionStore;
import io.github.markpollack.experiment.store.SweepStatus;
import io.github.markpollack.experiment.store.SweepStore;
import io.tuvium.experiment.coverage.judge.TestQualityJudge;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.judge.coverage.CoverageImprovementJudge;
import org.springaicommunity.judge.exec.BuildSuccessJudge;
import org.springaicommunity.judge.jury.Jury;
import org.springaicommunity.judge.jury.TierPolicy;
import org.yaml.snakeyaml.Yaml;

/**
 * Code coverage experiment application. Runs agent variants against
 * spring-petclinic-partial and judges test quality with a 3-tier jury.
 */
public class ExperimentApp {

	private static final Logger logger = LoggerFactory.getLogger(ExperimentApp.class);

	private static final DateTimeFormatter SESSION_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
		.withZone(ZoneOffset.UTC);

	private final ExperimentVariantConfig variantConfig;

	private final JuryFactory juryFactory;

	private final ResultStore resultStore;

	private final SessionStore sessionStore;

	private final ComparisonEngine comparisonEngine;

	private final GrowthStoryReporter reporter;

	private final @Nullable SweepStore sweepStore;

	private final Path projectRoot;

	public ExperimentApp(ExperimentVariantConfig variantConfig, JuryFactory juryFactory,
			ResultStore resultStore, SessionStore sessionStore, Path projectRoot) {
		this(variantConfig, juryFactory, resultStore, sessionStore, null, projectRoot);
	}

	public ExperimentApp(ExperimentVariantConfig variantConfig, JuryFactory juryFactory,
			ResultStore resultStore, SessionStore sessionStore,
			@Nullable SweepStore sweepStore, Path projectRoot) {
		this.variantConfig = variantConfig;
		this.juryFactory = juryFactory;
		this.resultStore = resultStore;
		this.sessionStore = sessionStore;
		this.sweepStore = sweepStore;
		this.comparisonEngine = new DefaultComparisonEngine(resultStore);
		this.reporter = new GrowthStoryReporter(projectRoot.resolve("analysis"));
		this.projectRoot = projectRoot;
	}

	public ExperimentResult runVariant(VariantSpec variant, String sessionName) {
		logger.info("Running variant: {} (session: {})", variant.name(), sessionName);

		Jury jury = juryFactory.build(variant);
		AgentInvoker invoker = createInvoker(variant);

		ExperimentConfig config = ExperimentConfig.builder()
			.experimentName(variantConfig.experimentName())
			.datasetDir(projectRoot.resolve("dataset"))
			.promptTemplate(loadPrompt(variant))
			.model(variantConfig.defaultModel())
			.perItemTimeout(Duration.ofMinutes(variantConfig.timeoutMinutes()))
			.knowledgeBaseDir(variant.knowledgeDir() != null ? projectRoot.resolve(variant.knowledgeDir()) : null)
			.preserveWorkspaces(true)
			.outputDir(projectRoot.resolve("results"))
			.build();

		DatasetManager datasetManager = variantConfig.itemSlugFilter() != null
				? new SlugFilteringDatasetManager(variantConfig.datasetManager(), variantConfig.itemSlugFilter())
				: variantConfig.datasetManager();

		ExperimentRunner runner = new ExperimentRunner(
				datasetManager, jury, resultStore, sessionStore, config);

		ActiveSession activeSession = new ActiveSession(
				sessionName, variantConfig.experimentName(), variant.name());

		ExperimentResult result = runner.run(invoker, activeSession);

		logger.info("Variant '{}' complete: passRate={}, cost=${}",
				variant.name(),
				String.format("%.1f%%", result.passRate() * 100),
				String.format("%.4f", result.totalCostUsd()));

		return result;
	}

	public void runAllVariants(@Nullable String sweepName) {
		List<VariantSpec> variants = variantConfig.variants();
		String experimentName = variantConfig.experimentName();
		String sessionName = SESSION_NAME_FORMAT.format(Instant.now());

		logger.info("Running {} variants for experiment '{}' (session: {})",
				variants.size(), experimentName, sessionName);

		if (sweepName != null && sweepStore != null) {
			List<String> variantNames = variants.stream().map(VariantSpec::name).toList();
			sweepStore.createSweep(sweepName, experimentName, variantNames, Map.of());
		}

		sessionStore.createSession(sessionName, experimentName, Map.of());

		RunSessionStatus finalStatus = RunSessionStatus.COMPLETED;
		SweepStatus sweepStatus = SweepStatus.COMPLETED;
		try {
			ExperimentResult previousResult = null;

			for (VariantSpec variant : variants) {
				ExperimentResult result = runVariant(variant, sessionName);

				if (previousResult != null) {
					ComparisonResult comparison = comparisonEngine.compare(result, previousResult);
					reporter.appendComparison(variant.name(), comparison);
				}
				else {
					reporter.appendBaseline(variant.name(), comparisonEngine.summarize(result));
				}

				previousResult = result;
			}

			reporter.generateReport();
		}
		catch (Exception ex) {
			finalStatus = RunSessionStatus.FAILED;
			sweepStatus = SweepStatus.FAILED;
			throw ex;
		}
		finally {
			sessionStore.finalizeSession(sessionName, experimentName, finalStatus);

			if (sweepName != null && sweepStore != null) {
				String gitCommit = resolveGitCommit();
				sweepStore.addSession(sweepName, experimentName, sessionName, gitCommit);
				sweepStore.finalizeSweep(sweepName, experimentName, sweepStatus);
			}
		}
	}

	AgentInvoker createInvoker(VariantSpec variant) {
		return new CoverageAgentInvoker();
	}

	private String loadPrompt(VariantSpec variant) {
		Path promptPath = projectRoot.resolve("prompts").resolve(variant.promptFile());
		try {
			return Files.readString(promptPath);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to load prompt: " + promptPath, ex);
		}
	}

	private @Nullable String resolveGitCommit() {
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
				.directory(projectRoot.toFile())
				.redirectErrorStream(true)
				.start();
			String output = new String(process.getInputStream().readAllBytes()).trim();
			int exitCode = process.waitFor();
			return exitCode == 0 ? output : null;
		}
		catch (IOException | InterruptedException ex) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	static ExperimentVariantConfig loadConfig(Path configPath) {
		Yaml yaml = new Yaml();
		Map<String, Object> raw;
		try (InputStream in = Files.newInputStream(configPath)) {
			raw = yaml.load(in);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to load config: " + configPath, ex);
		}

		String experimentName = (String) raw.get("experimentName");
		String defaultModel = (String) raw.get("defaultModel");
		int timeoutMinutes = (int) raw.get("timeoutMinutes");

		List<Map<String, Object>> rawVariants = (List<Map<String, Object>>) raw.get("variants");
		List<VariantSpec> variants = new ArrayList<>();
		for (Map<String, Object> rv : rawVariants) {
			String name = (String) rv.get("name");
			String promptFile = (String) rv.get("promptFile");
			String actPromptFile = (String) rv.get("actPromptFile");
			String knowledgeDir = (String) rv.get("knowledgeDir");
			List<String> knowledgeFiles = rv.get("knowledgeFiles") != null
					? (List<String>) rv.get("knowledgeFiles")
					: List.of();
			variants.add(new VariantSpec(name, promptFile, actPromptFile, knowledgeDir, knowledgeFiles));
		}

		return new ExperimentVariantConfig(
				experimentName, defaultModel, timeoutMinutes,
				List.copyOf(variants), new FileSystemDatasetManager());
	}

	static JuryFactory buildJuryFactory(Path projectRoot) {
		Path judgePromptPath = projectRoot.resolve("prompts/judge-practice-adherence.txt");
		return JuryFactory.builder()
			.addJudge(0, BuildSuccessJudge.maven("clean", "test", "jacoco:report",
					"-Dspring-javaformat.skip=true"))
			.tierPolicy(0, TierPolicy.REJECT_ON_ANY_FAIL)
			.addJudge(1, new CoverageImprovementJudge(50.0, 85.0))
			.tierPolicy(1, TierPolicy.REJECT_ON_ANY_FAIL)
			.addJudge(2, new TestQualityJudge(
					TestQualityJudge.defaultAgentClientFactory("claude-sonnet-4-6", Duration.ofMinutes(3)),
					judgePromptPath))
			.tierPolicy(2, TierPolicy.FINAL_TIER)
			.build();
	}

	public static void main(String[] args) {
		Path projectRoot = Path.of(System.getProperty("user.dir"));

		String targetVariant = null;
		String targetItem = null;
		String sweepName = null;
		boolean runAll = false;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--variant" -> targetVariant = args[++i];
				case "--item" -> targetItem = args[++i];
				case "--sweep" -> sweepName = args[++i];
				case "--run-all-variants" -> runAll = true;
				case "--project-root" -> projectRoot = Path.of(args[++i]);
				default -> {
					logger.error("Unknown argument: {}", args[i]);
					System.exit(1);
				}
			}
		}

		if (targetVariant == null && !runAll) {
			logger.error("Usage: --variant <name> | --run-all-variants [--sweep <name>] [--item <slug>] [--project-root <path>]");
			System.exit(1);
		}

		ExperimentVariantConfig variantConfig = loadConfig(projectRoot.resolve("experiment-config.yaml"));

		if (targetItem != null) {
			variantConfig = variantConfig.withItemFilter(targetItem);
		}

		Path resultsDir = projectRoot.resolve("results");
		ResultStore resultStore = new FileSystemResultStore(resultsDir);
		SessionStore sessionStore = new FileSystemSessionStore(resultsDir);
		JuryFactory juryFactory = buildJuryFactory(projectRoot);

		SweepStore sweepStore = sweepName != null
				? new FileSystemSweepStore(resultsDir, sessionStore) : null;

		ExperimentApp app = new ExperimentApp(variantConfig, juryFactory, resultStore, sessionStore,
				sweepStore, projectRoot);

		if (runAll) {
			app.runAllVariants(sweepName);
		}
		else {
			String variantName = targetVariant;
			VariantSpec variant = variantConfig.variants().stream()
				.filter(v -> v.name().equals(variantName))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"Unknown variant: " + variantName));

			String sessionName = SESSION_NAME_FORMAT.format(Instant.now());
			sessionStore.createSession(sessionName, variantConfig.experimentName(), Map.of());
			RunSessionStatus finalStatus = RunSessionStatus.COMPLETED;
			try {
				app.runVariant(variant, sessionName);
			}
			catch (Exception ex) {
				finalStatus = RunSessionStatus.FAILED;
				throw ex;
			}
			finally {
				sessionStore.finalizeSession(sessionName, variantConfig.experimentName(), finalStatus);
			}
		}
	}

}
