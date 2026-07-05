package club.heiqi.qz_miner.chain.statemachine;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.ModeSwitched;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;

/**
 * 连锁框架状态机：{@code currentPhase} 与 {@code currentGeneration} 的唯一写权威。
 *
 * <p>落地 NORTH_STAR §5 不变量 I10「合法转移表」：状态变更只经此类驱动，
 * 外部入口只能 {@link ChainEventBus#publish(ChainEvent)} 事件、不能直接 {@code transition} 切态。
 * 越界（非法源→目标组合）即丢弃事件并诊断日志，不得静默改态或抛异常中断 drain。</p>
 *
 * <h3>线程契约（守 I1/I4）</h3>
 * <ul>
 *   <li>本类所有 {@code onXxx} handler 仅被主线程 {@link ChainEventBus#drain()} 调用，
 *       契约上为单线程执行，无需自行加锁</li>
 *   <li>worker 线程只 {@code publish} 事件、不直接调任何 handler、不读写 {@code currentPhase} 字段（I10 对 I1 的延伸保证）</li>
 * </ul>
 *
 * <h3>代际陈旧判定</h3>
 * <ul>
 *   <li>派生事件（{@link PlanCompleted}/{@link PlanCancelled}/{@link ExecutionFinished}/
 *       {@link WatchdogTimeout}/{@link LifecycleCleanup}）携带 generation，
 *       与 {@code currentGeneration} 比较：{@code <} 丢弃+debug（陈旧规划事件迟到）；
 *       {@code ==} 处理；{@code >} 不转移+warn（不应出现）</li>
 *   <li>输入事件（{@link ChainKeyPressed}/{@link BlockBreakObserved}/{@link ModeSwitched}）豁免代际判定——
 *       它们是玩家直接动作，不归属某一具体代际，由转移规则本身约束其在何态被消费</li>
 * </ul>
 *
 * <h3>阶段 2 范围</h3>
 * <p>本阶段状态机只驱动 phase 转移，<b>不 publish 任何派生事件</b>（PlanStarted/ExecutionAdvanced 留阶段 4/5 发，
 * PlanProgress/ExecutionAdvanced 不在本阶段订阅）。订阅集仅含 8 个驱动事件。</p>
 */
public class ChainStateMachine {

    /** 注入的事件总线，构造期订阅 8 个驱动事件。 */
    private final ChainEventBus bus;
    /** 当前连锁阶段，唯一写权威在本类。 */
    private ChainPhase currentPhase = ChainPhase.IDLE;
    /** 当前连锁代际，BlockBreakObserved 武装→规划时自增。 */
    private int currentGeneration = 0;

    /**
     * 构造状态机并订阅 8 个驱动事件。
     *
     * @param bus 注入的事件总线
     */
    public ChainStateMachine(ChainEventBus bus) {
        this.bus = bus;
        subscribe();
    }

    /**
     * 订阅阶段 2 的 8 个驱动事件。PlanStarted/PlanProgress/ExecutionAdvanced 不在本阶段订阅。
     */
    private void subscribe() {
        // 输入事件（豁免代际判定）
        bus.subscribe(ChainKeyPressed.class, this::onChainKeyPressed);
        bus.subscribe(BlockBreakObserved.class, this::onBlockBreakObserved);
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
        ChainPhase to = null;
        if (event.isPressed()) {
            // T1: IDLE → ARMED
            if (currentPhase == ChainPhase.IDLE) {
                to = ChainPhase.ARMED;
            }
        } else {
            // T2: ARMED → IDLE
            if (currentPhase == ChainPhase.ARMED) {
                to = ChainPhase.IDLE;
            }
        }
        if (to != null) {
            applyTransition(currentPhase, to, event, currentGeneration);
        } else {
            // 越界（矩阵 —）：丢弃 + debug
            logIllegalDrop(event, currentPhase, event.isPressed() ? ChainPhase.ARMED : ChainPhase.IDLE);
        }
    }

    /**
     * 破坏方块观测事件：ARMED → PLANNING，并自增代际。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 破坏观测事件
     */
    private void onBlockBreakObserved(BlockBreakObserved event) {
        // T4: ARMED → PLANNING，++currentGeneration 后再转移
        if (currentPhase == ChainPhase.ARMED) {
            int nextGen = currentGeneration + 1;
            applyTransition(currentPhase, ChainPhase.PLANNING, event, nextGen);
            currentGeneration = nextGen;
        } else {
            logIllegalDrop(event, currentPhase, ChainPhase.PLANNING);
        }
    }

