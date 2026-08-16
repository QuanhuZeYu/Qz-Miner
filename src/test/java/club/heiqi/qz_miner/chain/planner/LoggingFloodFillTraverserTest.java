package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;

/** {@link LoggingFloodFillTraverser} 的候选连通与预算恢复纯逻辑测试。 */
public class LoggingFloodFillTraverserTest {

    private static final ChainTarget ORIGIN = new ChainTarget(-1, 0, 0);
    private static final ChainTarget A = new ChainTarget(0, 0, 0);
    private static final ChainTarget B = new ChainTarget(1, 0, 0);
    private static final ChainTarget C = new ChainTarget(2, 0, 0);

    /** matcher 能力拒绝的候选节点不入队、不计 confirmed，也不得桥接后续原木。 */
    @Test
    public void matcherRejectedCandidateBlocksTraversal() {
        Set<ChainTarget> candidates = setOf(A, B, C);
        List<ChainTarget> matcherCalls = new ArrayList<ChainTarget>();
        TraversalOutcome outcome = traverse(4096, candidates, target -> {
            matcherCalls.add(target);
            return !B.equals(target);
        });

        Assert.assertEquals(Arrays.asList(A, B), matcherCalls);
        Assert.assertEquals("B 不得入执行队列，C 也不得经 B 到达", Arrays.asList(A), outcome.accepted);
        Assert.assertEquals("matcher=false 的 B 不得增加 confirmed", 1, outcome.context.getConfirmedCount());
        Assert.assertFalse("matcher=false 的 B 不得生成 C", outcome.context.getVisited().contains(C));
    }

    /** candidate filter 拒绝仍是拓扑硬边界，不能借被拒节点绕到后续目标。 */
    @Test
    public void candidateRejectedNodeStillBlocksTraversal() {
        Set<ChainTarget> candidates = setOf(A, C);
        List<ChainTarget> matcherCalls = new ArrayList<ChainTarget>();
        TraversalOutcome outcome = traverse(4096, candidates, target -> {
            matcherCalls.add(target);
            return true;
        });

        Assert.assertEquals(Arrays.asList(A), matcherCalls);
        Assert.assertEquals(Arrays.asList(A), outcome.accepted);
        Assert.assertEquals(1, outcome.context.getConfirmedCount());
        Assert.assertTrue("B 会被检查并标记 visited", outcome.context.getVisited().contains(B));
        Assert.assertFalse("candidate=false 的 B 不得生成 C", outcome.context.getVisited().contains(C));
    }

    /** candidate 与 matcher 均接受时才可入队并继续扩展。 */
    @Test
    public void matcherAcceptedCandidateExpandsTraversal() {
        Set<ChainTarget> candidates = setOf(A, B, C);
        List<ChainTarget> matcherCalls = new ArrayList<ChainTarget>();
        TraversalOutcome outcome = traverse(4096, candidates, target -> {
            matcherCalls.add(target);
            return true;
        });

        Assert.assertEquals(Arrays.asList(A, B, C), matcherCalls);
        Assert.assertEquals(Arrays.asList(A, B, C), outcome.accepted);
        Assert.assertEquals(3, outcome.context.getConfirmedCount());
    }

    /** 很短 deadline checkpoint 窗口应跨分片恢复，并与充足窗口得到完全相同的结果。 */
    @Test
    public void lowBudgetSlicesMatchSingleSliceTraversal() {
        Set<ChainTarget> candidates = setOf(A, B, C);

        TraversalOutcome fullBudget = traverse(4096, candidates, target -> !B.equals(target));
        TraversalOutcome oneUnitSlices = traverse(8, candidates, target -> !B.equals(target));

        Assert.assertTrue("低预算必须实际跨越多个分片", oneUnitSlices.slices > 1);
        Assert.assertEquals(fullBudget.accepted, oneUnitSlices.accepted);
        Assert.assertEquals(fullBudget.context.getConfirmedCount(), oneUnitSlices.context.getConfirmedCount());
        Assert.assertEquals(fullBudget.context.getVisited(), oneUnitSlices.context.getVisited());
    }

