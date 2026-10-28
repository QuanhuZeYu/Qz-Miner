package club.heiqi.qz_miner.chain.statemachine;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LeftClickObserved;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.ModeSwitched;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.eventbus.event.RightClickObserved;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;

/**
 * 按玩家维护 phase、generation 与规划授权，统一应用合法状态转移。
 *
 * <p>所有 handler 由主线程 drain 串行调用，worker 经事件总线发布结果。
 * 输入按当前阶段处理，派生事件按代际筛选；非法转移记录诊断后结束处理。
 * 进入 PLANNING 时冻结代际和轮次并发布 PlanStarted，转移完成后发布阶段投影。
 * 收尾先广播 IDLE 供各消费者结算，再按持续按键电平恢复 ARMED。</p>
 */
public class ChainStateMachine {

    /** 注入的事件总线。 */
    private final ChainEventBus bus;
    /**
     * per-player 状态槽容器：{@code phase}/{@code generation} 唯一写权威（守唯一写权威）。
     *
     * <p>守跨线程只经事件总线：drain 单线程串行调用 handler，{@link HashMap} 无需加锁。
     * 容器内嵌值对象 {@link PlayerPhaseSlot}，{@link #applyTransition} 是唯一写点。</p>
     */
    private final Map<UUID, PlayerPhaseSlot> slots = new HashMap<UUID, PlayerPhaseSlot>();

    /**
     * 构造状态机并订阅驱动事件。
     *
     * @param bus 注入的事件总线
     */
    public ChainStateMachine(ChainEventBus bus) {
        this.bus = bus;
        subscribe();
    }

    /** 订阅输入与终局事件；规划开始和阶段变化由转移完成后广播。 */
    private void subscribe() {
        // 输入事件（豁免代际判定）
        bus.subscribe(ChainKeyPressed.class, this::onChainKeyPressed);
        bus.subscribe(BlockBreakObserved.class, this::onBlockBreakObserved);
        bus.subscribe(RightClickObserved.class, this::onRightClickObserved);
        bus.subscribe(LeftClickObserved.class, this::onLeftClickObserved);
        bus.subscribe(ModeSwitched.class, this::onModeSwitched);
        // 派生事件（需代际陈旧判定）
        bus.subscribe(PlanCompleted.class, this::onPlanCompleted);
        bus.subscribe(PlanCancelled.class, this::onPlanCancelled);
        bus.subscribe(ExecutionFinished.class, this::onExecutionFinished);
        bus.subscribe(WatchdogTimeout.class, this::onWatchdogTimeout);
        bus.subscribe(LifecycleCleanup.class, this::onLifecycleCleanup);
    }

    // ============================ 输入事件 handler（豁免代际判定） ============================

    /**
     * 连锁按键事件：IDLE↔ARMED 武装/解除。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 按键事件
     */
    private void onChainKeyPressed(ChainKeyPressed event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // 先更新持续授权电平，供当前转移与收尾再武装共同读取。
        slot.keyDown = event.isPressed();
        ChainPhase to = null;
        if (event.isPressed()) {
            // T1: IDLE → ARMED
            if (slot.phase == ChainPhase.IDLE) {
                to = ChainPhase.ARMED;
            }
        } else {
            // T2: ARMED → IDLE
            if (slot.phase == ChainPhase.ARMED) {
                to = ChainPhase.IDLE;
            }
        }
        if (to != null) {
            applyTransition(slot, slot.phase, to, event, slot.generation);
        } else {
            // 重复或活跃阶段的电平更新按幂等输入记录。
            MyMod.LOG.debug("[ChainStateMachine] key level {} in phase={} player={}",
                    event.isPressed() ? "down" : "up", slot.phase, event.getPlayerUUID());
        }
    }

    /**
     * 破坏方块观测事件：ARMED → PLANNING，并自增代际（T4 破坏观测入口）。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 破坏观测事件
     */
    private void onBlockBreakObserved(BlockBreakObserved event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // T4: ARMED → PLANNING，++generation 后再转移（generation 写回由 applyTransition 统一执行）
        if (slot.phase == ChainPhase.ARMED) {
            int nextGen = slot.generation + 1;
            applyTransition(slot, slot.phase, ChainPhase.PLANNING, event, nextGen);
            // 进入规划后广播冻结身份与种子，供规划桥启动 worker。
            // 破坏路径无命中偏移，hitX/Y/Z 填 0（Forge 1.7.10 BreakEvent 不暴露命中点偏移）
            // 种子透传：破坏路径方块在下一 tick drain 时已被原版 removeBlock 成空气，
            // 必须把 BlockBreakObserved 携带的 seedBlock/seedMeta 透传给 PlanStarted，供 bridge 跳过 resolver
            bus.publish(new PlanStarted(
                    event.getPlayerUUID(), event.getServerRoundId(), nextGen,
                    event.getServerTick(), ChainTickSource.nowNanos(),
                    event.getX(), event.getY(), event.getZ(),
                    event.getDimensionId(), event.getSideHit(),
                    0.0F, 0.0F, 0.0F,
                    event.getSeedBlock(), event.getSeedMeta(), event.getSeedTileIdentity()));
        } else {
            logIllegalDrop(event, slot.phase, ChainPhase.PLANNING);
        }
    }

