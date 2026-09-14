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

    @Test
    public void truncationReportExposesReasonCountsAndResetsWithGeneration() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(1, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(2, 0, 0));

        Assert.assertEquals(ChainPreviewState.TruncationReason.NONE, state.getTruncationReason());
        Assert.assertEquals(2, state.getTotalCount());
        Assert.assertEquals(0, state.getTruncatedCount());

        Assert.assertTrue(state.reportTruncation(
            generation, ChainPreviewState.TruncationReason.MAX_TARGETS, 0, 4096));
        Assert.assertEquals(ChainPreviewState.TruncationReason.MAX_TARGETS, state.getTruncationReason());
        Assert.assertEquals(0, state.getTruncatedCount());
        Assert.assertEquals(4096, state.getTotalCount());

        Assert.assertFalse("陈旧代不得写入截断", state.reportTruncation(
            generation - 1, ChainPreviewState.TruncationReason.HARD_CAP, 3, 5000));
        Assert.assertFalse("NONE 不是有效上报", state.reportTruncation(
            generation, ChainPreviewState.TruncationReason.NONE, 0, 1));
        Assert.assertEquals(4096, state.getTotalCount());

        state.clear();
        Assert.assertEquals(ChainPreviewState.TruncationReason.NONE, state.getTruncationReason());
        Assert.assertEquals(0, state.getTotalCount());
        Assert.assertEquals(0, state.getTruncatedCount());
    }

    @Test
    public void cancelPreviewDeactivatesGenerationNotifiesObserverAndKeepsReason() {
        ChainPreviewState state = new ChainPreviewState();
        final List<RenderChange> changes = new ArrayList<RenderChange>();
        ChainPreviewState.ObserverSubscription subscription = state.observe(new ChainPreviewState.Observer() {
            @Override
            public void onPreviewChanged(RenderChange change) {
                changes.add(change);
            }
        });
        int generation = state.begin(new ChainTarget(3, 3, 3));
        state.addPreviewTarget(generation, new ChainTarget(3, 3, 3));
        int observedBeforeCancel = changes.size();

        Assert.assertTrue(state.cancelPreview(
            generation, ChainPreviewState.CancelReason.REMOTE_TIMEOUT));
        Assert.assertFalse(state.isActive());
        Assert.assertEquals(ChainPreviewState.CancelReason.REMOTE_TIMEOUT, state.getCancelReason());
        Assert.assertEquals(1, changes.size() - observedBeforeCancel);
        Assert.assertFalse(changes.get(changes.size() - 1).isActive());
        Assert.assertTrue("取消是幂等的", !state.cancelPreview(
            generation, ChainPreviewState.CancelReason.REMOTE_UNAVAILABLE));
        Assert.assertEquals(ChainPreviewState.CancelReason.REMOTE_TIMEOUT, state.getCancelReason());

        int next = state.begin(new ChainTarget(4, 4, 4));
        Assert.assertTrue(state.isActive());
        Assert.assertEquals(ChainPreviewState.CancelReason.NONE, state.getCancelReason());
        Assert.assertEquals(ChainPreviewState.TruncationReason.NONE, state.getTruncationReason());
        Assert.assertFalse("新代开始后旧代取消不生效",
            state.cancelPreview(generation, ChainPreviewState.CancelReason.REMOTE_TIMEOUT));
        Assert.assertTrue(state.isActive());
        Assert.assertEquals(next, state.getGeneration());
        subscription.unsubscribe();
    }

    @Test
    public void semanticClassesFollowTargetOrderAppendSemanticsAndGenerationReset() {
        ChainPreviewState state = new ChainPreviewState();
        ChainTarget first = new ChainTarget(1, 1, 1);
        ChainTarget second = new ChainTarget(2, 2, 2);
        ChainTarget third = new ChainTarget(3, 3, 3);
        int generation = state.begin(first, ChainPreviewSemanticClass.CHAIN_LOCAL);
        state.addPreviewTarget(generation, first);
        state.addPreviewTarget(generation, second);

        RenderSnapshot snapshot = state.captureRenderSnapshot();
        int[] classes = snapshot.getSemanticClasses();
        Assert.assertEquals("数组长度必须等于目标数", snapshot.getTargetCount(), classes.length);
        Assert.assertEquals("索引 0 对应最新目标", 0, classes[0]);
        Assert.assertEquals(0, classes[1]);
        Assert.assertEquals(java.util.Arrays.asList(second, first), collect(snapshot.getTargets()));
        Assert.assertEquals("每次取用返回新的防御性拷贝",
            snapshot.getTargetCount(), snapshot.getSemanticClasses().length);

        Assert.assertTrue("代内切换只影响后续追加目标",
            state.setSemanticClass(generation, ChainPreviewSemanticClass.REMOTE_PREDICTED));
        state.addPreviewTarget(generation, third);
        int[] updated = state.captureRenderSnapshot().getSemanticClasses();
        Assert.assertEquals(3, updated.length);
        Assert.assertEquals(ChainPreviewSemanticClass.REMOTE_PREDICTED, updated[0]);
        Assert.assertEquals("已记录目标类别不变", ChainPreviewSemanticClass.CHAIN_LOCAL, updated[1]);
        Assert.assertEquals(ChainPreviewSemanticClass.CHAIN_LOCAL, updated[2]);

        int nextGeneration = state.begin(new ChainTarget(9, 9, 9), ChainPreviewSemanticClass.UNDEFINED);
        state.addPreviewTarget(nextGeneration, new ChainTarget(9, 9, 9));
        int[] reset = state.captureRenderSnapshot().getSemanticClasses();
        Assert.assertEquals("跨代重置不累积", 1, reset.length);
        Assert.assertEquals(ChainPreviewSemanticClass.UNDEFINED, reset[0]);

        state.clear();
        Assert.assertEquals(0, state.captureRenderSnapshot().getSemanticClasses().length);
    }

    @Test
    public void semanticClassRejectsStaleGenerationAndNormalizesIllegalValues() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0), 99);
        Assert.assertEquals("begin 返回当前代编号", generation, state.getGeneration());
        Assert.assertFalse("陈旧代不得切换类别",
            state.setSemanticClass(generation - 1, ChainPreviewSemanticClass.SUB_MODE_LOCAL));

        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        Assert.assertEquals("非法 begin 类别归 UNDEFINED",
            ChainPreviewSemanticClass.UNDEFINED,
            state.captureRenderSnapshot().getSemanticClasses()[0]);

        int negative = state.begin(new ChainTarget(1, 0, 0), -7);
        state.addPreviewTarget(negative, new ChainTarget(1, 0, 0));
        Assert.assertEquals(ChainPreviewSemanticClass.UNDEFINED,
            state.captureRenderSnapshot().getSemanticClasses()[0]);

        Assert.assertTrue(state.setSemanticClass(negative, ChainPreviewSemanticClass.EXECUTED));
        state.addPreviewTarget(negative, new ChainTarget(2, 0, 0));
        Assert.assertEquals(ChainPreviewSemanticClass.EXECUTED,
            state.captureRenderSnapshot().getSemanticClasses()[0]);
    }

    /**
     * 真机分流口径：matchedCount 记录每次读取（含重复坐标），uniqueTargetCount 按坐标去重。
     * 若上游把同一坐标投喂 61 次，会出现 matched=61 / unique=1 —— HUD 正常但几何合法地只有 1 根。
     */
    @Test
    public void matchedCountKeepsReadsWhileUniqueTargetCountDeduplicatesPositions() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        for (int index = 0; index < 61; index++) {
            Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(0, 0, 0)));
        }
        Assert.assertEquals("matchedCount 记录每次读取（含重复坐标）", 61, state.getMatchedCount());
        Assert.assertEquals("唯一坐标数必须为 1", 1, state.getUniqueTargetCount());
        Assert.assertEquals("快照读取数同样含重复", 61, state.captureRenderSnapshot().getTargetCount());

        Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(1, 0, 0)));
        Assert.assertEquals(62, state.getMatchedCount());
        Assert.assertEquals(2, state.getUniqueTargetCount());
    }

    private static List<ChainTarget> collect(Iterable<ChainTarget> targets) {
        List<ChainTarget> result = new ArrayList<ChainTarget>();
        for (ChainTarget target : targets) {
            result.add(target);
        }
        return result;
    }
}
