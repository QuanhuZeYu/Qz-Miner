package club.heiqi.qz_miner.chain.planner;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.selection.CuboidBounds;
import club.heiqi.qz_miner.parallel.ParallelTickContext;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import net.minecraftforge.common.util.ForgeDirection;

/** 七种 traverser 共用 soft deadline 候选事务的合同测试。 */
public class BudgetedChainTraverserDeadlineContractTest {

    private static final ChainTarget ORIGIN = new ChainTarget(0, 0, 0);
    private static final ChainTarget X1 = new ChainTarget(1, 0, 0);
    private static final ChainTarget X2 = new ChainTarget(2, 0, 0);

    @Test
    public void shortDeadlineWindowsMatchFullWindowForAllTraversers() {
        assertEquivalent("FloodFill", floodOutcome(1000000), floodOutcome(8), X1);
        assertEquivalent("LoggingFloodFill", loggingOutcome(1000000), loggingOutcome(8), X1);
        assertEquivalent("GregTechCable", gregTechOutcome(1000000), gregTechOutcome(8), ORIGIN);
        assertEquivalent("BoxScan", boxOutcome(1000000), boxOutcome(8), X1);
        assertEquivalent("TunnelBoxScan", tunnelOutcome(1000000), tunnelOutcome(8), X1);
        assertEquivalent("SectionClear", sectionOutcome(1000000), sectionOutcome(8), X1);
        assertEquivalent("CuboidScan", cuboidOutcome(1000000), cuboidOutcome(2), X1);
    }

    @Test
    public void floodAndGregTechSeedDoNotProbeCandidates() {
        assertSeedDoesNotProbe(new FloodFillTraverser(), ChainSubMode.CHAIN_BASE);
        assertSeedDoesNotProbe(new GregTechCableTraverser(null), ChainSubMode.SPECIAL_GT_CABLE_REPLACE);
    }

    @Test
    public void deadlineExpiringDuringReadCommitsQueueOnceAndResumesNextPhase() {
        MutableBlocks blocks = new MutableBlocks(false);
        ChainSearchContext context = context(ChainSubMode.AREA_HARVESTABLE_ALL, ORIGIN, 0, 1, blocks);
        BoxScanTraverser traverser = new BoxScanTraverser();
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        MutableDeadlineControl firstWindow = new MutableDeadlineControl();
        traverser.seed(context);
        context.getCurrentFrontier().add(X1);
        blocks.afterNextProbe(firstWindow::expire);

        Assert.assertEquals(TraversalStepResult.YIELDED,
                traverser.step(context, firstWindow, target -> true, accepted::add));
        Assert.assertTrue("已开始的读取应提交队首并在 matcher 前让出", context.getCurrentFrontier().isEmpty());
        Assert.assertEquals(0, context.getConfirmedCount());
        Assert.assertEquals("已提交世界读取必须推进 durable progress revision",
                1L, context.getProgressRevision());
        Assert.assertTrue(accepted.isEmpty());

        Assert.assertEquals(TraversalStepResult.COMPLETED,
                runToCompletion(traverser, context, 64, target -> true, accepted));
        Assert.assertEquals(singleton(X1), accepted);
        Assert.assertEquals("恢复 matcher/consumer 阶段不得重新读取坐标", 1, blocks.getProbeCount(X1));
    }

    @Test
    public void queuedTargetTurningIntoAirSkipsMatcherAndConsumer() {
        MutableBlocks blocks = new MutableBlocks(true);
        ChainSearchContext context = context(ChainSubMode.AREA_HARVESTABLE_ALL, ORIGIN, 0, 1, blocks);
        BoxScanTraverser traverser = new BoxScanTraverser();
        List<ChainTarget> matcherCalls = new ArrayList<ChainTarget>();
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        traverser.seed(context);
        context.getCurrentFrontier().add(X1);

        Assert.assertEquals(TraversalStepResult.COMPLETED,
                runToCompletion(traverser, context, 64, target -> {
                    matcherCalls.add(target);
                    return true;
                }, accepted));

        Assert.assertTrue(context.getCurrentFrontier().isEmpty());
        Assert.assertTrue(matcherCalls.isEmpty());
        Assert.assertTrue(accepted.isEmpty());
        Assert.assertEquals("手工入队目标只在 frontier 队首重验时读取一次", 1, blocks.getProbeCount(X1));
    }

