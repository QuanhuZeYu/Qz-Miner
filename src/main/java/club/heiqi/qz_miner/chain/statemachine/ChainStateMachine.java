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
 * 连锁框架状态机：各玩家 `phase` 与 `generation` 的唯一写权威。
 *
 * <p>落地唯一写权威「合法转移表」：状态变更只经此类驱动，且按玩家 UUID 分槽
 * （per-player {@link #slots}）。外部入口只能 {@link ChainEventBus#publish(ChainEvent)} 事件、
 * 不能直接 {@code transition} 切态。越界（非法源→目标组合）即丢弃事件并诊断日志，
 * 不得静默改态或抛异常中断 drain。</p>
 *
 * <h3>per-player 容器（分歧1 A 裁决）</h3>
 * <ul>
 *   <li>{@link #slots} 是 {@code phase}/{@code generation} 唯一写权威容器，{@link #applyTransition}
 *       仍是唯一写点，按 UUID 分槽；不同玩家的 phase/gen 互不串槽</li>
 *   <li>{@link PlayerPhaseSlot} 私有静态内嵌值对象，构造期 IDLE/0</li>
 * </ul>
 *
 * <h3>线程契约（守边界不越权与跨线程只经事件总线）</h3>
 * <ul>
 *   <li>本类所有 {@code onXxx} handler 仅被主线程 {@link ChainEventBus#drain()} 调用，
 *       契约上为单线程串行执行，{@link HashMap} 无需加锁（守跨线程只经事件总线）</li>
 *   <li>worker 线程只 {@code publish} 事件、不直接调任何 handler、不读写 {@link #slots} 字段（I10 对 I1 的延伸保证）</li>
 * </ul>
 *
 * <h3>代际陈旧判定</h3>
 * <ul>
 *   <li>派生事件（{@link PlanCompleted}/{@link PlanCancelled}/{@link ExecutionFinished}/
 *       {@link WatchdogTimeout}/{@link LifecycleCleanup}）携带 generation，
 *       与该玩家槽 {@code generation} 比较：{@code <} 丢弃+debug（陈旧规划事件迟到）；
 *       {@code ==} 处理；{@code >} 不转移+warn（不应出现）</li>
 *   <li>输入事件（{@link ChainKeyPressed}/{@link BlockBreakObserved}/{@link RightClickObserved}/
 *       {@link LeftClickObserved}/{@link ModeSwitched}）豁免代际判定——它们是玩家直接动作，不归属某一具体代际，
 *       由转移规则本身约束其在何态被消费</li>
 * </ul>
 *
 * <h3>阶段 4 范围（T4 扩右键观测 + publish PlanStarted 进态广播）</h3>
 * <p>阶段 4 起 T4 ARMED→PLANNING 转移完成后，状态机 <b>publish {@link PlanStarted}</b> 作为进态广播，
 * 供 {@code ChainPlanningEventBridge} 拿到 {@code generation} + origin/dimension/sideHit/hitOffset 上下文
 * 后发起影子 traverser。这是状态机对外广播"我已进 PLANNING"，不是外部改态（守唯一写权威）。
 * 订阅集仍含 9 个驱动事件：
 * T4 ARMED→PLANNING 由 {@link BlockBreakObserved} 或 {@link RightClickObserved} 或 {@link LeftClickObserved}
 * 三事件入口触发，三者均 {@code ++generation}。
 * 其余派生事件（{@link PlanCompleted}/{@link PlanCancelled} 等仍由功能订阅者发，如 bridge worker）。</p>
 *
 * <h3>输入语义：按住 = 电平授权（T11 收尾自动再武装）</h3>
 * <p>{@link ChainKeyPressed} 是<b>按键电平</b>通知而非一次性边沿：handler 无条件把 {@code slot.keyDown}
 * 写成事件携带的电平，再按「电平 × 当前态」决定是否转移。收尾路径（T6/T10/T8+T9）先回到 {@code IDLE}
 * （保留看门狗清条目、自动换位 round 的 {@code closesRound(IDLE)}、客户端相位投影等既有收尾语义），
 * 随后若 {@code slot.keyDown} 仍为 true 则追加一次 <b>T11 {@code IDLE→ARMED}</b>。</p>
 * <p>根因背景：客户端只在物理边沿发 {@code PacketKeyState}，"按住不放"期间没有任何重述，而每次连锁
 * 收尾都回 IDLE，于是第二次观测被 T4 前置条件丢弃——玩家表现为"按住连锁键只能连锁一次"。修复前该缺口
 * 只被自动换位 round 的 fresh-key 副作用偶然掩盖（该通道带 configuredEnabled/breakCapable/creative/
 * chainActive 四道 fail-closed 守卫，创造模式必失效）。实机证据见
 * docs/反馈层/errors/ERROR-20260915-hold-chain-key-no-rearm.md。</p>
 */
public class ChainStateMachine {

    /** 注入的事件总线，构造期订阅 9 个驱动事件。 */
    private final ChainEventBus bus;
    /**
     * per-player 状态槽容器：{@code phase}/{@code generation} 唯一写权威（守唯一写权威）。
     *
     * <p>守跨线程只经事件总线：drain 单线程串行调用 handler，{@link HashMap} 无需加锁。
     * 容器内嵌值对象 {@link PlayerPhaseSlot}，{@link #applyTransition} 是唯一写点。</p>
     */
    private final Map<UUID, PlayerPhaseSlot> slots = new HashMap<UUID, PlayerPhaseSlot>();

    /**
     * 构造状态机并订阅 9 个驱动事件。
     *
     * @param bus 注入的事件总线
     */
    public ChainStateMachine(ChainEventBus bus) {
        this.bus = bus;
        subscribe();
    }

    /**
     * 订阅 9 个驱动事件。PlanStarted/PlanProgress/ExecutionAdvanced 不订阅（状态机只发不消费 PlanStarted）。
     * T4 由 {@link BlockBreakObserved}、{@link RightClickObserved} 与 {@link LeftClickObserved} 三事件入口触发，
     * 转移完成后 publish {@link PlanStarted}。
     */
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
        // 输入语义（电平授权）：无条件先把事件电平写进槽。keyDown 是本 handler 独占写的电平镜像
        // （phase/generation 仍只写 applyTransition：电平是转移的输入，不是转移结果）。
        // 按住期间"收尾后自动再武装"(T11) 与"活跃态收到 true"都只靠这次写入生效。
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
            // 幂等：电平与当前态同义（ARMED 收 true / IDLE 收 false），或活跃态收到电平变化
            // （PLANNING/RUNNING/FINISHING 期间收 true 是常态——按住不放不会再有物理边沿）。
            // 只更新电平、不转移、不记越界，避免把持续意图误报成非法转移。
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
            // 阶段4：T4 转移后 publish PlanStarted 作为进态广播，供 bridge 拿 gen+上下文发起影子 traverser
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

    /**
     * 右键方块观测事件：ARMED → PLANNING，并自增代际（T4 右键观测入口，与破坏观测对称）。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。
     * 命中偏移命中字段（hitX/Y/Z）携带供 INTERACT 模式 flood fill 方向判定，
     * 是 oracle 决议扩 T4 触发入口（破坏/右键/左键三入口）的根因（见「唯一写权威」）。</p>
     *
     * @param event 右键观测事件
     */
    private void onRightClickObserved(RightClickObserved event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // T4 右键入口：ARMED → PLANNING，++generation 后再转移，逻辑等同 onBlockBreakObserved
        if (slot.phase == ChainPhase.ARMED) {
            int nextGen = slot.generation + 1;
            applyTransition(slot, slot.phase, ChainPhase.PLANNING, event, nextGen);
            // T4 转移后 publish PlanStarted；右键路径同时原样传播原事件窗口冻结的完整 seed，
            // 禁止规划时用已被本次右键改变的 live world 覆盖。
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
     * 左键方块观测事件：ARMED → PLANNING，并自增代际（T4 左键观测入口，GT 线缆替换模式专用）。
     *
     * <p>阶段8 D1：GT 线缆左键替换观测入口，与破坏观测/右键观测三事件入口对称扩展 T4
     * （见「唯一写权威」）。逻辑等同 {@link #onRightClickObserved}——
     * ARMED 态 ++gen → PLANNING → publish {@link PlanStarted} 携带命中偏移。
     * GT 线缆左键路径 {@code hitX/Y/Z} 默认填 0（1.7.10 {@code PlayerInteractEvent}
     * 左键分支未暴露命中偏移），flood fill 不依赖此值。</p>
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 左键观测事件
     */
    private void onLeftClickObserved(LeftClickObserved event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // T4 左键入口：ARMED → PLANNING，++generation 后再转移，逻辑等同 onRightClickObserved/onBlockBreakObserved
        if (slot.phase == ChainPhase.ARMED) {
            int nextGen = slot.generation + 1;
            applyTransition(slot, slot.phase, ChainPhase.PLANNING, event, nextGen);
            // 阶段8：T4 转移后 publish PlanStarted，左键路径携带事件自带命中偏移（GT 线缆路径默认 0）
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

    /**
     * 模式切换事件：ARMED → IDLE（切模式即解除武装，等下次按键重新武装）。
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 模式切换事件
     */
    private void onModeSwitched(ModeSwitched event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // T3: ARMED → IDLE。但"按住连锁键 + 滚轮切模式/子模式"是文档化手势
        // （README「按住连锁键后滚轮」）：此时电平仍为按下，解除武装会让玩家切完模式后再也连锁不了，
        // 故按住期间保持武装（幂等静默）；真正松开时由 T2 回 IDLE。
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
            // 永久 reason 日志：PlanCancelled 携带的可空取消原因落盘，便于实机诊断（shadow-seed-unresolvable 等）
            // log4j {} 占位打印 null 不抛异常
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
     * 生命周期清理事件：FINISHING → IDLE（T8）或任意非 IDLE → IDLE（T9 兜底）。
     *
     * <p>阶段7 三路回 IDLE 收口（F.1 W1 + F.2 S1）：</p>
     * <ul>
     *   <li><b>forced=true</b>（F.1 W1）：玩家登出/重生/切维度等生命周期强制清理豁免 genCheck。
     *       守生命周期收口：玩家都登出了，哪一代都得清；跨包拿不到 slot.generation，强制清理不该受代际约束。</li>
     *   <li><b>forced=false</b>：执行完成快速收尾路径走 genCheck（gen 已知）。</li>
     *   <li><b>removeSlot=true</b>（F.2 S1 + P2-1 收口）：LOGOUT 删槽防泄漏，守唯一写权威：唯一写权威（仅本 handler 内 remove）。
     *       P2-1：IDLE 态 early-return 分支也执行 removeSlot（常见登出场景：玩家完成连锁回 IDLE 后登出）。</li>
     *   <li><b>removeSlot=false</b>：RESPAWN/维度切换/执行完成保槽保 gen 单调。</li>
     * </ul>
     *
     * <p>契约：仅主线程 drain 调用，单线程假定无需自锁。</p>
     *
     * @param event 生命周期清理事件
     */
    private void onLifecycleCleanup(LifecycleCleanup event) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(event.getPlayerUUID(), k -> new PlayerPhaseSlot());
        // F.1 W1：生命周期强制清理豁免 genCheck（forced=true），执行完成快速路径走 genCheck（forced=false）
        // 守生命周期收口：玩家都登出了，哪一代都得清；跨包拿不到 slot.generation，强制清理不该受代际约束
        if (!event.isForced()) {
            if (!genCheck(slot, event)) {
                return;
            }
        }
        if (slot.phase == ChainPhase.IDLE) {
            // P2-1 收口：IDLE 态 LOGOUT 仍需删槽防泄漏（常见登出场景：玩家完成连锁回 IDLE 后登出）。
            // 原阶段7 实现只在非 IDLE 分支末尾 remove，IDLE early-return 命中后 LOGOUT 意图被吞 → 槽泄漏。
            // 守唯一写权威：slots.remove 仍在状态机 handler 内（唯一写权威）。
            if (event.isRemoveSlot()) {
                slots.remove(event.getPlayerUUID());
            }
            // 已 IDLE，幂等丢弃
            logIllegalDrop(event, slot.phase, ChainPhase.IDLE);
            return;
        }
        // T8 + T9 合流：任意非 IDLE → IDLE；T11 追加：按键仍按住则再武装
        // （removeSlot=true 的 LOGOUT 不重武装——槽即将删除，武装无意义）
        settleToIdle(slot, event, !event.isRemoveSlot());
        // F.2 S1：LOGOUT 删槽防泄漏；RESPAWN/维度切换/执行完成保槽保 gen 单调。
        // 守唯一写权威：slots 容器唯一写权威内的 remove（与 applyTransition 唯一写点同处 handler）。
        if (event.isRemoveSlot()) {
            slots.remove(event.getPlayerUUID());
        }
    }

    // ============================ 共用逻辑 ============================

    /**
     * 收尾转移：任意非 IDLE 态 → IDLE，若按键电平仍为按下则紧接 T11 {@code IDLE→ARMED}。
     *
     * <p><b>为什么仍先经过 IDLE</b>：看门狗按 {@code to == IDLE} 清条目、自动换位 round 的
     * {@code closesRound(IDLE)} 结束 wire projection、客户端相位投影与 HUD 也以 IDLE 为收尾信号；
     * 这些既有消费者语义不变，ARMED 只是同 drain 帧内的第二次进态广播。</p>
     *
     * <p><b>T11 存在的理由</b>：客户端只在物理边沿发送按键电平（{@code PacketKeyState}），按住不放
     * 期间不会再有边沿。收尾一律停回 IDLE 会让第二次观测被 T4 前置条件丢弃，玩家表现为
     * "按住连锁键只能连锁一次"。键电平表达的是持续意图，收尾后必须按它恢复授权。</p>
     *
     * @param slot       玩家槽
     * @param event      触发收尾的事件
     * @param allowRearm 是否允许 T11（removeSlot=true 的 LOGOUT 传 false）
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
            // 不应出现：未来代际事件，丢弃 + warn
            MyMod.LOG.warn("[ChainStateMachine] future-gen derived event {} gen={} > current={} phase={} player={}; drop",
                    event.getClass().getSimpleName(), gen, slot.generation, slot.phase, event.getPlayerUUID());
            return false;
        }
        return true;
    }

    /**
     * 应用合法转移：改 {@code slot.phase} + debug 日志。非法转移不调用本方法（调用前已过滤）。
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
        UUID player = event.getPlayerUUID();
        MyMod.LOG.debug("[ChainStateMachine] transition {} -> {} on {} gen={} player={}",
                from, to, event.getClass().getSimpleName(), nextGen, player);
        // 阶段6 G1：进态广播（B3 模式延伸），供快照下发订阅者接收"状态机已转移"信号。
        // 守唯一写权威：状态机 publish 是转移完成后的广播，不是外部改态入口；订阅者只读不可切态。
        // 与 T4 路径在 applyTransition 外单独 publish 的 PlanStarted 职责不同（PlanStarted 携带规划上下文，
        // ChainPhaseChanged 携带 from/to 通用进态信号），两者订阅集互不重叠，并行不冲突。
        bus.publish(new ChainPhaseChanged(player, event.getServerRoundId(), nextGen, from, to,
                ChainTickSource.currentServerTick(), ChainTickSource.nowNanos()));
    }

    /**
     * 越界事件丢弃诊断日志（不抛异常、不改态，守唯一写权威）。
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
     * @param playerUUID 玩家 UUID
     * @return 该玩家当前阶段（仅供同包单测读，不公开写入口）
     */
    ChainPhase getCurrentPhase(UUID playerUUID) {
        PlayerPhaseSlot slot = slots.computeIfAbsent(playerUUID, k -> new PlayerPhaseSlot());
        return slot.phase;
    }

    /**
     * @param playerUUID 玩家 UUID
     * @return 该玩家当前代际（仅供同包单测读，不公开写入口）
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
        /**
         * 按键电平镜像（true = 玩家仍按住连锁键）。
         *
         * <p>唯一写在 {@link #onChainKeyPressed}——与 phase/generation 的写点分离：电平不是转移结果，
         * 而是转移的输入。收尾路径 {@link #settleToIdle} 读它决定是否 T11 再武装；玩家登出删槽后
         * 重建为 false，客户端重连/切世界会重发一次真实电平。</p>
         */
        boolean keyDown;
    }
}
