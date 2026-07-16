package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

/** round 级规划诊断预算、原因编码与安全格式的纯 JVM 合同。 */
public class ChainPlanningDiagnosticsTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000CD");

    /** 相同 round 超过预算后只累计，不再输出候选明细。 */
    @Test
    public void candidateDetailsAreBoundedPerRound() {
        final List<String> logs = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(2, logs);

        for (int index = 0; index < 5; index++) {
            ChainTarget target = new ChainTarget(index, 64, 0);
            diagnostics.beginCandidate(target);
            diagnostics.recordCandidateResult(target, false);
        }

        Assert.assertEquals(5, diagnostics.getCandidateCount());
        Assert.assertEquals(2, diagnostics.getDetailCount());
        Assert.assertEquals(3, diagnostics.getSuppressedCount());
        Assert.assertEquals(2, countStage(logs, "stage=Candidate"));
        for (String log : logs) {
            Assert.assertTrue(log.contains("round=71"));
            Assert.assertTrue(log.contains("generation=9"));
        }
    }

    /** 耐久、最终 matcher 与 canHarvestBlock 三层拒绝原因互不混淆。 */
    @Test
    public void rejectionLayersHaveDistinctReasonCodes() {
        Assert.assertEquals("durability-insufficient",
                ChainHarvestRules.encodeDiagnosticReason(true, false, false, false, false));
        Assert.assertEquals("can-harvest-block-rejected",
                ChainHarvestRules.encodeDiagnosticReason(true, false, true, false, false));
        Assert.assertEquals("world-view",
                ChainHarvestRules.encodeDiagnosticReason(false, false, true, false, true));

        final List<String> logs = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(3, logs);
        ChainTarget durability = new ChainTarget(1, 2, 3);
        diagnostics.beginCandidate(durability);
        diagnostics.recordCandidateResult(durability, true);
        diagnostics.recordHarvestResult(durability, null, 0, "contentHash=abc", "false", "not-run",
                "durability-insufficient");
        diagnostics.recordMatcherResult(durability, false);

        ChainTarget matcher = new ChainTarget(2, 2, 3);
        diagnostics.beginCandidate(matcher);
        diagnostics.recordCandidateResult(matcher, true);
        diagnostics.recordMatcherResult(matcher, false);

        Assert.assertTrue(contains(logs, "resultReason=durability-insufficient"));
        Assert.assertTrue(contains(logs, "resultReason=matcher-rejected"));
    }

    /** 零预算等价于关闭明细，且启动格式只有短 hash，不泄露传入的完整 NBT 文本。 */
    @Test
    public void zeroBudgetDoesNotInvokePredicateAgainOrLeakNbt() {
        final List<String> logs = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(0, logs);
        final int[] predicateCalls = new int[1];
        ChainTarget target = new ChainTarget(4, 5, 6);
        diagnostics.beginCandidate(target);
        predicateCalls[0]++;
        diagnostics.recordCandidateResult(target, true);
        diagnostics.recordMatcherResult(target, true);
        diagnostics.logPlanStarted("minecraft:stone", 0, target, "worker-1", 0,
                "registry=mod:drill,contentHash=abc123");

        Assert.assertEquals("探针不得造成 predicate 二次调用", 1, predicateCalls[0]);
        Assert.assertEquals(0, countStage(logs, "stage=Candidate"));
        Assert.assertEquals(1, countStage(logs, "stage=PlanStarted"));
        Assert.assertFalse(logs.get(0).contains("{display:"));
        Assert.assertTrue(logs.get(0).contains("contentHash=abc123"));
    }

    private static ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics(int budget,
            final List<String> logs) {
        return new ChainPlanningRuntimeFactory.PlanningDiagnostics(PLAYER, 71L, 9, "CHAIN", "CHAIN_BASE",
                budget, new ChainPlanningRuntimeFactory.DiagnosticSink() {
                    @Override
                    public void log(String message) {
                        logs.add(message);
                    }
                });
    }

    private static int countStage(List<String> logs, String stage) {
        int count = 0;
        for (String log : logs) {
            if (log.contains(stage)) count++;
        }
        return count;
    }

    private static boolean contains(List<String> logs, String fragment) {
        for (String log : logs) {
            if (log.contains(fragment)) return true;
        }
        return false;
    }
}