    /**
     * 模式切换事件：ARMED → IDLE（切模式即解除武装，等下次按键重新武装）。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 模式切换事件
     */
    private void onModeSwitched(ModeSwitched event) {
        // T3: ARMED → IDLE
        if (currentPhase == ChainPhase.ARMED) {
            applyTransition(currentPhase, ChainPhase.IDLE, event, currentGeneration);
        } else {
            logIllegalDrop(event, currentPhase, ChainPhase.IDLE);
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
        if (!genCheck(event)) {
            return;
        }
        // T5: PLANNING → RUNNING
        if (currentPhase == ChainPhase.PLANNING) {
            applyTransition(currentPhase, ChainPhase.RUNNING, event, currentGeneration);
        } else {
            logIllegalDrop(event, currentPhase, ChainPhase.RUNNING);
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
        if (!genCheck(event)) {
            return;
        }
        // T6: PLANNING → IDLE
        if (currentPhase == ChainPhase.PLANNING) {
            applyTransition(currentPhase, ChainPhase.IDLE, event, currentGeneration);
        } else {
            logIllegalDrop(event, currentPhase, ChainPhase.IDLE);
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
        if (!genCheck(event)) {
            return;
        }
        // T7: RUNNING → FINISHING
        if (currentPhase == ChainPhase.RUNNING) {
            applyTransition(currentPhase, ChainPhase.FINISHING, event, currentGeneration);
        } else {
            logIllegalDrop(event, currentPhase, ChainPhase.FINISHING);
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
        if (!genCheck(event)) {
            return;
        }
        // T10: PLANNING / RUNNING / FINISHING → IDLE
        if (currentPhase == ChainPhase.PLANNING
                || currentPhase == ChainPhase.RUNNING
                || currentPhase == ChainPhase.FINISHING) {
            applyTransition(currentPhase, ChainPhase.IDLE, event, currentGeneration);
        } else {
            // ARMED/IDLE 不纳入 T10，越界丢弃
            logIllegalDrop(event, currentPhase, ChainPhase.IDLE);
        }
    }

    /**
     * 生命周期清理事件：FINISHING → IDLE（T8）或任意非 IDLE → IDLE（T9 兜底）。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 生命周期清理事件
     */
    private void onLifecycleCleanup(LifecycleCleanup event) {
        if (!genCheck(event)) {
            return;
        }
        if (currentPhase == ChainPhase.IDLE) {
            // 已 IDLE，幂等丢弃
            logIllegalDrop(event, currentPhase, ChainPhase.IDLE);
            return;
        }
        // T8 + T9 合流：任意非 IDLE → IDLE
        applyTransition(currentPhase, ChainPhase.IDLE, event, currentGeneration);
    }

    // ============================ 共用逻辑 ============================

    /**
     * 派生事件代际陈旧判定。仅对派生事件调用。
     *
     * @param event 派生事件
     * @return true 表示 gen 匹配可继续处理；false 表示已丢弃/告警，调用方应直接 return
     */
    private boolean genCheck(ChainEvent event) {
        int gen = event.getGeneration();
        if (gen < currentGeneration) {
            // 陈旧规划事件迟到，丢弃 + debug
            MyMod.LOG.debug("[ChainStateMachine] drop stale derived event {} gen={} < current={} phase={}",
                    event.getClass().getSimpleName(), gen, currentGeneration, currentPhase);
            return false;
        } else if (gen > currentGeneration) {
            // 不应出现：未来代际事件，丢弃 + warn
            MyMod.LOG.warn("[ChainStateMachine] future-gen derived event {} gen={} > current={} phase={}; drop",
                    event.getClass().getSimpleName(), gen, currentGeneration, currentPhase);
            return false;
        }
        return true;
    }

    /**
     * 应用合法转移：改 currentPhase + debug 日志。非法转移不调用本方法（调用前已过滤）。
     *
     * @param from        源态
     * @param to          目标态
     * @param event       触发事件
     * @param nextGen     转移后的代际（ARMED→PLANNING 时为 +1 后的新值；其余沿用 currentGeneration）
     */
    private void applyTransition(ChainPhase from, ChainPhase to, ChainEvent event, int nextGen) {
        currentPhase = to;
        // nextGen 仅在 T4 时与 currentGeneration 不同；T4 已在 onBlockBreakObserved 中先传 +1 再在调用后写回，
        // 这里仅记日志；其它路径 nextGen == currentGeneration。
        UUID player = event.getPlayerUUID();
        MyMod.LOG.debug("[ChainStateMachine] transition {} -> {} on {} gen={} player={}",
                from, to, event.getClass().getSimpleName(), nextGen, player);
    }

    /**
     * 越界事件丢弃诊断日志（不抛异常、不改态，守 I10）。
     *
     * @param event       越界事件
     * @param from        当前态
     * @param attemptedTo 该事件在合法表里"本应"去到的目标态（用于诊断）
     */
    private void logIllegalDrop(ChainEvent event, ChainPhase from, ChainPhase attemptedTo) {
        MyMod.LOG.debug("[ChainStateMachine] drop illegal {} in phase={} (would-be {}) gen={} player={}",
                event.getClass().getSimpleName(), from, attemptedTo, event.getGeneration(), event.getPlayerUUID());
    }

    // ============================ package-private getter 供单测 ============================

    /**
     * @return 当前阶段（仅供同包单测读，不公开写入口）
     */
    ChainPhase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * @return 当前代际（仅供同包单测读，不公开写入口）
     */
    int getCurrentGeneration() {
        return currentGeneration;
    }
}