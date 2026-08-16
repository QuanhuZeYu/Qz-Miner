package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
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
