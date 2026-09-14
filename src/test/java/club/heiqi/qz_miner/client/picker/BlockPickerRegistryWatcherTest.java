package club.heiqi.qz_miner.client.picker;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 注册表失效接入：三条来源只标脏、不重建（ADR §2.1 #4/§2.4、A-08）；装配接线由源码守卫钉死。
 */
public class BlockPickerRegistryWatcherTest {

    @Test
    public void loadCompleteAndModIdMappingMarkDirtyWithoutRebuild() {
        Fixture fixture = new Fixture();
        BlockPickerRegistryWatcher watcher = fixture.watcher(0);
        fixture.source.size();
        int captures = fixture.snapshots.calls;

        watcher.onRegistrySourceChanged("fml_load_complete");
        watcher.onRegistrySourceChanged("fml_modid_mapping");

        Assert.assertTrue(fixture.source.isRegistryDirty());
        Assert.assertEquals("事件回调内不得重建（in-event page() 调用数 = 0）", captures, fixture.snapshots.calls);
        fixture.source.size();
        Assert.assertEquals("重建只发生在下一次真实读取", captures + 1, fixture.snapshots.calls);
    }

    @Test
    public void tickFallbackOnlyMarksWhenRegistryKeyCountChanges() {
        Fixture fixture = new Fixture();
        fixture.counter.value = 5;
        BlockPickerRegistryWatcher watcher = fixture.watcher(20);
        fixture.source.size();

        Assert.assertFalse("首次观测只建立基线", watcher.probeRegistryKeyCount());
        Assert.assertEquals(5, watcher.observedKeyCount());
        Assert.assertFalse(fixture.source.isRegistryDirty());

        fixture.counter.value = 5;
        Assert.assertFalse("key 数不变不标脏", watcher.probeRegistryKeyCount());
        Assert.assertFalse(fixture.source.isRegistryDirty());

        fixture.counter.value = 6;
        Assert.assertTrue("key 数变化必须标脏", watcher.probeRegistryKeyCount());
        Assert.assertTrue(fixture.source.isRegistryDirty());
    }

    @Test
    public void tickFallbackIsThrottledToTwentyEndPhaseTicks() {
        Fixture fixture = new Fixture();
        fixture.counter.value = 3;
        BlockPickerRegistryWatcher watcher = fixture.watcher(0);
        fixture.source.size();

        for (int i = 0; i < BlockPickerRegistryWatcher.FALLBACK_PROBE_INTERVAL_TICKS - 1; i++) {
            watcher.onClientTick(tick(TickEvent.Phase.END));
        }
        Assert.assertEquals("未到间隔不得探测", -1, watcher.observedKeyCount());
        watcher.onClientTick(tick(TickEvent.Phase.START));
        Assert.assertEquals("START 阶段不探测", -1, watcher.observedKeyCount());
        watcher.onClientTick(tick(TickEvent.Phase.END));
        Assert.assertEquals("第 20 个 END tick 才探测（观测到当前 key 数）", 3, watcher.observedKeyCount());

        fixture.counter.value = 4;
        for (int i = 0; i < BlockPickerRegistryWatcher.FALLBACK_PROBE_INTERVAL_TICKS; i++) {
            watcher.onClientTick(tick(TickEvent.Phase.END));
        }
        Assert.assertTrue("下一轮探测发现变化 → 标脏", fixture.source.isRegistryDirty());
    }

    @Test
    public void unavailableRegistryDoesNotFabricateInvalidation() {
        Fixture fixture = new Fixture();
        fixture.counter.value = -1;
        BlockPickerRegistryWatcher watcher = fixture.watcher(0);
        fixture.source.size();

        Assert.assertFalse(watcher.probeRegistryKeyCount());
        Assert.assertEquals(-1, watcher.observedKeyCount());
        Assert.assertFalse("无客户端注册表时不得标脏", fixture.source.isRegistryDirty());
    }