    /** 右键观测触发规划，原样传播观测窗口冻结的种子身份及命中偏移。 */
    private void onRightClickObserved(RightClickObserved event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // T4 右键入口：ARMED → PLANNING，++generation 后再转移，逻辑等同 onBlockBreakObserved
        if (slot.phase == ChainPhase.ARMED) {
            int nextGen = slot.generation + 1;
            applyTransition(slot, slot.phase, ChainPhase.PLANNING, event, nextGen);
            // 原样传播右键事件冻结的完整 seed，保留交互前的目标身份。
            bus.publish(new PlanStarted(
                    event.getPlayerUUID(), event.getServerRoundId(), nextGen,
                    event.getServerTick(), ChainTickSource.nowNanos(),
                    event.getX(), event.getY(), event.getZ(),
                    event.getDimensionId(), event.getSideHit(),
                    event.getHitX(), event.getHitY(), event.getHitZ(),
                    event.getSeedBlock(), event.getSeedMeta(), event.getSeedTileIdentity()));
        } else {
            logIllegalDrop(event, slot.phase, ChainPhase.PLANNING);
        }
    }

    /**
     * GT 线缆左键观测触发规划；种子由规划桥读取仍在世界中的方块。
     * Forge 1.7.10 左键事件缺少命中偏移，线缆规划使用缺省值。
     */
    private void onLeftClickObserved(LeftClickObserved event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // T4 左键入口：ARMED → PLANNING，++generation 后再转移，逻辑等同 onRightClickObserved/onBlockBreakObserved
        if (slot.phase == ChainPhase.ARMED) {
            int nextGen = slot.generation + 1;
            applyTransition(slot, slot.phase, ChainPhase.PLANNING, event, nextGen);
            // 广播左键规划上下文及事件命中偏移。
            // 左键路径块仍在世界，seed 传 null/0 由 bridge 走 WorldBlockSeedResolver 兜底
            bus.publish(new PlanStarted(
                    event.getPlayerUUID(), event.getServerRoundId(), nextGen,
                    event.getServerTick(), ChainTickSource.nowNanos(),
                    event.getX(), event.getY(), event.getZ(),
                    event.getDimensionId(), event.getSideHit(),
                    event.getHitX(), event.getHitY(), event.getHitZ(),
                    null, 0));
        } else {
            logIllegalDrop(event, slot.phase, ChainPhase.PLANNING);
        }
    }

    /** 模式切换时按持续按键电平保留武装，松键状态回到 IDLE。 */
    private void onModeSwitched(ModeSwitched event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // 按住连锁键滚轮切模式后延续授权，松键由 T2 解除武装。
        if (slot.phase == ChainPhase.ARMED) {
            if (slot.keyDown) {
                MyMod.LOG.debug("[ChainStateMachine] keep ARMED on ModeSwitched (key held) player={}",
                        event.getPlayerUUID());
            } else {
                applyTransition(slot, slot.phase, ChainPhase.IDLE, event, slot.generation);
            }
        } else {
            logIllegalDrop(event, slot.phase, ChainPhase.IDLE);
        }
    }

    // ============================ 派生事件 handler（需代际陈旧判定） ============================

    /**
     * 规划完成事件：PLANNING → RUNNING（gen 匹配）。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 规划完成事件
     */
    private void onPlanCompleted(PlanCompleted event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        if (!genCheck(slot, event)) {
            return;
        }
        // T5: PLANNING → RUNNING
        if (slot.phase == ChainPhase.PLANNING) {
            applyTransition(slot, slot.phase, ChainPhase.RUNNING, event, slot.generation);
        } else {
            logIllegalDrop(event, slot.phase, ChainPhase.RUNNING);
        }
    }

