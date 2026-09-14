package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.parallel.ParallelTickTask;

/**
 * 真机阻断缺陷现场复现（**生产 JVM 全链路**）：ChainPreviewState（61 个相邻目标逐个加入）
 * → RenderSnapshot → ChainPreviewRenderCache → GenerationSession 增量 → MeshPublication。
 *
 * <p>与 GL 无关；若本类全绿，则「只渲染一根条柱」不在 JVM 侧装配/发布链路，而在 GL 上传 /
 * 着色器 / 绘制侧。</p>
 */
public class ChainPreviewProductionPipelineReproTest {

    private static final int TARGET_COUNT = 61;

    @Test
    public void sixtyOneTargetsReachPublicationThroughRealRenderCache() throws Exception {
        assertPipelinePublishesFullVein(() -> new NeverYieldControl());
    }

    /** 生产形态：任务在分片安全点反复让出、续跑（ParallelTick 逐 tick 调度，逐片新预算）。 */
    @Test
    public void sixtyOneTargetsReachPublicationUnderChunkedYield() throws Exception {
        assertPipelinePublishesFullVein(() -> new YieldAfterChecksControl(5));
    }

    private static void assertPipelinePublishesFullVein(
            Supplier<ParallelTickControl> controls) throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache =
            new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();

        int generation = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        for (int x = 1; x < TARGET_COUNT; x++) {
            state.addPreviewTarget(generation, new ChainTarget(x, 0, 0));
        }
        Assert.assertEquals("生产状态必须记录 61 个匹配目标", TARGET_COUNT, state.getMatchedCount());
        Assert.assertFalse(scheduler.tasks.isEmpty());

