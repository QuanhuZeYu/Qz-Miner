package club.heiqi.qz_miner.chain.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionAdvanced;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionDeferred;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
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
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import club.heiqi.qz_miner.parallel.TickTimeBudget;
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
 * 在服务端主线程登记执行上下文、管理掉落窗口，并按共享 soft deadline 消费目标。
 *
 * <p>worker 追加规划目标，主线程经模式执行器修改世界。普通采掘逐目标检查预算并在批次尾统一结算；
 * GT 线缆等待完整规划及预检后在单 tick 内原子执行。
 * 终局尝试恢复借用工具并归类结果，再依次发布 ExecutionFinished 与 LifecycleCleanup。
 * 本桥管理 registry 和执行窗口，状态机管理 phase、generation 与槽位。
 * 轮次事件沿用 context 冻结的 UUID、generation、serverRoundId，清理按所属身份隔离。</p>
 */
public class ChainExecutionEventBridge {

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;
    /** 注入的执行上下文注册表（主线程登记和领取）。 */
    private final ChainExecutionContextRegistry registry;
    /** 普通 CHAIN/AREA 的服务端本地候选与唯一 physical ledger owner。 */
    private final AutoToolSwapServerBatchService localToolSwap;
    /** 上一个获得执行窗口的玩家；下 tick 从其后继开始轮转。 */
    private UUID lastServedPlayerUUID;

    /**
     * 订阅连锁事件；FML tick 监听由 {@link #bootstrap()} 注册。
     *
     * <p>初始化先注册 drainer，再注册本桥，使同 tick 的完成事件先被观察，再消费执行队列。</p>
     *
     * @param bus 事件总线
     * @param registry 执行上下文注册表
     */
    public ChainExecutionEventBridge(ChainEventBus bus, ChainExecutionContextRegistry registry) {
        this(bus, registry, null);
    }

    /** 创建带服务端批量工具接替能力的执行桥。 */
    public static ChainExecutionEventBridge withLocalToolSwap(ChainEventBus bus,
            ChainExecutionContextRegistry registry, AutoToolSwapServerBatchService localToolSwap) {
        return new ChainExecutionEventBridge(bus, registry, localToolSwap);
    }

    private ChainExecutionEventBridge(ChainEventBus bus, ChainExecutionContextRegistry registry,
            AutoToolSwapServerBatchService localToolSwap) {
        this.bus = bus;
        this.registry = registry;
        this.localToolSwap = localToolSwap;
        bus.subscribe(PlanCompleted.class, this::onPlanCompleted);
        bus.subscribe(PlanCancelled.class, this::onPlanCancelled);
        // 超时与生命周期事件统一清理本桥持有的上下文。
        bus.subscribe(WatchdogTimeout.class, this::onWatchdogTimeout);
        bus.subscribe(LifecycleCleanup.class, this::onLifecycleCleanup);
    }

    /** 由 {@link MyMod#init} 注册服务端 tick 监听，将 Forge 接入与构造分开。 */
    public void bootstrap() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 规划桥在注册 worker 前调用：重验当前授权，登记成功后同步开启掉落窗口。
     * worker 仅追加已登记队列，取消按 UUID、generation 与 serverRoundId 关闭所属窗口。
     *
     * @return 是否取得该玩家当前执行上下文的所有权
     */
    public boolean registerPlanningContext(ChainExecutionContext context) {
        // 重验状态机中的当前规划授权，再登记上下文。
        if (MyMod.chainStateMachine == null || !MyMod.chainStateMachine.permitsPlanning(
                context.getPlayerUUID(), context.getGeneration(), context.getServerRoundId())) {
            return false;
        }
        registry.put(context);
        if (registry.get(context.getPlayerUUID(), context.getGeneration(), context.getServerRoundId()) != context) {
            return false;
        }
        setExecutionWindow(context.getPlayerUUID(), true, "planning-context-registered");
        return true;
    }

