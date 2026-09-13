package club.heiqi.qz_miner.chain.client.projection;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewBackendDiagnostics;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B5.2 执行进度（客户端世界采样）行为契约。
 *
 * <p>链路：世界采样投递观测 → clientChainEventBus drain → 位置去重计数。覆盖：同位置重复只计一次、
 * 非目标不计、换代/lifecycle/世界切换归零（去重集合随之清空）、生命周期与订阅唯一性、采样预算有界、
 * header 字段映射与「仅 executedCount 变化必须发布新 revision」。</p>
 */
public class ChainPreviewExecutionProgressTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final long WORLD = 11L;
    private static final long LIFECYCLE = 5L;

    /** 假世界探针：destroyed 内视为「已加载且已破坏」，unloaded 内视为未加载（不可判定）。 */
    private static final class FakeProbe implements ChainPreviewExecutionProgress.WorldProbe {

        private final Set<Long> destroyed = new HashSet<Long>();
        private final Set<Long> unloaded = new HashSet<Long>();

        FakeProbe markDestroyed(int x, int y, int z) {
            destroyed.add(key(x, y, z));
            return this;
        }

        FakeProbe markUnloaded(int x, int y, int z) {
            unloaded.add(key(x, y, z));
            return this;
        }

        @Override
        public boolean isDestroyed(int x, int y, int z) {
            long position = key(x, y, z);
            return !unloaded.contains(position) && destroyed.contains(position);
        }

        @Override
        public int getDimensionId() {
            return -1;
        }

        private static long key(int x, int y, int z) {
            return ((long) x << 42) ^ ((long) z << 20) ^ (long) (y & 0xFFF);
        }
    }

    private static ChainPreviewState stateWithTargets(int... xs) {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(xs.length == 0 ? 0 : xs[0], 0, 0));
        for (int x : xs) {
            state.addPreviewTarget(generation, new ChainTarget(x, 0, 0));
        }
        return state;
    }

    private static void publishObservation(ChainEventBus bus, int generation, int x, int y, int z) {
        bus.publish(new BlockBreakObserved(PLAYER, generation, 0L, 0L, x, y, z, 0, 0, null, 0));
    }

    @Test
    public void samplingPublishesDestroyedTargetsAndDrainCountsThem() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0, 3, 6);
        FakeProbe probe = new FakeProbe().markDestroyed(0, 0, 0).markDestroyed(6, 0, 0);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        Assert.assertEquals("两个已破坏目标必须各投递一条观测", 2, bus.pendingCount());
        Assert.assertEquals("计数只能发生在 drain 内", 0, progress.getExecutedCount());
        bus.drain();
        Assert.assertEquals("drain 后计数生效", 2, progress.getExecutedCount());
    }

    @Test
    public void repeatedObservationsOfSamePositionCountOnce() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0);
        FakeProbe probe = new FakeProbe().markDestroyed(0, 0, 0);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        bus.drain();
        Assert.assertEquals(1, progress.getExecutedCount());

        // 第二个 pass 重放同一位置：已计入位置不得再投递（流量优化），计数仍为 1
        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        Assert.assertEquals("已计入位置不得重复投递", 0, bus.pendingCount());
        bus.drain();
        Assert.assertEquals("同位置重复观测只计一次", 1, progress.getExecutedCount());

        // 外部重复事件（如其它来源）同样只计一次
        publishObservation(bus, state.getGeneration(), 0, 0, 0);
        publishObservation(bus, state.getGeneration(), 0, 0, 0);
        bus.drain();
        Assert.assertEquals(1, progress.getCountedPositionCount());
        Assert.assertEquals("同位置重复事件只计一次", 1, progress.getExecutedCount());
        Assert.assertEquals("回调实到 = 采样 1 次 + 外部 2 次", 3, progress.getObservedEventCount());
    }

    @Test
    public void nonTargetObservationIsNotCounted() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0);
        progress.sample(state, new FakeProbe(), WORLD, LIFECYCLE, true, PLAYER);

        publishObservation(bus, state.getGeneration(), 99, 0, 0);
        bus.drain();
        Assert.assertEquals("非本代目标位置不得计入", 0, progress.getExecutedCount());
        Assert.assertEquals(1, progress.getObservedEventCount());
    }

    @Test
    public void generationChangeResetsCountAndDedupSet() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0, 3);
        FakeProbe probe = new FakeProbe().markDestroyed(0, 0, 0).markDestroyed(3, 0, 0);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        bus.drain();
        Assert.assertEquals(2, progress.getExecutedCount());

        int nextGeneration = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(nextGeneration, new ChainTarget(0, 0, 0));
        state.addPreviewTarget(nextGeneration, new ChainTarget(3, 0, 0));
        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        Assert.assertEquals("换代必须归零", 0, progress.getExecutedCount());
        Assert.assertEquals("换代必须清空去重集合", 0, progress.getCountedPositionCount());
        bus.drain();
        Assert.assertEquals("新代同位置必须可再次计入", 2, progress.getExecutedCount());
    }

    @Test
    public void lifecycleAndWorldChangeResetCount() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0);
        FakeProbe probe = new FakeProbe().markDestroyed(0, 0, 0);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        bus.drain();
        Assert.assertEquals(1, progress.getExecutedCount());

        progress.sample(state, probe, WORLD, LIFECYCLE + 1L, true, PLAYER);
        Assert.assertEquals("lifecycle epoch 变化必须归零", 0, progress.getExecutedCount());
        bus.drain();
        Assert.assertEquals(1, progress.getExecutedCount());

        progress.sample(state, probe, WORLD + 1L, LIFECYCLE + 1L, true, PLAYER);
        Assert.assertEquals("世界身份变化必须归零", 0, progress.getExecutedCount());
        bus.drain();
        Assert.assertEquals(1, progress.getExecutedCount());
    }

    @Test
    public void unloadedOrInactiveStateIsNeverCounted() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0);

        progress.sample(state, new FakeProbe().markDestroyed(0, 0, 0).markUnloaded(0, 0, 0),
            WORLD, LIFECYCLE, true, PLAYER);
        Assert.assertEquals("区块未加载不可判定，不得投递观测", 0, bus.pendingCount());
        Assert.assertEquals(0, progress.getExecutedCount());

        state.clear();
        progress.sample(state, new FakeProbe().markDestroyed(0, 0, 0), WORLD, LIFECYCLE, true, PLAYER);
        Assert.assertEquals("无活动代不得采样", 0, bus.pendingCount());
        Assert.assertEquals("无活动代必须归零", 0, progress.getExecutedCount());
    }

    @Test
    public void disabledProgressPublishesNothingAndCountsNothing() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0);
        FakeProbe probe = new FakeProbe().markDestroyed(0, 0, 0);

        progress.sample(state, probe, WORLD, LIFECYCLE, false, PLAYER);
        Assert.assertEquals("关闭档不得采样", 0, bus.pendingCount());
        Assert.assertEquals(0, progress.getExecutedCount());

        publishObservation(bus, state.getGeneration(), 0, 0, 0);
        bus.drain();
        Assert.assertEquals("关闭档计数必须恒 0", 0, progress.getExecutedCount());

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        bus.drain();
        Assert.assertEquals("重新打开后从 0 起计", 1, progress.getExecutedCount());
    }

    @Test
    public void samplingIsBudgetedPerTickAndMakesBoundedProgress() {
        int total = ChainPreviewExecutionProgress.SAMPLE_BUDGET_PER_TICK + 44;
        int[] xs = new int[total];
        for (int index = 0; index < total; index++) {
            xs[index] = index;
        }
        ChainPreviewState state = stateWithTargets(xs);
        FakeProbe probe = new FakeProbe();
        for (int x : xs) {
            probe.markDestroyed(x, 0, 0);
        }
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        Assert.assertEquals("单 tick 采样预算必须有界",
            ChainPreviewExecutionProgress.SAMPLE_BUDGET_PER_TICK, bus.pendingCount());
        bus.drain();
        Assert.assertEquals(ChainPreviewExecutionProgress.SAMPLE_BUDGET_PER_TICK,
            progress.getExecutedCount());

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        bus.drain();
        Assert.assertEquals("下一 tick 必须续扫补齐剩余目标", total, progress.getExecutedCount());
        Assert.assertTrue("计数不得超过本代已匹配目标数",
            progress.getExecutedCount() <= state.getMatchedCount());
    }

    @Test
    public void installAndDisposeCycleNeverDuplicatesSubscription() {
        ChainEventBus bus = new ChainEventBus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        progress.install(bus);
        ChainPreviewState state = stateWithTargets(0);
        FakeProbe probe = new FakeProbe().markDestroyed(0, 0, 0);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        bus.drain();
        Assert.assertEquals(1, progress.getExecutedCount());
        Assert.assertEquals("重复 install 不得重复订阅", 1, progress.getObservedEventCount());

        progress.dispose();
        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        Assert.assertEquals("dispose 后不得再采样", 0, bus.pendingCount());
        publishObservation(bus, state.getGeneration(), 0, 0, 0);
        bus.drain();
        Assert.assertEquals("dispose 后事件不得计入", 0, progress.getExecutedCount());

        progress.install(bus);
        progress.sample(state, probe, WORLD, LIFECYCLE, true, PLAYER);
        bus.drain();
        Assert.assertEquals(1, progress.getExecutedCount());
        Assert.assertEquals("install/dispose 循环不得累积订阅", 2, progress.getObservedEventCount());
    }

    @Test
    public void headerMapsExecutionProgressAndRepublishesOnCountOnlyChange() {
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        ChainPreviewState state = stateWithTargets(0, 3);
        final int[] notifications = {0};
        projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
            @Override
            public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                notifications[0]++;
            }
        });

        ChainPreviewPresentationHeader first = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, 0L, 0L, 0L, false, true, 0, ChainPreviewBackendDiagnostics.DISABLED);
        Assert.assertEquals(0, first.getExecutedCount());
        Assert.assertTrue(first.isExecutionProgressEnabled());

        ChainPreviewPresentationHeader second = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, 0L, 0L, 0L, false, true, 1, ChainPreviewBackendDiagnostics.DISABLED);
        Assert.assertNotSame("仅 executedCount 变化必须发布新 revision", first, second);
        Assert.assertTrue("revision 必须递增", second.getRevision() > first.getRevision());
        Assert.assertEquals(1, second.getExecutedCount());
        Assert.assertFalse("executedCount 必须参与内容判定", first.sameContent(second));
        Assert.assertFalse("hashCode 必须包含 executedCount",
            first.hashCode() == second.hashCode());
        Assert.assertEquals("订阅者必须收到两次发布", 2, notifications[0]);

        ChainPreviewPresentationHeader unchanged = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, 0L, 0L, 0L, false, true, 1, ChainPreviewBackendDiagnostics.DISABLED);
        Assert.assertSame("内容不变不得重复发布", second, unchanged);
        Assert.assertEquals(2, notifications[0]);

        ChainPreviewPresentationHeader toggled = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, 0L, 0L, 0L, false, false, 1, ChainPreviewBackendDiagnostics.DISABLED);
        Assert.assertNotSame("开关位变化必须发布", second, toggled);
        Assert.assertFalse(toggled.isExecutionProgressEnabled());
    }
}
