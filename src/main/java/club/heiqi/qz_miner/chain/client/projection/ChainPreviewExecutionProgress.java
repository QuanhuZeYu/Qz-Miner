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
 * 客户端按预算采样预览目标，将已加载区块内变为空气的位置发布为观测，下一次 drain 去重计数。
 *
 * <p>计数和有界位置集随 worldIdentity、lifecycleEpoch 或 previewGeneration 变化重置。
 * 交互和替换目标按空气判据自然留在未计入状态；区块未加载时保留未知。
 * 总线订阅安装一次，dispose 关闭计数并清窗口，后续 install 复用订阅。</p>
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
