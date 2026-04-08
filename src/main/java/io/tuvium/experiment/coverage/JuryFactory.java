package io.tuvium.experiment.coverage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springaicommunity.judge.Judge;
import org.springaicommunity.judge.jury.CascadedJury;
import org.springaicommunity.judge.jury.ConsensusStrategy;
import org.springaicommunity.judge.jury.Jury;
import org.springaicommunity.judge.jury.SimpleJury;
import org.springaicommunity.judge.jury.TierConfig;
import org.springaicommunity.judge.jury.TierPolicy;

/**
 * Factory for building {@link Jury} instances.
 *
 * <p>Pre-wired tier structure for code coverage experiments:
 * <ul>
 *   <li>Tier 0: BuildSuccessJudge (./mvnw test) — REJECT_ON_ANY_FAIL</li>
 *   <li>Tier 1: JaCoCo coverage judge — ACCEPT_ON_ALL_PASS</li>
 *   <li>Tier 2: Practice adherence LLM judge — FINAL_TIER</li>
 * </ul>
 */
public class JuryFactory {

	private final Map<Integer, List<Judge>> tierJudges;

	private final Map<Integer, TierPolicy> tierPolicies;

	public JuryFactory(Map<Integer, List<Judge>> tierJudges, Map<Integer, TierPolicy> tierPolicies) {
		this.tierJudges = tierJudges;
		this.tierPolicies = tierPolicies;
	}

	public Jury build(VariantSpec variant) {
		List<TierConfig> tiers = new ArrayList<>();

		for (var entry : tierJudges.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			int tierNum = entry.getKey();
			List<Judge> judges = entry.getValue();
			TierPolicy policy = tierPolicies.getOrDefault(tierNum, TierPolicy.FINAL_TIER);

			SimpleJury.Builder juryBuilder = SimpleJury.builder()
					.votingStrategy(new ConsensusStrategy());
			for (Judge judge : judges) {
				juryBuilder.judge(judge);
			}
			SimpleJury tierJury = juryBuilder.build();
			tiers.add(new TierConfig("tier-" + tierNum, tierJury, policy));
		}

		CascadedJury.Builder cascadeBuilder = CascadedJury.builder();
		for (TierConfig tier : tiers) {
			cascadeBuilder.tier(tier.name(), tier.jury(), tier.policy());
		}
		return cascadeBuilder.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private final Map<Integer, List<Judge>> tierJudges = new TreeMap<>();

		private final Map<Integer, TierPolicy> tierPolicies = new HashMap<>();

		public Builder addJudge(int tier, Judge judge) {
			tierJudges.computeIfAbsent(tier, k -> new ArrayList<>()).add(judge);
			return this;
		}

		public Builder tierPolicy(int tier, TierPolicy policy) {
			tierPolicies.put(tier, policy);
			return this;
		}

		public JuryFactory build() {
			return new JuryFactory(tierJudges, tierPolicies);
		}

	}

}
