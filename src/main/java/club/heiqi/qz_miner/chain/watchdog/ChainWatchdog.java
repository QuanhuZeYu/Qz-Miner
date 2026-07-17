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
import club.heiqi.qz_miner.chain.eventbus.event.PlanProgress;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 连锁看门狗：异常兜底，N tick 无状态推进则 publish {@link WatchdogTimeout} 协作式回 IDLE（T10）。
 *
 * <h3>三路回 IDLE 中的角色（阶段7 收口）</h3>
 * <p>本类是<b>异常兜底路径</b>，与执行完成快速路径（{@code ChainExecutionEventBridge}）、
 * 生命周期清理（{@code ChainLifecycleBridge}）三路并存：</p>
 * <ul>
 *   <li>正常连锁完成走执行桥快速路径，不触发本类。</li>
 *   <li>异常卡死（worker 卡住、规划/执行卡在某态）→ 本类 N tick 无推进触发 WatchdogTimeout
 *       → 状态机 T10 回 IDLE。</li>
 *   <li>玩家登出/重生/切维度走生命周期桥强制清理（forced=true 豁免 genCheck）。</li>
 * </ul>
 *
 * <h3>推进信号源（奠基事实1）</h3>
 * <p>本类订阅 {@link ChainPhaseChanged}（阶段6 G1 加入，状态机 {@code applyTransition} 每次转移后 publish）
 * 建 per-player 活跃镜像。<b>不</b>自建钩子、<b>不</b>读状态机字段（守 I10 只读广播）。</p>
 *
 * <p>另订阅 {@link PlanProgress}（worker 分片 yield 时 publish）与 {@link ExecutionAdvanced}
 * （每 tick 破坏后 publish）作为 PLANNING/RUNNING 阶段的真实工作推进信号——
 * 因为 PLANNING/RUNNING 两次状态机转移之间无 ChainPhaseChanged 广播，长规划/长执行会被
 * 「N tick 无状态推进」误判卡死。补订阅这两路后，看门狗推进信号对齐「真实工作推进」语义
 * 而非「状态机转移」，根治误杀。新条目仍归 onPhaseChanged 的 T4 进 PLANNING 管，
 * 本订阅只刷新既有条目（守信号源分工）。</p>
 *
 * <h3>F.3 ARMED 不计时（A-armed-skip）</h3>
 * <p>遵循转移表 T10 现状：ARMED 态不纳入看门狗计时。ARMED 回收靠玩家松键 T2 或生命周期清理 T9，
 * 不靠看门狗。故 to=ARMED 的 ChainPhaseChanged 不新增镜像条目；已有条目保持（避免误删）。</p>
 *
 * <h3>F.4 镜像立即移除（C1）</h3>
 * <p>publish WatchdogTimeout 后立即从镜像移除该条目，避免后续 tick 重复 publish（防止看门狗风暴）。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本类只 publish/remove 自己容器，绝不切 phase、不碰 worker、不写世界。</li>
 *   <li><b>I2</b>：协作式取消——只 publish WatchdogTimeout，不 Future.cancel、不强杀 worker、
 *       不绕 endStage。worker 协作式停到安全边界，状态机 T10 自行切态。</li>
 *   <li><b>I10</b>：本类不写 slots，状态机是唯一写权威。</li>
 * </ul>
 *
 * <h3>线程契约</h3>
 * <p>{@link #onPhaseChanged} 由主线程 drain 调用，{@link #onServerTick} 由主线程 ServerTickEvent.START 调用，
 * 契约上单线程串行，{@link HashMap} 无需加锁（对齐 {@code ChainStateMachine.onLifecycleCleanup} 契约）。</p>
 */
public class ChainWatchdog {

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;
    /**
     * per-player 活跃追踪镜像：key=玩家 UUID，value=该玩家最后一次推进时的代际与 tick。
     *
     * <p>守 I4：订阅者仅主线程 drain/ServerTickEvent 调用，单线程假定无需自锁（对齐 onLifecycleCleanup 契约）。
     * 非线程安全容器 {@link HashMap} 在单线程契约下安全。</p>
     */
    private final Map<UUID, WatchEntry> activePlayers = new HashMap<UUID, WatchEntry>();

    /**
     * 构造看门狗并订阅 {@link ChainPhaseChanged}（不注册 FML bus，留 {@link #bootstrap()} 显式触发）。
     *
     * <p>构造器纯净可测：只调用 {@link ChainEventBus#subscribe}（操作 ConcurrentHashMap），
     * 不触碰 {@code FMLCommonHandler.instance()}（无 Forge 运行时环境会 NPE），
     * 对齐 {@code ChainStateProjectionBridge} 纯净可测模式。</p>
     *
     * @param bus 事件总线
     */
    public ChainWatchdog(ChainEventBus bus) {
        this.bus = bus;
        bus.subscribe(ChainPhaseChanged.class, this::onPhaseChanged);
        // B 方案：补订阅真实工作推进信号，根治 PLANNING/RUNNING 阶段长任务误判卡死
        bus.subscribe(PlanProgress.class, this::onProgress);
        bus.subscribe(ExecutionAdvanced.class, this::onProgress);
    }

    /**
     * 向 FML 事件总线注册 ServerTickEvent 监听。
     *
     * <p>由 {@link MyMod#init} 在 ChainEventBusDrainer.bootstrap 之后调用（确保订阅顺序）。
     * 提取独立方法是为了让单测构造时不触发 {@code FMLCommonHandler.instance()}（无 Forge 运行时环境会 NPE）。</p>
     */
    public void bootstrap() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 进态广播订阅者：维护 per-player 活跃镜像。
     *
     * <p>分支：</p>
     * <ul>
     *   <li>{@code to != IDLE && to != ARMED}（F.3 A-armed-skip）→ put 新条目（覆盖旧 gen，新一代接管）。</li>
     *   <li>{@code to == IDLE} → remove（回 IDLE 结束追踪）。</li>
     *   <li>{@code to == ARMED} → 不新增条目（A-armed-skip）；已有条目保持（避免误删玩家武装前的残留）。</li>
     * </ul>
     *
     * @param event 进态广播事件
     */
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
        // F.3 A-armed-skip：ARMED 不启动计时（遵循 T10 现状），已有条目保持
        if (to == ChainPhase.ARMED) {
            return;
        }
        // to ∈ {PLANNING, RUNNING, FINISHING}：新增/刷新追踪条目（新一代覆盖旧 gen）
        // P2-2：同时记录 nowNanos 作为 lastNanos，checkTimeouts 据此算真 elapsedNanos delta
        activePlayers.put(uuid, new WatchEntry(event.getGeneration(), event.getServerRoundId(), event.getServerTick(),
                ChainTickSource.nowNanos()));
    }

    /**
     * 真实工作推进信号订阅者：PLANNING 阶段的 {@link PlanProgress} 与 RUNNING 阶段的
     * {@link ExecutionAdvanced} 共用本方法刷新既有条目（B 方案）。
     *
     * <p>仅刷新既有条目，不新增条目——新条目仍归 {@link #onPhaseChanged} 的 T4 进 PLANNING 管
     * （守信号源分工：进态广播建条目 + 代际覆盖，工作推进信号只续命）。</p>
     *
     * <p>分支：</p>
     * <ul>
     *   <li>{@code existing == null} → return（无条目说明未在 PLANNING/RUNNING/FINISHING 追踪，
     *       不为 ARMED/IDLE 期迟到的事件误建条目）。</li>
      *   <li>{@code existing.generation/serverRoundId} 不匹配 → return（陈旧轮次防护：旧代际或旧轮迟到的
      *       PlanProgress/ExecutionAdvanced 不误刷新新条目，避免给已回 IDLE 后的新一代「续命」
     *       掩盖真卡死——世代隔离）。</li>
     *   <li>否则覆盖刷新：用事件的 serverTick 与 timestampNanos 重建不可变 WatchEntry
     *       （HashMap 单线程契约下安全，守 :52 注释；WatchEntry 保持不可变，最省改动）。</li>
     * </ul>
     *
     * @param event 工作推进信号（PlanProgress 或 ExecutionAdvanced）
     */
    private void onProgress(ChainEvent event) {
        UUID uuid = event.getPlayerUUID();
        int eventGen = event.getGeneration();
        WatchEntry existing = activePlayers.get(uuid);
        if (existing == null) {
            // 无条目：不为 ARMED/IDLE 期迟到的事件误建条目（守信号源分工）
            return;
        }
        if (existing.generation != eventGen || existing.serverRoundId != event.getServerRoundId()) {
            // 陈旧代际或轮次迟到事件不误刷新新条目
            return;
        }
        // 同代际推进刷新：用事件的 serverTick/timestampNanos 重建不可变条目
        activePlayers.put(uuid, new WatchEntry(existing.generation, existing.serverRoundId,
                event.getServerTick(), event.getTimestampNanos()));
    }

    /**
     * 服务端 tick 回调：取当前服务端 tick 后委托 {@link #checkTimeouts} 核心逻辑。
     *
     * <p>仅 START 阶段处理。{@link ChainTickSource#currentServerTick()} 在纯 JVM 单测环境返回 -1，
     * 超时判定不可达，故核心逻辑提取到 {@link #checkTimeouts} 供单测注入 tick 绕过（P1-1 收口）。</p>
     *
     * @param event 服务端 tick 事件
     */
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
     * <p>语义不变（守 I2）：运行时仍由 {@link #onServerTick} 经 ChainTickSource 取 tick 后调用本方法，
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
        // snapshot 避免遍历期 ConcurrentModification（put/remove 在同线程 drain 后才发生，但防御性快照更稳）
        // 实际单线程契约下可直接遍历，这里为可读性显式快照
        UUID[] keys = activePlayers.keySet().toArray(new UUID[0]);
        for (UUID uuid : keys) {
            WatchEntry entry = activePlayers.get(uuid);
            if (entry == null) {
                continue;
            }
            long elapsed = currentTick - entry.lastProgressTick;
            if (elapsed >= threshold) {
                long nanos = ChainTickSource.nowNanos();
                // P2-2：真实 elapsedNanos delta = nowNanos - 进态时记录的 lastNanos（不再占位）
                long elapsedNanos = Math.max(0L, nanos - entry.lastNanos);
                bus.publish(new WatchdogTimeout(uuid, entry.serverRoundId, entry.generation,
                        currentTick, nanos, elapsedNanos));
                // F.4 C1：publish 后立即移除，避免后续 tick 重复 publish（看门狗风暴防护）
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

        WatchEntry(int generation, long serverRoundId, long lastProgressTick, long lastNanos) {
            this.generation = generation;
            this.serverRoundId = serverRoundId;
            this.lastProgressTick = lastProgressTick;
            this.lastNanos = lastNanos;
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
