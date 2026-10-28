package club.heiqi.qz_miner.chain.watchdog;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionAdvanced;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionDeferred;
import club.heiqi.qz_miner.chain.eventbus.event.PlanProgress;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 主线程追踪活跃任务的阶段与工作进度，超时后发布 WatchdogTimeout 交状态机和执行桥收尾。
 * 阶段广播登记或更新活跃条目，IDLE 移除匹配轮次；真实工作事件刷新进度时间。
 * 共享 deadline 延期在临近超时时提供一次有界宽限，阈值读取运行时配置。
 */
public class ChainWatchdog {

    private static final long NO_DEFERRED_TICK = Long.MIN_VALUE;

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;
    /**
     * per-player 活跃追踪镜像：key=玩家 UUID，value=该玩家最后一次推进时的代际与 tick。
     *
     * <p>守跨线程只经事件总线：订阅者仅主线程 drain/ServerTickEvent 调用，单线程假定无需自锁（对齐 onLifecycleCleanup 契约）。
     * 非线程安全容器 {@link HashMap} 在单线程契约下安全。</p>
     */
    private final Map<UUID, WatchEntry> activePlayers = new HashMap<UUID, WatchEntry>();

    /** 构造时订阅阶段与工作事件，FML tick 注册由 bootstrap 单独接入。 */
    public ChainWatchdog(ChainEventBus bus) {
        this.bus = bus;
        bus.subscribe(ChainPhaseChanged.class, this::onPhaseChanged);
        // 工作推进和预算延期分别刷新进度与宽限状态。
        bus.subscribe(PlanProgress.class, this::onProgress);
        bus.subscribe(ExecutionAdvanced.class, this::onProgress);
        bus.subscribe(ExecutionDeferred.class, this::onDeferred);
    }

    /** 初始化后注册 FML tick 监听，与 drainer 共享服务端主线程。 */
    public void bootstrap() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /** 阶段广播维护活跃条目：IDLE 移除匹配轮次，ARMED 保留现状，其余阶段登记当前时间。 */
    private void onPhaseChanged(ChainPhaseChanged event) {
        UUID uuid = event.getPlayerUUID();
        ChainPhase to = event.getToPhase();
        if (to == ChainPhase.IDLE) {
            WatchEntry existing = activePlayers.get(uuid);
            if (existing != null && existing.generation == event.getGeneration()
                    && existing.serverRoundId == event.getServerRoundId()) {
                activePlayers.remove(uuid);
            }
            return;
        }
        // ARMED 等待输入，保留已有条目。
        if (to == ChainPhase.ARMED) {
            return;
        }
        // 活跃阶段登记代际、轮次、tick 与单调纳秒戳。
        activePlayers.put(uuid, new WatchEntry(event.getGeneration(), event.getServerRoundId(), event.getServerTick(),
                ChainTickSource.nowNanos()));
    }

    /** 仅刷新现有匹配代际和轮次的工作进度，时间戳保持单调。 */
    private void onProgress(ChainEvent event) {
        UUID uuid = event.getPlayerUUID();
        int eventGen = event.getGeneration();
        WatchEntry existing = activePlayers.get(uuid);
        if (existing == null) {
            // 工作事件仅刷新已登记条目。
            return;
        }
        if (existing.generation != eventGen || existing.serverRoundId != event.getServerRoundId()) {
            // 仅匹配代际与轮次的事件可刷新当前条目。
            return;
        }
        if (event.getServerTick() < existing.lastProgressTick) {
            return;
        }
        // 同代际推进刷新：用事件的 serverTick/timestampNanos 重建不可变条目
        activePlayers.put(uuid, new WatchEntry(existing.generation, existing.serverRoundId,
                event.getServerTick(), event.getTimestampNanos()));
    }

    /** 临近超时时记录首次共享预算延期，提供一个阈值长度的有界宽限窗口。 */
    private void onDeferred(ExecutionDeferred event) {
        UUID uuid = event.getPlayerUUID();
        WatchEntry existing = activePlayers.get(uuid);
        if (existing == null
                || existing.generation != event.getGeneration()
                || existing.serverRoundId != event.getServerRoundId()
                || event.getServerTick() < existing.lastProgressTick) {
            return;
        }
        long deferredSinceProgress = event.getServerTick() - existing.lastProgressTick;
        boolean progressNearTimeout = deferredSinceProgress >= Math.max(0, Config.chainWatchdogTimeoutTicks - 1);
        long firstDeferredTick = existing.firstDeferredTick;
        if (firstDeferredTick == NO_DEFERRED_TICK && progressNearTimeout) {
            firstDeferredTick = event.getServerTick();
        }
        long lastDeferredTick = Math.max(existing.lastDeferredTick, event.getServerTick());
        activePlayers.put(uuid, new WatchEntry(existing.generation, existing.serverRoundId,
                existing.lastProgressTick, existing.lastNanos, firstDeferredTick, lastDeferredTick));
    }

