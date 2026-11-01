package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.coverage.*;
import io.github.markpollack.judge.exec.BuildSuccessJudge;
import io.github.markpollack.judge.judgment.*;
import io.github.markpollack.judge.jury.*;
import io.github.markpollack.judge.verdict.RoutingRule;
import io.github.markpollack.judge.voting.ConsensusStrategy;
import io.tuvium.experiment.coverage.judge.TestQualityJudge;
import java.nio.file.*;
import java.util.*;

/** Configured per item, preserving fail-fast tiers and instrument errors. */
public final class JuryFactory {
    public static Jury create(PreparedItem prepared,Domain measured,Path prompt,Path evidence) throws Exception {
        Path ws=Path.of(prepared.workspace()); Files.createDirectories(evidence);
        double baseline=CoverageEvidence.value(prepared.baseline(),"lineCoverage");
        var prior=new ArrayList<Judgment>();
        Judge build=BuildSuccessJudge.maven("clean","test","jacoco:report","-Dspring-javaformat.skip=true",
                "-Dmaven.repo.local="+System.getProperty("coverage.mavenRepo")).evidence(ws).build();
        Judge coverage=()-> {
            try {
                var fresh=CoverageEvidence.read(ws.resolve("target/site/jacoco/jacoco.xml"),evidence.resolve("judge-coverage.xml"));
                double finalLine=CoverageEvidence.value(measured.measurements(),"lineCoverage");
                if(Math.abs(fresh.line()-finalLine)>0.000001) return Judgment.error("Final measurement differs from independently rebuilt report");
                return CoverageImprovementJudge.builder(50.0,85.0).evidence(new CoverageComparison(ws,baseline)).build().judge();
            } catch(Exception e) {return Judgment.error("Coverage instrument failure: "+e);}
        };
        var cascade=CascadedJury.builder();
        var judges=List.of(build,coverage,new TestQualityJudge(ws,prompt,evidence.resolve("quality")));
        var names=List.of("t0_build","t1_coverage","t2_quality");
        for(int i=0;i<judges.size();i++) {
            Judge judge=judges.get(i); String name=names.get(i);
            Judge guarded=()-> {
                Judgment judgment;
                if(prior.stream().anyMatch(j->j.status()==JudgmentStatus.ERROR)) judgment=Judgment.error("Earlier instrument error; this tier not executed");
                else try {judgment=judge.judge();} catch(Exception e) {judgment=Judgment.error("Judge threw: "+e);}
                prior.add(judgment);
                try {CoverageEvidence.write(evidence.resolve(name+".json"),judgment);} catch(Exception e) {throw new IllegalStateException("Cannot retain judge evidence",e);}
                return judgment;
            };
            var jury=SimpleJury.builder().judge(name,guarded).votingStrategy(new ConsensusStrategy()).build();
            cascade.tier("tier-"+i,jury,i==2?RoutingRule.FINAL_TIER:RoutingRule.STOP_ON_ANY_OPINION_FAIL);
        }
        return cascade.build();
    }
}
