package club.heiqi.qz_miner.chain.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.MeshBuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.RenderChange;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.RenderSnapshot;
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
    private volatile VisualState latestVisualState = new VisualState(
        0L,
        VisualParameters.fromCurrentConfig(0.0D, 0.0D, 0.0D));
    private VisualParameters latestCameraParameters = latestVisualState.parameters;
    private volatile BuildKey publishedKey = BuildKey.NONE;
    private ChainPreviewMesh publishedMesh = ChainPreviewMesh.EMPTY;

    private ChainPreviewState.ObserverSubscription observerSubscription;
    private BuildTask currentTask;
    private ParallelTickSubscription taskSubscription;
    private long lastEffectRefreshNanos = Long.MIN_VALUE;
    private long lifecycleEpoch;
    private int fencedGeneration = Integer.MIN_VALUE;
    private boolean lifecycleReady = true;
    private boolean visualRefreshPending;
    private boolean observing;

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
                }
                schedule = true;
            } else {
                visualRefreshPending = false;
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
     * RenderWorld 只采样相机；状态 targets 不在此读取。连续 active 时每秒至少请求一次效果刷新。
     */
    void refreshForCamera(double cameraX, double cameraY, double cameraZ, long nowNanos) {
        VisualParameters sampledParameters = VisualParameters.fromCurrentConfig(cameraX, cameraY, cameraZ);
        boolean schedule = false;
        synchronized (taskLock) {
            latestCameraParameters = sampledParameters;
            boolean refreshDue = lastEffectRefreshNanos == Long.MIN_VALUE
                || nowNanos - lastEffectRefreshNanos >= EFFECT_REFRESH_INTERVAL_NANOS;
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

    boolean isPreviewActive() {
        synchronized (taskLock) {
            return lifecycleReady && latestChange != null && latestChange.isActive();
        }
    }

    MeshPublication pollPublication() {
        MeshPublication publication = pendingPublication.getAndSet(null);
        if (publication == null) {
            return null;
        }
        MeshPublication accepted = null;
        boolean schedule = false;
        synchronized (taskLock) {
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
                schedule = lifecycleReady
                    && latestChange != null
                    && latestChange.isActive();
            }
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
            latestCameraParameters);
        visualRefreshPending = false;
    }

    private final class BuildTask implements ParallelTickTask {

        private final long ownerEpoch;
        private MeshBuildSession session;
        private BuildKey sessionKey;
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
                            session = meshBuilder.beginRecolor(publishedMesh, visuals.parameters);
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
                        session = meshBuilder.begin(snapshot.getTargets(), visuals.parameters);
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
                        }
                    }
                }
                session = null;
                sessionKey = null;

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
        private final VisualParameters parameters;

        private VisualState(long revision, VisualParameters parameters) {
            this.revision = revision;
            this.parameters = parameters;
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