        ChainPreviewMesh mesh = drainAndPollLatest(cache, scheduler, controls);
        Assert.assertNotNull("生产链路必须发布网格", mesh);
        System.out.println("[t-prod-pipe] targets=" + TARGET_COUNT
            + " matched=" + state.getMatchedCount()
            + " blocks=" + mesh.getBlockCount()
            + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount()
            + " auxBytes=" + mesh.getAuxByteCount());
        Assert.assertEquals("发布网格必须包含全部 61 根条柱", TARGET_COUNT, mesh.getBlockCount());
        Assert.assertFalse(mesh.isEmpty());
    }

    /**
     * 逐步生产模拟：每加入 1 个目标就跑完当前任务并消费发布，要求最终发布 = 全部 61 根条柱。
     * 这条路径覆盖「状态 revision 推进 → 每次重建 → 单槽发布」的真实节奏。
     */
    @Test
    public void incrementalProductionRevisionsPublishGrowingMesh() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache =
            new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));

        ChainPreviewMesh mesh = null;
        for (int x = 1; x < TARGET_COUNT; x++) {
            state.addPreviewTarget(generation, new ChainTarget(x, 0, 0));
            mesh = drainAndPollLatest(cache, scheduler, () -> new NeverYieldControl());
            if (mesh != null && !mesh.isEmpty()) {
                Assert.assertEquals("第 " + (x + 1) + " 个目标后发布网格必须同步",
                    x + 1, mesh.getBlockCount());
            }
        }
        Assert.assertNotNull(mesh);
        System.out.println("[t-prod-pipe] case=incrementalRevisions blocks=" + mesh.getBlockCount()
            + " verts=" + mesh.getVertexCount() + " indices=" + mesh.getIndexCount());
        Assert.assertEquals(TARGET_COUNT, mesh.getBlockCount());
    }

    /**
     * 反方向锁定（上游坐标重复机制）：同一坐标重复投喂 61 次 ⇒ HUD/匹配计数是 61，
     * 但几何按坐标去重后合法产出**1 根**。这条与
     * {@link #sixtyOneTargetsReachPublicationThroughRealRenderCache()}（61 互异坐标 ⇒ 61 根）
     * 一起把「计数正确但几何只有 1 根」的两种可能一次锁死。
     */
    @Test
    public void repeatedSameCoordinateYieldsSingleBarWithMatchedCountSixtyOne() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache =
            new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();
        int generation = state.begin(new ChainTarget(7, 64, -3));
        for (int index = 0; index < TARGET_COUNT; index++) {
            state.addPreviewTarget(generation, new ChainTarget(7, 64, -3));
        }
        Assert.assertEquals("投喂计数含重复：matchedCount 必须是 61", TARGET_COUNT, state.getMatchedCount());

        ChainPreviewMesh mesh = drainAndPollLatest(cache, scheduler, () -> new NeverYieldControl());
        Assert.assertNotNull(mesh);
        System.out.println("[t-prod-pipe] case=repeatedSameCoordinate matched=" + state.getMatchedCount()
            + " blocks=" + mesh.getBlockCount() + " verts=" + mesh.getVertexCount()
            + " indices=" + mesh.getIndexCount());
        Assert.assertEquals("同一坐标重复投喂必须只产出 1 根", 1, mesh.getBlockCount());
        Assert.assertEquals("单根条柱顶点数（T51 方案 A：每面一顶点）", 168, mesh.getVertexCount());
    }

    /** 跑完所有排队任务（让出后重新调度，直到 COMPLETED），返回最新发布网格。 */
    private static ChainPreviewMesh drainAndPollLatest(
            ChainPreviewRenderCache cache, RecordingScheduler scheduler,
            Supplier<ParallelTickControl> controls) throws Exception {
        for (int pass = 0; pass < 100000; pass++) {
            ScheduledTask pending = null;
            for (ScheduledTask scheduled : scheduler.tasks) {
                if (!scheduled.completed) {
                    pending = scheduled;
                    break;
                }
            }
            if (pending == null) {
                break;
            }
            ParallelTaskResult result = pending.task.run(controls.get());
            if (result == ParallelTaskResult.COMPLETED || result == ParallelTaskResult.TERMINATED) {
                pending.completed = true;
            } else if (result != ParallelTaskResult.YIELDED) {
                Assert.fail("任务返回了未知结果: " + result);
            }
        }
        ChainPreviewMesh mesh = null;
        ChainPreviewRenderCache.MeshPublication publication;
        while ((publication = cache.pollPublication()) != null) {
            mesh = publication.getMesh();
        }
        return mesh;
    }

    private static final class RecordingScheduler implements ChainPreviewRenderCache.TaskScheduler {

        private final List<ScheduledTask> tasks = new ArrayList<ScheduledTask>();

        @Override
        public ParallelTickSubscription schedule(ParallelTickTask task) {
            final ScheduledTask scheduled = new ScheduledTask(task);
            tasks.add(scheduled);
            return new ParallelTickSubscription() {
                @Override
                public void unregister() {
                    scheduled.cancelled = true;
                }
            };
        }
    }

    private static final class ScheduledTask {

        private final ParallelTickTask task;
        private boolean completed;
        private boolean cancelled;

        private ScheduledTask(ParallelTickTask task) {
            this.task = task;
        }
    }

    private static class NeverYieldControl implements ParallelTickControl {

        @Override
        public long getTickId() {
            return 1L;
        }

        @Override
        public ParallelTickStage getStage() {
            return ParallelTickStage.CLIENT_POST;
        }

        @Override
        public boolean isWindowOpen() {
            return true;
        }

        @Override
        public boolean isCancelRequested() {
            return false;
        }

        @Override
        public boolean shouldYield() {
            return false;
        }

        @Override
        public long getElapsedNanoTime() {
            return 0L;
        }

        @Override
        public String getCancelReason() {
            return "";
        }
    }

    private static final class YieldAfterChecksControl extends NeverYieldControl {

        private final int allowedChecks;
        private int checks;

        private YieldAfterChecksControl(int allowedChecks) {
            this.allowedChecks = allowedChecks;
        }

        @Override
        public boolean shouldYield() {
            return ++checks > allowedChecks;
        }
    }
}