    @Test
    public void airBreaksChainExpansion() {
        assertAirBreaksChain(new FloodFillTraverser(), ChainSubMode.CHAIN_BASE, false);
        assertAirBreaksChain(new LoggingFloodFillTraverser(1), ChainSubMode.CHAIN_LOGGING, false);
        assertAirBreaksChain(new GregTechCableTraverser(null),
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE, true);
    }

    @Test
    public void gregTechConnectedAirDoesNotBridge() throws Exception {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(ORIGIN, false);
        blocks.put(X2, false);
        ChainSearchContext context = context(
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE, ORIGIN, 4, 8, blocks);
        GregTechCableTraverser traverser = new GregTechCableTraverser(null);
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        MutableDeadlineControl firstWindow = new MutableDeadlineControl();
        traverser.seed(context);

        Assert.assertEquals(TraversalStepResult.YIELDED,
                traverser.step(context, firstWindow, target -> true, target -> {
                    accepted.add(target);
                    firstWindow.expire();
                }));
        Assert.assertEquals(singleton(ORIGIN), accepted);
        installConnectedDirections(traverser, ForgeDirection.EAST);

        Assert.assertEquals(TraversalStepResult.COMPLETED,
                runToCompletion(traverser, context, 64, target -> true, accepted));
        Assert.assertEquals(singleton(ORIGIN), accepted);
        Assert.assertTrue("connected 空气应被观察但不得入队", context.getVisited().contains(X1));
        Assert.assertEquals("connected 空气不得桥接后方实体", 0, blocks.getProbeCount(X2));
    }

    @Test
    public void gregTechDetectsFirstMatchingTargetBeyondAtomicLimit() throws Exception {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(ORIGIN, false);
        blocks.put(X1, false);
        ChainSearchContext context = context(
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE, ORIGIN, 4, 1, blocks);
        GregTechCableTraverser traverser = new GregTechCableTraverser(null);
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        MutableDeadlineControl firstWindow = new MutableDeadlineControl();
        traverser.seed(context);

        Assert.assertEquals(TraversalStepResult.YIELDED,
                traverser.step(context, firstWindow, target -> true, target -> {
                    accepted.add(target);
                    firstWindow.expire();
                }));
        installConnectedDirections(traverser, ForgeDirection.EAST);

        Assert.assertEquals(TraversalStepResult.COMPLETED,
                runToCompletion(traverser, context, 64, target -> true, accepted));
        Assert.assertEquals("超限探测不得向执行队列提交第 N+1 个目标", singleton(ORIGIN), accepted);
        Assert.assertEquals(1, context.getConfirmedCount());
        Assert.assertTrue("GT 必须显式记录链路前缀截断", context.isTargetLimitExceeded());
        Assert.assertEquals("第 N+1 个目标须经过邻居发现与 frontier 队首重验", 2, blocks.getProbeCount(X1));
    }

    @Test
    public void airProbeExpiryDefersCandidateFilterForAllTraversers() {
        assertAirProbeExpiryDefersFilter(new FloodFillTraverser(), ChainSubMode.CHAIN_BASE, 4);
        assertAirProbeExpiryDefersFilter(
                new LoggingFloodFillTraverser(1), ChainSubMode.CHAIN_LOGGING, 4);
        assertAirProbeExpiryDefersFilter(new GregTechCableTraverser(null),
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE, 4);
        assertAirProbeExpiryDefersFilter(new BoxScanTraverser(),
                ChainSubMode.AREA_HARVESTABLE_ALL, 1);
        assertAirProbeExpiryDefersFilter(new TunnelBoxScanTraverser(5),
                ChainSubMode.AREA_TUNNEL, 2);
        assertAirProbeExpiryDefersFilter(new SectionClearTraverser(),
                ChainSubMode.AREA_SECTION_CLEAR, 16);
        assertAirProbeExpiryDefersFilter(
                new CuboidScanTraverser(CuboidBounds.between(0, 1, 0, 0, 1, 0, 0)),
                ChainSubMode.AREA_CUBOID_CLEAR, 1);
    }

    @Test
    public void cancellationDuringCandidateFilterCommitsNoTraversalState() {
        assertFilterCancellationCommitsNoState(new FloodFillTraverser(), ChainSubMode.CHAIN_BASE, 4);
        assertFilterCancellationCommitsNoState(
                new LoggingFloodFillTraverser(1), ChainSubMode.CHAIN_LOGGING, 4);
        assertFilterCancellationCommitsNoState(new GregTechCableTraverser(null),
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE, 4);
        assertFilterCancellationCommitsNoState(new BoxScanTraverser(),
                ChainSubMode.AREA_HARVESTABLE_ALL, 1);
        assertFilterCancellationCommitsNoState(new TunnelBoxScanTraverser(5),
                ChainSubMode.AREA_TUNNEL, 2);
        assertFilterCancellationCommitsNoState(new SectionClearTraverser(),
                ChainSubMode.AREA_SECTION_CLEAR, 16);
        assertFilterCancellationCommitsNoState(
                new CuboidScanTraverser(CuboidBounds.between(0, 1, 0, 0, 1, 0, 0)),
                ChainSubMode.AREA_CUBOID_CLEAR, 1);
    }

