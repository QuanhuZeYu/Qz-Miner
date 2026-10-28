package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.execution.ChainExecutionContext;
import club.heiqi.qz_miner.chain.execution.ChainExecutionEventBridge;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.PlanProgress;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.selection.CuboidBounds;
import club.heiqi.qz_miner.chain.selection.CuboidSelection;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.toolswap.server.MinecraftAutoToolSwapInventoryPort;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 在主线程接收 PlanStarted，冻结会话配置与采掘能力，登记上下文后启动只读规划 worker。
 *
 * <p>worker 复用预算化 traverser 和 matcher，将确认目标追加到执行队列，并沿冻结代际、轮次发布进度与终局。
 * 完成事件成功入队后才公开 planningComplete；发布异常转为单次取消。
 * 执行桥管理掉落窗口及世界写入，状态机消费结果并维护阶段。</p>
 */
public class ChainPlanningEventBridge {

    /** 注入的事件总线，构造期订阅 PlanStarted。 */
    private final ChainEventBus bus;
    /** 主线程执行入口统一登记 context，并为成功登记的任务开启窗口。 */
    private final ChainExecutionEventBridge executionBridge;

    /**
     * 构造桥并订阅 {@link PlanStarted}。
     *
     * @param bus                      事件总线（与状态机共享同一实例）
     * @param executionBridge         主线程执行上下文登记入口
     */
    public ChainPlanningEventBridge(ChainEventBus bus, ChainExecutionEventBridge executionBridge) {
        this.bus = bus;
        this.executionBridge = executionBridge;
        bus.subscribe(PlanStarted.class, this::onPlanStarted);
    }

