package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewExecutionProgress;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T43 波次 9 执行进度行为契约（B5.2 / task-41）：独立驱动世界采样与事件总线，
 * 覆盖「目标被破坏 / 非目标被破坏 / 重复破坏 / 换代 / 世界与生命周期变化 / 预算 / header 映射」。
 */
public class ExecutionProgressBehaviorContractTest {

    private static final int SAMPLE_BUDGET = 256;
    private static final long WORLD = 7L;
    private static final long LIFECYCLE = 3L;

    /** 假世界探针：destroyed 集合内且未标记 unloaded 的坐标才算「已破坏」。 */
    private static final class FakeProbe implements ChainPreviewExecutionProgress.WorldProbe {

        private final Set<String> destroyed = new HashSet<String>();
        private final Set<String> unloaded = new HashSet<String>();

        void destroy(int x, int y, int z) {
            destroyed.add(key(x, y, z));
        }

        @Override
        public boolean isDestroyed(int x, int y, int z) {
            String key = key(x, y, z);
            return destroyed.contains(key) && !unloaded.contains(key);
        }

        @Override
        public int getDimensionId() {
            return 0;
        }
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static ChainEventBus bus() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        return bus;
    }

    private static int addTargets(ChainPreviewState state, int generation, int... xs) {
        int added = 0;
        for (int x : xs) {
            if (state.addPreviewTarget(generation, new ChainTarget(x, 0, 0))) {
                added++;
            }
        }
        return added;
    }

