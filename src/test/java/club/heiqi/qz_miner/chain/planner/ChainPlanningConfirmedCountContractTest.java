package club.heiqi.qz_miner.chain.planner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;

/** 服务端真实 queue 与 traverser confirmedCount 单一写点合同。 */
public class ChainPlanningConfirmedCountContractTest {

    private static final ChainTarget ORIGIN = new ChainTarget(0, 0, 0);
    private static final int MAX_TARGETS = 4;

    /** 六个生产 traverser 的每次真实 consumer 提交必须恰好对应一次 confirmed。 */
    @Test
    public void allTraversersKeepQueueConfirmedAndMaxTargetsExact() {
        assertExactAtMax("FloodFill", new FloodFillTraverser(), ChainSubMode.CHAIN_BASE, 2, MAX_TARGETS);
        assertExactAtMax("LoggingFloodFill", new LoggingFloodFillTraverser(1),
                ChainSubMode.CHAIN_LOGGING, 2, MAX_TARGETS);
        assertExactAtMax("BoxScan", new BoxScanTraverser(),
                ChainSubMode.AREA_HARVESTABLE_ALL, 1, MAX_TARGETS);
        assertExactAtMax("TunnelBoxScan", new TunnelBoxScanTraverser(5),
                ChainSubMode.AREA_TUNNEL, 2, MAX_TARGETS);
        assertExactAtMax("SectionClear", new SectionClearTraverser(),
                ChainSubMode.AREA_SECTION_CLEAR, 16, MAX_TARGETS);
        // GT origin 本身参与替换；max=1 可在不构造 live GT World 的纯 JVM fixture 中锁定同一计数合同。
        assertExactAtMax("GregTechCable", new GregTechCableTraverser(null),
                ChainSubMode.SPECIAL_GT_CABLE_REPLACE, 2, 1);
    }

    /** 取消路径不得向 queue 或 confirmed 人为补数。 */
    @Test
    public void cancellationDoesNotInventQueueOrConfirmedTargets() {
        ChainSearchContext context = context(ChainSubMode.AREA_HARVESTABLE_ALL, 1, MAX_TARGETS);
        BudgetedChainTraverser traverser = new BoxScanTraverser();
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        traverser.seed(context);

        TraversalStepResult result = traverser.step(context, new CancelledControl(), target -> true, queue::add);

        Assert.assertEquals(TraversalStepResult.TERMINATED, result);
        Assert.assertTrue(queue.isEmpty());
        Assert.assertEquals(0, context.getConfirmedCount());
    }

    /** consumer 抛错时 traverser 尚未完成提交，因此不得先增加 confirmed。 */
    @Test
    public void confirmedAdvancesOnlyAfterConsumerReturnsNormally() {
        ChainSearchContext context = context(ChainSubMode.AREA_HARVESTABLE_ALL, 1, MAX_TARGETS);
        BudgetedChainTraverser traverser = new BoxScanTraverser();
        traverser.seed(context);
        try {
            traverser.step(context, new SliceControl(100000), target -> true, target -> {
                throw new IllegalStateException("consumer rejected fixture");
            });
            Assert.fail("fixture consumer 应抛错");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("consumer 正常返回前不得计数", 0, context.getConfirmedCount());
        }
    }

    /** 生产 bridge consumer 只负责入队；预览 origin 的独立投影计数保持原语义。 */
    @Test
    public void bridgeDoesNotDoubleCountAndPreviewOriginRemainsSeparate() throws Exception {
        String bridge = read(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanningEventBridge.java");
        int workerStart = bridge.indexOf("private ParallelTaskResult runShadowSlice(");
        int workerEnd = bridge.indexOf("static boolean tryCompletePlanningOrCancel(", workerStart);
        String worker = bridge.substring(workerStart, workerEnd);
        Assert.assertTrue(worker.contains("shadowQueue.add(target)"));
        Assert.assertFalse("bridge consumer 不得成为第二 confirmed 写点",
                worker.contains("incrementConfirmedCount()"));

        String preview = read(
                "src/main/java/club/heiqi/qz_miner/chain/client/ChainPreviewController.java");
        int originAdd = preview.indexOf("previewState.addPreviewTarget(target)");
        int originCount = preview.indexOf("searchContext.incrementConfirmedCount()", originAdd);
        int traverserSeed = preview.indexOf("traverser.seed(searchContext)", originCount);
        Assert.assertTrue("预览 origin 仍须在 traverser seed 前单独计入投影",
                originAdd >= 0 && originCount > originAdd && traverserSeed > originCount);
    }

    private static void assertExactAtMax(String label, BudgetedChainTraverser traverser,
            ChainSubMode subMode, int maxRadius, int maxTargets) {
        ChainSearchContext context = context(subMode, maxRadius, maxTargets);
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        traverser.seed(context);

        for (int slice = 0; slice < 1000; slice++) {
            TraversalStepResult result = traverser.step(
                    context, new SliceControl(100000), target -> true, queue::add);
            if (result == TraversalStepResult.COMPLETED) {
                Assert.assertEquals(label, maxTargets, queue.size());
                Assert.assertEquals(label, queue.size(), context.getConfirmedCount());
                Assert.assertEquals(label, maxTargets, context.getConfirmedCount());
                return;
            }
            Assert.assertEquals(label, TraversalStepResult.YIELDED, result);
        }
        Assert.fail(label + " 未在安全分片上限内完成");
    }

    private static ChainSearchContext context(ChainSubMode subMode, int maxRadius, int maxTargets) {
        ConcurrentLinkedQueue<ChainTarget> current = new ConcurrentLinkedQueue<ChainTarget>();
        ConcurrentLinkedQueue<ChainTarget> next = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = new HashSet<ChainTarget>();
        ChainSearchContext context = new ChainSearchContext(
                null, ORIGIN, null, 0, TileIdentityToken.absent(), null,
                subMode, maxRadius, maxTargets, current, next, visited, null,
                target -> false);
        context.setCandidateFilter(target -> true);
        return context;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    /** 每个 step 独立提供固定工作预算。 */
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

    /** 起步即取消的协作式控制 fixture。 */
    private static final class CancelledControl implements ParallelTickControl {
        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return true; }
        @Override public boolean isCancelRequested() { return true; }
        @Override public boolean shouldYield() { return true; }
        @Override public boolean tryConsumeWork(int units) { return false; }
        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return "fixture-cancelled"; }
    }
}
