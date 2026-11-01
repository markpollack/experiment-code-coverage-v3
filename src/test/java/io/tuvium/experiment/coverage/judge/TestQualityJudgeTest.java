package io.tuvium.experiment.coverage.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.judge.judgment.JudgmentStatus;

class TestQualityJudgeTest {
    ObjectNode rubric(double score) {
        var root=new ObjectMapper().createObjectNode().put("boot_version","4.x"); var list=root.putArray("criteria");
        for(String name:TestQualityJudge.CRITERIA) list.addObject().put("name",name).put("score",score).put("evidence","Fixture.java observed pattern; counts=1");
        return root;
    }
    @Test void boundaryAndSeparateConvergence() {
        var pass=TestQualityJudge.parseJudgment(rubric(.5).toString());
        assertThat(pass.status()).isEqualTo(JudgmentStatus.PASS); assertThat(pass.score()).isEqualTo(.5);
        assertThat(pass.checks()).hasSize(6); assertThat(pass.metadata()).containsEntry("convergence_met",false);
        assertThat(TestQualityJudge.parseJudgment(rubric(.49).toString()).status()).isEqualTo(JudgmentStatus.FAIL);
    }
    @Test void malformedRostersAreInstrumentErrors() {
        var missing=rubric(.8); ((ArrayNode)missing.get("criteria")).remove(0);
        var duplicate=rubric(.8); ((ObjectNode)duplicate.at("/criteria/1")).put("name",TestQualityJudge.CRITERIA.getFirst());
        var unknown=rubric(.8); ((ObjectNode)unknown.at("/criteria/1")).put("name","invented");
        for(var root:new ObjectNode[]{missing,duplicate,unknown}) assertThat(TestQualityJudge.parseJudgment(root.toString()).status()).isEqualTo(JudgmentStatus.ERROR);
    }
    @Test void invalidScoresAndEvidenceAreNotClampedOrInvented() {
        for(double score:new double[]{-0.1,1.01,Double.NaN,Double.POSITIVE_INFINITY}) assertThat(TestQualityJudge.parseJudgment(rubric(score).toString()).status()).isEqualTo(JudgmentStatus.ERROR);
        var missing=rubric(.8); ((ObjectNode)missing.at("/criteria/0")).remove("score");
        var text=rubric(.8); ((ObjectNode)text.at("/criteria/0")).put("score","0.8");
        var empty=rubric(.8); ((ObjectNode)empty.at("/criteria/0")).put("evidence","");
        for(var root:new ObjectNode[]{missing,text,empty}) assertThat(TestQualityJudge.parseJudgment(root.toString()).status()).isEqualTo(JudgmentStatus.ERROR);
    }
}
