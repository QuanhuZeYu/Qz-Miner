package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;

/** 六个预算化 traverser 共用空气候选事务的合同测试。 */
public class BudgetedChainTraverserAirBudgetContractTest {

    private static final ChainTarget ORIGIN = new ChainTarget(0, 0, 0);
    private static final ChainTarget X1 = new ChainTarget(1, 0, 0);
    private static final ChainTarget X2 = new ChainTarget(2, 0, 0);

    /** 六种遍历器在 work budget=1 时均可恢复，并与充足预算输出相同。 */
    @Test
    public void oneUnitSlicesMatchFullBudgetForAllTraversers() {
        assertEquivalent("FloodFill", floodOutcome(4096), floodOutcome(1), X1);
        assertEquivalent("LoggingFloodFill", loggingOutcome(4096), loggingOutcome(1), X1);
        assertEquivalent("GregTechCable", gregTechOutcome(4096), gregTechOutcome(1), ORIGIN);
        assertEquivalent("BoxScan", boxOutcome(4096), boxOutcome(1), X1);
        assertEquivalent("TunnelBoxScan", tunnelOutcome(4096), tunnelOutcome(1), X1);
        assertEquivalent("SectionClear", sectionOutcome(4096), sectionOutcome(1), X1);
    }

    /** FloodFill 与 GT 的 seed 只能初始化状态，不得提前读取世界或 candidate。 */
    @Test
    public void floodAndGregTechSeedDoNotProbeCandidates() {
        assertSeedDoesNotProbe(new FloodFillTraverser(), ChainSubMode.CHAIN_BASE);
        assertSeedDoesNotProbe(new GregTechCableTraverser(null), ChainSubMode.SPECIAL_GT_CABLE_REPLACE);
    }

    /** AREA 队首在排队后变为空气时只 poll 一次，并跳过 matcher/consumer。 */
    @Test
    public void queuedTargetTurningIntoAirIsCommittedOnceAndSkipped() {
        ChainTarget firstShellCoordinate = new ChainTarget(-1, -1, -1);
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(firstShellCoordinate, false);
        ChainSearchContext context = context(
                ChainSubMode.AREA_HARVESTABLE_ALL, ORIGIN, 1, 8, blocks);
        BoxScanTraverser traverser = new BoxScanTraverser();
        List<ChainTarget> matcherCalls = new ArrayList<ChainTarget>();
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        traverser.seed(context);

        TraversalStepResult first = traverser.step(
                context, new SliceControl(2), target -> {
                    matcherCalls.add(target);
                    return true;
                }, accepted::add);
        Assert.assertEquals(TraversalStepResult.YIELDED, first);
        Assert.assertEquals(firstShellCoordinate, context.getCurrentFrontier().peek());

        blocks.put(firstShellCoordinate, true);
        TraversalStepResult terminal = runToCompletion(
                traverser, context, 64, target -> {
                    matcherCalls.add(target);
                    return true;
                }, accepted);

        Assert.assertEquals(TraversalStepResult.COMPLETED, terminal);
        Assert.assertTrue(context.getCurrentFrontier().isEmpty());
        Assert.assertTrue(accepted.isEmpty());
        Assert.assertTrue("空气队首不得调用 matcher", matcherCalls.isEmpty());
        Assert.assertEquals("生成与队首重验各读取一次", 2, blocks.getProbeCount(firstShellCoordinate));
    }

    /** 第 1024 个队首空气扣费失败时 queue 不动；世界变化后只提交一次。 */
    @Test
    public void quotaFailureKeepsQueueAndRetryUsesFreshWorldFact() {
        MutableBlocks blocks = new MutableBlocks(true);
        ChainSearchContext context = context(
                ChainSubMode.AREA_HARVESTABLE_ALL, ORIGIN, 1, 1, blocks);
        ChainTarget queued = new ChainTarget(7, 8, 9);
        for (int index = 0; index < 1023; index++) {
            Assert.assertEquals(
                    PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED,
                    context.tryCommitPlanningCandidate(new SliceControl(1),
                            new ChainTarget(index + 100, 0, 0)));
        }
        context.getCurrentFrontier().add(queued);
        BoxScanTraverser traverser = new BoxScanTraverser();
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();

        TraversalStepResult failed = traverser.step(
                context, new QuotaRejectingControl(), target -> true, accepted::add);

        Assert.assertEquals(TraversalStepResult.YIELDED, failed);
        Assert.assertEquals(queued, context.getCurrentFrontier().peek());
        Assert.assertEquals(1023, context.getPlanningAirRemainder());
        Assert.assertEquals(0, context.getConfirmedCount());

        blocks.put(queued, false);
        TraversalStepResult terminal = runToCompletion(
                traverser, context, 1, target -> true, accepted);

        Assert.assertEquals(TraversalStepResult.COMPLETED, terminal);
        Assert.assertEquals(singleton(queued), accepted);
        Assert.assertTrue(context.getCurrentFrontier().isEmpty());
        Assert.assertEquals(1023, context.getPlanningAirRemainder());
        Assert.assertEquals("失败空气与非空气重试各读取一次", 2, blocks.getProbeCount(queued));
    }

