package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.parallel.ParallelTickTask;

/** B4.1 第二步生产接线：代内 extend 等价性、锚点稳定、换代重置与单槽覆盖语义。 */
public class GenerationSessionWiringTest {

    private static final float THICKNESS = 0.045F;
    private static final VisualParameters VISUALS = new VisualParameters(
        0.0D, 0.0D, 0.0D, 2.0D, 6.0D, 0.78F, 0.15F, THICKNESS);

    /** 生产快照序（最新→最早）。 */
    private static List<ChainTarget> newestFirst(List<ChainTarget> chronological) {
        List<ChainTarget> result = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(result);
        return result;
    }

    private static void assertMeshEqual(ChainPreviewMesh expected, ChainPreviewMesh actual) {
        Assert.assertArrayEquals(expected.getVertices(), actual.getVertices(), 0.0F);
        Assert.assertArrayEquals(expected.getColors(), actual.getColors(), 0.0F);
        Assert.assertArrayEquals(expected.getIndices(), actual.getIndices());
        Assert.assertArrayEquals(expected.getAux(), actual.getAux());
        Assert.assertEquals(expected.getOriginX(), actual.getOriginX());
        Assert.assertEquals(expected.getOriginY(), actual.getOriginY());
        Assert.assertEquals(expected.getOriginZ(), actual.getOriginZ());
        Assert.assertEquals(expected.getBlockCount(), actual.getBlockCount());
        Assert.assertEquals(expected.isTruncated(), actual.isTruncated());
    }

    @Test
    public void incrementalExtendsMatchSingleShotSessionByteForByte() {
        List<ChainTarget> chronological = Arrays.asList(
            new ChainTarget(0, 0, 0), new ChainTarget(3, 0, 0), new ChainTarget(6, 0, 0));

        ChainPreviewMesh singleShot = new ChainPreviewMeshBuilder().beginGeneration()
            .extend(newestFirst(chronological), null, VISUALS, THICKNESS);

        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh step1 = session.extend(newestFirst(chronological.subList(0, 1)), null, VISUALS, THICKNESS);
        Assert.assertEquals(1, step1.getBlockCount());
        ChainPreviewMesh step2 = session.extend(newestFirst(chronological.subList(0, 2)), null, VISUALS, THICKNESS);
        Assert.assertEquals(2, step2.getBlockCount());
        ChainPreviewMesh step3 = session.extend(newestFirst(chronological), null, VISUALS, THICKNESS);
        Assert.assertEquals(3, step3.getBlockCount());

        assertMeshEqual(singleShot, step3);
        Assert.assertEquals("锚点必须是代内最早目标", 0, step3.getOriginX());
        Assert.assertEquals("锚点在代内稳定", 0, session.getAnchorX());
        Assert.assertEquals("代内增量不应触发重锚", 0, session.getReanchorCount());
    }

    @Test
    public void renderCacheKeepsAnchorInGenerationAndResetsOnNewGeneration() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = new ChainPreviewRenderCache(
            state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();

        int firstGeneration = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.PRIMARY_LOCAL);
        state.addPreviewTarget(firstGeneration, new ChainTarget(0, 0, 0));
        cache.pollPublication();
        runCompleted(scheduler.tasks.get(0));
        ChainPreviewRenderCache.MeshPublication first = cache.pollPublication();
        Assert.assertNotNull(first);
        Assert.assertEquals(1, first.getMesh().getBlockCount());
        Assert.assertEquals("锚点=代内最早目标", 0, first.getMesh().getOriginX());
        GenerationSession firstSession = generationSessionOf(cache);
        Assert.assertNotNull("首发必须建立代内增量会话", firstSession);

        state.addPreviewTarget(firstGeneration, new ChainTarget(3, 0, 0));
        runCompleted(scheduler.tasks.get(1));
        ChainPreviewRenderCache.MeshPublication second = cache.pollPublication();
        Assert.assertNotNull(second);
        Assert.assertEquals(2, second.getMesh().getBlockCount());
        Assert.assertEquals("代内追加不得换锚点", 0, second.getMesh().getOriginX());
        Assert.assertSame("同代推进必须复用同一增量装配会话", firstSession, generationSessionOf(cache));
        Assert.assertEquals("同代增量不得触发重锚", 0, firstSession.getReanchorCount());

        int secondGeneration = state.begin(new ChainTarget(40, 0, 0), ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        Assert.assertNotEquals(firstGeneration, secondGeneration);
        state.addPreviewTarget(secondGeneration, new ChainTarget(40, 0, 0));
        runCompleted(scheduler.tasks.get(2));
        ChainPreviewRenderCache.MeshPublication third = cache.pollPublication();
        Assert.assertNotNull(third);
        Assert.assertEquals("换代后发布必须属于新代", secondGeneration, third.getGeneration());
        Assert.assertEquals("换代不得残留上一代几何", 1, third.getMesh().getBlockCount());
        Assert.assertEquals("新代锚点必须重置为新代最早目标", 40, third.getMesh().getOriginX());
    }

    @Test
    public void unconsumedPublicationIsReplacedByNewerIncrementalBuild() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = new ChainPreviewRenderCache(
            state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();

        int generation = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.PRIMARY_LOCAL);
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        cache.pollPublication();
        runCompleted(scheduler.tasks.get(0));
        // 产出但故意不消费：单槽语义只保证"最新一份可被取走"，装配不得被阻止。
        state.addPreviewTarget(generation, new ChainTarget(3, 0, 0));
        Assert.assertFalse("未消费不得阻止新的装配", scheduler.tasks.isEmpty());
        runScheduled(scheduler);