    @Test
    public void absoluteNanoDeadlineStopsBeforeCandidateFilter() {
        MutableBlocks blocks = new MutableBlocks(false);
        ChainSearchContext context = context(
                ChainSubMode.AREA_HARVESTABLE_ALL, ORIGIN, 1, 1, blocks);
        AtomicInteger filterCalls = new AtomicInteger();
        context.setCandidateFilter(target -> {
            filterCalls.incrementAndGet();
            return true;
        });
        BoxScanTraverser traverser = new BoxScanTraverser();
        traverser.seed(context);
        AbsoluteDeadlineControl control = new AbsoluteDeadlineControl(100000000L);
        blocks.afterNextProbe(control::awaitDeadline);

        Assert.assertEquals(TraversalStepResult.YIELDED,
                traverser.step(context, control, target -> true, target -> { }));

        Assert.assertEquals(ParallelTickStage.SERVER_PRE, control.getStage());
        Assert.assertEquals("绝对 deadline 必须在首次空气探测后耗尽", 1, blocks.getTotalProbes());
        Assert.assertEquals("deadline 已耗尽时不得启动 candidate filter", 0, filterCalls.get());
    }

    private static TraversalOutcome floodOutcome(int checkpointBudget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(new FloodFillTraverser(),
                context(ChainSubMode.CHAIN_BASE, ORIGIN, 4, 64, blocks), checkpointBudget);
    }

    private static TraversalOutcome loggingOutcome(int checkpointBudget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(new LoggingFloodFillTraverser(1),
                context(ChainSubMode.CHAIN_LOGGING, ORIGIN, 4, 64, blocks), checkpointBudget);
    }

    private static TraversalOutcome gregTechOutcome(int checkpointBudget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(ORIGIN, false);
        return traverse(new GregTechCableTraverser(null),
                context(ChainSubMode.SPECIAL_GT_CABLE_REPLACE, ORIGIN, 4, 1, blocks), checkpointBudget);
    }

    private static TraversalOutcome boxOutcome(int checkpointBudget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(new BoxScanTraverser(),
                context(ChainSubMode.AREA_HARVESTABLE_ALL, ORIGIN, 1, 64, blocks), checkpointBudget);
    }

    private static TraversalOutcome tunnelOutcome(int checkpointBudget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(new TunnelBoxScanTraverser(5),
                context(ChainSubMode.AREA_TUNNEL, ORIGIN, 2, 64, blocks), checkpointBudget);
    }

    private static TraversalOutcome sectionOutcome(int checkpointBudget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(new SectionClearTraverser(),
                context(ChainSubMode.AREA_SECTION_CLEAR, ORIGIN, 16, 5000, blocks), checkpointBudget);
    }