    /**
     * 规划取消事件：PLANNING → IDLE。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 规划取消事件
     */
    private void onPlanCancelled(PlanCancelled event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        if (!genCheck(slot, event)) {
            return;
        }
        // T6: PLANNING → IDLE（T11：按键仍按住则立刻回到 ARMED）
        if (slot.phase == ChainPhase.PLANNING) {
            settleToIdle(slot, event, true);
            // 记录可空取消原因，供运行诊断关联规划终局。
            MyMod.LOG.info("[ChainStateMachine] PlanCancelled reason={} gen={} player={}",
                    event.getReason(), Integer.valueOf(event.getGeneration()), event.getPlayerUUID());
        } else {
            logIllegalDrop(event, slot.phase, ChainPhase.IDLE);
        }
    }

    /**
     * 执行结束事件：RUNNING → FINISHING。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 执行结束事件
     */
    private void onExecutionFinished(ExecutionFinished event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        if (!genCheck(slot, event)) {
            return;
        }
        // T7: RUNNING → FINISHING
        if (slot.phase == ChainPhase.RUNNING) {
            applyTransition(slot, slot.phase, ChainPhase.FINISHING, event, slot.generation);
        } else {
            logIllegalDrop(event, slot.phase, ChainPhase.FINISHING);
        }
    }

    /**
     * 看门狗超时事件：PLANNING/RUNNING/FINISHING → IDLE（T10 兜底，ARMED 不纳入）。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 看门狗超时事件
     */
    private void onWatchdogTimeout(WatchdogTimeout event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        if (!genCheck(slot, event)) {
            return;
        }
        // T10: PLANNING / RUNNING / FINISHING → IDLE
        if (slot.phase == ChainPhase.PLANNING
                || slot.phase == ChainPhase.RUNNING
                || slot.phase == ChainPhase.FINISHING) {
            // T10 + T11：异常兜底回 IDLE 后，若按键仍按住则恢复授权
            settleToIdle(slot, event, true);
        } else {
            // ARMED/IDLE 不纳入 T10，越界丢弃
            logIllegalDrop(event, slot.phase, ChainPhase.IDLE);
        }
    }

    /**
     * 生命周期事件统一回到 IDLE；forced 按玩家清理，普通完成按代际筛选。
     * removeSlot 释放登出玩家的槽，其余收尾保留代际并按按键电平再武装。
     */
    private void onLifecycleCleanup(LifecycleCleanup event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // forced 清理当前玩家任务，普通完成按代际筛选。
        if (!event.isForced()) {
            if (!genCheck(slot, event)) {
                return;
            }
        }
        if (slot.phase == ChainPhase.IDLE) {
            // IDLE 玩家登出时同样释放槽位。
            if (event.isRemoveSlot()) {
                slots.remove(event.getPlayerUUID());
            }
            // 已 IDLE，幂等丢弃
            logIllegalDrop(event, slot.phase, ChainPhase.IDLE);
            return;
        }
        // 先统一收尾，保留槽位时按持续电平再武装。
        settleToIdle(slot, event, !event.isRemoveSlot());
        // 登出释放槽位，其余路径保留代际。
        if (event.isRemoveSlot()) {
            slots.remove(event.getPlayerUUID());
        }
    }

    // ============================ 共用逻辑 ============================

    /**
     * 先发布 IDLE，供 watchdog、工具换位与客户端投影结算；
     * 允许再武装且按键仍按下时，紧接发布 ARMED，延续玩家的持续授权。
     */
    private void settleToIdle(PlayerPhaseSlot slot, ChainEvent event, boolean allowRearm) {
        applyTransition(slot, slot.phase, ChainPhase.IDLE, event, slot.generation);
        if (allowRearm && slot.keyDown) {
            // T11: IDLE → ARMED（按键电平仍按下 → 持续授权，等下一次观测点火）
            applyTransition(slot, ChainPhase.IDLE, ChainPhase.ARMED, event, slot.generation);
        }
    }

    /**
     * 派生事件代际陈旧判定。仅对派生事件调用，按玩家槽比对。
     *
     * @param slot  玩家槽
     * @param event 派生事件
     * @return true 表示 gen 匹配可继续处理；false 表示已丢弃/告警，调用方应直接 return
     */
    private boolean genCheck(PlayerPhaseSlot slot, ChainEvent event) {
        int gen = event.getGeneration();
        if (gen < slot.generation) {
            // 陈旧规划事件迟到，丢弃 + debug
            MyMod.LOG.debug("[ChainStateMachine] drop stale derived event {} gen={} < current={} phase={} player={}",
                    event.getClass().getSimpleName(), gen, slot.generation, slot.phase, event.getPlayerUUID());
            return false;
        } else if (gen > slot.generation) {
            // 未来代际事件记录告警并结束处理。
            MyMod.LOG.warn("[ChainStateMachine] future-gen derived event {} gen={} > current={} phase={} player={}; drop",
                    event.getClass().getSimpleName(), gen, slot.generation, slot.phase, event.getPlayerUUID());
            return false;
        }
        return true;
    }