    /** 在服务端 tick START 取当前 tick，交给超时判定入口。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        long currentTick = ChainTickSource.currentServerTick();
        checkTimeouts(currentTick);
    }

    /**
     * 核心超时检查逻辑（包级，供单测注入 tick 绕过 ChainTickSource）。
     *
     * <p>P1-1 收口：原 {@link #onServerTick} 内 {@link ChainTickSource#currentServerTick()} 纯 JVM 返回 -1，
     * 超时判定（elapsed &gt;= threshold 分支）完全不可达，3 个声称场景（超时触发/推进刷新不触发/
     * 镜像立即移除）零真测覆盖。提取本方法后单测可注入 forcedTick 直接驱动超时路径。</p>
     *
     * <p>语义不变（守协作式取消）：运行时仍由 {@link #onServerTick} 经 ChainTickSource 取 tick 后调用本方法，
     * 单测注入的 tick 只用于驱动逻辑分支验证，不影响生产路径。F.4 C1 镜像立即移除保持不变。</p>
     *
     * @param currentTick 当前服务端 tick（单测可注入；运行时来自 ChainTickSource）
     */
    void checkTimeouts(long currentTick) {
        if (currentTick < 0) {
            // 无 Forge 运行时（如纯 JVM 单测）：跳过，不误触发
            return;
        }
        int threshold = Config.chainWatchdogTimeoutTicks;
        // 遍历活跃条目快照，逐项发布超时并移除原条目。
        // 实际单线程契约下可直接遍历，这里为可读性显式快照
        UUID[] keys = activePlayers.keySet().toArray(new UUID[0]);
        for (UUID uuid : keys) {
            WatchEntry entry = activePlayers.get(uuid);
            if (entry == null) {
                continue;
            }
            long elapsed = currentTick - entry.lastProgressTick;
            long deferredElapsed = currentTick - entry.firstDeferredTick;
            long sinceLastDeferral = currentTick - entry.lastDeferredTick;
            boolean boundedDeferralGrace = entry.firstDeferredTick != NO_DEFERRED_TICK
                    && deferredElapsed >= 0L && deferredElapsed <= threshold
                    && sinceLastDeferral >= 0L && sinceLastDeferral <= 1L;
            // 推进事件在 execution/planning 回调发布后要到下一 Tick drain 才可见，阈值边界留一 Tick。
            if (elapsed > threshold && !boundedDeferralGrace) {
                long nanos = ChainTickSource.nowNanos();
                // 以当前单调纳秒戳减条目上次时间，记录真实间隔。
                long elapsedNanos = Math.max(0L, nanos - entry.lastNanos);
                bus.publish(new WatchdogTimeout(uuid, entry.serverRoundId, entry.generation,
                        currentTick, nanos, elapsedNanos));
                // 发布后移除所属条目，后续 tick 仅追踪新登记的工作。
                activePlayers.remove(uuid);
                MyMod.LOG.warn("[ChainWatchdog] timeout player={} gen={} elapsedTick={} threshold={}; publish WatchdogTimeout",
                        uuid, Integer.valueOf(entry.generation), Long.valueOf(elapsed), Integer.valueOf(threshold));
            }
        }
    }

    /**
     * per-player 追踪条目值对象（包级可见供单测断言）。
     */
    static final class WatchEntry {
        /** 该玩家最后一次推进时的代际（新一代 ChainPhaseChanged 覆盖）。 */
        final int generation;
        /** 该玩家最后一次推进时的不可变服务端轮次关联。 */
        final long serverRoundId;
        /** 该玩家最后一次推进时的服务端 tick（用于判定无推进时长）。 */
        final long lastProgressTick;
        /** 该玩家最后一次进态时记录的纳秒戳（P2-2：checkTimeouts 据此算真 elapsedNanos delta）。 */
        final long lastNanos;
        /** 当前连续 scheduler deferral 的起点；真实推进会重置。 */
        final long firstDeferredTick;
        /** 最近一次 scheduler deferral；用于确认宽限仍连续。 */
        final long lastDeferredTick;

        WatchEntry(int generation, long serverRoundId, long lastProgressTick, long lastNanos) {
            this(generation, serverRoundId, lastProgressTick, lastNanos,
                    NO_DEFERRED_TICK, NO_DEFERRED_TICK);
        }

        WatchEntry(int generation, long serverRoundId, long lastProgressTick, long lastNanos,
                long firstDeferredTick, long lastDeferredTick) {
            this.generation = generation;
            this.serverRoundId = serverRoundId;
            this.lastProgressTick = lastProgressTick;
            this.lastNanos = lastNanos;
            this.firstDeferredTick = firstDeferredTick;
            this.lastDeferredTick = lastDeferredTick;
        }
    }

    // ============================ package-private getter 供单测 ============================

    /**
     * @param playerUUID 玩家 UUID
     * @return 镜像内该玩家条目（仅供同包单测读），无则 null
     */
    WatchEntry getEntry(UUID playerUUID) {
        return activePlayers.get(playerUUID);
    }

    /**
     * @return 镜像当前条目数（仅供同包单测断言）
     */
    int activeCount() {
        return activePlayers.size();
    }
}