    /** 主线程观察匹配轮次的规划完成；处理挂起停止或空规划，其余目标留 tick 回调消费。 */
    private void onPlanCompleted(PlanCompleted event) {
        UUID playerUUID = event.getPlayerUUID();
        int gen = event.getGeneration();
        ChainExecutionContext context = registry.get(playerUUID, gen, event.getServerRoundId());
        if (context == null) {
            // 缺少匹配上下文时保留当前轮次并结束处理。
            MyMod.LOG.debug("[ChainExecution] PlanCompleted gen={} player={} has no matching context; skip",
                    Integer.valueOf(gen), playerUUID);
            return;
        }
        logTimeline(context, "PlanCompleted", "workerConfirmed=" + event.getTotalTargets()
                + " queueNow=" + context.getTargets().size());

        // 完成先胜出时，待主线程观察 PlanCompleted 后再兑现挂起的停止请求。
        if (context.observePlanningCompletionAndShouldStop()) {
            publishExecutionFinishedWithCleanup(context, "auto-tool-local-stopped-after-plan-completed");
            registry.remove(playerUUID, gen, context.getServerRoundId());
            return;
        }

        // 规划完成且队列已空时立即收尾。
        if (context.isCompleted()) {
            publishExecutionFinishedWithCleanup(context, "empty-plan");
            registry.remove(playerUUID, gen, context.getServerRoundId());
            return;
        }

        // 观察完成时同步确认掉落窗口，目标交由后续 tick 回调消费。
        setExecutionWindow(playerUUID, true, "plan-completed");
    }

    /** 服务端 tick 的 START 阶段在共享预算内消费活跃上下文，完成后发布收尾事件。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        TickTimeBudget timeBudget = currentServerTimeBudget();
        if (timeBudget == null) {
            return;
        }
        consumeScheduledContexts(timeBudget);
    }

    /** 遍历所有上下文：普通路径按预算延期，GT 路径自行完成原子入场检查。 */
    void consumeScheduledContexts(TickTimeBudget timeBudget) {
        if (timeBudget == null) return;
        // 将弱一致视图按 UUID 排序，起点跨 tick 轮转。
        List<ChainExecutionContext> contexts = roundRobinSnapshot(registry.snapshot(), lastServedPlayerUUID);
        for (ChainExecutionContext context : contexts) {
            if (consumeContext(context, timeBudget)) {
                lastServedPlayerUUID = context.getPlayerUUID();
            }
        }
    }

    private TickTimeBudget currentServerTimeBudget() {
        return MyMod.parallelTickExecutor == null ? null
                : MyMod.parallelTickExecutor.currentTimeBudget(ParallelTickStage.SERVER_PRE);
    }