    /** CHAIN/Logging/GT 的空气节点不得入队或桥接其后的实体目标。 */
    @Test
    public void airBreaksChainExpansion() {
        assertAirBreaksChain(new FloodFillTraverser(), ChainSubMode.CHAIN_BASE, false);
        assertAirBreaksChain(new LoggingFloodFillTraverser(1), ChainSubMode.CHAIN_LOGGING, false);
        assertAirBreaksChain(
                new GregTechCableTraverser(null), ChainSubMode.SPECIAL_GT_CABLE_REPLACE, true);
    }

    private static TraversalOutcome floodOutcome(int budget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(
                new FloodFillTraverser(),
                context(ChainSubMode.CHAIN_BASE, ORIGIN, 4, 64, blocks),
                budget);
    }

    private static TraversalOutcome loggingOutcome(int budget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(
                new LoggingFloodFillTraverser(1),
                context(ChainSubMode.CHAIN_LOGGING, ORIGIN, 4, 64, blocks),
                budget);
    }

    private static TraversalOutcome gregTechOutcome(int budget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(ORIGIN, false);
        return traverse(
                new GregTechCableTraverser(null),
                context(ChainSubMode.SPECIAL_GT_CABLE_REPLACE, ORIGIN, 4, 1, blocks),
                budget);
    }

    private static TraversalOutcome boxOutcome(int budget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(
                new BoxScanTraverser(),
                context(ChainSubMode.AREA_HARVESTABLE_ALL, ORIGIN, 1, 64, blocks),
                budget);
    }

    private static TraversalOutcome tunnelOutcome(int budget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(
                new TunnelBoxScanTraverser(5),
                context(ChainSubMode.AREA_TUNNEL, ORIGIN, 2, 64, blocks),
                budget);
    }

    private static TraversalOutcome sectionOutcome(int budget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(
                new SectionClearTraverser(),
                context(ChainSubMode.AREA_SECTION_CLEAR, ORIGIN, 16, 5000, blocks),
                budget);
    }

    private static void assertEquivalent(
        String label,
        TraversalOutcome full,
        TraversalOutcome sliced,
        ChainTarget expected) {
        Assert.assertEquals(label, singleton(expected), full.accepted);
        Assert.assertEquals(label, full.accepted, sliced.accepted);
        Assert.assertEquals(label, full.matcherCalls, sliced.matcherCalls);
        Assert.assertEquals(label, full.context.getConfirmedCount(), sliced.context.getConfirmedCount());
        Assert.assertEquals(label, full.context.getVisited(), sliced.context.getVisited());
        Assert.assertEquals(label, full.context.getScanDepth(), sliced.context.getScanDepth());
        Assert.assertEquals(
                label, full.context.getPlanningAirRemainder(), sliced.context.getPlanningAirRemainder());
        Assert.assertTrue(label + " 必须实际跨越多个 slice", sliced.slices > 1);
    }

    private static void assertSeedDoesNotProbe(BudgetedChainTraverser traverser, ChainSubMode subMode) {
        MutableBlocks blocks = new MutableBlocks(false);
        AtomicInteger candidateCalls = new AtomicInteger();
        ChainSearchContext context = context(subMode, ORIGIN, 4, 4, blocks);
        context.setCandidateFilter(target -> {
            candidateCalls.incrementAndGet();
            return true;
        });

        traverser.seed(context);

        Assert.assertEquals(0, blocks.getTotalProbes());
        Assert.assertEquals(0, candidateCalls.get());
        Assert.assertTrue(context.getCurrentFrontier().isEmpty());
    }