    /**
     * 应用调用方筛选后的合法转移，统一写回 phase 与 generation。
     *
     * <p>守唯一写权威：唯一写点。{@code phase} 与 {@code generation} 均唯一写在本方法内，
     * T4 ARMED→PLANNING 由调用方传 {@code nextGen = slot.generation + 1} 后由本方法写回新代际，
     * 其余路径调用方传 {@code nextGen == slot.generation}，写回幂等无副作用。</p>
     *
     * @param slot    玩家槽
     * @param from    源态
     * @param to      目标态
     * @param event   触发事件
     * @param nextGen 转移后的代际（T4 时为 +1 后的新值；其余沿用 slot.generation）
     */
    private void applyTransition(PlayerPhaseSlot slot, ChainPhase from, ChainPhase to, ChainEvent event, int nextGen) {
        slot.phase = to;
        // T4 时 nextGen = slot.generation + 1，其余路径 nextGen == slot.generation，写回幂等
        slot.generation = nextGen;
        if (to == ChainPhase.PLANNING) {
            // 与本次代际同步冻结规划轮次，供入口重验授权。
            slot.planningServerRoundId = event.getServerRoundId();
        }
        UUID player = event.getPlayerUUID();
        MyMod.LOG.debug("[ChainStateMachine] transition {} -> {} on {} gen={} player={}",
                from, to, event.getClass().getSimpleName(), nextGen, player);
        // 转移后广播 from/to，供快照与表现订阅者读取；PlanStarted 另携带规划上下文。
        bus.publish(new ChainPhaseChanged(player, event.getServerRoundId(), nextGen, from, to,
                ChainTickSource.currentServerTick(), ChainTickSource.nowNanos()));
    }

    /**
     * 记录被拒绝的阶段转移，供诊断查询。
     *
     * @param event       越界事件
     * @param from        当前态
     * @param attemptedTo 该事件在合法表里"本应"去到的目标态（用于诊断）
     */
    private void logIllegalDrop(ChainEvent event, ChainPhase from, ChainPhase attemptedTo) {
        MyMod.LOG.debug("[ChainStateMachine] drop illegal {} in phase={} (would-be {}) gen={} player={}",
                event.getClass().getSimpleName(), from, attemptedTo, event.getGeneration(), event.getPlayerUUID());
    }

    /**
     * 服务端主线程只读查询当前规划授权。
     * PlanStarted 可能晚于同次 drain 的清理出队，因此按真实槽位、代际与轮次复核；
     * 轮次进一步隔离登出后复用的 generation。
     */
    public boolean permitsPlanning(UUID playerUUID, int generation, long serverRoundId) {
        PlayerPhaseSlot slot = slots.get(playerUUID);
        return slot != null && slot.phase == ChainPhase.PLANNING
                && slot.generation == generation && slot.planningServerRoundId == serverRoundId;
    }

    // ============================ package-private getter 供单测 ============================

    /**
     * @param playerUUID 玩家 UUID
     * @return 该玩家当前阶段，供同包验证读取
     */
    ChainPhase getCurrentPhase(UUID playerUUID) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(playerUUID, k -> new PlayerPhaseSlot());
        return slot.phase;
    }

    /**
     * @param playerUUID 玩家 UUID
     * @return 该玩家当前代际，供同包验证读取
     */
    int getCurrentGeneration(UUID playerUUID) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(playerUUID, k -> new PlayerPhaseSlot());
        return slot.generation;
    }

    /**
     * per-player 状态槽值对象：phase 起点 IDLE，generation 起点 0。
     *
     * <p>私有静态内嵌类，phase 与 generation 均唯一写在 {@link #applyTransition}（守唯一写权威）。</p>
     */
    private static final class PlayerPhaseSlot {
        /** 玩家当前连锁阶段，唯一写在 {@link #applyTransition}。 */
        ChainPhase phase = ChainPhase.IDLE;
        /** 玩家当前代际，T4 ARMED→PLANNING 自增，唯一写在 {@link #applyTransition}。 */
        int generation = 0;
        /** 最近T4冻结的规划轮次，与phase/generation在同一主线程写点更新。 */
        long planningServerRoundId = ChainEvent.NO_SERVER_ROUND_ID;
        /**
         * 按键电平镜像（true = 玩家仍按住连锁键）。
         *
         * <p>由 {@link #onChainKeyPressed} 更新，作为转移输入。
         * 收尾路径 {@link #settleToIdle} 读它决定是否 T11 再武装；玩家登出删槽后
         * 重建为 false，客户端重连/切世界会重发一次真实电平。</p>
         */
        boolean keyDown;
    }
}