    /**
     * 主线程解析玩家和模式执行器，再按普通批次或 GT 原子路径消费上下文。
     *
     * @param context 执行上下文
     * @param timeBudget 当前 tick 的共享 soft deadline；GT 入场后完成整个原子批次
     * @return 是否推进 ordinary round-robin cursor
     */
    boolean consumeContext(ChainExecutionContext context, TickTimeBudget timeBudget) {
        UUID playerUUID = context.getPlayerUUID();
        int gen = context.getGeneration();

        // 三元组1：解析玩家
        EntityPlayer rawPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
        if (!(rawPlayer instanceof EntityPlayerMP)) {
            publishExecutionFinishedWithCleanup(context, "player-unavailable");
            registry.remove(playerUUID, gen, context.getServerRoundId());
            return false;
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
            return false;
        }

        // 三元组3：按执行模式分叉
        boolean waitForPlanner = actionExecutor.shouldWaitForPlannerCompletion(session);

        if (waitForPlanner) {
            // ===== GT 线缆特例：单 tick 原子替换（B1+B2+B3）=====
            // 等完整链路规划完成后统一替换，保持链路电压一致。
            if (!context.isPlanningComplete()) {
                // worker 仍在搜，留下一 tick 再判
                return shouldAdvanceOrdinaryCursor(true, null, timeBudget);
            }

            final EntityPlayerMP cablePlayer = player;
            final ChainSession cableSession = session;
            final ChainActionExecutor cableExecutor = actionExecutor;
            CableDrainResult cableResult = consumeGregTechCableTargets(context,
                    () -> precheckCableReplacement(cablePlayer, cableSession, context),
                    new OrdinaryTargetExecutor() {
                        @Override
                        public boolean canExecute(ChainTarget target) {
                            return cableExecutor.canExecute(cablePlayer, cableSession, target);
                        }

                        @Override
                        public boolean execute(ChainTarget target) {
                            return cableExecutor.execute(cablePlayer, cableSession, target);
                        }
                    });
            String precheckFail = cableResult.getPrecheckFailure();
            if (precheckFail != null) {
                // 预校验失败：取消连锁 + 聊天提示 + 清锁
                notifyPlayer(player, "[QzMiner] " + precheckFail);
                publishExecutionFinishedWithCleanup(context, "cable-precheck-failed");
                GregTechCableSessionState.clear(playerUUID);
                registry.remove(playerUUID, gen, context.getServerRoundId());
                return shouldAdvanceOrdinaryCursor(true, null, timeBudget);
            }

            int totalTargets = cableResult.getTotalTargets();
            int executed = cableResult.getExecutedTargets();
            int failed = cableResult.getFailedTargets();

            // best-effort 提示
            if (failed > 0) {
                notifyPlayer(player, "[QzMiner] 线缆替换完成：" + executed + "/" + totalTargets + " 根成功，" + failed + " 根失败");
            }

            // 发布本批执行推进，供 watchdog 与投影观察。
            bus.publish(new ExecutionAdvanced(playerUUID, context.getServerRoundId(), gen,
                ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                executed, 0));

            // 完成后发布终局并释放线缆会话锁。
            publishExecutionFinishedWithCleanup(context, "cable-atomic-complete:" + executed);
            GregTechCableSessionState.clear(playerUUID);
            registry.remove(playerUUID, gen, context.getServerRoundId());
            return shouldAdvanceOrdinaryCursor(true, null, timeBudget);
        }

        // ===== 非 GT：与 planning 共享本 server tick 的 soft deadline =====
        if (timeBudget == null || !timeBudget.hasTimeLeft()) {
            publishDeadlineDeferral(context);
            return shouldAdvanceOrdinaryCursor(false, null, timeBudget);
        }
        if (!usesLocalToolSwap(session)) {
            // INTERACT 与非 GT SPECIAL 直接使用模式执行器，共享 deadline 与消费计数。
            return consumeNonTakeoverTargets(player, session, actionExecutor, context, timeBudget);
        }
        final EntityPlayerMP ordinaryPlayer = player;
        final ChainSession ordinarySession = session;
        final ChainActionExecutor ordinaryExecutor = actionExecutor;
        final long ordinaryTick = Math.max(0L, ChainTickSource.currentServerTick());
        final BatchToken batchToken = localToolSwap == null ? null
                : localToolSwap.beginOrdinaryBatch(ordinaryPlayer, context.getServerRoundId(), gen, ordinaryTick);
        OrdinaryTickResult tickResult = consumeOrdinaryTargets(context, timeBudget::hasTimeLeft,
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
        boolean advanceOrdinaryCursor = shouldAdvanceOrdinaryCursor(false, tickResult, timeBudget);
        if (!advanceOrdinaryCursor) {
            publishDeadlineDeferral(context);
        }
        return advanceOrdinaryCursor;
    }

    /** INTERACT 与非 GT SPECIAL 直接在共享 deadline 内消费模式目标。 */
    private boolean consumeNonTakeoverTargets(EntityPlayerMP player, ChainSession session,
            ChainActionExecutor actionExecutor, ChainExecutionContext context, TickTimeBudget timeBudget) {
        OrdinaryTickResult tickResult = consumeOrdinaryTargets(context, timeBudget::hasTimeLeft,
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
        boolean advanceOrdinaryCursor = shouldAdvanceOrdinaryCursor(false, tickResult, timeBudget);
        if (!advanceOrdinaryCursor) {
            publishDeadlineDeferral(context);
        }
        return advanceOrdinaryCursor;
    }

    private void publishDeadlineDeferral(ChainExecutionContext context) {
        if (context == null) {
            return;
        }
        bus.publish(new ExecutionDeferred(context.getPlayerUUID(), context.getServerRoundId(),
                context.getGeneration(), ChainTickSource.currentServerTick(), ChainTickSource.nowNanos()));
    }

    /** 纯测试/非 local 路径：先发布本 tick 真实推进，再沿既有 STOP 合同收口。 */
    void finishOrdinaryTickAndStopIfNeeded(ChainExecutionContext context, OrdinaryTickResult tickResult) {
        finishOrdinaryTick(context, tickResult);
        if (tickResult.isStopped()) {
            stopOrdinaryPlanning(context);
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
            stopOrdinaryPlanning(context);
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
     * 先结算工具恢复尝试与结果，再沿 context 身份发布执行结束与生命周期清理。
     * 状态机按此事件顺序完成 RUNNING→FINISHING→IDLE，并保留在线玩家代际。
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
        // 关闭收集窗口，交给后续 WorldTick 释放已聚合的掉落。
        setExecutionWindow(playerUUID, false, "execution-finished:" + reason);
        // 同轮清理紧随执行结束入队，供状态机退出 FINISHING 并保留玩家代际。
        bus.publish(new LifecycleCleanup(
                playerUUID, serverRoundId, gen, tick, nanos, "execution-complete", false, false));
    }

    /**
     * ordinary local STOP 与 worker 完成线性化：取消胜出只发精确 Cleanup，完成胜出则等待
     * PlanCompleted 被主线程观察后再走合法 ExecutionFinished 收口。
     */
    void stopOrdinaryPlanning(ChainExecutionContext context) {
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
        // 规划期取消使用冻结身份发布 Cleanup，直接收口本轮。
        setExecutionWindow(playerUUID, false, "planning-cancelled:auto-tool-local-stopped");
        bus.publish(new LifecycleCleanup(playerUUID, context.getServerRoundId(), context.getGeneration(),
                tick, nanos, "auto-tool-local-stopped", false, false));
        registry.remove(playerUUID, context.getGeneration(), context.getServerRoundId());
    }

    /** 移除匹配轮次的流式上下文并关闭掉落窗口；状态机处理规划取消转移。 */
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

    /** 超时后请求规划协作停止，移除匹配上下文并关闭所属窗口。 */
    private void onWatchdogTimeout(WatchdogTimeout event) {
        ChainExecutionContext context = registry.get(
                event.getPlayerUUID(), event.getGeneration(), event.getServerRoundId());
        if (context != null) context.requestPlanningStop();
        boolean removed = registry.remove(event.getPlayerUUID(), event.getGeneration(), event.getServerRoundId());
        // 成功移除所属上下文后关闭窗口，使缓冲掉落进入释放流程。
        if (removed) {
            setExecutionWindow(event.getPlayerUUID(), false, "watchdog-timeout");
        }
        MyMod.LOG.debug("[ChainExecution] registry cleanup on WatchdogTimeout player={} gen={}",
                event.getPlayerUUID(), Integer.valueOf(event.getGeneration()));
    }

    /**
     * forced 按 UUID 停止当前规划、移除上下文并幂等关闭窗口；
     * 普通收尾按 UUID、generation 与 serverRoundId 清理所属执行轮。
     */
    private void onLifecycleCleanup(LifecycleCleanup event) {
        UUID playerUUID = event.getPlayerUUID();
        if (event.isForced()) {
            // 强制清理按玩家释放当前上下文，并幂等关闭窗口。
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

    /** 普通执行队首的服务端工具准备入口。 */
    interface OrdinaryTargetGate {
        PrepareResult prepareTarget(ChainTarget target);
    }

    /** 普通目标执行边界；生产实现委托给当前模式的 ChainActionExecutor。 */
    interface OrdinaryTargetExecutor {
        boolean canExecute(ChainTarget target);
        boolean execute(ChainTarget target);
    }

    /** GT 预检与原子 drain 的纯值结果。 */
    static final class CableDrainResult {
        private final String precheckFailure;
        private final int totalTargets;
        private final int executedTargets;
        private final int failedTargets;

        private CableDrainResult(String precheckFailure, int totalTargets, int executedTargets, int failedTargets) {
            this.precheckFailure = precheckFailure;
            this.totalTargets = totalTargets;
            this.executedTargets = executedTargets;
            this.failedTargets = failedTargets;
        }

        String getPrecheckFailure() { return precheckFailure; }
        int getTotalTargets() { return totalTargets; }
        int getExecutedTargets() { return executedTargets; }
        int getFailedTargets() { return failedTargets; }
    }

    /** 截断计划在任何背包检查或目标回调前拒绝；放行后单 tick drain 到队列为空。 */
    CableDrainResult consumeGregTechCableTargets(ChainExecutionContext context,
            Supplier<String> precheck, OrdinaryTargetExecutor executor) {
        if (context == null || precheck == null || executor == null) {
            throw new IllegalArgumentException("cable execution dependencies must not be null");
        }
        int totalTargets = context.getTargets().size();
        if (context.isPlanningTargetLimitExceeded()) {
            return new CableDrainResult(
                    "线缆链路超过本轮安全上限，已取消避免只替换链路前缀", totalTargets, 0, 0);
        }
        String precheckFailure = precheck.get();
        if (precheckFailure != null) {
            return new CableDrainResult(precheckFailure, totalTargets, 0, 0);
        }

        int executed = 0;
        int failed = 0;
        while (true) {
            ChainTarget target = context.getTargets().poll();
            if (target == null) break;
            context.recordExecutionConsumed();
            if (!executor.canExecute(target)) continue;
            try {
                if (!executor.execute(target)) {
                    failed++;
                    continue;
                }
            } catch (RuntimeException e) {
                MyMod.LOG.error("[CableReplace] 单 tick 批量替换异常 player={} pos=({},{},{})",
                        context.getPlayerUUID(), Integer.valueOf(target.getX()), Integer.valueOf(target.getY()),
                        Integer.valueOf(target.getZ()), e);
                failed++;
                continue;
            }
            executed++;
            if (context.recordExecutionSucceeded()) logFirstSuccessfulExecution(context, target);
        }
        return new CableDrainResult(null, totalTargets, executed, failed);
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
     * 在共享 deadline 内消费非 GT 目标；SKIP_TARGET 消费队首并记录局部跳过。
     * deadline 只在目标事务开始前检查，已开始的 prepare/poll/execute 必须完整收口。
     */
    static OrdinaryTickResult consumeOrdinaryTargets(ChainExecutionContext context, BooleanSupplier hasTimeLeft,
            OrdinaryTargetGate gate, OrdinaryTargetExecutor executor) {
        if (context == null || hasTimeLeft == null || gate == null || executor == null) {
            throw new IllegalArgumentException("ordinary execution dependencies must not be null");
        }
        int processed = 0;
        int executed = 0;
        boolean stopped = false;
        ChainTarget firstRoundSuccessfulTarget = null;
        while (hasTimeLeft.getAsBoolean()) {
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
                // 单目标异常结束本次尝试，批次尾统一结算。
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

    /** 已处理目标、收到 STOP 或仍有预算时推进轮转游标；预算延期保留当前位置。 */
    static boolean shouldAdvanceOrdinaryCursor(boolean waitForPlanner,
            OrdinaryTickResult tickResult, TickTimeBudget timeBudget) {
        if (waitForPlanner || tickResult == null || timeBudget == null) return false;
        return tickResult.getProcessedTargets() > 0 || tickResult.isStopped() || timeBudget.hasTimeLeft();
    }

    /** 按 UUID 稳定排序并从上次服务玩家的后继起步，保证跨 Tick 的确定性轮转。 */
    static List<ChainExecutionContext> roundRobinSnapshot(
            java.util.Collection<ChainExecutionContext> contexts, UUID afterPlayer) {
        List<ChainExecutionContext> ordered = new ArrayList<ChainExecutionContext>();
        if (contexts != null) {
            ordered.addAll(contexts);
        }
        Collections.sort(ordered, new Comparator<ChainExecutionContext>() {
            @Override
            public int compare(ChainExecutionContext left, ChainExecutionContext right) {
                return left.getPlayerUUID().compareTo(right.getPlayerUUID());
            }
        });
        if (afterPlayer == null || ordered.size() < 2) {
            return ordered;
        }
        int split = 0;
        while (split < ordered.size()
                && ordered.get(split).getPlayerUUID().compareTo(afterPlayer) <= 0) {
            split++;
        }
        if (split == 0 || split == ordered.size()) {
            if (split == ordered.size()) {
                return ordered;
            }
            return ordered;
        }
        List<ChainExecutionContext> rotated = new ArrayList<ChainExecutionContext>(ordered.size());
        rotated.addAll(ordered.subList(split, ordered.size()));
        rotated.addAll(ordered.subList(0, split));
        return rotated;
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
     * 更新玩家的掉落收集窗口；窗口开启时聚合掉落，关闭后交给收集器释放。
     * 玩家状态已释放时直接结束，适配重复生命周期清理。
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

    /** 构造兼容缺省轮次的执行结束事件，gen 沿用调用方冻结的上下文身份。 */
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
     * GT 线缆单 tick 原子替换前的放行门。
     * 三项校验：主手是线缆 / 链路不超单 tick 上限 / 背包同种线缆充足。
     *
     * @param player  触发玩家（主线程）
     * @param session 会话参数
     * @param context 执行上下文（读 targets 队列）
     * @return null 表示放行；非 null 为失败原因（聊天提示用）
     */
    private String precheckCableReplacement(EntityPlayerMP player, ChainSession session, ChainExecutionContext context) {
        // 执行前复核当前主手线缆。
        ItemStack mainHand = player.inventory.getCurrentItem();
        if (!CompatAdapters.cable().isCableStack(mainHand)) {
            return "主手未持有线缆，无法替换";
        }
        int targetMetaId = mainHand.getItemDamage();

        // 2. 链路不超单 tick 上限。
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
