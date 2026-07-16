package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
        final AtomicInteger predicateCalls = new AtomicInteger();
        ChainCandidateFilter filter = ChainPlanningRuntimeFactory.decorateCandidateFilterWithDiagnostics(target -> {
            predicateCalls.incrementAndGet();
            return false;
        }, diagnostics);

        for (int index = 0; index < 5; index++) {
            Assert.assertFalse(filter.canTraverse(new ChainTarget(index, 64, 0)));
        }

        Assert.assertEquals(5, predicateCalls.get());
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
        List<String> matcherReasons = Arrays.asList(
                ChainHarvestRules.encodeDiagnosticReason(true, false, false, false, false),
                ChainHarvestRules.encodeDiagnosticReason(true, false, true, false, true),
                ChainHarvestRules.encodeDiagnosticReason(true, false, true, false, false),
                ChainHarvestRules.encodeDiagnosticReason(true, false, true, true, false));
        Assert.assertEquals(Arrays.asList("durability-insufficient", "accepted",
                "can-harvest-block-rejected", "accepted"), matcherReasons);

        final List<String> logs = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(4, logs);
        ChainBlockMatcher[] matchers = {
                new HarvestableBlockMatcher(),
                new SameBlockHarvestableMatcher(null, 0, null),
                new OreBlockHarvestableMatcher(),
                new LogBlockHarvestableMatcher()
        };
        for (int index = 0; index < matchers.length; index++) {
            Assert.assertTrue("正式采掘 matcher 必须接受共享诊断上下文",
                    ChainPlanningRuntimeFactory.setMatcherDiagnostics(matchers[index], diagnostics));
            ChainTarget target = new ChainTarget(index, 2, 3);
            diagnostics.beginCandidate(target);
            diagnostics.recordCandidateResult(target, true);
            String reason = matcherReasons.get(index);
            boolean accepted = "accepted".equals(reason);
            diagnostics.recordHarvestResult(target, null, index, "contentHash=abc" + index,
                    index == 0 ? "false" : "true", index == 2 ? "false" : "true", reason);
            diagnostics.recordMatcherResult(target, accepted);
        }

        Assert.assertEquals(2, countContaining(logs, "resultReason=accepted"));
        Assert.assertEquals(1, countContaining(logs, "resultReason=durability-insufficient"));
        Assert.assertEquals(1, countContaining(logs, "resultReason=can-harvest-block-rejected"));
    }

    /** 生产包装链保持 candidate/matcher 短路顺序，且不二次读取业务运行态。 */
    @Test
    public void productionWrappersObserveSingleBusinessEvaluationInOriginalOrder() {
        final List<String> logs = new ArrayList<String>();
        final List<String> order = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(2, logs);
        final AtomicInteger worldReads = new AtomicInteger();
        final AtomicInteger playerReads = new AtomicInteger();
        ChainCandidateFilter rejectedFilter = ChainPlanningRuntimeFactory.decorateCandidateFilterWithDiagnostics(
                target -> {
                    order.add("candidate-rejected");
                    worldReads.incrementAndGet();
                    return false;
                }, diagnostics);
        ChainBlockMatcher matcher = ChainPlanningRuntimeFactory.decorateMatcherWithDiagnostics((player, target) -> {
            order.add("matcher");
            playerReads.incrementAndGet();
            return true;
        }, diagnostics);

        ChainTarget rejected = new ChainTarget(4, 5, 6);
        Assert.assertFalse(rejectedFilter.canTraverse(rejected) && matcher.matches(null, rejected));
        Assert.assertEquals(Arrays.asList("candidate-rejected"), order);
        Assert.assertEquals(1, worldReads.get());
        Assert.assertEquals(0, playerReads.get());

        ChainCandidateFilter acceptedFilter = ChainPlanningRuntimeFactory.decorateCandidateFilterWithDiagnostics(
                target -> {
                    order.add("candidate-accepted");
                    worldReads.incrementAndGet();
                    return true;
                }, diagnostics);
        ChainBlockMatcher acceptedMatcher = ChainPlanningRuntimeFactory.decorateMatcherWithDiagnostics(
                (player, target) -> {
                    order.add("matcher");
                    playerReads.incrementAndGet();
                    diagnostics.recordHarvestResult(target, null, 0, "contentHash=abc123", "true", "true",
                            "accepted");
                    return true;
                }, diagnostics);
        ChainTarget accepted = new ChainTarget(5, 5, 6);
        Assert.assertTrue(acceptedFilter.canTraverse(accepted) && acceptedMatcher.matches(null, accepted));
        Assert.assertEquals(Arrays.asList("candidate-rejected", "candidate-accepted", "matcher"), order);
        Assert.assertEquals(2, worldReads.get());
        Assert.assertEquals(1, playerReads.get());
        Assert.assertTrue(contains(logs, "resultReason=accepted"));
    }

    /** 预算耗尽只关闭明细，业务返回值与安全摘要格式不受影响。 */
    @Test
    public void zeroBudgetSuppressesOnlyDetailsAndDoesNotLeakNbt() {
        final List<String> logs = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(0, logs);
        final AtomicInteger predicateCalls = new AtomicInteger();
        ChainCandidateFilter filter = ChainPlanningRuntimeFactory.decorateCandidateFilterWithDiagnostics(target -> {
            predicateCalls.incrementAndGet();
            return true;
        }, diagnostics);
        ChainBlockMatcher matcher = ChainPlanningRuntimeFactory.decorateMatcherWithDiagnostics((player, target) -> {
            predicateCalls.incrementAndGet();
            return true;
        }, diagnostics);
        ChainTarget target = new ChainTarget(4, 5, 6);
        Assert.assertTrue(filter.canTraverse(target));
        Assert.assertTrue(matcher.matches(null, target));
        diagnostics.logPlanStarted("minecraft:stone", 0, target, "worker-1", 0,
                "registry=mod:drill,contentHash=abc123");

        Assert.assertEquals("两个原 predicate 均只能执行一次", 2, predicateCalls.get());
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
        return countContaining(logs, stage);
    }

    private static int countContaining(List<String> logs, String fragment) {
        int count = 0;
        for (String log : logs) {
            if (log.contains(fragment)) count++;
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