    @Test
    public void destroyedTargetsCountOnceWhileNonTargetsAndRepeatsDoNot() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0, 3, 6);
        ChainEventBus bus = bus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        FakeProbe probe = new FakeProbe();
        probe.destroy(0, 0, 0);
        probe.destroy(3, 0, 0);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        Assert.assertEquals("未 drain 前不得计数", 0, progress.getExecutedCount());
        Assert.assertTrue("必须投递观测事件", bus.pendingCount() > 0);
        bus.drain();
        Assert.assertEquals("两个被破坏目标必须各计一次", 2, progress.getExecutedCount());
        Assert.assertEquals("采样返回值必须与计数一致", 2,
            progress.sample(state, probe, WORLD, LIFECYCLE, true, null));

        // 重复破坏（同一位置再次观测）不得重复计数
        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals("同位置重复观测必须去重", 2, progress.getExecutedCount());

        // 非目标被破坏：探针报告非目标坐标，不得计数
        probe.destroy(99, 99, 99);
        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals("非本代目标不得计入", 2, progress.getExecutedCount());

        // 新区块加入后被破坏 -> 单调递增
        addTargets(state, generation, 9);
        probe.destroy(9, 0, 0);
        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals("新增目标被破坏后必须递增", 3, progress.getExecutedCount());
    }

    @Test
    public void unloadedCoordinatesAreNotCounted() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0);
        ChainEventBus bus = bus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        FakeProbe probe = new FakeProbe();
        probe.destroy(0, 0, 0);
        probe.unloaded.add(key(0, 0, 0));

        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals("区块未加载/不可判定必须不计", 0, progress.getExecutedCount());
    }

    @Test
    public void switchOffPostsNothingAndKeepsCountZero() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0, 3);
        ChainEventBus bus = bus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        FakeProbe probe = new FakeProbe();
        probe.destroy(0, 0, 0);
        probe.destroy(3, 0, 0);

        Assert.assertEquals(0, progress.sample(state, probe, WORLD, LIFECYCLE, false, null));
        Assert.assertEquals("关闭档必须零投递", 0, bus.pendingCount());
        bus.drain();
        Assert.assertEquals("关闭档必须恒 0", 0, progress.getExecutedCount());

        // 打开后同一状态必须立即恢复计数
        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals(2, progress.getExecutedCount());
    }

    @Test
    public void generationWorldAndLifecycleChangesResetTheWindow() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0, 3);
        ChainEventBus bus = bus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);
        FakeProbe probe = new FakeProbe();
        probe.destroy(0, 0, 0);
        probe.destroy(3, 0, 0);
        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals(2, progress.getExecutedCount());

        // 换代：新代同位置必须可再计一次（去重集合已清空）
        int next = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, next, 0);
        Assert.assertEquals("换代后必须先归零", 0,
            progress.sample(state, probe, WORLD, LIFECYCLE, true, null));
        bus.drain();
        Assert.assertEquals("新代同位置必须重新可计", 1, progress.getExecutedCount());

        // 世界身份变化：旧计数丢弃（采样返回 0），随后在**新世界窗口重新计数**
        Assert.assertEquals(0, progress.sample(state, probe, WORLD + 1, LIFECYCLE, true, null));
        bus.drain();
        Assert.assertEquals("世界变化必须先归零再在新窗口重计（不得携带旧代计数）",
            1, progress.getExecutedCount());

        // 生命周期 epoch 变化：归零
        progress.sample(state, probe, WORLD + 1, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals(0, progress.sample(state, probe, WORLD + 1, LIFECYCLE + 1, true, null));
        Assert.assertEquals("生命周期变化必须归零", 0, progress.getExecutedCount());

        // 预览结束（clear）：不活动必须归零且不再计数
        state.clear();
        Assert.assertEquals(0, progress.sample(state, probe, WORLD + 1, LIFECYCLE + 1, true, null));
        Assert.assertEquals("无活动代必须恒 0", 0, progress.getExecutedCount());
    }

    @Test
    public void perTickBudgetIsBoundedAndResumableAcrossTicks() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        FakeProbe probe = new FakeProbe();
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < SAMPLE_BUDGET + 40; index++) {
            ChainTarget target = new ChainTarget(index * 3, 0, 0);
            targets.add(target);
            probe.destroy(index * 3, 0, 0);
            state.addPreviewTarget(generation, target);
        }
        ChainEventBus bus = bus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        progress.install(bus);

        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals("单 tick 投递必须受预算约束", SAMPLE_BUDGET, progress.getExecutedCount());
        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals("下一 tick 必须可续扫完", SAMPLE_BUDGET + 40, progress.getExecutedCount());
    }

    @Test
    public void installDisposeCyclesDoNotAccumulateSubscriptions() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        addTargets(state, generation, 0);
        ChainEventBus bus = bus();
        ChainPreviewExecutionProgress progress = new ChainPreviewExecutionProgress();
        for (int cycle = 0; cycle < 5; cycle++) {
            progress.install(bus);
            Assert.assertTrue(progress.isSubscribed());
            progress.dispose();
            Assert.assertEquals("dispose 必须清空计数", 0, progress.getExecutedCount());
            Assert.assertTrue("dispose 后订阅关系保留（install 幂等）", progress.isSubscribed());
        }
        progress.install(bus);
        FakeProbe probe = new FakeProbe();
        probe.destroy(0, 0, 0);
        progress.sample(state, probe, WORLD, LIFECYCLE, true, null);
        bus.drain();
        Assert.assertEquals("多次 install/dispose 循环不得重复计数（订阅未累积）",
            1, progress.getExecutedCount());
    }

    @Test
    public void headerCarriesExecutedCountAndPublishesOnChange() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        ChainPreviewPresentationHeader withProgress = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, 0L, 1L, 2L, true, true, 5);
        Assert.assertEquals("header 必须透传 executedCount", 5, withProgress.getExecutedCount());
        Assert.assertTrue("header 必须透传开关位", withProgress.isExecutionProgressEnabled());

        final int[] notifications = {0};
        projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
            @Override
            public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                notifications[0]++;
            }
        });
        ChainPreviewPresentationHeader advanced = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, 0L, 1L, 2L, true, true, 6);
        Assert.assertNotSame("仅计数变化也必须发布新 revision", withProgress, advanced);
        Assert.assertEquals(6, advanced.getExecutedCount());
        Assert.assertEquals(1, notifications[0]);

        ChainPreviewPresentationHeader legacy = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, 0L, 1L, 2L, true);
        Assert.assertEquals("9 参重载必须等价于关闭进度", 0, legacy.getExecutedCount());
        Assert.assertFalse(legacy.isExecutionProgressEnabled());
    }
}