    /** 主线程重验规划授权，冻结种子与会话配置，登记执行上下文后注册 worker。 */
    private void onPlanStarted(PlanStarted event) {
        // cleanup可能已先于排队的PlanStarted完成；任何玩家/世界解析前核对当前授权。
        if (MyMod.chainStateMachine == null || !MyMod.chainStateMachine.permitsPlanning(
                event.getPlayerUUID(), event.getGeneration(), event.getServerRoundId())) {
            return;
        }
        final UUID playerUUID = event.getPlayerUUID();
        // 主线程冻结轮次关联，供 worker 沿事件流回填。
        final long serverRoundId = event.getServerRoundId();
        // 规划代际与轮次一起沿用启动事件。
        final int planningGen = event.getGeneration();

        // 主线程解析玩家实体（worker 启动后每次分片重新解析，此处仅在注册前快速失败）
        EntityPlayer rawPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
        if (!(rawPlayer instanceof EntityPlayerMP)) {
            bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    "shadow-player-unavailable"));
            return;
        }
        final EntityPlayerMP player = (EntityPlayerMP) rawPlayer;

        // 从玩家状态读取启动配置，规划授权由状态机提供。
        ChainPlayerState playerState = MyMod.chainStateService == null ? null : MyMod.chainStateService.getPlayerState(playerUUID);
        if (playerState == null) {
            bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    "shadow-state-missing"));
            return;
        }
        final ChainMode mode = playerState.getSelectedMode();
        final ChainSubMode subMode = playerState.getSelectedSubMode();
        final CuboidBounds cuboidBounds;
        if (subMode == ChainSubMode.AREA_CUBOID_CLEAR) {
            CuboidSelection selection = playerState.getCuboidSelection();
            CuboidBounds selectedBounds = selection == null ? null : selection.bounds();
            if (selectedBounds == null
                    || selectedBounds.getDimensionId() != player.dimension
                    || !selectedBounds.fitsWithin(playerState.resolveAcceptedChainMaxBlocks())) {
                bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                        ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                        "cuboid-selection-unavailable"));
                return;
            }
            cuboidBounds = selectedBounds;
        } else {
            cuboidBounds = null;
        }
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
        if (definition == null) {
            bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    "shadow-mode-undefined"));
            return;
        }

        final ChainTarget origin = new ChainTarget(event.getX(), event.getY(), event.getZ());
        // 破坏与右键沿用事件窗口冻结的 block/meta/token，保留动作前身份；左键缺省种子由 resolver 解析。
        BlockSeedSnapshot seedSnapshot;
        if (event.getSeedBlock() != null) {
            seedSnapshot = new BlockSeedSnapshot(
                    origin, event.getSeedBlock(), event.getSeedMeta(), event.getSeedTileIdentity());
        } else {
            BlockSeedResolver seedResolver = new WorldBlockSeedResolver();
            seedSnapshot = seedResolver.resolve(player, origin);
        }
        if (seedSnapshot == null) {
            bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    "shadow-seed-unresolvable"));
            return;
        }
        if (!seedSnapshot.getSampleTileIdentity().isResolved()) {
            bus.publish(buildUnresolvedSeedIdentityPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos()));
            return;
        }

        ObjectGroupRuleSet rules = playerState.getObjectGroupRules();
        String registry = ObjectGroupBlockPredicate.registryName(seedSnapshot.getSampleBlock());
        ModeExtensionSnapshot modeExtension = rules == null ? ModeExtensionSnapshot.EMPTY
                : rules.resolve(ObjectGroupMode.maskFor(subMode), registry, seedSnapshot.getSampleMeta());

        // 冻结本轮配置，供 runtime 工厂装配 traverser/matcher 及执行桥解析执行器。
        final int requestedRadius = playerState.getRequestedChainRadius();
        final int requestedMaxBlocks = playerState.getRequestedChainMaxBlocks();
        final ChainSession shadowSession = new ChainSession(
                playerUUID, mode, subMode, origin,
                event.getSideHit(), event.getHitX(), event.getHitY(), event.getHitZ(),
                requestedRadius, requestedMaxBlocks, modeExtension,
                seedSnapshot.getSampleBlock(), seedSnapshot.getSampleMeta(),
                seedSnapshot.getSampleTileIdentity(), cuboidBounds);
        final ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics =
                ChainPlanningRuntimeFactory.PlanningDiagnostics.production(playerUUID, serverRoundId, planningGen,
                        String.valueOf(mode), String.valueOf(subMode));
        Object seedRegistryName = Block.blockRegistry.getNameForObject(seedSnapshot.getSampleBlock());
        diagnostics.logPlanStarted(seedRegistryName == null ? "minecraft:unknown" : String.valueOf(seedRegistryName),
                seedSnapshot.getSampleMeta(), origin, Thread.currentThread().getName(), player.inventory.currentItem,
                MinecraftAutoToolSwapInventoryPort.describeStack(player.inventory.getCurrentItem()));
        // CHAIN 冻结采掘能力供 worker 判定拓扑；其余模式按世界、空间和子模式结构收集目标。
        final PlanningToolCapabilitySnapshot capabilitySnapshot =
                ChainPlanningRuntimeFactory.usesFrozenToolCapabilities(mode)
                        ? PlanningToolCapabilitySnapshot.capture(
                                player, Config.autoToolPrioritySelectors, true)
                        : null;
        // 复用 runtime 工厂装配本轮规划。
        final ChainPlanningRuntime runtime = ChainPlanningRuntimeFactory.createForServer(
                player.worldObj, player, shadowSession, seedSnapshot, diagnostics, capabilitySnapshot);
        if (runtime == null) {
            diagnostics.logPlanCancelled("shadow-runtime-null");
            bus.publish(buildRuntimeNullPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos()));
            return;
        }
        final ChainSearchContext searchContext = runtime.getSearchContext();
        final BudgetedChainTraverser traverser = runtime.getTraverser();
        final ChainBlockMatcher matcher = runtime.getMatcher();
        // worker 追加确认目标，主线程执行桥消费。
        final ConcurrentLinkedQueue<ChainTarget> shadowQueue = new ConcurrentLinkedQueue<ChainTarget>();
        traverser.seed(searchContext);

        // worker 注册前登记并开窗；规划完成与队列耗尽共同决定执行终局。
        // registerPre 拒绝时发布 PlanCancelled，由执行桥释放已登记的上下文与窗口。
        final ChainExecutionContext context = new ChainExecutionContext(
                playerUUID, serverRoundId, planningGen, shadowQueue, shadowSession, diagnostics);
        if (!executionBridge.registerPlanningContext(context)) {
            // 登记失败即结束，保留当前上下文的所有权。
            return;
        }

        // 注册协作式规划 worker。
        try {
            ParallelTickSubscription subscription = MyMod.ensureParallelTickExecutor().registerPre(
                    "shadow-plan-" + playerUUID,
                    control -> runShadowSlice(control, playerUUID, serverRoundId, planningGen, traverser, searchContext,
                            matcher, shadowQueue, shadowSession, context, diagnostics));
            context.attachPlanningSubscription(subscription);
        } catch (RejectedExecutionException e) {
            // 线程池拒绝注册时主动取消本轮，让状态机与执行桥统一收尾。
            MyMod.LOG.error("[ChainPlanning] Shadow worker pool exhausted for player {}, cancelling plan gen {}",
                    playerUUID, Integer.valueOf(planningGen), e);
            diagnostics.logPlanCancelled("shadow-pool-exhausted");
            bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    "shadow-pool-exhausted"));
            return;
        }
    }

    /**
     * 推进一片只读规划，向 shadowQueue 追加确认目标并发布进度或终局。
     * 身份及会话配置沿用启动快照，控制器在预算与取消安全点协作让出。
     */
    private ParallelTaskResult runShadowSlice(
            ParallelTickControl control,
            UUID playerUUID,
            long serverRoundId,
            int planningGen,
            BudgetedChainTraverser traverser,
            ChainSearchContext searchContext,
            ChainBlockMatcher matcher,
            ConcurrentLinkedQueue<ChainTarget> shadowQueue,
            ChainSession shadowSession,
            ChainExecutionContext context,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics) {
        if (context.isExternalPlanningCancellationRequested()) {
            return ParallelTaskResult.TERMINATED;
        }
        if (control.isCancelRequested()) {
            publishWorkerCancellation(context, diagnostics, playerUUID, serverRoundId, planningGen,
                    "shadow-cancel-requested");
            return ParallelTaskResult.TERMINATED;
        }

        // worker 每分片重新解析玩家（玩家可能登出/切维度）
        EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
        if (!(currentPlayer instanceof EntityPlayerMP)) {
            publishWorkerCancellation(context, diagnostics, playerUUID, serverRoundId, planningGen,
                    "shadow-player-unavailable");
            return ParallelTaskResult.TERMINATED;
        }

        // 活性由 context 与控制器共同判定，事件携带冻结代际供状态机筛选。

        if (control.shouldYield()) {
            return ParallelTaskResult.YIELDED;
        }

        long progressRevisionBefore = searchContext.getProgressRevision();
        TraversalStepResult traversalResult = ChainTraversalSupport.step(
                traverser,
                searchContext,
                control,
                target -> !control.isCancelRequested()
                        && !context.isExternalPlanningCancellationRequested()
                        && matcher.matches((EntityPlayerMP) currentPlayer, target),
                target -> {
                    if (!control.isCancelRequested()
                            && !context.isExternalPlanningCancellationRequested()) {
                        // 将确认目标交给主线程执行队列。
                        shadowQueue.add(target);
                    }
                });
        if (traversalResult == TraversalStepResult.TERMINATED) {
            publishWorkerCancellation(context, diagnostics, playerUUID, serverRoundId, planningGen,
                    "shadow-traversal-terminated");
            return ParallelTaskResult.TERMINATED;
        }
        // 仅已提交的候选事务或可恢复游标推进刷新 watchdog。
        // 两个诊断计数字段均传 confirmedCount，watchdog 依据进度事件时间戳判断活性。
        final int confirmedCount = searchContext.getConfirmedCount();
        if (searchContext.getProgressRevision() != progressRevisionBefore) {
            if (!context.publishPlanningProgressIfActive(new Runnable() {
                @Override
                public void run() {
                    bus.publish(new PlanProgress(playerUUID, serverRoundId, planningGen,
                            ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                            confirmedCount, confirmedCount));
                }
            })) {
                return ParallelTaskResult.TERMINATED;
            }
        }

        boolean shouldContinue = traversalResult == TraversalStepResult.CONTINUE
                || traversalResult == TraversalStepResult.YIELDED;
        if (!shouldContinue) {
            // PlanCompleted 成功入队后公开完成状态，异常统一发布单次 PlanCancelled。
            boolean completed = tryCompletePlanningOrCancel(context, confirmedCount,
                    searchContext.isTargetLimitExceeded(), new Runnable() {
                @Override
                public void run() {
                    // publication 仅负责入队，诊断在成功后单独执行。
                    bus.publish(buildPlanCompleted(playerUUID, serverRoundId, planningGen,
                            ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(), confirmedCount));
                }
            }, new Runnable() {
                @Override
                public void run() {
                    final String reason = "plan-completion-publication-failed";
                    diagnostics.logPlanCancelled(reason);
                    bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                            ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(), reason));
                }
            });
            if (completed) {
                diagnostics.logPlanCompleted(confirmedCount);
            }
            return completed ? ParallelTaskResult.COMPLETED : ParallelTaskResult.TERMINATED;
        }

        if (control.shouldYield()) {
            return ParallelTaskResult.YIELDED;
        }
        return ChainTraversalSupport.toParallelTaskResult(traversalResult);
    }

    /**
     * 将 PlanCompleted publication 异常收口为既有的单次 worker 取消 publication。
     *
     * @return true 表示 PlanCompleted publication 成功并固化完成；false 表示取消已发布或已有其它终局
     */
    static boolean tryCompletePlanningOrCancel(ChainExecutionContext context, int confirmedCount,
            Runnable completionPublication, Runnable cancellationPublication) {
        return tryCompletePlanningOrCancel(context, confirmedCount, false,
                completionPublication, cancellationPublication);
    }

    /** 完成 publication 与目标上限截断事实共享同一线性化点。 */
    static boolean tryCompletePlanningOrCancel(ChainExecutionContext context, int confirmedCount,
            boolean targetLimitExceeded, Runnable completionPublication, Runnable cancellationPublication) {
        try {
            return context.tryCompletePlanningAndPublish(
                    confirmedCount, targetLimitExceeded, completionPublication);
        } catch (RuntimeException failure) {
            context.cancelPlanningAndPublishIfActive(cancellationPublication);
            return false;
        } catch (LinkageError failure) {
            context.cancelPlanningAndPublishIfActive(cancellationPublication);
            return false;
        }
    }

    /** worker 自然取消只在仍活跃时发布；外部取消胜出后保持静默。 */
    private void publishWorkerCancellation(ChainExecutionContext context,
            ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics, UUID playerUUID,
            long serverRoundId, int planningGen, final String reason) {
        context.cancelPlanningAndPublishIfActive(new Runnable() {
            @Override
            public void run() {
                diagnostics.logPlanCancelled(reason);
                bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                        ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(), reason));
            }
        });
    }

    // ============================ 纯逻辑构造（供单测覆盖） ============================

    /**
     * 构造规划完成事件（纯逻辑，供单测覆盖）。
     *
     * @param playerUUID     玩家 UUID
     * @param gen            规划代际（与 PlanStarted 注入闭包的值一致）
     * @param tick           服务端 tick
     * @param nanos          纳秒戳
     * @param confirmedCount 已确认目标数
     * @return 规划完成事件
     */
    public static PlanCompleted buildPlanCompleted(UUID playerUUID, int gen, long tick, long nanos, int confirmedCount) {
        return buildPlanCompleted(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, gen, tick, nanos, confirmedCount);
    }

    /** 构造带不可变服务端轮次关联的规划完成事件。 */
    public static PlanCompleted buildPlanCompleted(UUID playerUUID, long serverRoundId, int gen, long tick, long nanos,
                                                   int confirmedCount) {
        return new PlanCompleted(playerUUID, serverRoundId, gen, tick, nanos, confirmedCount);
    }

    /**
     * 构造规划取消事件（纯逻辑，供单测覆盖）。
     *
     * @param playerUUID 玩家 UUID
     * @param gen        规划代际
     * @param tick       服务端 tick
     * @param nanos      纳秒戳
     * @param reason     取消原因（自由文本，用于诊断/HUD）
     * @return 规划取消事件
     */
    public static PlanCancelled buildPlanCancelled(UUID playerUUID, int gen, long tick, long nanos, String reason) {
        return buildPlanCancelled(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, gen, tick, nanos, reason);
    }

    /** 构造带不可变服务端轮次关联的规划取消事件。 */
    public static PlanCancelled buildPlanCancelled(UUID playerUUID, long serverRoundId, int gen, long tick, long nanos,
                                                   String reason) {
        return new PlanCancelled(playerUUID, serverRoundId, gen, tick, nanos, reason);
    }

    /**
     * 构造 runtime 装配失败的规划取消事件，确保生产分支与测试共用轮次关联接缝。
     *
     * @param playerUUID   玩家 UUID
     * @param serverRoundId 不可变服务端轮次关联
     * @param gen          规划代际
     * @param tick         服务端 tick
     * @param nanos        纳秒戳
     * @return reason 固定为 {@code shadow-runtime-null} 的规划取消事件
     */
    public static PlanCancelled buildRuntimeNullPlanCancelled(UUID playerUUID, long serverRoundId, int gen, long tick,
                                                                long nanos) {
        return buildPlanCancelled(playerUUID, serverRoundId, gen, tick, nanos, "shadow-runtime-null");
    }

    /**
     * 构造 seed TileEntity 身份无法可靠读取时的固定 fail-closed 取消事件。
     *
     * @return reason 固定为 {@code shadow-seed-tile-identity-unresolved}
     */
    public static PlanCancelled buildUnresolvedSeedIdentityPlanCancelled(UUID playerUUID, long serverRoundId,
            int gen, long tick, long nanos) {
        return buildPlanCancelled(playerUUID, serverRoundId, gen, tick, nanos,
                "shadow-seed-tile-identity-unresolved");
    }
}
