package club.heiqi.qz_miner.chain.client.projection;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * B5.2 执行进度（客户端世界采样）· 投影侧数据源。
 *
 * <p><b>数据链路</b>（零协议改动、可回退；off 档零开销）：</p>
 * <ol>
 *   <li><b>采样</b>：客户端主线程每 tick 按 {@link #SAMPLE_BUDGET_PER_TICK} 预算扫描当前预览代目标坐标；
 *       命中「区块已加载且方块已消失」的位置即向客户端总线 {@link ChainEventBus#publish} 一条
 *       {@link BlockBreakObserved} 观测事件（观测语义，不是玩家动作回放：seedBlock=null、sideHit=0，
 *       维度取被采样世界，uuid 为本地玩家）。同一位置在后续 pass 会重复投递，由计数侧去重。</li>
 *   <li><b>计数</b>：订阅 {@code MyMod.clientChainEventBus} 的 {@link BlockBreakObserved}，由既有
 *       {@code ClientChainEventBusDrainer} 在 {@code ClientTickEvent.START} 主线程 drain 时回调；
 *       只有「属于当前预览代目标集合」的位置才计入，同一位置只计一次。</li>
 * </ol>
 *
 * <h3>身份窗口与单调性（沿用 header 六维失效口径）</h3>
 * <p>{@code executedCount} 在同一身份窗口（worldIdentity / lifecycleEpoch / previewGeneration）内单调不减；
 * 任一身位变化（换代 / 世界切换 / 断线重连 lifecycle epoch 自增）即清空计数与去重集合，从头计。
 * 预览不活动或开关关闭时恒 0。</p>
 *
 * <h3>内存有界</h3>
 * <p>去重集合只保存「本代已计入的位置」，容量上界 = 本代目标数（已由 B4.2 容量上限与
 * {@code clientPreviewMaxTargetsHardCap} 约束），随身份窗口变化清空；另设 {@link #MAX_COUNTED_POSITIONS}
 * 防御硬顶，达到后停止计入（宁可少计，不可无界增长）。采样侧不复制目标集合，只持有一个 pass 的
 * 持久链迭代器（一次引用）。</p>
 *
 * <h3>采样口径（如实标注）</h3>
 * <p>只有「目标位置区块已加载 + 方块变为空气/air 材质」才算已执行 ⇒ 破坏/采掘类模式准确；
 * 交互类（不破坏方块）与替换类（方块变成另一方块而非空气）目标不计入；区块未加载时不可判定，
 * 保守不计入。</p>
 *
 * <h3>订阅唯一性</h3>
 * <p>{@link ChainEventBus} 无退订 API，故 {@link #install(ChainEventBus)} 只订阅一次且 {@code subscribed}
 * 永不复位（重复 install/dispose 不累积回调）；生命周期开关走 {@link #dispose()} 的 active 位，
 * 配置开关走每次采样的 enabled 入参。</p>
 */
@SideOnly(Side.CLIENT)
public final class ChainPreviewExecutionProgress {

    /** 每 tick 世界采样预算（目标坐标数）：主线程每 tick 最多做这么多次方块查询。 */
    static final int SAMPLE_BUDGET_PER_TICK = 256;

    /** 去重集合防御硬顶：正常路径 ≤ 本代目标数；达到硬顶后停止计入。 */
    static final int MAX_COUNTED_POSITIONS = 1 << 16;

    /** 世界采样探针：只读判定目标位置是否已被破坏（生产实现见 ticker，测试可注入假探针）。 */
    public interface WorldProbe {

        /**
         * @param x 坐标 X
         * @param y 坐标 Y
         * @param z 坐标 Z
         * @return true 表示该位置所在区块已加载且方块已消失（被破坏）；未加载/不可判定必须返回 false
         */
        boolean isDestroyed(int x, int y, int z);

        /** @return 被采样世界的维度 id（事件的诊断字段；无世界时 0） */
        int getDimensionId();
    }

    /** 已计入位置（打包坐标）；容量上界见类注释。 */
    private final Set<Long> countedPositions = new HashSet<Long>();

    private ChainEventBus bus;
    /** 是否已在总线上注册唯一订阅（一旦注册永不复位；总线无退订 API）。 */
    private boolean subscribed;
    /** 生命周期开关：install 置 true，dispose 置 false。 */
    private boolean lifecycleActive;
    /** 本次采样是否可计数：由每 tick 采样的 enabled/活动代/探针共同决定。 */
    private boolean countingEnabled;
    private int executedCount;
    private long windowWorldIdentity = Long.MIN_VALUE;
    private long windowLifecycleEpoch = Long.MIN_VALUE;
    private int windowGeneration = Integer.MIN_VALUE;
    private ChainPreviewState windowState;
    private Iterator<ChainTarget> passIterator;
    /** 回调实到次数（测试/诊断：用于证明订阅未重复累积）。 */
    private int observedEventCount;

    /**
     * 安装：订阅客户端总线的破坏观测事件（客户端主线程调用一次）。
     *
     * <p>幂等：重复调用（含 dispose 之后重新 install）不会重复订阅，只翻转生命周期开关。</p>
     *
     * @param clientBus 客户端事件总线 {@code MyMod.clientChainEventBus}；null 视为未接线（只置开关不订阅）
     */
    public void install(ChainEventBus clientBus) {
        if (clientBus != null) {
            bus = clientBus;
        }
        if (!subscribed && bus != null) {
            subscribed = true;
            bus.subscribe(BlockBreakObserved.class, this::onBlockBreakObserved);
        }
        lifecycleActive = true;
    }

    /**
     * 生命周期清理（断线/世界卸载/测试）：停止计数并清空本代进度。
     *
     * <p>订阅关系保留（总线无退订 API，且 install 幂等），后续 install 只重新打开开关。</p>
     */
    public void dispose() {
        lifecycleActive = false;
        resetWindow();
    }

    /** @return 是否已在客户端总线上注册订阅（订阅唯一性守卫） */
    public boolean isSubscribed() {
        return subscribed;
    }

    /** @return 当前身份窗口内已计入的目标位置数（同代单调不减；未启用/无活动代时为 0） */
    public int getExecutedCount() {
        return executedCount;
    }

    /** @return 去重集合当前大小（诊断/测试：正常 ≤ 本代目标数） */
    int getCountedPositionCount() {
        return countedPositions.size();
    }

    /** @return 破坏观测回调实到次数（诊断/测试：证明订阅未重复累积） */
    int getObservedEventCount() {
        return observedEventCount;
    }

    /**
     * 每 tick 采样入口（客户端主线程）。
     *
     * <p>流程：身份窗口变化即重置 → 开关/活动代判定 → 按预算扫描并投递观测事件。计数在
     * 下一次 drain 回调中完成，故本方法返回的是「上一次 drain 之后的计数」。</p>
     *
     * @param state           当前预览状态（只读；null 视为无代）
     * @param probe           世界采样探针（null = 不采样；关闭档与无世界时传 null）
     * @param worldIdentity   世界身份（六维之一）
     * @param lifecycleEpoch  生命周期 epoch（六维之一）
     * @param enabled         配置开关 {@code clientPreviewExecutionProgress}
     * @param playerUUID      本地玩家 UUID（事件诊断字段；允许 null）
     * @return 采样后的已执行计数
     */
    public int sample(ChainPreviewState state, WorldProbe probe, long worldIdentity, long lifecycleEpoch,
            boolean enabled, UUID playerUUID) {
        int generation = state == null ? Integer.MIN_VALUE : state.getGeneration();
        if (windowWorldIdentity != worldIdentity || windowLifecycleEpoch != lifecycleEpoch
                || windowGeneration != generation) {
            resetWindow();
            windowWorldIdentity = worldIdentity;
            windowLifecycleEpoch = lifecycleEpoch;
            windowGeneration = generation;
        }
        windowState = state;
        boolean samplable = lifecycleActive && subscribed && enabled && state != null && state.isActive()
            && probe != null;
        countingEnabled = samplable;
        if (!samplable) {
            clearCount();
            return 0;
        }
        scan(probe, playerUUID, generation);
        return executedCount;
    }

    /** 按预算推进一个采样 pass：预算耗尽即停，下一 tick 接着扫（pass 结束后重新捕获快照）。 */
    private void scan(WorldProbe probe, UUID playerUUID, int generation) {
        int budget = SAMPLE_BUDGET_PER_TICK;
        while (budget > 0) {
            if (passIterator == null) {
                ChainPreviewState state = windowState;
                if (state == null) {
                    return;
                }
                passIterator = state.captureRenderSnapshot().getTargets().iterator();
            }
            if (!passIterator.hasNext()) {
                passIterator = null;
                return;
            }
            ChainTarget target = passIterator.next();
            budget--;
            if (target == null) {
                continue;
            }
            if (!probe.isDestroyed(target.getX(), target.getY(), target.getZ())) {
                continue;
            }
            // 已计入位置不再重复投递（纯流量优化；计数去重仍是唯一口径——事件可能尚未 drain）。
            if (countedPositions.contains(packPosition(target.getX(), target.getY(), target.getZ()))) {
                continue;
            }
            publishObservation(target, playerUUID, generation, probe.getDimensionId());
        }
    }

    private void publishObservation(ChainTarget target, UUID playerUUID, int generation, int dimensionId) {
        ChainEventBus activeBus = bus;
        if (activeBus == null) {
            return;
        }
        activeBus.publish(new BlockBreakObserved(
            playerUUID,
            generation,
            ChainTickSource.currentServerTick(),
            ChainTickSource.nowNanos(),
            target.getX(),
            target.getY(),
            target.getZ(),
            dimensionId,
            0,
            null,
            0));
    }

    /**
     * 破坏观测回调（clientChainEventBus.drain 内，客户端主线程）。
     *
     * <p>三层过滤：生命周期/开关窗口 → 代际匹配 → 「属于本代目标集合」；随后以位置集合去重，
     * 同一位置重复观测只计一次。</p>
     */
    private void onBlockBreakObserved(BlockBreakObserved event) {
        if (!subscribed || !lifecycleActive || !countingEnabled || event == null) {
            return;
        }
        observedEventCount++;
        ChainPreviewState state = windowState;
        if (state == null || !state.isActive() || state.getGeneration() != windowGeneration) {
            return;
        }
        if (!state.containsPreviewTarget(new ChainTarget(event.getX(), event.getY(), event.getZ()))) {
            return;
        }
        if (countedPositions.size() >= MAX_COUNTED_POSITIONS) {
            return;
        }
        if (!countedPositions.add(packPosition(event.getX(), event.getY(), event.getZ()))) {
            return;
        }
        executedCount++;
    }

    /** 位置打包：x/z 各 26 位、y 12 位（MC 高度区间内唯一）。 */
    private static long packPosition(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    /** 清空身份窗口（计数 + 去重集合 + 采样游标）。 */
    private void resetWindow() {
        windowWorldIdentity = Long.MIN_VALUE;
        windowLifecycleEpoch = Long.MIN_VALUE;
        windowGeneration = Integer.MIN_VALUE;
        windowState = null;
        countingEnabled = false;
        clearCount();
    }

    private void clearCount() {
        executedCount = 0;
        countedPositions.clear();
        passIterator = null;
    }
}
