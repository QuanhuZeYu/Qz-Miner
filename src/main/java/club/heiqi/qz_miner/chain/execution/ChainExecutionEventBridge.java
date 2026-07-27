package club.heiqi.qz_miner.chain.execution;

import java.util.UUID;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionAdvanced;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapTakeoverCoordinator;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.BatchOutcome;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.BatchToken;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CloseCause;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.PrepareResult;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
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
 *   <li><b>G1 掉落窗口</b>：五点 setExecuting 接线（onPlanStarted 流式开窗 true /
 *       onPlanCompleted 登记 true / publishExecutionFinishedWithCleanup
 *       完成 false / onWatchdogTimeout 兜底 false / onLifecycleCleanup 幂等 false /
 *       onPlanCancelled 取消 false），让
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
    /** 仅为旧 source surface 保留的 dormant coordinator；生产 ordinary 热路不调用。 */
    private final AutoToolSwapTakeoverCoordinator takeoverCoordinator;
    /** 普通 CHAIN/AREA 的服务端本地候选与唯一 physical ledger owner。 */
    private final AutoToolSwapServerBatchService localToolSwap;

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
        this(bus, registry, null, null);
    }

    /** 兼容旧构造；coordinator 只保留 lifecycle memory cleanup，不进入 ordinary 热路。 */
    public ChainExecutionEventBridge(ChainEventBus bus, ChainExecutionContextRegistry registry,
            AutoToolSwapTakeoverCoordinator takeoverCoordinator) {
        this(bus, registry, takeoverCoordinator, null);
    }

    /** 创建服务端本地批量工具接替执行桥，避免与旧三参构造的 null 调用产生重载歧义。 */
    public static ChainExecutionEventBridge withLocalToolSwap(ChainEventBus bus,
            ChainExecutionContextRegistry registry, AutoToolSwapServerBatchService localToolSwap) {
        return new ChainExecutionEventBridge(bus, registry, null, localToolSwap);
    }

    private ChainExecutionEventBridge(ChainEventBus bus, ChainExecutionContextRegistry registry,
            AutoToolSwapTakeoverCoordinator takeoverCoordinator,
            AutoToolSwapServerBatchService localToolSwap) {
        this.bus = bus;
        this.registry = registry;
        this.takeoverCoordinator = takeoverCoordinator;
        this.localToolSwap = localToolSwap;
        bus.subscribe(PlanStarted.class, this::onPlanStarted);
        bus.subscribe(PlanCompleted.class, this::onPlanCompleted);
        bus.subscribe(PlanCancelled.class, this::onPlanCancelled);
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
     * C 流式执行：PlanStarted 进态广播 → 立即开掉落收集窗口。
     *
     * <p>修复边搜边破坏丢失：withstreaming 改造后，{@link ChainPlanningEventBridge#onPlanStarted}
     * 在注册 worker 前已 registry.put(context)（planningComplete=false），主线程执行订阅者
     * 在下一 tick 的 {@link #onServerTick} 即可 snapshot 到 context 并开始 poll shadowQueue 破坏。
     * worker 边搜边 shadowQueue.add，主线程边消费边破坏——破坏产生的掉落必须由
     * {@code ChainDropCollector} 收集，而收集开关是 {@code isExecuting()}（I5 生命线）。
     * 若窗口仍按旧 {@link #onPlanCompleted} 才打开，PLANNING 期间 worker 已 add 但消费未启的
     * 边界也会有同步破坏掉落（主线程边搜边消费）， collector 守卫 isExecuting()=false 会丢弃 → 丢感。</p>
     *
     * <p>守 I5：窗口提前到 PlanStarted，保证 PLANNING 期间边搜边破坏的掉落全部进 buffer。
     * 后续 {@link #onPlanCompleted} 仍幂等 setExecutionWindow(true, "plan-completed") 保窗，
     * {@link #publishExecutionFinishedWithCleanup} / {@link #onPlanCancelled} /
     * {@link #onWatchdogTimeout} / {@link #onLifecycleCleanup} 关窗。</p>
     *
     * @param event 规划启动事件
     */
    private void onPlanStarted(PlanStarted event) {
        setExecutionWindow(event.getPlayerUUID(), true, "plan-started-stream");
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
        ChainExecutionContext context = registry.get(playerUUID, gen, event.getServerRoundId());
        if (context == null) {
            // 陈旧/不存在：worker 未 put 或 gen 不匹配，状态机 genCheck 已兜底丢弃
            MyMod.LOG.debug("[ChainExecution] PlanCompleted gen={} player={} has no matching context; skip",
                    Integer.valueOf(gen), playerUUID);
            return;
        }
        logTimeline(context, "PlanCompleted", "workerConfirmed=" + event.getTotalTargets()
                + " queueNow=" + context.getTargets().size());

        // worker 完成先胜出但执行 STOP 已挂起：先让 PlanCompleted 合法进 RUNNING，
        // 再按正常执行终局发布 ExecutionFinished + Cleanup，禁止 STOP 抢跑。
        if (context.observePlanningCompletionAndShouldStop()) {
            publishExecutionFinishedWithCleanup(context, "auto-tool-local-stopped-after-plan-completed");
            registry.remove(playerUUID, gen, context.getServerRoundId());
            return;
        }

        // 卡点5：空规划边界——totalTargets=0，队列初始即空，立即 publish ExecutionFinished + LifecycleCleanup，
        // 不能卡 RUNNING（否则玩家槽卡 RUNNING 致二次连锁哑火）
        if (context.isCompleted()) {
            publishExecutionFinishedWithCleanup(context, "empty-plan");
            registry.remove(playerUUID, gen, context.getServerRoundId());
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
     * @param maxBreakPerTick 非 GT 普通模式的最大 poll 数；GT 原子分支不受此预算约束
     */
    private void consumeContext(ChainExecutionContext context, int maxBreakPerTick) {
        UUID playerUUID = context.getPlayerUUID();
        int gen = context.getGeneration();

        // 三元组1：解析玩家
        EntityPlayer rawPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
        if (!(rawPlayer instanceof EntityPlayerMP)) {
            publishExecutionFinishedWithCleanup(context, "player-unavailable");
            registry.remove(playerUUID, gen, context.getServerRoundId());
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
            publishExecutionFinishedWithCleanup(context, "executor-unresolved");
            registry.remove(playerUUID, gen, context.getServerRoundId());
            return;
        }

        // 三元组3：按执行模式分叉
        boolean waitForPlanner = actionExecutor.shouldWaitForPlannerCompletion(session);

        if (waitForPlanner) {
            // ===== GT 线缆特例：单 tick 原子替换（B1+B2+B3）=====
            // B1 门：等规划完整链路，不流式边搜边替换（防中间态混压）
            if (!context.isPlanningComplete()) {
                // worker 仍在搜，留下一 tick 再判
                return;
            }

            // B3 预校验放行门
            String precheckFail = precheckCableReplacement(player, session, context);
            if (precheckFail != null) {
                // 预校验失败：取消连锁 + 聊天提示 + 清锁
                notifyPlayer(player, "[QzMiner] " + precheckFail);
                publishExecutionFinishedWithCleanup(context, "cable-precheck-failed");
                GregTechCableSessionState.clear(playerUUID);
                registry.remove(playerUUID, gen, context.getServerRoundId());
                return;
            }

            // B2 单 tick 原子执行：while 到空，绕过 maxBreakPerTick + 50ms 节流
            int totalTargets = context.getTargets().size();
            int executed = 0;
            int failed = 0;
            while (true) {
                ChainTarget target = context.getTargets().poll();
                if (target == null) {
                    break;
                }
                context.recordExecutionConsumed();
                if (!actionExecutor.canExecute(player, session, target)) {
                    continue;
                }
                try {
                    if (!actionExecutor.execute(player, session, target)) {
                        failed++;
                        continue;
                    }
                } catch (RuntimeException e) {
                    // F4 防护：单根异常不崩 drain 帧，best-effort 继续
                    MyMod.LOG.error("[CableReplace] 单 tick 批量替换异常 player={} pos=({},{},{})",
                        playerUUID, Integer.valueOf(target.getX()), Integer.valueOf(target.getY()), Integer.valueOf(target.getZ()), e);
                    failed++;
                    continue;
                }
                executed++;
                if (context.recordExecutionSucceeded()) logFirstSuccessfulExecution(context, target);
                // 单 tick 原子执行仍需喂看门狗推进信号（虽然不跨 tick，但防 drain 帧内被误判）
            }

            // best-effort 提示
            if (failed > 0) {
                notifyPlayer(player, "[QzMiner] 线缆替换完成：" + executed + "/" + totalTargets + " 根成功，" + failed + " 根失败");
            }

            // 喂看门狗推进信号（即便单 tick 完成，也 publish 一次防状态机卡 RUNNING）
            bus.publish(new ExecutionAdvanced(playerUUID, context.getServerRoundId(), gen,
                ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                executed, 0));

            // 完成：publish ExecutionFinished + 清会话锁（F2 补齐：正常完成路径显式清锁）
            publishExecutionFinishedWithCleanup(context, "cable-atomic-complete:" + executed);
            GregTechCableSessionState.clear(playerUUID);
            registry.remove(playerUUID, gen, context.getServerRoundId());
            return;
        }

        // ===== 非 GT：按实际 poll 计预算的流式执行 =====
        long nowMillis = System.currentTimeMillis();
        if (!context.isExecutorReady(nowMillis)) {
            return;
        }
        if (!usesLocalToolSwap(session)) {
            // INTERACT 与非 GT SPECIAL 不接入接替门，但与普通采掘共用 poll 预算和零成功推进。
            consumeNonTakeoverTargets(player, session, actionExecutor, context, maxBreakPerTick);
            return;
        }
        final EntityPlayerMP ordinaryPlayer = player;
        final ChainSession ordinarySession = session;
        final ChainActionExecutor ordinaryExecutor = actionExecutor;
        final long ordinaryTick = Math.max(0L, ChainTickSource.currentServerTick());
        final BatchToken batchToken = localToolSwap == null ? null
                : localToolSwap.beginOrdinaryBatch(ordinaryPlayer, context.getServerRoundId(), gen, ordinaryTick);
        OrdinaryTickResult tickResult = consumeOrdinaryTargets(context, maxBreakPerTick,
                new OrdinaryTargetGate() {
                    @Override
                    public PrepareResult prepareTarget(ChainTarget target) {
                        return localToolSwap == null ? PrepareResult.PROCEED
                                : batchToken == null ? PrepareResult.STOP
                                : localToolSwap.prepareTarget(batchToken, target, ordinaryTick);
                    }
                }, new OrdinaryTargetExecutor() {
                    @Override
                    public boolean canExecute(ChainTarget target) {
                        return ordinaryExecutor.canExecute(ordinaryPlayer, ordinarySession, target);
                    }

                    @Override
                    public boolean execute(ChainTarget target) {
                        return ordinaryExecutor.execute(ordinaryPlayer, ordinarySession, target);
                    }
                });
        finishOrdinaryTickAndStopIfNeeded(context, tickResult, batchToken, ordinaryPlayer, ordinaryTick);
    }

    /** INTERACT 与非 GT SPECIAL 不走接替门，但复用按 poll 计数的有界消费。 */
    private void consumeNonTakeoverTargets(EntityPlayerMP player, ChainSession session,
            ChainActionExecutor actionExecutor, ChainExecutionContext context, int maxBreakPerTick) {
        OrdinaryTickResult tickResult = consumeOrdinaryTargets(context, maxBreakPerTick,
                target -> PrepareResult.PROCEED,
                new OrdinaryTargetExecutor() {
                    @Override
                    public boolean canExecute(ChainTarget target) {
                        return actionExecutor.canExecute(player, session, target);
                    }

                    @Override
                    public boolean execute(ChainTarget target) {
                        return actionExecutor.execute(player, session, target);
                    }
                });
        finishOrdinaryTickAndStopIfNeeded(context, tickResult);
    }

    /** 纯测试/非 local 路径：先发布本 tick 真实推进，再沿既有 STOP 合同收口。 */
    void finishOrdinaryTickAndStopIfNeeded(ChainExecutionContext context, OrdinaryTickResult tickResult) {
        finishOrdinaryTick(context, tickResult);
        if (tickResult.isStopped()) {
            stopForTakeover(context);
        }
    }

    /** production local batch 尾屏障：progress -> restore/publication -> completion/cleanup。 */
    private void finishOrdinaryTickAndStopIfNeeded(ChainExecutionContext context,
            OrdinaryTickResult tickResult, BatchToken token, Object endpoint, long serverTick) {
        finishOrdinaryProgress(context, tickResult);
        BatchOutcome outcome = tickResult.isStopped() ? BatchOutcome.STOPPED
                : context.isCompleted() ? BatchOutcome.FINISHED : BatchOutcome.CONTINUE;
        if (localToolSwap != null) {
            if (token != null) {
                localToolSwap.endOrdinaryBatch(token, outcome, serverTick);
            } else if (outcome != BatchOutcome.CONTINUE) {
                localToolSwap.finalizePlayer(context.getPlayerUUID(), endpoint, null,
                        CloseCause.ORDINARY_STOP, serverTick);
            }
        }
        if (tickResult.isStopped()) {
            stopForTakeover(context);
        } else if (context.isCompleted()) {
            publishExecutionFinishedWithCleanup(context, "executor-consumed-all-targets");
            registry.remove(context.getPlayerUUID(), context.getGeneration(), context.getServerRoundId());
        }
    }

    /**
     * 完成非 GT 普通模式单 tick 的推进尾处理。STOP 若发生在已有消费之后也必须先走到这里。
     */
    void finishOrdinaryTick(ChainExecutionContext context, OrdinaryTickResult tickResult) {
        if (context == null || tickResult == null) {
            throw new IllegalArgumentException("ordinary tick context and result must not be null");
        }
        finishOrdinaryProgress(context, tickResult);
        if (context.isCompleted()) {
            publishExecutionFinishedWithCleanup(context, "executor-consumed-all-targets");
            registry.remove(context.getPlayerUUID(), context.getGeneration(), context.getServerRoundId());
        }
    }

    /** 普通批次真实推进；terminal publication 由调用方在 restore 屏障后决定。 */
    private void finishOrdinaryProgress(ChainExecutionContext context, OrdinaryTickResult tickResult) {
        if (tickResult.getProcessedTargets() > 0) {
            if (tickResult.getExecutedTargets() > 0) {
                // 只有真实成功执行才设置 50ms 节流；纯跳过/失败可以在下一 tick 继续。
                context.setNextExecutorAllowedMillis(System.currentTimeMillis() + 50L);
            }
            if (tickResult.getFirstRoundSuccessfulTarget() != null) {
                logFirstSuccessfulExecution(context, tickResult.getFirstRoundSuccessfulTarget());
            }
            // consumed 是看门狗的真实推进；即使 executed=0 也必须发布，防全跳过批次被误判卡死。
            bus.publish(new ExecutionAdvanced(context.getPlayerUUID(), context.getServerRoundId(),
                    context.getGeneration(), ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    tickResult.getExecutedTargets(), context.getTargets().size()));
        }

    }

    /**
     * publish ExecutionFinished + 临时 LifecycleCleanup（E4-b 桥）。
     *
     * <p>generation 与 serverRoundId 均来源于 ChainExecutionContext（事件流注入），绝不实时读状态机字段。
     * 两条事件 publish 间隔几乎为零（同 drain 帧），状态机 drain 时 T7 先于 T8 处理（合法转移）。</p>
     *
     * <p>tick/nanos 来源：{@link ChainTickSource}。ChainTickSource 自身兜底无 Forge 运行时环境
     * （如纯 JVM 单测）返回 {@code -1L}，故本方法无需防御性 catch（守 I4 ChainTickSource 仅诊断字段语义）。</p>
     *
     * @param context    三元身份已冻结的执行上下文
     * @param reason     ExecutionFinished 原因
     */
    private void publishExecutionFinishedWithCleanup(ChainExecutionContext context, String reason) {
        UUID playerUUID = context.getPlayerUUID();
        int gen = context.getGeneration();
        long serverRoundId = context.getServerRoundId();
        long tick = ChainTickSource.currentServerTick();
        long nanos = ChainTickSource.nowNanos();
        // 所有自然终局（含空规划、endpoint/executor 异常、GT 特例）在状态事件前统一过
        // local finalizer。ordinary 热路若已 end batch，此处是同 tick 幂等 no-op。
        if (localToolSwap != null) {
            Object endpoint = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
            localToolSwap.finalizePlayer(playerUUID, endpoint, null, CloseCause.NATURAL_FINISH,
                    Math.max(0L, tick));
        }
        bus.publish(buildExecutionFinished(playerUUID, serverRoundId, gen, tick, nanos, reason));
        logTimeline(context, "ExecutionFinished", "reason=" + reason
                + " confirmed=" + context.getPlanningConfirmedCount()
                + " consumed=" + context.getExecutionConsumedCount()
                + " skipped=" + context.getExecutionSkippedCount()
                + " succeeded=" + context.getExecutionSucceededCount());
        // G1（I5 生命线）：正常完成关掉落收集窗口（executionStatus=IDLE）。
        // ChainDropCollector:58 检测到 IDLE 后下个 WorldTick 释放 buffer 中聚合的掉落。
        setExecutionWindow(playerUUID, false, "execution-finished:" + reason);
        // 阶段7 三路并存正名（H2 裁决）：执行完成快速收尾路径 publish LifecycleCleanup 走 T8 回 IDLE。
        // forced=false（走 genCheck，执行完成 gen 已知）+ removeSlot=false（玩家在线保 gen 单调）。
        // 三路铁律：本桥绝不删，FINISHING 只有 T8 能出，删了会卡死 FINISHING 致二次连锁哑火。
        bus.publish(new LifecycleCleanup(
                playerUUID, serverRoundId, gen, tick, nanos, "execution-complete", false, false));
    }

    /**
     * ordinary local STOP 与 worker 完成线性化：取消胜出只发精确 Cleanup，完成胜出则等待
     * PlanCompleted 被主线程观察后再走合法 ExecutionFinished 收口。方法名保留旧 source surface。
     */
    void stopForTakeover(ChainExecutionContext context) {
        ChainExecutionContext.PlanningStopResult result = context.requestPlanningStop();
        if (result == ChainExecutionContext.PlanningStopResult.COMPLETION_PENDING_OBSERVATION
                || result == ChainExecutionContext.PlanningStopResult.CANCELLATION_ALREADY_WON) {
            return;
        }
        if (result == ChainExecutionContext.PlanningStopResult.COMPLETION_OBSERVED) {
            publishExecutionFinishedWithCleanup(context, "auto-tool-local-stopped");
            registry.remove(context.getPlayerUUID(), context.getGeneration(), context.getServerRoundId());
            return;
        }

        UUID playerUUID = context.getPlayerUUID();
        long tick = ChainTickSource.currentServerTick();
        long nanos = ChainTickSource.nowNanos();
        // PLANNING 期取消不得伪造 ExecutionFinished；非 forced cleanup 按冻结三元身份收口。
        setExecutionWindow(playerUUID, false, "planning-cancelled:auto-tool-local-stopped");
        bus.publish(new LifecycleCleanup(playerUUID, context.getServerRoundId(), context.getGeneration(),
                tick, nanos, "auto-tool-local-stopped", false, false));
        registry.remove(playerUUID, context.getGeneration(), context.getServerRoundId());
    }

    /**
     * C 流式登记后的清理：PlanCancelled 时 context 可能已 put 进 registry。
     *
     * <p>修复边搜边破坏引入的清理路径：{@link ChainPlanningEventBridge#onPlanStarted} 改为提前 registry.put
     * 后，worker 启动失败（shadow-pool-exhausted / shadow-player-unavailable 等返回前分支除外，
     * 它们在 put 之前）以及 worker 运行中 cancelled / 全部搜完前 traversal-terminated 都会 publish
     * PlanCancelled。若不订阅清理，registry 内残留 planningComplete=false 的幽灵 context 会被
     * onServerTick 每次 tick snapshot 出来 poll（queue 空 isCompleted=false，无害但占内存并对状态机
     * 无推进）。本订阅者守 I5：关掉落窗口 + 清 registry（避免下 tick 消费幽灵 context）。</p>
     *
     * <p>守 I10：只清本桥管的 registry，不碰状态机 slots（状态机 T6 PLANNING→IDLE 自行处理）。</p>
     *
     * @param event 规划取消事件
     */
    private void onPlanCancelled(PlanCancelled event) {
        UUID playerUUID = event.getPlayerUUID();
        int gen = event.getGeneration();
        ChainExecutionContext ctx = registry.get(playerUUID, gen, event.getServerRoundId());
        if (ctx != null) {
            registry.remove(playerUUID, gen, event.getServerRoundId());
            // 仅匹配上下文才允许旧事件关闭对应执行窗口。
            setExecutionWindow(playerUUID, false, "plan-cancelled:" + event.getReason());
        }
        MyMod.LOG.debug("[ChainExecution] registry cleanup on PlanCancelled player={} gen={} reason={}",
                playerUUID, Integer.valueOf(gen), event.getReason());
    }

    /**
     * 看门狗超时订阅者：清理 registry 幽灵队列（阶段7 B.4 两容器清理分工）。
     *
     * <p>看门狗 publish WatchdogTimeout 后状态机 T10 回 IDLE，但执行桥 registry 内的
     * {@link ChainExecutionContext} 队列可能仍有未消费目标（异常卡死时 worker 仍 put）。
     * 若不清理，下一 tick ServerTickEvent 仍会消费幽灵队列（{@code oracle A.4} 竞态）。
     * 本桥订阅 WatchdogTimeout 后仅按三元身份移除匹配的幽灵队列。</p>
     *
     * <p>守 I10：本桥只 remove 自己管的 registry，不碰状态机 slots；状态机 T10 自行处理 slots。</p>
     *
     * @param event 看门狗超时事件
     */
    private void onWatchdogTimeout(WatchdogTimeout event) {
        ChainExecutionContext context = registry.get(
                event.getPlayerUUID(), event.getGeneration(), event.getServerRoundId());
        if (context != null) context.requestPlanningStop();
        boolean removed = registry.remove(event.getPlayerUUID(), event.getGeneration(), event.getServerRoundId());
        // G1（I5 生命线，必须）：看门狗只 publish WatchdogTimeout + 清 registry，
        // 若漏设 executionStatus 会卡 RUNNING → ChainDropCollector:58 暂存条件永不满足 → buffer 永不释放。
        if (removed) {
            setExecutionWindow(event.getPlayerUUID(), false, "watchdog-timeout");
        }
        MyMod.LOG.debug("[ChainExecution] registry cleanup on WatchdogTimeout player={} gen={}",
                event.getPlayerUUID(), Integer.valueOf(event.getGeneration()));
    }

    /**
     * 生命周期清理订阅者：清理 registry（阶段7 B.4 两容器清理分工）。
     *
     * <p>玩家登出/重生/切维度时 {@code ChainLifecycleBridge} publish LifecycleCleanup，
     * 状态机 handler 处理 slots，本订阅者清理 registry（登出防泄漏，重生/维度切换清幽灵队列）。</p>
     *
     * <p>{@code forced=true} 是 I7 全玩家生命周期收口：事件中的 generation/round 可为占位值，
     * 必须按玩家 UUID 无条件移除当前 context，并无条件关闭执行窗口。{@code forced=false} 是执行轮
     * 派生收口，仅在 UUID/generation/serverRoundId 三元身份匹配时移除并关闭对应窗口，防止旧轮事件
     * 清理新轮 context。</p>
     *
     * <p>守 I10：本桥只 remove 自己管的 registry，不碰状态机 slots。</p>
     *
     * @param event 生命周期清理事件
     */
    private void onLifecycleCleanup(LifecycleCleanup event) {
        UUID playerUUID = event.getPlayerUUID();
        if (takeoverCoordinator != null) takeoverCoordinator.cleanup(playerUUID);
        if (event.isForced()) {
            // I7 全量收口不依赖事件占位身份；即使 registry 已空，也必须幂等关闭执行窗口。
            ChainExecutionContext active = null;
            for (ChainExecutionContext candidate : registry.snapshot()) {
                if (playerUUID.equals(candidate.getPlayerUUID())) {
                    active = candidate;
                    break;
                }
            }
            if (active != null) active.requestPlanningStop();
            registry.remove(playerUUID);
            setExecutionWindow(playerUUID, false, "lifecycle-cleanup-forced:" + event.getReason());
            MyMod.LOG.debug(
                    "[ChainExecution] forced LifecycleCleanup cleared player context player={} gen={} round={} reason={}",
                    playerUUID, Integer.valueOf(event.getGeneration()), Long.valueOf(event.getServerRoundId()),
                    event.getReason());
            return;
        }

        ChainExecutionContext context = registry.get(
                playerUUID, event.getGeneration(), event.getServerRoundId());
        if (context != null) context.requestPlanningStop();
        boolean removed = registry.remove(playerUUID, event.getGeneration(), event.getServerRoundId());
        if (removed) {
            setExecutionWindow(playerUUID, false, "lifecycle-cleanup-round:" + event.getReason());
        }
        MyMod.LOG.debug(
                "[ChainExecution] round-isolated LifecycleCleanup player={} gen={} round={} removed={} reason={}",
                playerUUID, Integer.valueOf(event.getGeneration()), Long.valueOf(event.getServerRoundId()),
                Boolean.valueOf(removed), event.getReason());
    }

    /** 仅普通 CHAIN/AREA 采掘使用 local batch；GT SPECIAL 与交互分支保持原分支。 */
    private static boolean usesLocalToolSwap(ChainSession session) {
        if (session == null || session.getRequest() == null) return false;
        return usesLocalToolSwap(session.getRequest().getMode());
    }

    /** 纯模式范围判定；AREA 包含 AREA_TUNNEL，INTERACT/SPECIAL 保持既有执行分支。 */
    static boolean usesLocalToolSwap(ChainMode mode) {
        return mode == ChainMode.CHAIN || mode == ChainMode.AREA;
    }

    /** 普通执行队首的 local prepare 接缝；正常值不含网络 WAIT。 */
    interface OrdinaryTargetGate {
        PrepareResult prepareTarget(ChainTarget target);
    }

    /** 普通目标执行边界；生产实现委托给当前模式的 ChainActionExecutor。 */
    interface OrdinaryTargetExecutor {
        boolean canExecute(ChainTarget target);
        boolean execute(ChainTarget target);
    }

    /** 单 tick 非 GT 普通执行纯值结果，用于统一推进、节流和完成尾处理。 */
    static final class OrdinaryTickResult {
        private final int processedTargets;
        private final int executedTargets;
        private final boolean stopped;
        private final ChainTarget firstRoundSuccessfulTarget;

        private OrdinaryTickResult(int processedTargets, int executedTargets, boolean stopped,
                ChainTarget firstRoundSuccessfulTarget) {
            this.processedTargets = processedTargets;
            this.executedTargets = executedTargets;
            this.stopped = stopped;
            this.firstRoundSuccessfulTarget = firstRoundSuccessfulTarget;
        }

        int getProcessedTargets() { return processedTargets; }
        int getExecutedTargets() { return executedTargets; }
        boolean isStopped() { return stopped; }
        ChainTarget getFirstRoundSuccessfulTarget() { return firstRoundSuccessfulTarget; }
    }

    /**
     * 按实际 poll 数而非成功数消费非 GT 普通目标；SKIP_TARGET 只消费队首且绝不到达执行器。
     */
    static OrdinaryTickResult consumeOrdinaryTargets(ChainExecutionContext context, int maxBreakPerTick,
            OrdinaryTargetGate gate, OrdinaryTargetExecutor executor) {
        if (context == null || gate == null || executor == null) {
            throw new IllegalArgumentException("ordinary execution dependencies must not be null");
        }
        int processed = 0;
        int executed = 0;
        boolean stopped = false;
        ChainTarget firstRoundSuccessfulTarget = null;
        int processingBudget = Math.max(0, maxBreakPerTick);
        while (processed < processingBudget) {
            ChainTarget target = context.getTargets().peek();
            if (target == null) break;
            PrepareResult decision = gate.prepareTarget(target);
            if (decision == null || decision == PrepareResult.STOP) {
                stopped = true;
                break;
            }
            target = pollTargetAfterLocalPreparation(context, decision);
            if (target == null) break;
            processed++;
            if (decision == PrepareResult.SKIP_TARGET) continue;
            try {
                if (!executor.canExecute(target) || !executor.execute(target)) continue;
            } catch (RuntimeException failure) {
                // 单目标执行异常不得跳过 batch 尾屏障，否则 local ledger 可能失去恢复机会。
                continue;
            } catch (LinkageError failure) {
                continue;
            }
            executed++;
            if (context.recordExecutionSucceeded() && firstRoundSuccessfulTarget == null) {
                firstRoundSuccessfulTarget = target;
            }
        }
        return new OrdinaryTickResult(processed, executed, stopped, firstRoundSuccessfulTarget);
    }

    /**
     * 普通执行循环共用的队首消费接缝：PROCEED/SKIP_TARGET 可 poll，后者额外记录跳过。
     *
     * @return 被消费的队首；STOP 或空队列返回 null
     */
    static ChainTarget pollTargetAfterLocalPreparation(ChainExecutionContext context,
            PrepareResult gate) {
        if (context == null || gate != PrepareResult.PROCEED
                && gate != PrepareResult.SKIP_TARGET) return null;
        ChainTarget target = context.getTargets().poll();
        if (target != null) {
            context.recordExecutionConsumed();
            if (gate == PrepareResult.SKIP_TARGET) {
                context.recordExecutionSkipped();
            }
        }
        return target;
    }

    // ============================ 纯逻辑构造（供单测覆盖） ============================

    /**
     * G1 掉落窗口接线（I5 生命线）。
     *
     * <p>通过 {@code MyMod.chainStateService.getPlayerState(uuid).setExecuting(...)} 控
     * {@link ChainDropCollector} 的收集开关（:32 isExecuting）与释放开关
     * （:58 executionStatus!=IDLE 时暂存、回 IDLE 才释放）。调用点五处：
     * {@link #onPlanStarted}（流式开窗 true）、{@link #onPlanCompleted}（登记 true）、
     * {@link #publishExecutionFinishedWithCleanup}
     * （publish 后 false）、{@link #onWatchdogTimeout}（必须 false）、
     * {@link #onLifecycleCleanup}（幂等 false）、{@link #onPlanCancelled}（取消 false）。</p>
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

    /** 仅为本 round 已确认的首个真实额外执行成功输出一次坐标诊断。 */
    private void logFirstSuccessfulExecution(ChainExecutionContext context, ChainTarget target) {
        logTimeline(context, "FirstExtraExecution", "pos=(" + target.getX() + "," + target.getY() + ","
                + target.getZ() + ") executionConsumed=" + context.getExecutionConsumedCount());
    }

    /** 输出与 planner/工具换位 round 可关联的有界执行时间线。 */
    private void logTimeline(ChainExecutionContext context, String stage, String details) {
        MyMod.LOG.debug("[ChainPlanDiag] player={} round={} generation={} stage={} {}",
                context.getPlayerUUID(), Long.valueOf(context.getServerRoundId()),
                Integer.valueOf(context.getGeneration()), stage, details);
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
        return buildExecutionFinished(playerUUID,
                club.heiqi.qz_miner.chain.eventbus.ChainEvent.NO_SERVER_ROUND_ID, gen, tick, nanos, reason);
    }

    /** 构造带不可变服务端轮次关联的执行结束事件。 */
    public static ExecutionFinished buildExecutionFinished(UUID playerUUID, long serverRoundId, int gen, long tick,
                                                           long nanos, String reason) {
        return new ExecutionFinished(playerUUID, serverRoundId, gen, tick, nanos, reason);
    }

    /**
     * B3 预校验：GT 线缆单 tick 原子替换前的放行门。
     * 三项校验：主手是线缆 / 链路不超单 tick 上限 / 背包同种线缆充足。
     *
     * @param player  触发玩家（主线程）
     * @param session 会话（保留参数以备后续扩展）
     * @param context 执行上下文（读 targets 队列）
     * @return null 表示放行；非 null 为失败原因（聊天提示用）
     */
    private String precheckCableReplacement(EntityPlayerMP player, ChainSession session, ChainExecutionContext context) {
        // 1. 主手是线缆（planner 已拦截，此处复核防会话锁与主手不一致）
        ItemStack mainHand = player.inventory.getCurrentItem();
        if (!CompatAdapters.cable().isCableStack(mainHand)) {
            return "主手未持有线缆，无法替换";
        }
        int targetMetaId = mainHand.getItemDamage();

        // 2. 链路不超单 tick 上限（F3 truncated 也由此兜住：chainMaxBlocks=1024=cableReplaceMaxPerTick）
        int queueSize = context.getTargets().size();
        if (queueSize > Config.cableReplaceMaxPerTick) {
            return "线缆链路过大（" + queueSize + " 超过单 tick 上限 " + Config.cableReplaceMaxPerTick + "），已取消避免电压不匹配";
        }

        // 3. 背包同种线缆总数 >= 链路目标数
        int available = countMatchingCables(player, targetMetaId);
        if (available < queueSize) {
            return "线缆不足：需要 " + queueSize + " 根，背包仅有 " + available + " 根";
        }

        return null;
    }

    /**
     * 统计背包中指定 metaTileId 的线缆物品总数（含主手，含堆叠 size）。
     *
     * @param player        触发玩家
     * @param targetMetaId  目标线缆 metaTileId（= 主手线缆 getItemDamage）
     * @return 背包中匹配线缆的总数
     */
    private int countMatchingCables(EntityPlayerMP player, int targetMetaId) {
        int count = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null
                && CompatAdapters.cable().isCableStack(stack)
                && stack.getItemDamage() == targetMetaId) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    /**
     * 聊天提示玩家（主线程安全）。
     *
     * @param player  目标玩家（主线程）
     * @param message 提示内容
     */
    private void notifyPlayer(EntityPlayerMP player, String message) {
        player.addChatComponentMessage(new ChatComponentText(message));
    }
}
