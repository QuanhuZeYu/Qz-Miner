package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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
        final AtomicInteger matcherCalls = new AtomicInteger();
        ChainPlanningRuntimeFactory.DiagnosticAssembly assembly = ChainPlanningRuntimeFactory.assembleDiagnosticRuntime(
                searchContext(),
                target -> {
                    predicateCalls.incrementAndGet();
                    return false;
                }, (player, target) -> {
                    matcherCalls.incrementAndGet();
                    return true;
                }, diagnostics);

        for (int index = 0; index < 5; index++) {
            Assert.assertFalse(matches(assembly, new ChainTarget(index, 64, 0)));
        }

        Assert.assertEquals(5, predicateCalls.get());
        Assert.assertEquals(0, matcherCalls.get());
        Assert.assertEquals(5, diagnostics.getCandidateCount());
        Assert.assertEquals(2, diagnostics.getDetailCount());
        Assert.assertEquals(3, diagnostics.getSuppressedCount());
        Assert.assertEquals(2, countStage(logs, "stage=Candidate"));
        for (String log : logs) {
            Assert.assertTrue(log.contains("round=71"));
            Assert.assertTrue(log.contains("generation=9"));
        }
    }

    /** 四类正式 matcher 均经过真实 matches 委托，并保留判定结果与原因。 */
    @Test
    public void formalMatchersEvaluateAndRecordThroughRealMatchesEntry() {
        List<String> matcherReasons = Arrays.asList(
                ChainHarvestRules.encodeDiagnosticReason(true, false, false, false, false),
                ChainHarvestRules.encodeDiagnosticReason(true, false, true, false, true),
                ChainHarvestRules.encodeDiagnosticReason(true, false, true, false, false),
                ChainHarvestRules.encodeDiagnosticReason(true, false, true, true, false));
        Assert.assertEquals(Arrays.asList("durability-insufficient", "accepted",
                "can-harvest-block-rejected", "accepted"), matcherReasons);

        AtomicInteger defaultHarvestCalls = new AtomicInteger();
        assertMatcherEvaluation(new HarvestableBlockMatcher(
                evaluator(defaultHarvestCalls, false, matcherReasons.get(0))), false, matcherReasons.get(0),
                defaultHarvestCalls, null);
        AtomicInteger sameClassifierCalls = new AtomicInteger();
        AtomicInteger sameHarvestCalls = new AtomicInteger();
        assertMatcherEvaluation(new SameBlockHarvestableMatcher(null, 0, null,
                classifier(sameClassifierCalls, true), evaluator(sameHarvestCalls, true, matcherReasons.get(1))),
                true, matcherReasons.get(1), sameHarvestCalls, sameClassifierCalls);
        AtomicInteger oreClassifierCalls = new AtomicInteger();
        AtomicInteger oreHarvestCalls = new AtomicInteger();
        assertMatcherEvaluation(new OreBlockHarvestableMatcher(classifier(oreClassifierCalls, true),
                evaluator(oreHarvestCalls, false, matcherReasons.get(2))), false, matcherReasons.get(2),
                oreHarvestCalls, oreClassifierCalls);
        AtomicInteger logClassifierCalls = new AtomicInteger();
        AtomicInteger logHarvestCalls = new AtomicInteger();
        assertMatcherEvaluation(new LogBlockHarvestableMatcher(classifier(logClassifierCalls, true),
                evaluator(logHarvestCalls, true, matcherReasons.get(3))), true, matcherReasons.get(3),
                logHarvestCalls, logClassifierCalls);
    }

    /** 三类身份 matcher 在 classifier 拒绝时短路采掘，接受时仅采掘一次。 */
    @Test
    public void identityMatchersShortCircuitHarvestEvaluatorOnce() {
        AtomicInteger sameClassifierCalls = new AtomicInteger();
        AtomicInteger sameHarvestCalls = new AtomicInteger();
        assertClassifierShortCircuit(new SameBlockHarvestableMatcher(null, 0, null,
                classifier(sameClassifierCalls, false), evaluator(sameHarvestCalls, true, "accepted")),
                sameClassifierCalls, sameHarvestCalls);
        AtomicInteger oreClassifierCalls = new AtomicInteger();
        AtomicInteger oreHarvestCalls = new AtomicInteger();
        assertClassifierShortCircuit(new OreBlockHarvestableMatcher(classifier(oreClassifierCalls, false),
                evaluator(oreHarvestCalls, true, "accepted")), oreClassifierCalls, oreHarvestCalls);
        AtomicInteger logClassifierCalls = new AtomicInteger();
        AtomicInteger logHarvestCalls = new AtomicInteger();
        assertClassifierShortCircuit(new LogBlockHarvestableMatcher(classifier(logClassifierCalls, false),
                evaluator(logHarvestCalls, true, "accepted")), logClassifierCalls, logHarvestCalls);
    }

    /** 生产共享装配接缝保持 candidate/matcher 短路顺序，且不二次求值。 */
    @Test
    public void productionAssemblyPreservesSingleEvaluationAndShortCircuitOrder() {
        final List<String> logs = new ArrayList<String>();
        final List<String> order = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(2, logs);
        final AtomicInteger worldReads = new AtomicInteger();
        final AtomicInteger playerReads = new AtomicInteger();
        ChainBlockMatcher matcher = (player, target) -> {
            order.add("matcher");
            playerReads.incrementAndGet();
            return true;
        };
        ChainPlanningRuntimeFactory.DiagnosticAssembly rejectedAssembly =
                ChainPlanningRuntimeFactory.assembleDiagnosticRuntime(searchContext(), target -> {
                    order.add("candidate-rejected");
                    worldReads.incrementAndGet();
                    return false;
                }, matcher, diagnostics);

        ChainTarget rejected = new ChainTarget(4, 5, 6);
        Assert.assertFalse(matches(rejectedAssembly, rejected));
        Assert.assertEquals(Arrays.asList("candidate-rejected"), order);
        Assert.assertEquals(1, worldReads.get());
        Assert.assertEquals(0, playerReads.get());

        ChainPlanningRuntimeFactory.DiagnosticAssembly acceptedAssembly =
                ChainPlanningRuntimeFactory.assembleDiagnosticRuntime(searchContext(), target -> {
                    order.add("candidate-accepted");
                    worldReads.incrementAndGet();
                    return true;
                }, (player, target) -> {
                    order.add("matcher");
                    playerReads.incrementAndGet();
                    return true;
                }, diagnostics);
        ChainTarget accepted = new ChainTarget(5, 5, 6);
        Assert.assertTrue(matches(acceptedAssembly, accepted));
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
        ChainPlanningRuntimeFactory.DiagnosticAssembly assembly = ChainPlanningRuntimeFactory.assembleDiagnosticRuntime(
                searchContext(),
                target -> {
                    predicateCalls.incrementAndGet();
                    return true;
                }, (player, target) -> {
                    predicateCalls.incrementAndGet();
                    return true;
                }, diagnostics);
        ChainTarget target = new ChainTarget(4, 5, 6);
        Assert.assertTrue(matches(assembly, target));
        diagnostics.logPlanStarted("minecraft:stone", 0, target, "worker-1", 0,
                "registry=mod:drill,contentHash=abc123");

        Assert.assertEquals("两个原 predicate 均只能执行一次", 2, predicateCalls.get());
        Assert.assertEquals(0, countStage(logs, "stage=Candidate"));
        Assert.assertEquals(1, countStage(logs, "stage=PlanStarted"));
        Assert.assertFalse(logs.get(0).contains("{display:"));
        Assert.assertTrue(logs.get(0).contains("contentHash=abc123"));
    }

    /** createRuntime 只能从原子接缝取得最终 filter/matcher，禁止回到中间装配步骤。 */
    @Test
    public void productionRuntimeUsesAtomicAssemblySeam() throws IOException {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningRuntimeFactory.java").toPath()),
                StandardCharsets.UTF_8);
        int runtimeStart = source.indexOf("private static ChainPlanningRuntime createRuntime(");
        int runtimeEnd = source.indexOf("    /** 为所有正式采掘 matcher", runtimeStart);
        Assert.assertTrue("createRuntime source must be present", runtimeStart >= 0);
        Assert.assertTrue("atomic assembly method must follow createRuntime", runtimeEnd > runtimeStart);
        String runtimeSource = source.substring(runtimeStart, runtimeEnd);
        Assert.assertEquals(1, countOccurrences(runtimeSource, "assembleDiagnosticRuntime("));
        Assert.assertTrue(runtimeSource.contains(
                "assembleDiagnosticRuntime(\n                searchContext, candidateFilter, matcher, diagnostics, planningEvaluator)"));
        Assert.assertFalse(runtimeSource.contains("bindMatcherDiagnostics("));
        Assert.assertFalse(runtimeSource.contains("decorateModeExtensionMatcher("));
        Assert.assertFalse(runtimeSource.contains("assembleDiagnostics("));

        int assemblyStart = source.indexOf("static DiagnosticAssembly assembleDiagnosticRuntime(");
        int extensionStart = source.indexOf("    /**\n     * 为模式扩展装饰器", assemblyStart);
        Assert.assertTrue("atomic assembly source must be present", assemblyStart >= 0);
        Assert.assertTrue("atomic assembly body must be bounded", extensionStart > assemblyStart);
        String assemblySource = source.substring(assemblyStart, extensionStart);
        Assert.assertTrue(assemblySource.indexOf("bindMatcherPlanning(")
                < assemblySource.indexOf("decorateModeExtensionMatcher("));
        Assert.assertTrue(assemblySource.indexOf("decorateModeExtensionMatcher(")
                < assemblySource.indexOf("decorateCandidateFilterWithDiagnostics("));
        Assert.assertTrue(assemblySource.indexOf("decorateCandidateFilterWithDiagnostics(")
                < assemblySource.indexOf("decorateMatcherWithDiagnostics("));
    }

    private static void assertMatcherEvaluation(ChainBlockMatcher matcher, boolean expectedResult,
            String expectedReason, AtomicInteger harvestCalls, AtomicInteger classifierCalls) {
        final List<String> logs = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(1, logs);
        ChainPlanningRuntimeFactory.DiagnosticAssembly assembly =
                ChainPlanningRuntimeFactory.assembleDiagnosticRuntime(searchContext(), target -> true, matcher,
                        diagnostics);
        ChainTarget target = new ChainTarget(1, 2, 3);

        Assert.assertTrue(assembly.getCandidateFilter().canTraverse(target));
        boolean actualResult = assembly.getMatcher().matches(null, target);

        Assert.assertEquals(expectedResult, actualResult);
        Assert.assertTrue("真实 matcher 必须把 evaluator 原因交给统一诊断接线",
                contains(logs, "harvestReason=" + expectedReason));
        Assert.assertTrue("最终 matcher 必须由 assembly 返回值执行",
                contains(logs, "resultReason=" + (expectedResult ? "accepted" : expectedReason)));
        Assert.assertEquals("harvest evaluator 必须恰好执行一次", 1, harvestCalls.get());
        if (classifierCalls != null) {
            Assert.assertEquals("classifier 必须恰好执行一次", 1, classifierCalls.get());
        }
    }

    private static void assertClassifierShortCircuit(ChainBlockMatcher matcher, AtomicInteger classifierCalls,
            AtomicInteger harvestCalls) {
        final List<String> logs = new ArrayList<String>();
        ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics = diagnostics(1, logs);
        ChainPlanningRuntimeFactory.DiagnosticAssembly assembly =
                ChainPlanningRuntimeFactory.assembleDiagnosticRuntime(searchContext(), target -> true, matcher,
                        diagnostics);

        Assert.assertFalse(matches(assembly, new ChainTarget(7, 8, 9)));
        Assert.assertEquals(1, classifierCalls.get());
        Assert.assertEquals(0, harvestCalls.get());
        Assert.assertTrue(contains(logs, "resultReason=matcher-rejected"));
    }

    private static ChainHarvestRules.HarvestEvaluator evaluator(final AtomicInteger calls, final boolean result,
            final String reason) {
        return (player, target, diagnosticTracking) -> {
            calls.incrementAndGet();
            return new ChainHarvestRules.HarvestEvaluation(result, null, 0, "contentHash=abc123", "true",
                    String.valueOf(result), reason);
        };
    }

    private static ChainHarvestRules.TargetClassifier classifier(final AtomicInteger calls, final boolean result) {
        return (player, target) -> {
            calls.incrementAndGet();
            return result;
        };
    }

    private static boolean matches(ChainPlanningRuntimeFactory.DiagnosticAssembly assembly, ChainTarget target) {
        return assembly.getCandidateFilter().canTraverse(target) && assembly.getMatcher().matches(null, target);
    }

    private static ChainSearchContext searchContext() {
        return new ChainSearchContext(null, null, null, 0, null, null, 0, 0,
                new ConcurrentLinkedQueue<ChainTarget>(), new ConcurrentLinkedQueue<ChainTarget>(),
                Collections.<ChainTarget>emptySet());
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

    private static int countOccurrences(String source, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
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
