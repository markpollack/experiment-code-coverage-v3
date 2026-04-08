package io.tuvium.experiment.coverage;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Specification for a single experiment variant.
 *
 * @param name variant identifier (e.g., "simple", "hardened-skills")
 * @param promptFile filename in prompts/ directory
 * @param actPromptFile filename in prompts/ for act phase (null for single-phase)
 * @param knowledgeDir relative path to knowledge directory (null for no knowledge)
 * @param knowledgeFiles specific knowledge files to include
 * @param judgeOverrides judge configuration overrides for this variant
 */
public record VariantSpec(
		String name,
		String promptFile,
		@Nullable String actPromptFile,
		String knowledgeDir,
		List<String> knowledgeFiles,
		@Nullable Map<String, String> judgeOverrides) {

	public VariantSpec(String name, String promptFile, String knowledgeDir, List<String> knowledgeFiles) {
		this(name, promptFile, null, knowledgeDir, knowledgeFiles, null);
	}

	public VariantSpec(String name, String promptFile, @Nullable String actPromptFile,
			String knowledgeDir, List<String> knowledgeFiles) {
		this(name, promptFile, actPromptFile, knowledgeDir, knowledgeFiles, null);
	}

	public boolean isTwoPhase() {
		return actPromptFile != null;
	}

}