    private static void assertAirBreaksChain(
        BudgetedChainTraverser traverser,
        ChainSubMode subMode,
        boolean originIsAirCandidate) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X2, false);
        ChainSearchContext context = context(subMode, ORIGIN, 4, 8, blocks);
        TraversalOutcome outcome = traverse(traverser, context, 4096);

        Assert.assertTrue(outcome.accepted.isEmpty());
        Assert.assertTrue(outcome.matcherCalls.isEmpty());
        Assert.assertEquals("空气之后的目标不得被探测", 0, blocks.getProbeCount(X2));
        if (originIsAirCandidate) {
            Assert.assertEquals(1, blocks.getProbeCount(ORIGIN));
        }
    }

    private static TraversalOutcome traverse(
        BudgetedChainTraverser traverser,
        ChainSearchContext context,
        int workBudget) {
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        List<ChainTarget> matcherCalls = new ArrayList<ChainTarget>();
        traverser.seed(context);

        for (int slice = 1; slice <= 50000; slice++) {
            TraversalStepResult result = traverser.step(
                    context, new SliceControl(workBudget), target -> {
                        matcherCalls.add(target);
                        return true;
                    }, accepted::add);
            if (result == TraversalStepResult.COMPLETED) {
                return new TraversalOutcome(context, accepted, matcherCalls, slice);
            }
            Assert.assertEquals("未完成分片只能在预算边界让出", TraversalStepResult.YIELDED, result);
        }
        Assert.fail("遍历未在安全分片上限内完成");
        return null;
    }

    private static TraversalStepResult runToCompletion(
        BudgetedChainTraverser traverser,
        ChainSearchContext context,
        int workBudget,
        ChainTargetMatcher matcher,
        List<ChainTarget> accepted) {
        for (int slice = 0; slice < 50000; slice++) {
            TraversalStepResult result = traverser.step(
                    context, new SliceControl(workBudget), matcher, accepted::add);
            if (result == TraversalStepResult.COMPLETED) {
                return result;
            }
            Assert.assertEquals(TraversalStepResult.YIELDED, result);
        }
        Assert.fail("续跑未在安全分片上限内完成");
        return TraversalStepResult.TERMINATED;
    }

    private static ChainSearchContext context(
        ChainSubMode subMode,
        ChainTarget origin,
        int maxRadius,
        int maxTargets,
        MutableBlocks blocks) {
        ConcurrentLinkedQueue<ChainTarget> current = new ConcurrentLinkedQueue<ChainTarget>();
        ConcurrentLinkedQueue<ChainTarget> next = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = new HashSet<ChainTarget>();
        ChainSearchContext context = new ChainSearchContext(
                null, origin, null, 0, null, null, subMode, maxRadius, maxTargets,
                current, next, visited, null, blocks);
        context.setCandidateFilter(target -> true);
        return context;
    }

    private static List<ChainTarget> singleton(ChainTarget target) {
        List<ChainTarget> result = new ArrayList<ChainTarget>();
        result.add(target);
        return result;
    }

    private static final class TraversalOutcome {
        private final ChainSearchContext context;
        private final List<ChainTarget> accepted;
        private final List<ChainTarget> matcherCalls;
        private final int slices;

        private TraversalOutcome(
            ChainSearchContext context,
            List<ChainTarget> accepted,
            List<ChainTarget> matcherCalls,
            int slices) {
            this.context = context;
            this.accepted = accepted;
            this.matcherCalls = matcherCalls;
            this.slices = slices;
        }
    }

    /** 纯逻辑世界方块读取器，未显式设置的坐标返回默认方块。 */
    private static final class MutableBlocks implements PlanningCandidateWorkBudget.CandidateBlockReader {
        private final boolean defaultAir;
        private final Map<ChainTarget, Boolean> airByTarget = new HashMap<ChainTarget, Boolean>();
        private final Map<ChainTarget, Integer> probes = new HashMap<ChainTarget, Integer>();
        private int totalProbes;

        private MutableBlocks(boolean defaultAir) {
            this.defaultAir = defaultAir;
        }

        void put(ChainTarget target, boolean air) {
            airByTarget.put(target, air);
        }

        int getProbeCount(ChainTarget target) {
            Integer count = probes.get(target);
            return count == null ? 0 : count;
        }

        int getTotalProbes() {
            return totalProbes;
        }

        @Override
        public boolean isOriginalAir(ChainTarget target) {
            totalProbes++;
            probes.put(target, getProbeCount(target) + 1);
            Boolean air = airByTarget.get(target);
            return air == null ? defaultAir : air;
        }
    }

    /** 每次 step 独立提供固定预算，模拟跨 tick/slice 恢复。 */
    private static final class SliceControl implements ParallelTickControl {
        private int remaining;

        private SliceControl(int workBudget) {
            remaining = Math.max(0, workBudget);
        }

        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return true; }
        @Override public boolean isCancelRequested() { return false; }
        @Override public boolean shouldYield() { return remaining <= 0; }

        @Override
        public boolean tryConsumeWork(int units) {
            if (units <= 0) return true;
            if (units > remaining) return false;
            remaining -= units;
            return true;
        }

        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return ""; }
    }

    /** shouldYield 仍为 false，但正预算提交失败，用于命中第 1024 个配额边界。 */
    private static final class QuotaRejectingControl implements ParallelTickControl {
        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return true; }
        @Override public boolean isCancelRequested() { return false; }
        @Override public boolean shouldYield() { return false; }
        @Override public boolean tryConsumeWork(int units) { return units <= 0; }
        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return ""; }
    }
}