        ChainPreviewMesh pending = peekPendingMesh(cache);
        Assert.assertNotNull("单槽必须持有最新一份发布", pending);
        Assert.assertEquals("单槽必须被更新的一代覆盖（而非阻塞在原地）", 2, pending.getBlockCount());
        Assert.assertEquals(
            "同代推进必须走增量路径：锚点不得漂移",
            0,
            pending.getOriginX());
        ChainPreviewRenderCache.MeshPublication advanced = cache.pollPublication();
        Assert.assertNotNull(advanced);
        Assert.assertEquals(2, advanced.getMesh().getBlockCount());
        Assert.assertEquals("最新一份取走后单槽必须为空（不得堆积）", null, peekPendingMesh(cache));
        Assert.assertNull("不得堆积额外的历史发布", cache.pollPublication());
    }

    /**
     * 同一次分片内状态连续推进（远端目标同帧到达）：未消费发布不得阻断重建，
     * 且每次重建必须仍走同一代增量会话（锚点不漂移、不重锚），单槽始终只留最新一份。
     */
    @Test
    public void sameSliceChurnRebuildsIncrementallyWithoutAccumulation() throws Exception {
        final ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        final ChainPreviewRenderCache cache = new ChainPreviewRenderCache(
            state, new ChainPreviewMeshBuilder(), scheduler);
        cache.observeState();

        final int generation = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.PRIMARY_LOCAL);
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        cache.pollPublication();
        runCompleted(scheduler.tasks.get(0));
        GenerationSession session = generationSessionOf(cache);
        Assert.assertNotNull("首发必须建立代内增量会话", session);
        // 首发故意不消费：未消费发布不得阻断同帧内的后续推进。
        Assert.assertEquals(1, peekPendingMesh(cache).getBlockCount());

        state.addPreviewTarget(generation, new ChainTarget(3, 0, 0));
        final int[] churn = {0};
        HookControl control = new HookControl(new Runnable() {
            @Override
            public void run() {
                if (churn[0] < 4) {
                    churn[0]++;
                    state.addPreviewTarget(generation, new ChainTarget(3 + churn[0] * 3, 0, 0));
                }
            }
        });
        ParallelTickTask worker = scheduler.tasks.get(scheduler.tasks.size() - 1);
        Assert.assertEquals(ParallelTaskResult.COMPLETED, worker.run(control));

        Assert.assertTrue("同一次分片内必须发生多次重建（实际 " + churn[0] + " 次推进）", churn[0] >= 4);
        Assert.assertSame("同帧重建必须复用同一增量会话", session, generationSessionOf(cache));
        Assert.assertEquals("同帧增量不得重锚", 0, session.getReanchorCount());
        ChainPreviewMesh pending = peekPendingMesh(cache);
        Assert.assertNotNull("单槽必须持有最新一份发布", pending);
        Assert.assertEquals(
            "单槽只保留最新一份（覆盖而非堆积）",
            2 + churn[0],
            pending.getBlockCount());
        Assert.assertEquals("锚点必须稳定在代内最早目标", 0, pending.getOriginX());
        Assert.assertNotNull(cache.pollPublication());
        Assert.assertNull("取走最新一份后单槽必须为空", peekPendingMesh(cache));
    }

    private static void runScheduled(RecordingScheduler scheduler) throws Exception {
        List<ParallelTickTask> pending = new ArrayList<ParallelTickTask>(scheduler.tasks);
        scheduler.tasks.clear();
        for (ParallelTickTask task : pending) {
            ParallelTaskResult result = task.run(new NeverYieldControl());
            if (result == ParallelTaskResult.YIELDED) {
                scheduler.tasks.add(task);
            }
        }
    }

    private static void runCompleted(ParallelTickTask task) throws Exception {
        Assert.assertEquals(ParallelTaskResult.COMPLETED, task.run(new NeverYieldControl()));
    }

    /** 只读查看当前代的增量装配会话（不消费、不推进会话）。 */
    private static GenerationSession generationSessionOf(ChainPreviewRenderCache cache) throws Exception {
        java.lang.reflect.Field field = ChainPreviewRenderCache.class.getDeclaredField("generationSession");
        field.setAccessible(true);
        return (GenerationSession) field.get(cache);
    }

    /** 只读查看待消费发布的网格（不消费单槽）。 */
    private static ChainPreviewMesh peekPendingMesh(ChainPreviewRenderCache cache) throws Exception {
        java.lang.reflect.Field field = ChainPreviewRenderCache.class.getDeclaredField("pendingPublication");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<?> reference =
            (java.util.concurrent.atomic.AtomicReference<?>) field.get(cache);
        Object publication = reference.get();
        if (publication == null) {
            return null;
        }
        java.lang.reflect.Method mesh = publication.getClass().getDeclaredMethod("getMesh");
        mesh.setAccessible(true);
        return (ChainPreviewMesh) mesh.invoke(publication);
    }

    /** 每次被询问 shouldYield 时触发一次钩子（不 yield）：模拟同帧内连续到达的状态变更。 */
    private static final class HookControl implements ParallelTickControl {

        private final Runnable hook;

        private HookControl(Runnable hook) {
            this.hook = hook;
        }

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
            hook.run();
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

    private static final class RecordingScheduler implements ChainPreviewRenderCache.TaskScheduler {

        private final List<ParallelTickTask> tasks = new ArrayList<ParallelTickTask>();

        @Override
        public ParallelTickSubscription schedule(ParallelTickTask task) {
            tasks.add(task);
            return new ParallelTickSubscription() {
                @Override
                public void unregister() {
                }
            };
        }
    }

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
