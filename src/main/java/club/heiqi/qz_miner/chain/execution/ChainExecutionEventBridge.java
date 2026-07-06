package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 阶段5 起：执行事件桥（新链路队列消费订阅者）。阶段8 块2 起接管真实破坏（旧 ChainExecutor 已于块1 删除）。
 *
 * <p>订阅 {@link PlanCompleted}（领取执行上下文）+ {@link TickEvent.ServerTickEvent#START}
 * （主线程每 tick 消费队列），实现新链路完整执行闭环：
 * worker publish PlanCompleted → 主线程 drain：状态机 T5 进 RUNNING + 本桥登记 ExecutionContext
 * （G1 此时 setExecuting(true) 开掉落收集窗口）→ 后续每 tick START 消费（{@code maxBreakPerTick} 控速，
 * 真实破坏经 {@link ChainActionExecutor#execute}）→ 队列空 publish ExecutionFinished（G1 setExecuting(false) 关窗口）
 * → 状态机 T7 RUNNING→FINISHING → 同 tick publish LifecycleCleanup(reason="execution-complete") → 状态机 T8 FINISHING→IDLE。</p>
 *
 * <h3>阶段7 三路回 IDLE 收口（H2 三路并存裁决）</h3>
 * <p>本桥是<b>执行完成快速收尾路径</b>，与<b>看门狗异常兜底</b>（{@code ChainWatchdog}）+
 * <b>生命周期清理</b>（{@code ChainLifecycleBridge}）三路并存回 IDLE：</p>
 * <ul>
 *   <li>正常连锁完成走本桥快速路径（同 tick 完成 T7+T8，不卡 N tick 看门狗阈值，手感不受影响）。</li>
 *   <li>异常卡死走看门狗（N tick 无推进 → publish WatchdogTimeout → 状态机 T10 回 IDLE）。</li>
 *   <li>玩家登出/重生/切维度走生命周期桥（forced=true 豁免 genCheck 强制回 IDLE）。</li>
 * </ul>
 * <p>本桥 publish 的 LifecycleCleanup 用 {@code forced=false}（走 genCheck，gen 已知）+
 * {@code removeSlot=false}（玩家在线保 gen 单调）。三路铁律：本桥<b>绝不删</b>，FINISHING 只能由
 * 本桥 publish 的 LifecycleCleanup 走 T8 出口，删了会卡死 FINISHING 致二次连锁哑火。</p>
 *
 * <h3>两容器清理分工（阶段7 收口）</h3>
 * <p>状态机管 {@code slots}（{@code ChainStateMachine.onLifecycleCleanup} 内 remove），
 * 本桥管 {@code registry}（订阅 WatchdogTimeout/LifecycleCleanup 清理幽灵队列），
 * 各清各的容器，互不夺权（守 I10）。</p>
 *
 * <h3>阶段8 块2：真实破坏桥 + G1 掉落窗口接线</h3>
 * <ul>
 *   <li><b>真实破坏</b>：consumeContext 调 {@link ChainActionExecutor#execute}（tryHarvestBlock/activateBlockOrUseItem），
 *       由 session 携带的 mode/subMode 经 ChainModeRegistry 解析执行器。守 I1：consumeContext 由
 *       onServerTick 在 {@code ServerTickEvent.START} 主线程调用，破坏在主线程。</li>
 *   <li><b>G1 掉落窗口</b>：四点 setExecuting 接线（onPlanCompleted 登记 true / publishExecutionFinishedWithCleanup
 *       完成 false / onWatchdogTimeout 兜底 false / onLifecycleCleanup 幂等 false），让
 *       {@code ChainDropCollector}:32 收集开关 + :58 释放开关正确工作（I5 生命线）。</li>
 * </ul>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：consumeContext 在主线程 ServerTickEvent.START 调 tryHarvestBlock/activateBlockOrUseItem；
 *       session 仅是配置载体（mode/subMode/origin/interactFace），不破坏世界。</li>
 *   <li><b>I5</b>：G1 四点 setExecuting 全覆盖，ChainDropCollector 收集/释放窗口正确；
 *       flushPlayerDrops 保留在 ChainStateService（I5 兜底）。</li>
 *   <li><b>I4</b>：消费在主线程 ServerTickEvent.START，publish 经事件总线入队→主线程 drain。
 *       跨线程只发生在 worker put registry 与主线程 get registry，{@link java.util.concurrent.ConcurrentHashMap}
 *       保证可见性。</li>
 *   <li><b>I10</b>：本桥 <b>只 publish 不 transition</b>。状态机仍是 phase/generation 唯一写权威。</li>
 * </ul>
 *
 * <h3>gen 传递链铁律（根治时序竞态）</h3>
 * <p>{@link ExecutionFinished} 的 generation <b>必须</b>来自 {@link ChainExecutionContext}（经
 * {@link PlanCompleted#getGeneration()} 注入 context 构造时），<b>绝不</b>调
 * {@code ChainStateMachine.getCurrentGeneration}（package-private 跨包不可见，且时序竞态——
 * 执行中玩家重按键触发新一代会让状态机 gen 已自增，迟到旧 gen ExecutionFinished 被 genCheck 丢弃）。</p>
 *
 * <h3>E4-b 执行完成快速收尾桥（阶段7 三路并存正名）</h3>
 * <p>本桥 publish ExecutionFinished 后，<b>同 tick 紧接</b> publish LifecycleCleanup(reason="execution-complete")
 * 走 T8 FINISHING→IDLE，让状态机跑完完整闭环（避免玩家槽卡 FINISHING 致二次连锁哑火）。
 * 阶段7 起本桥与看门狗异常兜底（{@code ChainWatchdog}）、生命周期清理（{@code ChainLifecycleBridge}）
 * 三路并存回 IDLE，本路径是<b>正常完成快速路径</b>，绝不删（FINISHING 只有 T8 能出，删了卡死）。</p>
 */
public class ChainExecutionEventBridge {

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;
    /** 注入的执行上下文注册表（worker put，本桥 get）。 */
    private final ChainExecutionContextRegistry registry;

    /**
     * 构造桥并订阅 {@link PlanCompleted}（不注册 FML bus，留 {@link #bootstrap()} 显式触发）。
     *
     * <p>构造器纯净可测：只调用 {@link ChainEventBus#subscribe}（操作 ConcurrentHashMap），
     * 不触碰 {@code FMLCommonHandler.instance()}（无 Forge 运行时环境会 NPE）。
     * FML bus 注册由 {@link #bootstrap()} 显式触发，与 {@code ChainEventBusDrainer.bootstrap()} 模式一致。</p>
     *
     * <p>接线顺序（{@link MyMod#init}）：状态机 → registry → planningBridge（注入 registry）
     * → <b>本桥</b>（注入 bus + registry，订阅 PlanCompleted）→ ChainEventBusDrainer.bootstrap() → 本桥.bootstrap()。
     * Drainer 先注册 FML bus，故 ServerTickEvent 触发顺序：drainer.onServerTick（drain，同步触发 onPlanCompleted 登记 context）
     * → 本桥.onServerTick（消费 context），同 tick 完成登记+消费，无延迟。</p>
     *
     * @param bus      事件总线
     * @param registry 执行上下文注册表
     */
    public ChainExecutionEventBridge(ChainEventBus bus, ChainExecutionContextRegistry registry) {
        this.bus = bus;
        this.registry = registry;
        bus.subscribe(PlanCompleted.class, this::onPlanCompleted);
        // 阶段7 B.4：订阅 WatchdogTimeout + LifecycleCleanup 清理 registry 幽灵队列（两容器清理分工）。
        // 状态机管 slots，本桥管 registry，各清各的容器，互不夺权（守 I10）。
        bus.subscribe(WatchdogTimeout.class, this::onWatchdogTimeout);
        bus.subscribe(LifecycleCleanup.class, this::onLifecycleCleanup);
    }

    /**
     * 向 FML 事件总线注册 ServerTickEvent 监听。
     *
     * <p>阶段5 起由 {@link MyMod#init} 调用。提取独立方法是为了让单测构造 bridge 时
     * 不必触发 {@code FMLCommonHandler.instance()}（无 Forge 运行时环境会 NPE）。</p>
     */
    public void bootstrap() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 收到 PlanCompleted：领取执行上下文，登记以供后续 tick 消费。
     *
     * <p>契约：仅主线程 drain 调用。本桥与状态机 onPlanCompleted（T5）是同一事件的两个订阅者，
     * 互不依赖、顺序不影响正确性（执行订阅者只登记 context，不消费、不依赖状态机 phase）。</p>
     *
     * <p>三种处理分支：</p>
     * <ol>
     *   <li><b>领取失败</b>（registry.get 返回 null，陈旧/不存在）：debug 日志 + return。</li>
     *   <li><b>空规划边界（卡点5）</b>：context.isCompleted() 初始即为 true（totalTargets=0，队列初始即空）
     *       → 立即 publish ExecutionFinished(reason="empty-plan") + 临时 LifecycleCleanup → return。</li>
     *   <li><b>正常登记</b>：留后续 ServerTickEvent 消费。</li>
     * </ol>
     *
     * @param event 规划完成事件（gen 取自事件注入 context）
     */
    private void onPlanCompleted(PlanCompleted event) {
        UUID playerUUID = event.getPlayerUUID();
        int gen = event.getGeneration();
        ChainExecutionContext context = registry.get(playerUUID, gen);
        if (context == null) {
            // 陈旧/不存在：worker 未 put 或 gen 不匹配，状态机 genCheck 已兜底丢弃
            MyMod.LOG.debug("[ChainExecution] PlanCompleted gen={} player={} has no matching context; skip",
                    Integer.valueOf(gen), playerUUID);
            return;
        }

        // 卡点5：空规划边界——totalTargets=0，队列初始即空，立即 publish ExecutionFinished + LifecycleCleanup，
        // 不能卡 RUNNING（否则玩家槽卡 RUNNING 致二次连锁哑火）
        if (context.isCompleted()) {
            publishExecutionFinishedWithCleanup(playerUUID, gen, "empty-plan");
            registry.remove(playerUUID);
            return;
        }

        // 正常登记：留后续 ServerTickEvent 消费（不立刻消费，避免本 drain 帧内嵌套执行）
        // G1（I5 生命线）：登记时即开掉落收集窗口（executionStatus=RUNNING），
        // 让 ChainDropCollector:32 isExecuting() 通过、:58 暂存到 buffer（执行中不释放）。
        // 时序铁律：必须在本处登记帧设，不能在 consumeContext 内设——consumeContext 在
        // onServerTick START 才触发，迟于 onPlanCompleted 的登记帧。登记在先，setExecuting 必须同步在先。
        setExecutionWindow(playerUUID, true, "plan-completed");
    }

    /**
     * 服务端 tick 回调，仅 START 阶段消费所有活跃执行上下文（真实破坏，守 I1 主线程）。
     *
     * <p>对每个 context：经 {@link #consumeContext} 走真实破坏三元组（玩家解析 → 执行器解析 →
     * canExecute/execute 循环 + 控速）。队列空（isCompleted）→ publish
     * ExecutionFinished(reason="executor-consumed-all-targets") + 临时 LifecycleCleanup（E4-b 桥）+ registry.remove。</p>
     *
     * <p>注：Drainer 先于本桥注册到 FML bus，本回调在 drainer.onServerTick（drain）之后触发。
     * drain 同步触发 onPlanCompleted 登记 context（含 G1 setExecuting(true) 开窗口），随后本回调同 tick 消费，无延迟。</p>
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        int maxBreakPerTick = Config.maxBreakPerTick;
        // snapshot 是弱一致视图，遍历期间 worker put 不影响本批
        for (ChainExecutionContext context : registry.snapshot()) {
            consumeContext(context, maxBreakPerTick);
        }
    }

    /**
     * 消费单个执行上下文（阶段8 块2 起真实破坏，守 I1）。
     *
     * <p>真实破坏三元组（对齐旧 ChainExecutor:52-110）：</p>
     * <ol>
     *   <li>解析玩家（{@code MyMod.playerManager.getPlayer}），非 EntityPlayerMP → publish ExecutionFinished + return。</li>
     *   <li>解析执行器（{@code context.getSession()} → mode → ChainModeDefinition → resolveActionExecutor(subMode)），
     *       null → publish ExecutionFinished + return。</li>
     *   <li>控速检查（{@code context.isExecutorReady(now)}）+ while 循环 {@code canExecute}/{@code execute}。</li>
     * </ol>
     *
     * <p>守 I1：本方法由 {@link #onServerTick} 在 {@link TickEvent.ServerTickEvent#START} 主线程调用，
     * {@link ChainActionExecutor#execute} 调 {@code tryHarvestBlock}/{@code activateBlockOrUseItem} 均在主线程。
     * session 仅作配置载体（mode/subMode/interactFace/hit），不破坏世界。</p>
     *
     * @param context         执行上下文
     * @param maxBreakPerTick 本 tick 最大破坏数（控速，对齐旧 ChainExecutor:89）
     */
    private void consumeContext(ChainExecutionContext context, int maxBreakPerTick) {
        UUID playerUUID = context.getPlayerUUID();
        int gen = context.getGeneration();

        // 三元组1：解析玩家
        EntityPlayer rawPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
        if (!(rawPlayer instanceof EntityPlayerMP)) {
            publishExecutionFinishedWithCleanup(playerUUID, gen, "player-unavailable");
            registry.remove(playerUUID);
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) rawPlayer;

        // 三元组2：解析执行器
        ChainSession session = context.getSession();
        ChainActionExecutor actionExecutor = null;
        if (session != null && session.getRequest() != null) {
            ChainMode mode = session.getRequest().getMode();
            ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
            if (definition != null) {
                ChainSubMode subMode = session.getRequest().getSubMode();
                actionExecutor = definition.resolveActionExecutor(subMode);
            }
        }
        if (actionExecutor == null) {
            publishExecutionFinishedWithCleanup(playerUUID, gen, "executor-unresolved");
            registry.remove(playerUUID);
            return;
        }

        // 三元组3：控速 + while 循环真实破坏
        long nowMillis = System.currentTimeMillis();
        if (!context.isExecutorReady(nowMillis)) {
            return;
        }
        int executed = 0;
        while (executed < maxBreakPerTick) {
            ChainTarget target = context.getTargets().poll();
            if (target == null) {
                break;
            }
            if (!actionExecutor.canExecute(player, session, target)) {
                continue;
            }
            if (!actionExecutor.execute(player, session, target)) {
                continue;
            }
            executed++;
        }

        if (executed > 0) {
            // 控速：本 tick 破坏过方块，设下次允许戳为 now+50（对齐旧 ChainExecutor:110）
            context.setNextExecutorAllowedMillis(System.currentTimeMillis() + 50L);
        }

        if (context.isCompleted()) {
            // 队列消费完：publish ExecutionFinished → 状态机 T7 RUNNING→FINISHING
            // + 临时 LifecycleCleanup（E4-b 桥）→ 状态机 T8 FINISHING→IDLE
            publishExecutionFinishedWithCleanup(playerUUID, gen, "executor-consumed-all-targets");
            registry.remove(playerUUID);
        }
        // 若未消费完，留下一 tick 继续消费（控速）
    }

    /**
     * publish ExecutionFinished + 临时 LifecycleCleanup（E4-b 桥）。
     *
     * <p>gen 来源：经 ChainExecutionContext（事件流注入），绝不实时读状态机 generation 字段。
     * 两条事件 publish 间隔几乎为零（同 drain 帧），状态机 drain 时 T7 先于 T8 处理（合法转移）。</p>
     *
     * <p>tick/nanos 来源：{@link ChainTickSource}。ChainTickSource 自身兜底无 Forge 运行时环境
     * （如纯 JVM 单测）返回 {@code -1L}，故本方法无需防御性 catch（守 I4 ChainTickSource 仅诊断字段语义）。</p>
     *
     * @param playerUUID 玩家 UUID
     * @param gen        代际（事件流注入，回填 ExecutionFinished）
     * @param reason     ExecutionFinished 原因
     */
    private void publishExecutionFinishedWithCleanup(UUID playerUUID, int gen, String reason) {
        long tick = ChainTickSource.currentServerTick();
        long nanos = ChainTickSource.nowNanos();
        bus.publish(buildExecutionFinished(playerUUID, gen, tick, nanos, reason));
        // G1（I5 生命线）：正常完成关掉落收集窗口（executionStatus=IDLE）。
        // ChainDropCollector:58 检测到 IDLE 后下个 WorldTick 释放 buffer 中聚合的掉落。
        setExecutionWindow(playerUUID, false, "execution-finished:" + reason);
        // 阶段7 三路并存正名（H2 裁决）：执行完成快速收尾路径 publish LifecycleCleanup 走 T8 回 IDLE。
        // forced=false（走 genCheck，执行完成 gen 已知）+ removeSlot=false（玩家在线保 gen 单调）。
        // 三路铁律：本桥绝不删，FINISHING 只有 T8 能出，删了会卡死 FINISHING 致二次连锁哑火。
        bus.publish(new LifecycleCleanup(
                playerUUID, gen, tick, nanos, "execution-complete", false, false));
    }

    /**
     * 看门狗超时订阅者：清理 registry 幽灵队列（阶段7 B.4 两容器清理分工）。
     *
     * <p>看门狗 publish WatchdogTimeout 后状态机 T10 回 IDLE，但执行桥 registry 内的
     * {@link ChainExecutionContext} 队列可能仍有未消费目标（异常卡死时 worker 仍 put）。
     * 若不清理，下一 tick ServerTickEvent 仍会消费幽灵队列（{@code oracle A.4} 竞态）。
     * 本桥订阅 WatchdogTimeout 后 {@code registry.remove(uuid)} 清幽灵队列。</p>
     *
     * <p>守 I10：本桥只 remove 自己管的 registry，不碰状态机 slots；状态机 T10 自行处理 slots。</p>
     *
     * @param event 看门狗超时事件
     */
    private void onWatchdogTimeout(WatchdogTimeout event) {
        registry.remove(event.getPlayerUUID());
        // G1（I5 生命线，必须）：看门狗只 publish WatchdogTimeout + 清 registry，
        // 若漏设 executionStatus 会卡 RUNNING → ChainDropCollector:58 暂存条件永不满足 → buffer 永不释放。
        setExecutionWindow(event.getPlayerUUID(), false, "watchdog-timeout");
        MyMod.LOG.debug("[ChainExecution] registry cleanup on WatchdogTimeout player={} gen={}",
                event.getPlayerUUID(), Integer.valueOf(event.getGeneration()));
    }

    /**
     * 生命周期清理订阅者：清理 registry（阶段7 B.4 两容器清理分工）。
     *
     * <p>玩家登出/重生/切维度时 {@code ChainLifecycleBridge} publish LifecycleCleanup，
     * 状态机 handler 处理 slots，本订阅者清理 registry（登出防泄漏，重生/维度切换清幽灵队列）。</p>
     *
     * <p>守 I10：本桥只 remove 自己管的 registry，不碰状态机 slots。</p>
     *
     * @param event 生命周期清理事件
     */
    private void onLifecycleCleanup(LifecycleCleanup event) {
        registry.remove(event.getPlayerUUID());
        // G1（I5 生命线，幂等）：clearRuntimeState 已通过 setExecutionStatus(IDLE) 收口，
        // 此处再幂等 setExecuting(false) 补缺口——若事件由本桥自己 publish（execution-complete）
        // 已经设过，幂等无副作用；若由 ChainLifecycleBridge publish（玩家登出/重生/切维度），
        // cleanupPlayerState→clearRuntimeState 路径也会 setExecutionStatus(IDLE)，本处仍是幂等。
        setExecutionWindow(event.getPlayerUUID(), false, "lifecycle-cleanup:" + event.getReason());
        MyMod.LOG.debug("[ChainExecution] registry cleanup on LifecycleCleanup player={} reason={}",
                event.getPlayerUUID(), event.getReason());
    }

    // ============================ 纯逻辑构造（供单测覆盖） ============================

    /**
     * G1 掉落窗口接线（I5 生命线）。
     *
     * <p>通过 {@code MyMod.chainStateService.getPlayerState(uuid).setExecuting(...)} 控
     * {@link ChainDropCollector} 的收集开关（:32 isExecuting）与释放开关
     * （:58 executionStatus!=IDLE 时暂存、回 IDLE 才释放）。调用点四处：
     * {@link #onPlanCompleted}（登记时 true）、{@link #publishExecutionFinishedWithCleanup}
     * （publish 后 false）、{@link #onWatchdogTimeout}（必须 false）、
     * {@link #onLifecycleCleanup}（幂等 false）。</p>
     *
     * <p>所有调用都防御 null（chainStateService / getPlayerState 均可能为 null，
     * 玩家登出后 getPlayerState 返回 null，本桥不应在生命周期清理时抛 NPE）。</p>
     *
     * @param playerUUID 玩家 UUID
     * @param executing  true 开窗口（RUNNING）/ false 关窗口（IDLE）
     * @param reason     诊断原因（写入日志，便于排查时序）
     */
    private void setExecutionWindow(UUID playerUUID, boolean executing, String reason) {
        if (MyMod.chainStateService == null) {
            return;
        }
        ChainPlayerState playerState = MyMod.chainStateService.getPlayerState(playerUUID);
        if (playerState == null) {
            return;
        }
        playerState.setExecuting(executing);
        MyMod.LOG.debug("[ChainExecution] G1 drop window player={} executing={} reason={}",
                playerUUID, Boolean.valueOf(executing), reason);
    }

    /**
     * 构造执行结束事件（纯逻辑，供单测覆盖）。
     *
     * <p>gen 传递链锚点：worker 收到的 planningGen 经 PlanCompleted 注入 ChainExecutionContext，
     * 本方法 publish ExecutionFinished 时回填同一 gen，状态机据此 genCheck 判定陈旧/匹配。</p>
     *
     * @param playerUUID 触发玩家
     * @param gen        代际（与 PlanCompleted 注入 context 的值一致，绝不实时读状态机）
     * @param tick       服务端 tick
     * @param nanos      纳秒戳
     * @param reason     结束原因（自由文本，用于诊断/HUD）
     * @return 执行结束事件
     */
    public static ExecutionFinished buildExecutionFinished(UUID playerUUID, int gen, long tick, long nanos, String reason) {
        return new ExecutionFinished(playerUUID, gen, tick, nanos, reason);
    }
}
