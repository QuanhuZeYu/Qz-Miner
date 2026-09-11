package club.heiqi.qz_miner.client.picker;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLModIdMappingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;

/**
 * 注册表代际接入点：把「客户端 Block registry 可能变化」的三条已知来源收敛为
 * <b>O(1) 标脏</b>（不重建）。
 *
 * <p>契约出处：{@code team/P0-ADR-契约与测量.md} §2.1 #4（1.7.10 无注册表变更事件，故需新建）
 * /§2.4（标脏 + 首窗重建）；{@code team/P2-Miner-Provider-改造设计.md} §2.3 registryRevision 三来源。</p>
 *
 * <ul>
 *   <li>{@link FMLLoadCompleteEvent}：模组加载完成（首次建快照的实际触发点是首个真实读取）；</li>
 *   <li>{@link FMLModIdMappingEvent}：ID 重映射（可能在加载/重映射过程中反复出现 ⇒ 只标脏，
 *       天然合并同帧多次标脏）；</li>
 *   <li><b>tick 节流兜底</b>：每 {@value #FALLBACK_PROBE_INTERVAL_TICKS} 客户端 tick 比较
 *       {@code Block.blockRegistry.getKeys().size()}（O(1) 视图大小读取），防漏事件。</li>
 * </ul>
 *
 * <p><b>不编造事件</b>：兜底探针在注册表不可用（headless / 服务端）时返回 -1 且不标脏；
 * 三条来源都不做重建——重建只发生在候选源的下一次真实读取（ADR A-08）。</p>
 *
 * <p><b>线程</b>：FML 事件与客户端 tick 都在客户端主线程；标脏本身只写 volatile 布尔，
 * 因此即使某条 FML 事件在加载期由非客户端线程派发也不会产出错数据。</p>
 */
@SideOnly(Side.CLIENT)
public final class BlockPickerRegistryWatcher {

    /** 兜底探针间隔（tick）：ADR §2.1 #4 写死的 20 tick。 */
    public static final int FALLBACK_PROBE_INTERVAL_TICKS = 20;

    /** 注册表 key 数探针（生产 = {@code Block.blockRegistry.getKeys().size()}；测试可注入）。 */
    public interface RegistryKeyCounter {

        /** @return 当前注册表 key 数；不可用返回 -1 */
        int count();
    }

    private final BlockPickerCandidateSource source;
    private final RegistryKeyCounter counter;
    private int ticks;
    private int observedKeyCount = -1;

    /**
     * 生产构造：探针直读客户端 Block registry。
     *
     * @param source 候选源（非 null）
     */
    public BlockPickerRegistryWatcher(BlockPickerCandidateSource source) {
        this(source, new RegistryKeyCounter() {
            @Override
            public int count() {
                try {
                    return Block.blockRegistry.getKeys().size();
                } catch (RuntimeException e) {
                    return -1;
                } catch (LinkageError e) {
                    return -1;
                }
            }
        });
    }

    /**
     * 可注入探针（测试用）。
     *
     * @param source  候选源
     * @param counter 注册表 key 数探针
     */
    public BlockPickerRegistryWatcher(BlockPickerCandidateSource source, RegistryKeyCounter counter) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (counter == null) {
            throw new IllegalArgumentException("counter must not be null");
        }
        this.source = source;
        this.counter = counter;
    }

    /** 注册到 FML 事件总线（客户端装配期调用一次）。 */
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 模组加载完成：标脏（首次建快照延后到首个真实读取）。
     *
     * @param event FML 加载完成事件
     */
    @SubscribeEvent
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        onRegistrySourceChanged("fml_load_complete");
    }

    /**
     * ID 重映射：标脏（不重建；事件可能反复出现）。
     *
     * @param event FML ID 重映射事件
     */
    @SubscribeEvent
    public void onModIdMapping(FMLModIdMappingEvent event) {
        onRegistrySourceChanged("fml_modid_mapping");
    }

    /**
     * 客户端 tick 末尾：每 {@value #FALLBACK_PROBE_INTERVAL_TICKS} tick 一次 O(1) 兜底探测。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        ticks++;
        if (ticks < FALLBACK_PROBE_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;
        probeRegistryKeyCount();
    }

    /** 事件委托入口（包内可见：单测不构造 FML 事件即可驱动）。 */
    void onRegistrySourceChanged(String reason) {
        source.markRegistryDirty(reason);
    }

    /**
     * 兜底探测：key 数变化才标脏。
     *
     * @return 本次是否标脏
     */
    boolean probeRegistryKeyCount() {
        int count = counter.count();
        if (count < 0) {
            return false;
        }
        boolean changed = observedKeyCount >= 0 && count != observedKeyCount;
        observedKeyCount = count;
        if (changed) {
            MyMod.LOG.debug("[BlockPicker] registry key count changed to {}; marking candidate source dirty", count);
            source.markRegistryDirty("tick_fallback");
            return true;
        }
        return false;
    }

    /** @return 上次观测到的 key 数（-1 = 尚未成功观测；诊断/测试探针） */
    int observedKeyCount() {
        return observedKeyCount;
    }
}
