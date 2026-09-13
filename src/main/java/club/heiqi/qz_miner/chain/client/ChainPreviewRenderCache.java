package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.MeshBuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.RenderChange;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.RenderSnapshot;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.config.PreviewLodMode;
import club.heiqi.qz_miner.config.PreviewRenderBackend;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.parallel.ParallelTickTask;

/**
 * Observer 驱动的 preview CPU render cache。
 *
 * <p>核心状态只发布 O(1) change signal；完整 target snapshot 与条柱构建都在短寿命
 * CLIENT_POST worker 内完成。无 dirty 工作时不会保留 scheduler task。</p>
 */
final class ChainPreviewRenderCache implements ChainPreviewState.Observer {

    static final long EFFECT_REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L);

    /** B1.3 signal 档的 fadeMode 稳定 id（接口冻结 §E）。 */
    static final String SIGNAL_FADE_MODE_ID = "signal";

    interface TaskScheduler {
        ParallelTickSubscription schedule(ParallelTickTask task);
    }

    private final Object taskLock = new Object();
    private final ChainPreviewState state;
    private final ChainPreviewMeshBuilder meshBuilder;
    private final TaskScheduler scheduler;
    private final AtomicReference<MeshPublication> pendingPublication =
        new AtomicReference<MeshPublication>();

    private volatile RenderChange latestChange;
    private volatile VisualState latestVisualState = new VisualState(0L, 0.0D, 0.0D, 0.0D);
    private volatile ChainPreviewVisualSettings visualSettings = ChainPreviewVisualSettings.fromConfig();
    private PreviewRenderBackend lastConfiguredBackend;
    private double latestCameraX;
    private double latestCameraY;
    private double latestCameraZ;
    private volatile BuildKey publishedKey = BuildKey.NONE;
    private ChainPreviewMesh publishedMesh = ChainPreviewMesh.EMPTY;

    private ChainPreviewState.ObserverSubscription observerSubscription;
    private BuildTask currentTask;
    private ParallelTickSubscription taskSubscription;
    private long lastEffectRefreshNanos = Long.MIN_VALUE;
    private long lastSettingsSampleNanos = Long.MIN_VALUE;
    private long lifecycleEpoch;
    private int fencedGeneration = Integer.MIN_VALUE;
    private boolean lifecycleReady = true;
    private boolean visualRefreshPending;
    private boolean observing;
    /** 只读诊断：最近一次渲染线程交接的规模计数器（未接线时 null → 诊断行打 -1）。 */
    private volatile ChainPreviewScaleCounters lastScaleCounters;

    /** B4.1 第二步：当前代的增量装配会话（构建线程持有；代/lifecycle 变化时 dispose 另起）。 */
    private GenerationSession generationSession;
    private int generationSessionGeneration = Integer.MIN_VALUE;
    private long generationSessionLifecycleEpoch = Long.MIN_VALUE;

    static ChainPreviewRenderCache createProduction(ChainPreviewState state) {
        return new ChainPreviewRenderCache(
            state,
            new ChainPreviewMeshBuilder(),
            new TaskScheduler() {
                @Override
                public ParallelTickSubscription schedule(ParallelTickTask task) {
                    return MyMod.ensureParallelTickExecutor().registerClientPost(
                        "chain-preview-render-cache",
                        task);
                }
            });
    }

    ChainPreviewRenderCache(
            ChainPreviewState state,
            ChainPreviewMeshBuilder meshBuilder,
            TaskScheduler scheduler) {
        if (state == null) {
            throw new IllegalArgumentException("state");
        }
        if (meshBuilder == null) {
            throw new IllegalArgumentException("meshBuilder");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler");
        }
        this.state = state;
        this.meshBuilder = meshBuilder;
        this.scheduler = scheduler;
    }

    void observeState() {
        synchronized (taskLock) {
            if (observing) {
                return;
            }
            observing = true;
        }
        observerSubscription = state.observe(this);
    }

    @Override
    public void onPreviewChanged(RenderChange change) {
        if (change == null) {
            return;
        }

        boolean schedule = false;
        synchronized (taskLock) {
            if (latestChange != null && change.getRevision() <= latestChange.getRevision()) {
                return;
            }
            boolean generationChanged = latestChange == null
                || latestChange.getGeneration() != change.getGeneration();
            latestChange = change;
            if (change.isActive()) {
                if (!lifecycleReady && change.getGeneration() == fencedGeneration) {
                    return;
                }
                lifecycleReady = true;
                if (generationChanged) {
                    BuildKey publicationKey = new BuildKey(
                        lifecycleEpoch,
                        change.getGeneration(),
                        change.getRevision(),
                        latestVisualState.revision);
                    publishedKey = new BuildKey(
                        lifecycleEpoch,
                        change.getGeneration(),
                        -1L,
                        latestVisualState.revision);
                    publishedMesh = ChainPreviewMesh.EMPTY;
                    pendingPublication.set(new MeshPublication(publicationKey, ChainPreviewMesh.EMPTY));
                    // Lead 必修项 L-a：换代必须清 LOD 滞回记忆（同一目标跨代存在也不得残留）。
                    meshBuilder.resetLodHysteresis();
                }
                schedule = true;
            } else {
                visualRefreshPending = false;
                disposeGenerationSessionLocked();
                BuildKey emptyKey = new BuildKey(
                    lifecycleEpoch,
                    change.getGeneration(),
                    change.getRevision(),
                    latestVisualState.revision);
                publishedKey = emptyKey;
                publishedMesh = ChainPreviewMesh.EMPTY;
                pendingPublication.set(new MeshPublication(emptyKey, ChainPreviewMesh.EMPTY));
            }
        }
        if (schedule) {
            ensureWorker();
        }
    }

    /**
     * RenderWorld 只采样相机；状态 targets 不在此读取。
     *
     * <p>B1.3 刷新触发：{@code fadeMode=timer}（默认，逐字等于历史行为）恒按
     * {@link #EFFECT_REFRESH_INTERVAL_NANOS} 提升 visual revision；{@code fadeMode=signal} 改为
     * 「相机位移 &gt;= clientPreviewFadeRefreshDistance 或距上次提升 &gt;= clientPreviewFadeFallbackMs」。</p>
     *
     * <p>每帧路径只做标量读写与纯函数判定（零分配）；视觉设置快照仍按既有 1 Hz 节奏采样，
     * 不随 signal 档逐帧重建。generation / lifecycle / world 变化仍由状态信号立即失效。</p>
     *
     * <p><b>已知代价（登记）</b>：signal 档把 recolor 频率从固定 1 Hz 提高到「位移 &gt;= 阈值 或
     * 兜底到期」，而 chain recolor 每次仍会新建一份颜色数组（Builder 的不可变 mesh 契约，
     * 本轮明确不做原地复用，避免把新颜色写进渲染线程可能正在上传的数组）。大链路（如 4096 目标）
     * 真机若观察到 GC 压力，下一批用双缓冲 + publication 上传完成回执来消除，不要在本轮改动。</p>
     */
    void refreshForCamera(double cameraX, double cameraY, double cameraZ, long nowNanos) {
        boolean schedule = false;
        synchronized (taskLock) {
            // B0.4：每帧只记录相机标量（零分配）；VisualParameters 只在提升视觉 revision 时冻结一次。
            latestCameraX = cameraX;
            latestCameraY = cameraY;
            latestCameraZ = cameraZ;
            // 后端档位热切换必须「下一帧生效」（接口冻结 §G）：每帧只做一次枚举引用比较（零分配），
            // 引用变化才重建不可变快照。
            PreviewRenderBackend configuredBackend = Config.clientPreviewRenderBackend;
            if (configuredBackend != lastConfiguredBackend) {
                lastConfiguredBackend = configuredBackend;
                visualSettings = ChainPreviewVisualSettings.fromConfig();
                ChainPreviewVisualSettings.publish(visualSettings);
            }
            // 视觉设置按既有 1 Hz 节奏采样（配置热更新通道），与 visual revision 提升解耦：
            // signal 档可能逐帧提升 revision，但不得逐帧重建设置快照。
            if (lastSettingsSampleNanos == Long.MIN_VALUE
                    || nowNanos - lastSettingsSampleNanos >= EFFECT_REFRESH_INTERVAL_NANOS) {
                lastSettingsSampleNanos = nowNanos;
                visualSettings = ChainPreviewVisualSettings.fromConfig();
                ChainPreviewVisualSettings.publish(visualSettings);
            }

            ChainPreviewVisualSettings sampled = visualSettings;
            boolean refreshDue;
            if (SIGNAL_FADE_MODE_ID.equals(sampled.getFadeModeId())) {
                refreshDue = lastEffectRefreshNanos == Long.MIN_VALUE
                    || ChainPreviewRefreshPolicy.isSignalRefreshDue(
                        cameraDisplacementLocked(cameraX, cameraY, cameraZ),
                        sampled.getFadeRefreshDistance(),
                        (nowNanos - lastEffectRefreshNanos) / 1000000L,
                        sampled.getFadeFallbackMs());
            } else {
                // timer（默认，等于现状）；gpu 档尚未接线（B5.3），一并保持固定 1 Hz 提升节奏。
                refreshDue = lastEffectRefreshNanos == Long.MIN_VALUE
                    || nowNanos - lastEffectRefreshNanos >= EFFECT_REFRESH_INTERVAL_NANOS;
            }
            if (refreshDue) {
                lastEffectRefreshNanos = nowNanos;
                if (currentTask != null || pendingPublication.get() != null) {
                    visualRefreshPending = true;
                } else {
                    promoteVisualRefreshLocked();
                    schedule = lifecycleReady && latestChange != null && latestChange.isActive();
                }
            }
        }
        if (schedule) {
            ensureWorker();
        }
    }

    /**
     * 取与目标同序的类别载体（task-16a 契约）。
     *
     * <p>索引 i 对应 {@code snapshot.getTargets()} 第 i 个迭代目标；a 侧保证长度等于目标数。
     * 若长度不一致则降级为 null（Builder 全部按 UNDEFINED 处理）并告警，绝不静默错位。</p>
     *
     * @param snapshot 本次构建的状态快照
     * @return 类别数组，或 null 表示降级为全部 UNDEFINED
     */
    private static int[] semanticClassesFor(RenderSnapshot snapshot) {
        int[] semanticClasses = snapshot.getSemanticClasses();
        if (semanticClasses.length != snapshot.getTargetCount()) {
            MyMod.LOG.warn(
                "[ChainPreview] Semantic class carrier mismatch: classes={} targets={};"
                    + " falling back to UNDEFINED",
                semanticClasses.length,
                snapshot.getTargetCount());
            return null;
        }
        return semanticClasses;
    }

    /** 诊断开关的系统属性名（默认关闭；每次成功发布读一次，便于运行中开启，不新增用户可见配置键）。 */
    static final String DIAGNOSTICS_PROPERTY = "qz_miner.preview.diagnostics";

    /** @return 诊断开关是否打开（{@code -Dqz_miner.preview.diagnostics=true}） */
    static boolean previewDiagnosticsEnabled() {
        return Boolean.getBoolean(DIAGNOSTICS_PROPERTY);
    }

    /**
     * 诊断行格式（headless 契约；纯格式化，无副作用）。
     *
     * <p>分流用途：{@code matched} 远大于 {@code uniquePositions} ⇒ 上游把同一坐标重复投喂，
     * 几何合法地只有一根；两值相等而 {@code meshBlockCount} 偏小 ⇒ 问题在装配/绘制侧。</p>
     */
    static String formatPreviewDiagnostics(long generation, long revision, int matched, int stateTargetCount,
            int uniquePositions, int meshBlockCount, int meshIndexCount, long uploads, long rebuilds) {
        return "previewDiag gen=" + generation
            + " rev=" + revision
            + " matched=" + matched
            + " stateTargetCount=" + stateTargetCount
            + " uniquePositions=" + uniquePositions
            + " meshBlockCount=" + meshBlockCount
            + " meshIndexCount=" + meshIndexCount
            + " uploads=" + uploads
            + " rebuilds=" + rebuilds;
    }

    /** 输出一行诊断（只在开关打开时由构建线程调用；全部读数走既有只读访问器）。 */
    private void logPreviewDiagnostics(long generation, long revision, int stateTargetCount,
            int meshBlockCount, int meshIndexCount) {
        ChainPreviewScaleCounters counters = lastScaleCounters;
        MyMod.LOG.info(formatPreviewDiagnostics(
            generation,
            revision,
            state.getMatchedCount(),
            stateTargetCount,
            state.getUniqueTargetCount(),
            meshBlockCount,
            meshIndexCount,
            counters == null ? -1L : counters.getUploads(),
            counters == null ? -1L : counters.getRebuilds()));
    }

    /** @return 相对上次提升视觉 revision 的相机位移（格）；由 taskLock 保护 */
    private double cameraDisplacementLocked(double cameraX, double cameraY, double cameraZ) {
        VisualState snapshot = latestVisualState;
        double dx = cameraX - snapshot.cameraX;
        double dy = cameraY - snapshot.cameraY;
        double dz = cameraZ - snapshot.cameraZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    boolean isPreviewActive() {
        synchronized (taskLock) {
            return lifecycleReady && latestChange != null && latestChange.isActive();
        }
    }

    /** @return 当前视觉参数快照（volatile 读、零分配、非 null），供 renderer 每帧读取 */
    public ChainPreviewVisualSettings getVisualSettings() {
        return visualSettings;
    }

    /**
     * B4.2：把当前代装配会话的容量峰值交接进调用方计数器（渲染线程；null 安全、零分配、无 GL）。
     *
     * <p>会话在构建线程采样峰值（volatile 读交接），本方法只做一次最大值合并：不重置会话峰值、
     * 不改动既有计数、不触碰 GL；无会话 / counters 为 null 时无操作。</p>
     *
     * @param counters 目标规模计数器，可为 null
     */
    public void publishCapacityInto(ChainPreviewScaleCounters counters) {
        if (counters == null) {
            return;
        }
        // 只读诊断出口需要 uploads/rebuilds；本方法本就由渲染线程每次 applyPublication 调用一次。
        lastScaleCounters = counters;
        GenerationSession session;
        synchronized (taskLock) {
            session = generationSession;
        }
        if (session != null) {
            session.publishCapacityInto(counters);
        }
    }

    MeshPublication pollPublication() {
        MeshPublication publication;
        MeshPublication accepted = null;
        boolean schedule = false;
        synchronized (taskLock) {
            publication = pendingPublication.getAndSet(null);
            if (publication == null) {
                return null;
            }
            boolean currentLifecycle = publication.key.lifecycleEpoch == lifecycleEpoch;
            boolean currentGeneration = publication.key.generation < 0
                || latestChange == null
                || publication.key.generation == latestChange.getGeneration();
            boolean currentVisual = publication.mesh.isEmpty()
                || publication.key.visualRevision == latestVisualState.revision;
            if (currentLifecycle && currentGeneration && currentVisual) {
                accepted = publication;
            }
            if (accepted != null && visualRefreshPending) {
                promoteVisualRefreshLocked();
            }
            schedule = currentTask == null && needsBuildLocked();
        }
        if (schedule) {
            ensureWorker();
        }
        return accepted;
    }

    /** lifecycle 主线程入口：丢弃 CPU publication，并协作取消当前短寿命 worker。 */
    void resetForLifecycle() {
        ParallelTickSubscription subscription;
        BuildKey emptyKey;
        synchronized (taskLock) {
            lifecycleReady = false;
            lifecycleEpoch++;
            lastEffectRefreshNanos = Long.MIN_VALUE;
            lastSettingsSampleNanos = Long.MIN_VALUE;
            visualRefreshPending = false;
            RenderChange change = latestChange;
            fencedGeneration = change == null ? Integer.MIN_VALUE : change.getGeneration();
            emptyKey = change == null
                ? new BuildKey(lifecycleEpoch, -1, -1L, latestVisualState.revision)
                : new BuildKey(
                    lifecycleEpoch,
                    change.getGeneration(),
                    change.getRevision(),
                    latestVisualState.revision);
            publishedKey = emptyKey;
            publishedMesh = ChainPreviewMesh.EMPTY;
            pendingPublication.set(new MeshPublication(emptyKey, ChainPreviewMesh.EMPTY));
            // Lead 必修项 L-a：lifecycle/世界切换入口同样清 LOD 滞回记忆。
            meshBuilder.resetLodHysteresis();
            disposeGenerationSessionLocked();
            subscription = taskSubscription;
            currentTask = null;
            taskSubscription = null;
        }
        if (subscription != null) {
            subscription.unregister();
        }
    }

    private void ensureWorker() {
        BuildTask task;
        synchronized (taskLock) {
            if (!lifecycleReady || latestChange == null || !latestChange.isActive()) {
                return;
            }
            if (currentTask != null || !needsBuildLocked()) {
                return;
            }
            task = new BuildTask(lifecycleEpoch);
            currentTask = task;
        }

        ParallelTickSubscription createdSubscription;
        try {
            createdSubscription = scheduler.schedule(task);
        } catch (RuntimeException failure) {
            synchronized (taskLock) {
                if (currentTask == task) {
                    currentTask = null;
                    taskSubscription = null;
                }
            }
            MyMod.LOG.warn("[ChainPreview] Render cache worker registration failed", failure);
            return;
        }

        boolean keepSubscription;
        synchronized (taskLock) {
            keepSubscription = currentTask == task
                && lifecycleReady
                && lifecycleEpoch == task.ownerEpoch;
            if (keepSubscription) {
                taskSubscription = createdSubscription;
            }
        }
        if (!keepSubscription) {
            createdSubscription.unregister();
        }
    }

    private boolean needsBuildLocked() {
        if (!lifecycleReady || latestChange == null || !latestChange.isActive()) {
            return false;
        }
        return publishedKey.lifecycleEpoch != lifecycleEpoch
            || publishedKey.generation != latestChange.getGeneration()
            || publishedKey.stateRevision < latestChange.getRevision()
            || publishedKey.visualRevision < latestVisualState.revision;
    }

    private void taskFinished(BuildTask task, boolean allowReschedule) {
        boolean reschedule = false;
        synchronized (taskLock) {
            if (currentTask != task) {
                return;
            }
            currentTask = null;
            taskSubscription = null;
            if (visualRefreshPending && pendingPublication.get() == null) {
                promoteVisualRefreshLocked();
            }
            reschedule = allowReschedule && needsBuildLocked();
        }
        if (reschedule) {
            ensureWorker();
        }
    }

    private void promoteVisualRefreshLocked() {
        latestVisualState = new VisualState(
            latestVisualState.revision + 1L,
            latestCameraX,
            latestCameraY,
            latestCameraZ);
        visualRefreshPending = false;
    }

    /**
     * B4.1 第二步：按代取（或新建）增量装配会话。
     *
     * <p>代内复用同一会话（保留增量缓存与 LOD 滞回）；世代变化 / lifecycle 变化时 dispose 旧会话
     * 并新建（等效一次全量重建）。会话切换只发生在构建线程；dispose 仅置 volatile 位，
     * 由构建线程下次 extend 入口消费。</p>
     */
    private MeshBuildSession generationExtendSession(
            RenderSnapshot snapshot,
            VisualState visuals,
            ChainPreviewVisualSettings settingsSnapshot) {
        GenerationSession session;
        synchronized (taskLock) {
            int generation = snapshot.getGeneration();
            if (generationSession == null
                    || generationSessionGeneration != generation
                    || generationSessionLifecycleEpoch != lifecycleEpoch) {
                disposeGenerationSessionLocked();
                generationSession = meshBuilder.beginGeneration();
                generationSessionGeneration = generation;
                generationSessionLifecycleEpoch = lifecycleEpoch;
            }
            session = generationSession;
        }
        List<ChainTarget> targets = new ArrayList<ChainTarget>(snapshot.getTargetCount());
        for (ChainTarget target : snapshot.getTargets()) {
            targets.add(target);
        }
        // 可分片、可续跑：安全点与既有 BuildSession.advance 一致；再次 beginRevision 取代旧会话。
        return session.beginRevision(
            targets,
            semanticClassesFor(snapshot),
            visuals.toVisualParameters(settingsSnapshot),
            settingsSnapshot.getBarThickness());
    }

    /** 释放当前代会话（持 taskLock 调用；dispose 只置位，构建线程下次 extend 消费）。 */
    private void disposeGenerationSessionLocked() {
        if (generationSession != null) {
            generationSession.dispose();
            generationSession = null;
            generationSessionGeneration = Integer.MIN_VALUE;
            generationSessionLifecycleEpoch = Long.MIN_VALUE;
        }
    }

    private final class BuildTask implements ParallelTickTask {

        private final long ownerEpoch;
        private MeshBuildSession session;
        private BuildKey sessionKey;
        /** 本次装配快照的目标读取数（含重复坐标；诊断用，不参与任何行为判定）。 */
        private int sessionTargetCount;
        private boolean finished;

        private BuildTask(long ownerEpoch) {
            this.ownerEpoch = ownerEpoch;
        }

        @Override
        public ParallelTaskResult run(final ParallelTickControl control) {
            try {
                return runSlice(control);
            } catch (RuntimeException failure) {
                finish(false);
                throw failure;
            } catch (LinkageError failure) {
                finish(false);
                MyMod.LOG.error("[ChainPreview] Render cache worker linkage failed", failure);
                return ParallelTaskResult.TERMINATED;
            }
        }

        private ParallelTaskResult runSlice(final ParallelTickControl control) {
            if (control.isCancelRequested()) {
                finish(false);
                return ParallelTaskResult.TERMINATED;
            }

            while (true) {
                RenderChange desiredChange;
                synchronized (taskLock) {
                    if (currentTask != this || lifecycleEpoch != ownerEpoch) {
                        finish(false);
                        return ParallelTaskResult.TERMINATED;
                    }
                    desiredChange = latestChange;
                }
                if (desiredChange == null || !desiredChange.isActive()) {
                    finish(true);
                    return ParallelTaskResult.COMPLETED;
                }
                if (sessionKey != null && sessionKey.generation != desiredChange.getGeneration()) {
                    session = null;
                    sessionKey = null;
                }

                if (session == null) {
                    if (!needsBuild()) {
                        finish(true);
                        return ParallelTaskResult.COMPLETED;
                    }
                    if (control.shouldYield()) {
                        return ParallelTaskResult.YIELDED;
                    }

                    VisualState visuals = latestVisualState;
                    ChainPreviewVisualSettings settingsSnapshot = visualSettings;
                    synchronized (taskLock) {
                        RenderChange currentChange = latestChange;
                        boolean sameTopology = currentTask == this
                            && lifecycleEpoch == ownerEpoch
                            && currentChange != null
                            && currentChange.isActive()
                            && publishedKey.lifecycleEpoch == ownerEpoch
                            && publishedKey.generation == currentChange.getGeneration()
                            && publishedKey.stateRevision == currentChange.getRevision();
                        if (sameTopology && publishedMesh.isEmpty()) {
                            publishedKey = new BuildKey(
                                ownerEpoch,
                                currentChange.getGeneration(),
                                currentChange.getRevision(),
                                visuals.revision);
                            continue;
                        }
                        if (sameTopology && publishedMesh.isRecolorable()) {
                            sessionKey = new BuildKey(
                                ownerEpoch,
                                currentChange.getGeneration(),
                                currentChange.getRevision(),
                                visuals.revision);
                            session = meshBuilder.beginRecolor(
                                publishedMesh, visuals.toVisualParameters(settingsSnapshot));
                            sessionTargetCount = publishedMesh.getBlockCount();
                        }
                    }
                    if (session == null) {
                        RenderSnapshot snapshot = state.captureRenderSnapshot();
                        if (!snapshot.isActive()) {
                            finish(true);
                            return ParallelTaskResult.COMPLETED;
                        }
                        sessionKey = new BuildKey(
                            ownerEpoch,
                            snapshot.getGeneration(),
                            snapshot.getRevision(),
                            visuals.revision);
                        sessionTargetCount = snapshot.getTargetCount();
                        // B4.1 第二步：按代复用 GenerationSession 做增量装配（代内 extend 累积）。
                        session = generationExtendSession(snapshot, visuals, settingsSnapshot);
                    }
                }

                boolean complete = session.advance(new ChainPreviewMeshBuilder.WorkGate() {
                    @Override
                    public boolean shouldYield() {
                        return control.shouldYield();
                    }
                });
                if (!complete) {
                    return ParallelTaskResult.YIELDED;
                }

                boolean diagnosticsPublished = false;
                long diagnosticsGeneration = 0L;
                long diagnosticsRevision = 0L;
                int diagnosticsMeshBlocks = 0;
                int diagnosticsMeshIndices = 0;
                int diagnosticsTargetCount = 0;
                synchronized (taskLock) {
                    RenderChange currentChange = latestChange;
                    if (currentTask == this
                            && lifecycleReady
                            && lifecycleEpoch == ownerEpoch
                            && currentChange != null
                            && currentChange.isActive()
                            && currentChange.getGeneration() == sessionKey.generation) {
                        ChainPreviewMesh mesh = session.getMesh();
                        publishedKey = sessionKey;
                        publishedMesh = mesh;
                        if (latestVisualState.revision == sessionKey.visualRevision) {
                            pendingPublication.set(new MeshPublication(sessionKey, mesh));
                            if (previewDiagnosticsEnabled()) {
                                diagnosticsPublished = true;
                                diagnosticsGeneration = sessionKey.generation;
                                diagnosticsRevision = sessionKey.stateRevision;
                                diagnosticsMeshBlocks = mesh.getBlockCount();
                                diagnosticsMeshIndices = mesh.getIndexCount();
                                diagnosticsTargetCount = sessionTargetCount;
                            }
                        }
                    }
                }
                session = null;
                sessionKey = null;
                sessionTargetCount = 0;
                // 真机故障自证出口：默认关闭（-Dqz_miner.preview.diagnostics=true 才输出），不改任何行为。
                if (diagnosticsPublished) {
                    logPreviewDiagnostics(
                        diagnosticsGeneration,
                        diagnosticsRevision,
                        diagnosticsTargetCount,
                        diagnosticsMeshBlocks,
                        diagnosticsMeshIndices);
                }

                if (control.shouldYield()) {
                    return ParallelTaskResult.YIELDED;
                }
            }
        }

        private boolean needsBuild() {
            synchronized (taskLock) {
                return needsBuildLocked();
            }
        }

        private void finish(boolean allowReschedule) {
            if (finished) {
                return;
            }
            finished = true;
            taskFinished(this, allowReschedule);
        }

        @Override
        public void cleanupAfterTermination() {
            finish(false);
        }
    }

    static final class MeshPublication {

        private final BuildKey key;
        private final ChainPreviewMesh mesh;

        private MeshPublication(BuildKey key, ChainPreviewMesh mesh) {
            this.key = key;
            this.mesh = mesh == null ? ChainPreviewMesh.EMPTY : mesh;
        }

        int getGeneration() {
            return key.generation;
        }

        long getStateRevision() {
            return key.stateRevision;
        }

        long getVisualRevision() {
            return key.visualRevision;
        }

        long getLifecycleEpoch() {
            return key.lifecycleEpoch;
        }

        ChainPreviewMesh getMesh() {
            return mesh;
        }
    }

    private static final class VisualState {

        private final long revision;
        private final double cameraX;
        private final double cameraY;
        private final double cameraZ;

        private VisualState(long revision, double cameraX, double cameraY, double cameraZ) {
            this.revision = revision;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
        }

        /**
         * @param settings 本次构建的视觉设置快照
         * @return 冻结本次视觉 revision 的视觉参数（只在构建时构造，不在每帧路径上）
         */
        private VisualParameters toVisualParameters(ChainPreviewVisualSettings settings) {
            // B2.4：LOD 档位经 settings 快照注入（lod=off 时逐字等于现状）。
            boolean lodEnabled = PreviewLodMode.AUTO.id().equals(settings.getLodId());
            return new VisualParameters(
                cameraX,
                cameraY,
                cameraZ,
                settings.getAlphaFadeStartRadius(),
                settings.getAlphaFadeEndRadius(),
                settings.getAlphaStartValue(),
                settings.getAlphaEndValue(),
                settings.getBarThickness())
                .withLod(lodEnabled, settings.getLodMinAlpha());
        }
    }

    private static final class BuildKey {

        private static final BuildKey NONE = new BuildKey(-1L, -1, -1L, -1L);

        private final long lifecycleEpoch;
        private final int generation;
        private final long stateRevision;
        private final long visualRevision;

        private BuildKey(long lifecycleEpoch, int generation, long stateRevision, long visualRevision) {
            this.lifecycleEpoch = lifecycleEpoch;
            this.generation = generation;
            this.stateRevision = stateRevision;
            this.visualRevision = visualRevision;
        }
    }
}
