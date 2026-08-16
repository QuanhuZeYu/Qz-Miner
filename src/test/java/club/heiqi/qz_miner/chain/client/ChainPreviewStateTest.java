package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewState.RenderChange;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.RenderSnapshot;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

public class ChainPreviewStateTest {

    @Test
    public void observerReceivesMonotonicHeadersAndPersistentSnapshotStaysImmutable() {
        ChainPreviewState state = new ChainPreviewState();
        final List<RenderChange> changes = new ArrayList<RenderChange>();
        ChainPreviewState.ObserverSubscription subscription = state.observe(new ChainPreviewState.Observer() {
            @Override
            public void onPreviewChanged(RenderChange change) {
                changes.add(change);
            }
        });

        Assert.assertEquals(1, changes.size());
        Assert.assertFalse(changes.get(0).isActive());
        Assert.assertEquals(0, changes.get(0).getGeneration());
        Assert.assertEquals(0L, changes.get(0).getRevision());

        ChainTarget origin = new ChainTarget(1, 2, 3);
        ChainTarget second = new ChainTarget(4, 5, 6);
        int generation = state.begin(origin);
        state.addPreviewTarget(generation, origin);
        RenderSnapshot frozen = state.captureRenderSnapshot();
        state.addPreviewTarget(generation, second);

        Assert.assertEquals(4, changes.size());
        for (int index = 1; index < changes.size(); index++) {
            Assert.assertTrue(changes.get(index).getRevision() > changes.get(index - 1).getRevision());
        }
        Assert.assertTrue(changes.get(1).isActive());
        Assert.assertEquals(1, changes.get(1).getGeneration());
        Assert.assertEquals(1, frozen.getTargetCount());
        Assert.assertEquals(java.util.Collections.singletonList(origin), collect(frozen.getTargets()));

        RenderSnapshot current = state.captureRenderSnapshot();
        Assert.assertEquals(2, current.getTargetCount());
        Assert.assertEquals(java.util.Arrays.asList(second, origin), collect(current.getTargets()));
        Assert.assertTrue(state.containsPreviewTarget(origin));
        Assert.assertTrue(state.containsPreviewTarget(second));

        state.clear();
        RenderSnapshot cleared = state.captureRenderSnapshot();
        Assert.assertFalse(cleared.isActive());
        Assert.assertEquals(2, cleared.getGeneration());
        Assert.assertEquals(0, cleared.getTargetCount());
        Assert.assertTrue(collect(cleared.getTargets()).isEmpty());

        int observedBeforeUnsubscribe = changes.size();
        subscription.unsubscribe();
        state.begin(origin);
        Assert.assertEquals(observedBeforeUnsubscribe, changes.size());
    }

    @Test
    public void duplicateTargetsKeepUpstreamCountButSnapshotCaptureIsConstantShape() {
        ChainPreviewState state = new ChainPreviewState();
        ChainTarget target = new ChainTarget(7, 8, 9);
        int generation = state.begin(target);
        state.addPreviewTarget(generation, target);
        state.addPreviewTarget(generation, target);

        RenderSnapshot snapshot = state.captureRenderSnapshot();
        Assert.assertEquals(2, state.getMatchedCount());
        Assert.assertEquals(2, snapshot.getTargetCount());
        Assert.assertEquals(java.util.Arrays.asList(target, target), collect(snapshot.getTargets()));
    }

    @Test
    public void staleGenerationCannotWriteTargetsProgressOrCompletion() {
        ChainPreviewState state = new ChainPreviewState();
        int staleGeneration = state.begin(new ChainTarget(0, 0, 0));
        state.clear();
        int currentGeneration = state.begin(new ChainTarget(1, 1, 1));

        Assert.assertFalse(state.addPreviewTarget(staleGeneration, new ChainTarget(2, 2, 2)));
        Assert.assertEquals(-1, state.incrementScannedCount(staleGeneration));
        Assert.assertFalse(state.setCompleted(staleGeneration, true));
        Assert.assertEquals(0, state.getMatchedCount());
        Assert.assertEquals(0, state.getScannedCount());
        Assert.assertFalse(state.isCompleted());

        Assert.assertTrue(state.addPreviewTarget(currentGeneration, new ChainTarget(3, 3, 3)));
        Assert.assertEquals(1, state.incrementScannedCount(currentGeneration));
        Assert.assertTrue(state.setCompleted(currentGeneration, true));
    }

    @Test
    public void coreStateDoesNotApplyRenderMeshCapacity() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        int updates = ChainPreviewMeshBuilder.MAX_RENDER_TARGETS + 1;
        for (int index = 0; index < updates; index++) {
            Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(index, 0, 0)));
        }

        Assert.assertEquals(updates, state.getMatchedCount());
        Assert.assertEquals(updates, state.captureRenderSnapshot().getTargetCount());
    }

    private static List<ChainTarget> collect(Iterable<ChainTarget> targets) {
        List<ChainTarget> result = new ArrayList<ChainTarget>();
        for (ChainTarget target : targets) {
            result.add(target);
        }
        return result;
    }
}