    /**
     * 装配单点 + 事件入口只标脏不重建。
     *
     * <p>事件通道本身（哪条事件走哪个入口、参数是否为 Forge Event 子类）由
     * {@code BlockPickerEventRegistrationContractTest} 的反射与产物断言覆盖，本用例不再重复；
     * 这里只钉两件结构契约：{@code ClientProxy.init} 内构造并注册监听、事件入口方法体内
     * 不得出现注册表快照重建路径（行为面的「回调不重建」已由本类前三个行为用例覆盖）。</p>
     */
    @Test
    public void clientBootstrapRegistersWatcherAndEventCallbacksOnlyMarkDirty() throws Exception {
        String proxy = JavaSourceSlices.stripCommentsIgnoringStringLiterals(JavaSourceSlices.read(
                "src/main/java/club/heiqi/qz_miner/ClientProxy.java"));
        String init = JavaSourceSlices.methodBody(proxy,
                "public void init(FMLInitializationEvent event)", "ClientProxy.init");
        JavaSourceSlices.assertBefore(init, "new BlockPickerRegistryWatcher(", "blockPickerRegistryWatcher.register()", "注册表监听必须构造后注册");

        String watcher = JavaSourceSlices.stripCommentsIgnoringStringLiterals(JavaSourceSlices.read(
                "src/main/java/club/heiqi/qz_miner/client/picker/BlockPickerRegistryWatcher.java"));
        assertDelegatesWithoutRebuild(watcher, "public void onLoadComplete(", "onLoadComplete");
        assertDelegatesWithoutRebuild(watcher, "public void onModIdMapping(", "onModIdMapping");
        assertOnlyMarksDirty(watcher, "void onRegistrySourceChanged(String reason)", "onRegistrySourceChanged");

        String tick = JavaSourceSlices.methodBody(watcher, "public void onClientTick(", "onClientTick");
        JavaSourceSlices.requireAt(tick, "onClientTick 必须只走 O(1) 兜底探测", "probeRegistryKeyCount(");
        JavaSourceSlices.requireAbsent(tick, "onClientTick 不得重建注册表快照", "BlockRegistrySnapshot");
        JavaSourceSlices.requireAbsent(tick, "onClientTick 不得触发捕获", ".capture(");
    }

    private static TickEvent.ClientTickEvent tick(TickEvent.Phase phase) {
        return new TickEvent.ClientTickEvent(phase);
    }

    /** FML 生命周期入口只允许委托标脏入口，不得自行重建。 */
    private static void assertDelegatesWithoutRebuild(String source, String signature, String label) {
        String body = JavaSourceSlices.methodBody(source, signature, label);
        JavaSourceSlices.requireAt(body, label + " 必须委托标脏入口", "onRegistrySourceChanged(");
        JavaSourceSlices.requireAbsent(body, label + " 事件回调内不得重建注册表快照", "BlockRegistrySnapshot");
        JavaSourceSlices.requireAbsent(body, label + " 事件回调内不得触发捕获", ".capture(");
    }

    /** 标脏入口只允许写脏标记。 */
    private static void assertOnlyMarksDirty(String source, String signature, String label) {
        String body = JavaSourceSlices.methodBody(source, signature, label);
        JavaSourceSlices.requireAt(body, label + " 必须标脏", "markRegistryDirty");
        JavaSourceSlices.requireAbsent(body, label + " 不得重建注册表快照", "BlockRegistrySnapshot");
        JavaSourceSlices.requireAbsent(body, label + " 不得触发捕获", ".capture(");
    }

    /** 假清单捕获桩 + 可注入 key 数探针。 */
    private static final class Fixture {
        private final CountingSnapshotSource snapshots = new CountingSnapshotSource();
        private final MutableCounter counter = new MutableCounter();
        private final BlockPickerCandidateSource source;

        private Fixture() {
            Map<String, Block> blocks = new LinkedHashMap<String, Block>();
            blocks.put("test:block", new TestBlock());
            snapshots.snapshot = BlockRegistrySnapshot.of(blocks);
            source = new BlockPickerCandidateSource(snapshots, new BlockVariantShardCache(8,
                    new BlockVariantShardCache.Materializer() {
                        @Override
                        public BlockCandidate materialize(String registry, Block block) {
                            return BlockVariantMaterializer.placeholder(registry);
                        }
                    }));
        }

        private BlockPickerRegistryWatcher watcher(int unusedCapacity) {
            return new BlockPickerRegistryWatcher(source, counter);
        }
    }

    private static final class CountingSnapshotSource implements BlockPickerCandidateSource.SnapshotSource {
        private BlockRegistrySnapshot snapshot;
        private int calls;

        @Override
        public BlockRegistrySnapshot capture() {
            calls++;
            return snapshot;
        }
    }

    private static final class MutableCounter implements BlockPickerRegistryWatcher.RegistryKeyCounter {
        private int value = -1;

        @Override
        public int count() {
            return value;
        }
    }

    /** Block(Material) 是 protected 构造。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
