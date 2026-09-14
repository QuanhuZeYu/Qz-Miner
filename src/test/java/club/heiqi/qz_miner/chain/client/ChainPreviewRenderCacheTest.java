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
    public void unconsumedPublicationIsReplacedByNewerBuildWithoutAccumulation() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();

        state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(0, 0, 0));
        Assert.assertEquals(1, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(0).task);
        Assert.assertNotNull(cache.pollPublication());

        // 产出但故意不消费：单槽只保证"最新一份可被取走"，装配不得被阻止。
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(1, 0, 0));
        Assert.assertEquals("未消费不得阻止新的装配", 2, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(1).task);
        state.addPreviewTarget(state.getGeneration(), new ChainTarget(2, 0, 0));
        Assert.assertEquals("未消费仍可继续推进", 3, scheduler.tasks.size());
        runCompleted(scheduler.tasks.get(2).task);

        ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
        Assert.assertNotNull(publication);
        Assert.assertEquals("单槽只保留最新一份发布（覆盖而非堆积）", 3, publication.getMesh().getBlockCount());
        Assert.assertNull("最新一份取走后单槽必须为空", cache.pollPublication());
        Assert.assertEquals("无待构建工作不得产生空转任务", 3, scheduler.tasks.size());
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
        assertAuxSemanticClass(ChainPreviewSemanticClass.CHAIN_LOCAL);
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

    /**
     * 逐步到达证据（真机 61 目标只显示 1 根的核对项 1）：每推进一次 renderRevision 都必须产出新 publication，
     * 且最终 publication 必须是「61 目标那一份」，不得停留在首修订的 1 目标快照。
     *
     * <p>驱动口径：生产 executor **每个 tick 都给全新 yield 预算**，因此上面的 runAll/NeverYield 与
     * {@link #productionPerTickYieldBudgetsPublishFullMesh} 的「每 tick 新实例」才是生产形态；
     * 禁止用单调累加、永不重置的 yield 计数器驱动任务——预算耗尽后每次 run 入口即让出，会造出
     * 「永久饿死 + 只剩空占位发布」的假象（真机链路上不存在该语义）。</p>
     */
    @Test
    public void progressiveTargetsEveryRevisionPublishesAndFinalMeshHoldsAllTargets() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();

        int generation = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        Assert.assertNotNull(cache.pollPublication());
        runCompleted(scheduler.tasks.get(0).task);
        ChainPreviewRenderCache.MeshPublication initial = cache.pollPublication();
        Assert.assertNotNull(initial);
        Assert.assertEquals("首修订发布必须是 1 目标", 1, initial.getMesh().getBlockCount());

        int publications = 0;
        int lastBlocks = 0;
        for (int index = 1; index < 61; index++) {
            state.addPreviewTarget(generation, new ChainTarget(index * 3, 0, 0));
            runAll(scheduler);
            ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
            Assert.assertNotNull("修订 " + index + " 追加后必须产出新 publication", publication);
            publications++;
            lastBlocks = publication.getMesh().getBlockCount();
            Assert.assertEquals("第 " + index + " 次追加的 publication 必须包含 "
                + (index + 1) + " 个目标", index + 1, lastBlocks);
        }
        Assert.assertEquals("60 次追加必须产生 60 份新 publication", 60, publications);
        Assert.assertEquals("最终 publication 必须是 61 目标那一份", 61, lastBlocks);
        Assert.assertNull("不得残留过期 publication", cache.pollPublication());
    }

    /**
     * 真机核对项 2：构建在「只有 1 个目标」时开始并 yield，挂起期间规划器追加到 61 个目标；
     * 恢复后必须先发布挂起快照（1 目标，证明快照确实旧），再在同一个 runSlice 循环内重建到 61 目标，
     * 不得把 publishedKey 错误追平到最终 revision 而停在 1 目标。
     */
    @Test
    public void suspendedBuildOverManyRevisionsRebuildsToLatestBeforeCompletion() throws Exception {
        final ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        final ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();

        int generation = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        Assert.assertNotNull(cache.pollPublication());

        ParallelTickTask task = scheduler.tasks.get(0).task;
        Assert.assertEquals("首个分片必须在只有 1 个目标时 yield",
            ParallelTaskResult.YIELDED, task.run(new YieldAfterChecksControl(1)));
        Assert.assertEquals("挂起快照必须只含 1 个目标", 1, state.getMatchedCount());

        for (int index = 1; index < 61; index++) {
            state.addPreviewTarget(generation, new ChainTarget(index * 3, 0, 0));
        }
        Assert.assertEquals("挂起期间追加后必须是 61 个目标", 61, state.getMatchedCount());

        ParallelTickControl yieldAfterFirstPublish = new NeverYieldControl() {
            @Override
            public boolean shouldYield() {
                return peekPendingMesh(cache) != null;
            }
        };
        Assert.assertEquals("恢复后必须在发布第一份（旧快照）后停在安全点",
            ParallelTaskResult.YIELDED, task.run(yieldAfterFirstPublish));
        ChainPreviewMesh stale = peekPendingMesh(cache);
        Assert.assertNotNull(stale);
        Assert.assertEquals("第一份发布必须是挂起时的 1 目标快照", 1, stale.getBlockCount());

        Assert.assertEquals("同一 runSlice 循环必须继续重建到最新 revision",
            ParallelTaskResult.COMPLETED, task.run(new NeverYieldControl()));
        ChainPreviewRenderCache.MeshPublication latest = cache.pollPublication();
        Assert.assertNotNull(latest);
        Assert.assertEquals("最终 publication 必须是 61 目标那一份", 61, latest.getMesh().getBlockCount());
        Assert.assertNull(cache.pollPublication());
    }

    /**
     * 生产形态核对（每 tick 预算重置）：executor 每个 tick 的 yield 预算都是新的，分片 yield 不得让重建
     * 停在旧修订。若用「单调累加、永不重置」的 yield 计数器驱动，第二次 run 会在任何进展前就 yield，
     * 造出「永久饿死 + 只有空占位发布」的假象——本用例用每 tick 全新预算排除该假象。
     */
    @Test
    public void productionPerTickYieldBudgetsPublishFullMesh() throws Exception {
        ChainPreviewState state = new ChainPreviewState();
        RecordingScheduler scheduler = new RecordingScheduler();
        ChainPreviewRenderCache cache = cache(state, scheduler);
        cache.observeState();

        int generation = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        for (int x = 1; x < 61; x++) {
            state.addPreviewTarget(generation, new ChainTarget(x, 0, 0));
        }
        Assert.assertEquals(61, state.getMatchedCount());

        List<ParallelTickTask> tasks = new ArrayList<ParallelTickTask>();
        for (ScheduledTask scheduled : scheduler.tasks) {
            tasks.add(scheduled.task);
        }
        Assert.assertFalse(tasks.isEmpty());
        int ticks = 0;
        for (ParallelTickTask task : tasks) {
            ParallelTaskResult result;
            do {
                result = task.run(new YieldAfterChecksControl(2));
                ticks++;
            } while (result == ParallelTaskResult.YIELDED && ticks < 10000);
            Assert.assertEquals("分片续跑必须最终完成", ParallelTaskResult.COMPLETED, result);
        }

        ChainPreviewRenderCache.MeshPublication publication = cache.pollPublication();
        Assert.assertNotNull(publication);
        Assert.assertEquals("每-tick 预算下最终 publication 必须是 61 目标",
            61, publication.getMesh().getBlockCount());
        Assert.assertFalse("不得停在空占位发布", publication.getMesh().isEmpty());
    }

    /** 诊断行格式契约：四组关键读数必须齐全且顺序固定（真机分流用）。 */
    @Test
    public void previewDiagnosticsLineCarriesAllRequiredNumbers() {
        String line = ChainPreviewRenderCache.formatPreviewDiagnostics(7L, 42L, 61, 61, 1, 1, 24, 3L, 2L);
        Assert.assertEquals(
            "previewDiag gen=7 rev=42 matched=61 stateTargetCount=61 uniquePositions=1"
                + " meshBlockCount=1 meshIndexCount=24 uploads=3 rebuilds=2",
            line);
        Assert.assertFalse("已接线读数不得出现 -1 占位", line.contains("=-1"));
    }

    /** 默认关闭：系统属性未设置时不输出诊断；显式打开才生效（不新增用户可见配置键）。 */
    @Test
    public void previewDiagnosticsStayOffUnlessSystemPropertyEnabled() {
        String property = ChainPreviewRenderCache.DIAGNOSTICS_PROPERTY;
        Assert.assertEquals("qz_miner.preview.diagnostics", property);
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            Assert.assertFalse("默认必须关闭", ChainPreviewRenderCache.previewDiagnosticsEnabled());
            System.setProperty(property, "true");
            Assert.assertTrue("属性打开即开启", ChainPreviewRenderCache.previewDiagnosticsEnabled());
            System.setProperty(property, "false");
            Assert.assertFalse("属性 false 必须关闭", ChainPreviewRenderCache.previewDiagnosticsEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private static void runAll(RecordingScheduler scheduler) throws Exception {
        List<ScheduledTask> pending = new ArrayList<ScheduledTask>(scheduler.tasks);
        scheduler.tasks.clear();
        for (ScheduledTask scheduled : pending) {
            ParallelTaskResult result = scheduled.task.run(new NeverYieldControl());
            if (result == ParallelTaskResult.YIELDED) {
                scheduler.tasks.add(scheduled);
            }
        }
    }

    /** 只读查看待消费发布的网格（不消费单槽）。 */
    private static ChainPreviewMesh peekPendingMesh(ChainPreviewRenderCache cache) {
        try {
            java.lang.reflect.Field field =
                ChainPreviewRenderCache.class.getDeclaredField("pendingPublication");
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
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("peekPendingMesh failed", failure);
        }
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
