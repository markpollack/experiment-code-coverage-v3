package io.tuvium.experiment.coverage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.experiment.agent.AgentInvoker;
import io.github.markpollack.experiment.agent.InvocationResult;
import io.github.markpollack.experiment.dataset.FileSystemDatasetManager;
import io.github.markpollack.experiment.result.ExperimentResult;
import io.github.markpollack.experiment.runner.ExperimentConfig;
import io.github.markpollack.experiment.runner.ExperimentRunner;
import io.github.markpollack.experiment.store.ActiveSession;
import io.github.markpollack.experiment.store.InMemorySessionStore;
import io.github.markpollack.experiment.store.ResultStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.judge.jury.Jury;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring smoke test — validates that all components connect without making real
 * API calls. Judges will FAIL (no real agent output) — that is expected.
 * We are testing wiring, not agent quality.
 *
 * <p>Run with: {@code COVERAGE_SMOKE_TEST=true ./mvnw test -Dgroups=integration}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "COVERAGE_SMOKE_TEST", matches = "true")
class SmokeTest {

	@TempDir
	Path tempDir;

	@Test
	void datasetLoads() {
		Path projectRoot = Path.of(System.getProperty("user.dir"));
		FileSystemDatasetManager manager = new FileSystemDatasetManager();

		var dataset = manager.load(projectRoot.resolve("dataset"));
		var items = manager.activeItems(dataset);

		assertThat(items).isNotEmpty();
		System.out.println("Dataset: " + items.size() + " items — "
				+ items.stream().map(i -> i.slug()).toList());
	}

	@Test
	void juryBuilds() {
		Path projectRoot = Path.of(System.getProperty("user.dir"));
		Jury jury = ExperimentApp.buildJuryFactory(projectRoot).build(
				new VariantSpec("smoke", "v0-simple.txt", null, List.of()));

		assertThat(jury).isNotNull();
	}

	@Test
	void runnerWiresAndExecutes() throws IOException {
		Path projectRoot = Path.of(System.getProperty("user.dir"));

		FileSystemDatasetManager baseManager = new FileSystemDatasetManager();
		SlugFilteringDatasetManager datasetManager = new SlugFilteringDatasetManager(
				baseManager, "spring-petclinic-partial");

		Path nonGitRoot = tempDir.resolve("non-git");
		Files.createDirectories(nonGitRoot);

		Jury jury = ExperimentApp.buildJuryFactory(projectRoot).build(
				new VariantSpec("smoke", "v0-simple.txt", null, List.of()));

		ExperimentConfig config = ExperimentConfig.builder()
				.experimentName("coverage-v3-smoke")
				.datasetDir(projectRoot.resolve("dataset"))
				.model("claude-sonnet-4-6")
				.promptTemplate("Write tests. Done when ./mvnw test passes.")
				.perItemTimeout(Duration.ofSeconds(30))
				.preserveWorkspaces(false)
				.outputDir(nonGitRoot.resolve("results"))
				.projectRoot(nonGitRoot)
				.build();

		InMemoryResultStore resultStore = new InMemoryResultStore();
		InMemorySessionStore sessionStore = new InMemorySessionStore();

		ExperimentRunner runner = new ExperimentRunner(datasetManager, jury, resultStore,
				sessionStore, config);

		ActiveSession session = new ActiveSession("smoke-session", "coverage-v3-smoke", "smoke");
		sessionStore.createSession("smoke-session", "coverage-v3-smoke", Map.of());

		ExperimentResult result = runner.run(placeholderInvoker(), session);

		assertThat(result.items()).isNotEmpty();
		System.out.println("Smoke run complete: " + result.items().size() + " item(s), "
				+ "passRate=" + result.passRate());
	}

	private AgentInvoker placeholderInvoker() {
		return ctx -> InvocationResult.completed(
				List.of(), 0, 0, 0, 0.0, 100L, "smoke", ctx.metadata());
	}

	// ---------------------------------------------------------------------------
	// Inline InMemoryResultStore — ResultStore is in test scope in experiment-core
	// and cannot be imported. Inlined here per SmokeTest pattern.
	// ---------------------------------------------------------------------------

	static class InMemoryResultStore implements ResultStore {

		private final Map<String, ExperimentResult> results = new LinkedHashMap<>();

		@Override
		public void save(ExperimentResult result) {
			results.put(result.experimentId(), result);
		}

		@Override
		public Optional<ExperimentResult> load(String id) {
			return Optional.ofNullable(results.get(id));
		}

		@Override
		public List<ExperimentResult> listByName(String experimentName) {
			return results.values()
					.stream()
					.filter(r -> r.experimentName().equals(experimentName))
					.sorted(Comparator.comparing(ExperimentResult::timestamp))
					.toList();
		}

		@Override
		public Optional<ExperimentResult> mostRecent(String experimentName) {
			return results.values()
					.stream()
					.filter(r -> r.experimentName().equals(experimentName))
					.max(Comparator.comparing(ExperimentResult::timestamp));
		}

	}

}
