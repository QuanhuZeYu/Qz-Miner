package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.config.PreviewFadeMode;
import club.heiqi.qz_miner.config.PreviewRenderBackend;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.parallel.ParallelTickTask;

public class ChainPreviewRenderCacheTest {

    @Test
    public void observerCoalescesDirtyChangesAndWorkerResumesAfterDeadlineYield() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();
        Assert.assertEquals(0, scheduler.tasks.size());

        ChainTarget origin = new ChainTarget(0, 0, 0);
        state.begin(origin);
        Assert.assertNotNull(cache.pollPublication());
        state.addPreviewTarget(state.getGeneration(), origin);
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(1, 0, 0));
        Assert.assertEquals(1, scheduler.tasks.size());

        ParallelTickTask task = scheduler.tasks.get(0).task;
        Assert.assertEquals(ParallelTaskResult.YIELDED, task.run(new YieldAfterChecksControl(5)));
        Assert.assertNull(cache.pollPublication());
        Assert.assertEquals(ParallelTaskResult.COMPLETED, task.run(new NeverYieldControl()));

        ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
        Assert.assertNotNull(publication);
        Assert.assertEquals(state.getGeneration(), publication.getGeneration());
        Assert.assertEquals(2, publication.getMesh().getBlockCount());
        Assert.assertEquals(1, scheduler.tasks.size());
    }

    @Test
    public void attachingToAlreadyActiveStateBuildsExistingPersistentSnapshot() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        ChainTarget target = new ChainTarget(3, 4, 5);
        state.begin(target);
        state.addPreviewTarget(state.getGeneration(), target);
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);

        cache.observeState();
        Assert.assertEquals(1, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(0).task);
        ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
        Assert.assertNotNull(publication);
        Assert.assertEquals(1, publication.getMesh().getBlockCount());
    }

    @Test
    public void activePreviewRequestsDistanceRefreshAtMostOneSecondApart() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();
        state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 0, 0));
        runCompleted(scheduler.tasks.get(0).task);
        ChainPreviewRenderCache.MeshPublication topology = cache.pollPublication();
        Assert.assertNotNull(topology);

        cache.refreshForCamera(1.0D, 2.0D, 3.0D, 0L);
        Assert.assertEquals(2, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(1).task);
        ChainPreviewRenderCache.MeshPublication firstRefresh = cache.pollPublication();
        Assert.assertNotNull(firstRefresh);
        Assert.assertSame(topology.getMesh().vertexArray(), firstRefresh.getMesh().vertexArray());
        Assert.assertSame(topology.getMesh().indexArray(), firstRefresh.getMesh().indexArray());

        cache.refreshForCamera(
            2.0D,
            3.0D,
            4.0D,
            ChainPreviewRenderCache.EFFECT_REFRESH_INTERVAL_NANOS - 1L);
        Assert.assertEquals(2, scheduler.tasks.size());

        cache.refreshForCamera(
            2.0D,
            3.0D,
            4.0D,
            ChainPreviewRenderCache.EFFECT_REFRESH_INTERVAL_NANOS);
        Assert.assertEquals(3, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(2).task);
        ChainPreviewRenderCache.MeshPublication secondRefresh = cache.pollPublication();
        Assert.assertTrue(secondRefresh.getVisualRevision() > firstRefresh.getVisualRevision());
    }

    @Test
    public void inFlightVisualRefreshesCoalesceBehindCompletedTopology() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();
        state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 0, 0));
        cache.pollPublication();

        ParallelTickTask task = scheduler.tasks.get(0).task;
        Assert.assertEquals(ParallelTaskResult.YIELDED, task.run(new YieldAfterChecksControl(4)));
        cache.refreshForCamera(100.0D, 100.0D, 100.0D, 0L);
        cache.refreshForCamera(
            200.0D,
            200.0D,
            200.0D,
            ChainPreviewRenderCache.EFFECT_REFRESH_INTERVAL_NANOS);
        cache.refreshForCamera(
            300.0D,
            300.0D,
            300.0D,
            ChainPreviewRenderCache.EFFECT_REFRESH_INTERVAL_NANOS * 2L);

        runCompleted(task);
        ChainPreviewRenderCache.MeshPublication topology = cache.pollPublication();
        Assert.assertNotNull("completed topology must publish despite queued camera samples", topology);
        Assert.assertEquals(2, scheduler.tasks.size());

        runCompleted(scheduler.tasks.get(1).task);
        ChainPreviewRenderCache.MeshPublication recolor = cache.pollPublication();
        Assert.assertNotNull(recolor);
        Assert.assertTrue(recolor.getVisualRevision() > topology.getVisualRevision());
        Assert.assertSame(topology.getMesh().vertexArray(), recolor.getMesh().vertexArray());
        Assert.assertSame(topology.getMesh().indexArray(), recolor.getMesh().indexArray());
    }

    @Test
    public void lifecycleResetDetachesOldTaskAndOldGenerationCannotPublish() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();

        state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 0, 0));
        ParallelTickTask oldTask = scheduler.tasks.get(0).task;
        Assert.assertEquals(ParallelTaskResult.YIELDED, oldTask.run(new YieldAfterChecksControl(4)));
        cache.resetForLifecycle();
        Assert.assertEquals(1, scheduler.cancelCount);
        long resetEpoch = cache.pollPublication().getLifecycleEpoch();

        state.clear();
        ChainTarget replacement = new ChainTarget(10, 20, 30);
        state.begin(replacement);
        state.addPreviewTarget(state.getGeneration(), replacement);
        Assert.assertEquals(2, scheduler.tasks.size());
        Assert.assertEquals(ParallelTaskResult.TERMINATED, oldTask.run(new NeverYieldControl()));

        ParallelTickTask replacementTask = scheduler.tasks.get(1).task;
        runCompleted(replacementTask);
        ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
        Assert.assertNotNull(publication);
        Assert.assertTrue(publication.getLifecycleEpoch() >= resetEpoch);
        Assert.assertEquals(state.getGeneration(), publication.getGeneration());
        Assert.assertEquals(1, publication.getMesh().getBlockCount());
    }

    @Test
    public void resetDuringSchedulerRegistrationCancelsLostSubscriptionAndAllowsReplacement() {
        ChainPreviewState state = new ChainPreviewState();
        final RecordingScheduler scheduler = new RecordingScheduler();
        final ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();
        scheduler.onSchedule = new Runnable() {
            @Override
            public void run() {
                scheduler.onSchedule = null;
                cache.resetForLifecycle();
            }
        };

        state.begin(new ChainTarget(0, 0, 0));
        Assert.assertEquals(1, scheduler.tasks.size());
        Assert.assertEquals(1, scheduler.cancelCount);
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 0, 0));
        Assert.assertEquals("same generation must stay behind lifecycle fence", 1, scheduler.tasks.size());

        state.clear();
        state.begin(new ChainTarget(1, 1, 1));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(1, 1, 1));
        Assert.assertEquals(2, scheduler.tasks.size());
    }

    @Test
    public void unconsumedPublicationBlocksRebuildUntilPollConsumesIt() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();

        state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 0, 0));
        Assert.assertEquals(1, scheduler.tasks.size());

        runCompleted(scheduler.tasks.get(0).task);
        ChainPreviewRenderCache.MeshPublication first = cache.pollPublication();
        Assert.assertNotNull(first);
        Assert.assertEquals(1, first.getMesh().getBlockCount());

        // 消费后 redo 一次，并让产物停在单槽里不被取走
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(1, 0, 0));
        Assert.assertEquals("消费后才允许再次入队", 2, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(1).task);

        // 门控生效：单槽未消费时，状态继续前进也不得入队新的拓扑重建
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(2, 0, 0));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(3, 0, 0));
        Assert.assertEquals("未消费的 publication 必须阻止新入队", 2, scheduler.tasks.size());

        // 取走后才恰好唤醒一次，并构建出包含最新目标的新产物
        ChainPreviewRenderCache.MeshPublication blocked = cache.pollPublication();
        Assert.assertNotNull(blocked);
        Assert.assertEquals(2, blocked.getMesh().getBlockCount());
        Assert.assertEquals("取走后恰好一次重建", 3, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(2).task);
        ChainPreviewRenderCache.MeshPublication resumed = cache.pollPublication();
        Assert.assertNotNull(resumed);
        Assert.assertEquals(4, resumed.getMesh().getBlockCount());
        Assert.assertEquals(3, scheduler.tasks.size());
    }

    @Test
    public void consumedPublicationWithoutPendingWorkDoesNotEnqueueIdleWorker() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();

        state.begin(new ChainTarget(5, 5, 5));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(5, 5, 5));
        cache.pollPublication();
        runCompleted(scheduler.tasks.get(0).task);
        Assert.assertEquals(1, scheduler.tasks.size());

        Assert.assertNotNull(cache.pollPublication());
        Assert.assertEquals("无待构建工作时消费不得产生空转任务", 1, scheduler.tasks.size());
        Assert.assertNull(cache.pollPublication());
        Assert.assertEquals(1, scheduler.tasks.size());
    }

    @Test
    public void backendIdReferenceChangeRefreshesSnapshotOnNextFrameWithoutRebuildWhenUnchanged() {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        PreviewRenderBackend original = Config.clientPreviewRenderBackend;
        try {
            Config.clientPreviewRenderBackend = PreviewRenderBackend.LEGACY;
            ChainPreviewRenderCache cache = cache(state, scheduler);
            Assert.assertEquals("legacy", cache.getVisualSettings().getRenderBackendId());

            cache.refreshForCamera(0.0D, 0.0D, 0.0D, 1000000L);
            ChainPreviewVisualSettings before = cache.getVisualSettings();
            Assert.assertEquals("legacy", before.getRenderBackendId());

            cache.refreshForCamera(0.0D, 0.0D, 0.0D, 1000001L);
            Assert.assertSame(
                "后端引用未变且未到 1 Hz 采样点时不得重建快照",
                before,
                cache.getVisualSettings());

            Config.clientPreviewRenderBackend = PreviewRenderBackend.SHADER;
            cache.refreshForCamera(0.0D, 0.0D, 0.0D, 1000002L);
            ChainPreviewVisualSettings refreshed = cache.getVisualSettings();
            Assert.assertNotSame(before, refreshed);
            Assert.assertEquals("后端热切换必须下一帧生效", "shader", refreshed.getRenderBackendId());
        } finally {
            Config.clientPreviewRenderBackend = original;
        }
    }

    @Test
    public void signalFadeModeRefreshesOnThresholdOrFallbackWhileTimerStaysOnOneHertz() throws Exception {
        final long millis = 1000000L;
        PreviewFadeMode originalFadeMode = Config.clientPreviewFadeMode;
        try {
            Config.clientPreviewFadeMode = PreviewFadeMode.SIGNAL;
            ChainPreviewState state = new ChainPreviewState();
            RecordingScheduler scheduler = new RecordingScheduler();
            ChainPreviewRenderCache cache = cache(state, scheduler);
            cache.observeState();
            state.begin(new ChainTarget(0, 0, 0));
            state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 0, 0));
            cache.pollPublication();
            runCompleted(scheduler.tasks.get(0).task);
            Assert.assertNotNull(cache.pollPublication());
            Assert.assertEquals(1, scheduler.tasks.size());
            Assert.assertEquals("signal", cache.getVisualSettings().getFadeModeId());

            long t0 = 1000000L;
            cache.refreshForCamera(10.0D, 0.0D, 0.0D, t0);
            Assert.assertEquals("首次刷新必须建立位移基准", 2, scheduler.tasks.size());
            runCompleted(scheduler.tasks.get(1).task);
            Assert.assertNotNull(cache.pollPublication());
            Assert.assertEquals(2, scheduler.tasks.size());

            ChainPreviewVisualSettings settingsBefore = cache.getVisualSettings();
            cache.refreshForCamera(10.1D, 0.0D, 0.0D, t0 + 1L * millis);
            Assert.assertEquals("位移不足且未超兜底不得刷新", 2, scheduler.tasks.size());
            cache.refreshForCamera(10.1D, 0.0D, 0.0D, t0 + 249L * millis);
            Assert.assertEquals("兜底未到不得刷新", 2, scheduler.tasks.size());
            Assert.assertSame("刷新路径不得重建设置快照", settingsBefore, cache.getVisualSettings());

            cache.refreshForCamera(10.61D, 0.0D, 0.0D, t0 + 250L * millis);
            Assert.assertEquals("位移 0.61 格达标必须刷新", 3, scheduler.tasks.size());
            runCompleted(scheduler.tasks.get(2).task);
            Assert.assertNotNull(cache.pollPublication());
            Assert.assertEquals(3, scheduler.tasks.size());

            cache.refreshForCamera(10.61D, 0.0D, 0.0D, t0 + 499L * millis);
            Assert.assertEquals("位移归零但兜底未到不得刷新", 3, scheduler.tasks.size());
            cache.refreshForCamera(10.61D, 0.0D, 0.0D, t0 + 500L * millis);
            Assert.assertEquals("兜底到期必须刷新", 4, scheduler.tasks.size());

            Config.clientPreviewFadeMode = PreviewFadeMode.TIMER;
            ChainPreviewState timerState = new ChainPreviewState();
            RecordingScheduler timerScheduler = new RecordingScheduler();
            ChainPreviewRenderCache timerCache = cache(timerState, timerScheduler);
            timerCache.observeState();
            timerState.begin(new ChainTarget(2, 0, 0));
            timerState.addPreviewTarget(timerState.getGeneration(), new ChainTarget(2, 0, 0));
            timerCache.pollPublication();
            runCompleted(timerScheduler.tasks.get(0).task);
            Assert.assertNotNull(timerCache.pollPublication());
            Assert.assertEquals("timer", timerCache.getVisualSettings().getFadeModeId());

            timerCache.refreshForCamera(0.0D, 0.0D, 0.0D, 0L);
            Assert.assertEquals(2, timerScheduler.tasks.size());
            runCompleted(timerScheduler.tasks.get(1).task);
            Assert.assertNotNull(timerCache.pollPublication());
            timerCache.refreshForCamera(100.0D, 0.0D, 0.0D, 500L * millis);
            Assert.assertEquals("timer 档只看固定 1 Hz，不看位移", 2, timerScheduler.tasks.size());
            timerCache.refreshForCamera(100.0D, 0.0D, 0.0D, 1000L * millis);
            Assert.assertEquals("timer 档 1 Hz 到期必须刷新", 3, timerScheduler.tasks.size());
        } finally {
            Config.clientPreviewFadeMode = originalFadeMode;
        }
    }

    @Test
    public void semanticClassesReachBuilderAuxForEveryLocalAndRemoteClass() throws Exception {
        assertAuxSemanticClass(ChainPreviewSemanticClass.PRIMARY_LOCAL);
        assertAuxSemanticClass(ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        assertAuxSemanticClass(ChainPreviewSemanticClass.REMOTE_PREDICTED);
        assertAuxSemanticClass(ChainPreviewSemanticClass.UNDEFINED);
    }

    private static void assertAuxSemanticClass(int semanticClass) throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();
        int generation = state.begin(new ChainTarget(0, 0, 0), semanticClass);
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        cache.pollPublication();
        runCompleted(scheduler.tasks.get(0).task);
        ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
        Assert.assertNotNull(publication);
        ChainPreviewMesh mesh = publication.getMesh();
        Assert.assertTrue("mesh 必须带 aAux", mesh.isAuxAvailable());
        byte[] aux = mesh.getAux();
        Assert.assertTrue(aux.length >= 4);
        Assert.assertEquals("aAux.x 必须是真实 semanticClass", semanticClass, aux[0] & 0xFF);
    }

    private static ChainPreviewRenderCache cache(ChainPreviewState state, RecordingScheduler scheduler) {
        return new ChainPreviewRenderCache(state, new ChainPreviewMeshBuilder(), scheduler);
    }

    private static void runCompleted(ParallelTickTask task) throws Exception {
        Assert.assertEquals(ParallelTaskResult.COMPLETED, task.run(new NeverYieldControl()));
    }

    private static final class RecordingScheduler implements ChainPreviewRenderCache.TaskScheduler {

        private final List<ScheduledTask> tasks = new ArrayList<ScheduledTask>();
        private int cancelCount;
        private Runnable onSchedule;

        @Override
        public ParallelTickSubscription schedule(ParallelTickTask task) {
            final ScheduledTask scheduled = new ScheduledTask(task);
            tasks.add(scheduled);
            if (onSchedule != null) {
                onSchedule.run();
            }
            return new ParallelTickSubscription() {
                @Override
                public void unregister() {
                    if (!scheduled.cancelled) {
                        scheduled.cancelled = true;
                        cancelCount++;
                    }
                }
            };
        }
    }

    private static final class ScheduledTask {

        private final ParallelTickTask task;
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
