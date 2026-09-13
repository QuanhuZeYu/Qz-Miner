package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.parallel.ParallelTickTask;

/**
 * T34 / B4.2 峰值接线 headless 断言：{@link ChainPreviewRenderCache#publishCapacityInto} 的发布路径
 * 必须让消费者计数器峰值可读（构建产物 → 计数器通道），且无会话 / null 计数器时零副作用。
 */
public class ChainPreviewRenderCacheCapacityHandoffTest {

    @Test
    public void publishedGenerationPeaksBecomeReadableOnConsumerCounters() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache =
            new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        cache.observeState();

        state.begin(new ChainTarget(0, 64, 0));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 64, 0));
        Assert.assertEquals(1, scheduler.tasks.size());
        runToCompletion(scheduler.tasks.get(0));

        ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
        Assert.assertNotNull(publication);

        cache.publishCapacityInto(counters);

        Assert.assertTrue("build 后计数器峰值必须可读", counters.getPeakVertexCount() > 0);
        Assert.assertTrue(
            "峰值不得低于已发布网格顶点数",
            counters.getPeakVertexCount() >= publication.getMesh().getVertexCount());
        Assert.assertTrue(counters.getPeakIndexCount() > 0);
        Assert.assertTrue(counters.describe().contains("preview.peakVertices="));
        Assert.assertTrue(counters.describe().contains("preview.peakGenerationCacheEntries="));
    }

    @Test
    public void handoffIsNullSafeAndNoOpWithoutSession() {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache =
            new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();

        cache.publishCapacityInto(null);
        cache.publishCapacityInto(counters);

        Assert.assertEquals("无会话不得写入峰值", 0L, counters.getPeakVertexCount());
        Assert.assertEquals(0L, counters.getPeakIndexCount());
        Assert.assertEquals(0L, counters.getPeakAuxBytes());
        Assert.assertEquals(0L, counters.getPeakGenerationCacheEntries());
        Assert.assertEquals("交接不得改动既有计数", 0L, counters.getRebuilds());
        Assert.assertEquals(0L, counters.getUploads());
    }

    private static void runToCompletion(ScheduledTask scheduled) throws Exception {
        Assert.assertEquals(
            ParallelTaskResult.COMPLETED,
            scheduled.task.run(new NeverYieldControl()));
    }

    private static final class RecordingScheduler implements ChainPreviewRenderCache.TaskScheduler {

        final List<ScheduledTask> tasks = new ArrayList<ScheduledTask>();

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

        final ParallelTickTask task;
        boolean cancelled;

        ScheduledTask(ParallelTickTask task) {
            this.task = task;
        }
    }

    /** 单次 run 即完成的控制面（与构建线程无关的纯 JVM 驱动）。 */
    private static final class NeverYieldControl implements ParallelTickControl {

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
}