    private static TraversalOutcome cuboidOutcome(int checkpointBudget) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X1, false);
        return traverse(new CuboidScanTraverser(CuboidBounds.between(0, 1, 0, 0, 1, 0, 0)),
                context(ChainSubMode.AREA_CUBOID_CLEAR, ORIGIN, 1, 1, blocks), checkpointBudget);
    }

    private static void assertEquivalent(String label, TraversalOutcome full,
            TraversalOutcome sliced, ChainTarget expected) {
        Assert.assertEquals(label, singleton(expected), full.accepted);
        Assert.assertEquals(label, full.accepted, sliced.accepted);
        Assert.assertEquals(label, full.matcherCalls, sliced.matcherCalls);
        Assert.assertEquals(label, full.context.getConfirmedCount(), sliced.context.getConfirmedCount());
        Assert.assertEquals(label, full.context.getVisited(), sliced.context.getVisited());
        Assert.assertEquals(label, full.context.getScanDepth(), sliced.context.getScanDepth());
        Assert.assertTrue(label + " 必须实际跨越多个窗口", sliced.slices > 1);
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

    private static void assertAirBreaksChain(BudgetedChainTraverser traverser,
            ChainSubMode subMode, boolean originIsAirCandidate) {
        MutableBlocks blocks = new MutableBlocks(true);
        blocks.put(X2, false);
        ChainSearchContext context = context(subMode, ORIGIN, 4, 8, blocks);
        TraversalOutcome outcome = traverse(traverser, context, 1000000);

        Assert.assertTrue(outcome.accepted.isEmpty());
        Assert.assertTrue(outcome.matcherCalls.isEmpty());
        Assert.assertEquals("空气之后的目标不得被探测", 0, blocks.getProbeCount(X2));
        if (originIsAirCandidate) Assert.assertEquals(1, blocks.getProbeCount(ORIGIN));
    }

    private static void assertAirProbeExpiryDefersFilter(
            BudgetedChainTraverser traverser, ChainSubMode subMode, int maxRadius) {
        MutableBlocks blocks = new MutableBlocks(false);
        ChainSearchContext context = context(subMode, ORIGIN, maxRadius, 1, blocks);
        MutableDeadlineControl firstWindow = new MutableDeadlineControl();
        AtomicInteger filterCalls = new AtomicInteger();
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        context.setCandidateFilter(target -> {
            filterCalls.incrementAndGet();
            return true;
        });
        traverser.seed(context);
        blocks.afterNextProbe(firstWindow::expire);

        Assert.assertEquals(traverser.getClass().getSimpleName(), TraversalStepResult.YIELDED,
                traverser.step(context, firstWindow, target -> true, accepted::add));
        Assert.assertEquals(traverser.getClass().getSimpleName(), 1, blocks.getTotalProbes());
        Assert.assertEquals(traverser.getClass().getSimpleName(), 0, filterCalls.get());

        Assert.assertEquals(traverser.getClass().getSimpleName(), TraversalStepResult.COMPLETED,
                runToCompletion(traverser, context, 128, target -> true, accepted));
        Assert.assertEquals(traverser.getClass().getSimpleName(), 1, accepted.size());
        Assert.assertTrue(traverser.getClass().getSimpleName(), filterCalls.get() > 0);
    }

    private static void assertFilterCancellationCommitsNoState(
            BudgetedChainTraverser traverser, ChainSubMode subMode, int maxRadius) {
        MutableBlocks blocks = new MutableBlocks(false);
        ChainSearchContext context = context(subMode, ORIGIN, maxRadius, 1, blocks);
        MutableDeadlineControl control = new MutableDeadlineControl();
        AtomicInteger filterCalls = new AtomicInteger();
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        context.setCandidateFilter(target -> {
            filterCalls.incrementAndGet();
            control.cancel();
            return true;
        });
        traverser.seed(context);
        Set<ChainTarget> visitedBefore = new HashSet<ChainTarget>(context.getVisited());

        Assert.assertEquals(traverser.getClass().getSimpleName(), TraversalStepResult.TERMINATED,
                traverser.step(context, control, target -> true, accepted::add));
        Assert.assertEquals(traverser.getClass().getSimpleName(), 1, filterCalls.get());
        Assert.assertEquals(traverser.getClass().getSimpleName(), visitedBefore, context.getVisited());
        Assert.assertTrue(traverser.getClass().getSimpleName(), context.getCurrentFrontier().isEmpty());
        Assert.assertTrue(traverser.getClass().getSimpleName(), context.getNextFrontier().isEmpty());
        Assert.assertTrue(traverser.getClass().getSimpleName(), accepted.isEmpty());
    }

    private static TraversalOutcome traverse(BudgetedChainTraverser traverser,
            ChainSearchContext context, int checkpointBudget) {
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        List<ChainTarget> matcherCalls = new ArrayList<ChainTarget>();
        traverser.seed(context);

        for (int slice = 1; slice <= 50000; slice++) {
            TraversalStepResult result = traverser.step(context, new SliceControl(checkpointBudget), target -> {
                matcherCalls.add(target);
                return true;
            }, accepted::add);
            if (result == TraversalStepResult.COMPLETED) {
                return new TraversalOutcome(context, accepted, matcherCalls, slice);
            }
            Assert.assertEquals("未完成窗口只能在 deadline 边界让出", TraversalStepResult.YIELDED, result);
        }
        Assert.fail("遍历未在安全窗口上限内完成");
        return null;
    }

    private static TraversalStepResult runToCompletion(BudgetedChainTraverser traverser,
            ChainSearchContext context, int checkpointBudget, ChainTargetMatcher matcher,
            List<ChainTarget> accepted) {
        for (int slice = 0; slice < 50000; slice++) {
            TraversalStepResult result = traverser.step(
                    context, new SliceControl(checkpointBudget), matcher, accepted::add);
            if (result == TraversalStepResult.COMPLETED) return result;
            Assert.assertEquals(TraversalStepResult.YIELDED, result);
        }
        Assert.fail("续跑未在安全窗口上限内完成");
        return TraversalStepResult.TERMINATED;
    }

    private static ChainSearchContext context(ChainSubMode subMode, ChainTarget origin,
            int maxRadius, int maxTargets, MutableBlocks blocks) {
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

    private static void installConnectedDirections(GregTechCableTraverser traverser,
            ForgeDirection... directions) throws Exception {
        Field field = GregTechCableTraverser.class.getDeclaredField("neighborDirections");
        field.setAccessible(true);
        field.set(traverser, new ArrayList<ForgeDirection>(java.util.Arrays.asList(directions)));
    }

    private static final class TraversalOutcome {
        private final ChainSearchContext context;
        private final List<ChainTarget> accepted;
        private final List<ChainTarget> matcherCalls;
        private final int slices;

        private TraversalOutcome(ChainSearchContext context, List<ChainTarget> accepted,
                List<ChainTarget> matcherCalls, int slices) {
            this.context = context;
            this.accepted = accepted;
            this.matcherCalls = matcherCalls;
            this.slices = slices;
        }
    }

    private static final class MutableBlocks implements PlanningCandidateGate.CandidateBlockReader {
        private final boolean defaultAir;
        private final Map<ChainTarget, Boolean> airByTarget = new HashMap<ChainTarget, Boolean>();
        private final Map<ChainTarget, Integer> probes = new HashMap<ChainTarget, Integer>();
        private Runnable afterNextProbe;
        private int totalProbes;

        private MutableBlocks(boolean defaultAir) { this.defaultAir = defaultAir; }

        void put(ChainTarget target, boolean air) { airByTarget.put(target, air); }
        void afterNextProbe(Runnable callback) { afterNextProbe = callback; }
        int getProbeCount(ChainTarget target) {
            Integer count = probes.get(target);
            return count == null ? 0 : count.intValue();
        }
        int getTotalProbes() { return totalProbes; }

        @Override
        public boolean isOriginalAir(ChainTarget target) {
            totalProbes++;
            probes.put(target, Integer.valueOf(getProbeCount(target) + 1));
            Runnable callback = afterNextProbe;
            afterNextProbe = null;
            if (callback != null) callback.run();
            Boolean air = airByTarget.get(target);
            return air == null ? defaultAir : air.booleanValue();
        }
    }

    /** 每次 step 独立提供固定 checkpoint 窗口，模拟跨 tick deadline 恢复。 */
    private static final class SliceControl implements ParallelTickControl {
        private int remainingChecks;

        private SliceControl(int checkpointBudget) {
            remainingChecks = Math.max(0, checkpointBudget);
        }

        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return remainingChecks >= 0; }
        @Override public boolean isCancelRequested() { return false; }
        @Override public boolean shouldYield() { return remainingChecks-- <= 0; }
        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return ""; }
    }

    private static final class MutableDeadlineControl implements ParallelTickControl {
        private boolean expired;
        private boolean cancelled;

        void expire() { expired = true; }
        void cancel() { cancelled = true; }

        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return !expired; }
        @Override public boolean isCancelRequested() { return cancelled; }
        @Override public boolean shouldYield() { return cancelled || expired; }
        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return cancelled ? "test" : ""; }
    }

    /** 直接委托真实 {@link ParallelTickContext} 的绝对纳秒 deadline。 */
    private static final class AbsoluteDeadlineControl implements ParallelTickControl {
        private final ParallelTickContext context;

        private AbsoluteDeadlineControl(long windowNanos) {
            long start = System.nanoTime();
            context = new ParallelTickContext(
                    91L, start, start + windowNanos, ParallelTickStage.SERVER_PRE);
        }

        void awaitDeadline() {
            while (context.hasTimeLeft()) {
                LockSupport.parkNanos(100000L);
            }
        }

        @Override public long getTickId() { return context.getTickId(); }
        @Override public ParallelTickStage getStage() { return context.getStage(); }
        @Override public boolean isWindowOpen() { return true; }
        @Override public boolean isCancelRequested() { return false; }
        @Override public boolean shouldYield() { return !context.hasTimeLeft(); }
        @Override public long getElapsedNanoTime() { return context.getElapsedNanoTime(); }
        @Override public String getCancelReason() { return ""; }
    }
}