    @Test
    public void visitedOnlySlicesAdvanceDurableProgressRevision() {
        ConcurrentLinkedQueue<ChainTarget> current = new ConcurrentLinkedQueue<ChainTarget>();
        ConcurrentLinkedQueue<ChainTarget> next = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = new HashSet<ChainTarget>();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    visited.add(new ChainTarget(ORIGIN.getX() + x, ORIGIN.getY() + y, ORIGIN.getZ() + z));
                }
            }
        }
        ChainSearchContext context = new ChainSearchContext(null, ORIGIN, null, 0, null,
                ChainSubMode.CHAIN_LOGGING, 16, 16, current, next, visited);
        context.setCandidateFilter(target -> true);
        LoggingFloodFillTraverser traverser = new LoggingFloodFillTraverser(4);
        traverser.seed(context);

        int yieldedSlices = 0;
        for (int slice = 0; slice < 1024; slice++) {
            long before = context.getProgressRevision();
            TraversalStepResult result = traverser.step(
                    context, new SliceControl(8), target -> true, target -> Assert.fail("visited target submitted"));
            if (result == TraversalStepResult.COMPLETED) {
                Assert.assertTrue("测试必须实际覆盖多个仅 visited-skip 的 deadline 分片", yieldedSlices > 1);
                return;
            }
            Assert.assertEquals(TraversalStepResult.YIELDED, result);
            Assert.assertTrue("已持久化的 visited-skip 游标必须刷新 durable progress",
                    context.getProgressRevision() > before);
            yieldedSlices++;
        }
        Assert.fail("visited-only 遍历未在安全分片上限内完成");
    }

    private static TraversalOutcome traverse(int checkpointBudget, Set<ChainTarget> candidates,
            ChainTargetMatcher matcher) {
        ConcurrentLinkedQueue<ChainTarget> current = new ConcurrentLinkedQueue<ChainTarget>();
        ConcurrentLinkedQueue<ChainTarget> next = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = new HashSet<ChainTarget>();
        ChainSearchContext context = new ChainSearchContext(null, ORIGIN, null, 0, null,
                ChainSubMode.CHAIN_LOGGING, 16, 16, current, next, visited);
        context.setCandidateFilter(candidates::contains);
        LoggingFloodFillTraverser traverser = new LoggingFloodFillTraverser(1);
        List<ChainTarget> accepted = new ArrayList<ChainTarget>();
        traverser.seed(context);

        for (int slice = 1; slice <= 4096; slice++) {
            TraversalStepResult result = traverser.step(
                    context, new SliceControl(checkpointBudget), matcher, accepted::add);
            if (result == TraversalStepResult.COMPLETED) {
                return new TraversalOutcome(context, accepted, slice);
            }
            Assert.assertEquals("未完成分片只能在预算边界让出", TraversalStepResult.YIELDED, result);
        }
        Assert.fail("遍历未在安全分片上限内完成");
        return null;
    }

    private static Set<ChainTarget> setOf(ChainTarget... targets) {
        return new HashSet<ChainTarget>(Arrays.asList(targets));
    }

    private static final class TraversalOutcome {
        private final ChainSearchContext context;
        private final List<ChainTarget> accepted;
        private final int slices;

        private TraversalOutcome(ChainSearchContext context, List<ChainTarget> accepted, int slices) {
            this.context = context;
            this.accepted = accepted;
            this.slices = slices;
        }
    }

    /** 每次 step 独立提供固定 checkpoint 窗口，模拟 deadline 跨 tick 恢复。 */
    private static final class SliceControl implements ParallelTickControl {
        private int remaining;

        private SliceControl(int checkpointBudget) {
            this.remaining = Math.max(0, checkpointBudget);
        }

        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return true; }
        @Override public boolean isCancelRequested() { return false; }
        @Override public boolean shouldYield() { return remaining-- <= 0; }

        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return ""; }
    }
}
