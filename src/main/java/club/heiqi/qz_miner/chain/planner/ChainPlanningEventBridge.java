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
import club.heiqi.qz_miner.chain.execution.ChainExecutionContextRegistry;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.PlanProgress;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
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
 * 阶段 4 规划事件桥（A-shadow 影子双 worker 核心）。
 *
 * <p>订阅 {@link PlanStarted}（状态机 T4 ARMED→PLANNING 转移后广播）→ 在主线程解析玩家与配置性状态 →
 * 发起独立影子 traverser worker（复用 {@link BudgetedChainTraverser} + {@link ChainTraversalSupport}）→
 * worker 完成 publish {@link PlanCompleted}（推进 PLANNING→RUNNING）/ 取消 publish {@link PlanCancelled}
 * （回 PLANNING→IDLE）。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：影子 worker 只读世界（traverser 只读）+ 只 publish 事件，<b>绝不</b>
 *       {@code setExecutionStatus}、<b>绝不</b>写 {@code ChainSession}、<b>绝不</b>
 *       {@code syncPlayerState}。阶段8 块1 已删除旧链路 worker（{@code AbstractFloodFillPlanningStrategy}/
 *       {@code BlockBoxScanPlanningStrategy} 等），I1 偏离随之清偿（NORTH_STAR §8 偏离条目已移除）。</li>
 *   <li><b>I3</b>：复用 {@link ChainPlanningRuntimeFactory#createForServer} 与
 *       {@link ChainTraversalSupport#step}，不新造遍历逻辑。</li>
 *   <li><b>I4</b>：publish 跨线程入队、drain 主线程消费（{@link ChainEventBus} 天然满足），
 *       bridge 不碰主线程语义状态。</li>
 *   <li><b>I10</b>：bridge 只 publish 不 {@code transition}；陈旧 generation 的
 *       {@link PlanCompleted} 由状态机 genCheck 丢弃，bridge 无需也无法读状态机内部 generation。</li>
 * </ul>
 *
 * <h3>gen 传递链（根治时序竞态）</h3>
 * <p>规划代际经 {@link PlanStarted#getGeneration()} 注入 worker 闭包，worker publish 时回填同一值。
 * 这避免 worker 实时读状态机 generation 字段（跨包不可见 + 时序竞态——worker 启动早于状态机进 PLANNING），
 * 状态机收到 {@link PlanCompleted} 时用 genCheck 判定陈旧/匹配。</p>
 *
 * <h3>P2-C 双状态漂移防护</h3>
 * <p>影子并行期 {@code ChainStateService} 与 {@code ChainStateMachine} 双状态系统并存。
 * bridge 只从 {@link ChainPlayerState} 读 <b>配置性字段</b>（mode/subMode/radius/maxBlocks），
 * <b>绝不</b>读 {@code executionStatus}/{@code isChainKeyPressed} 做决策——新链路活性由状态机 generation 判定。</p>
 */
public class ChainPlanningEventBridge {

    /** 注入的事件总线，构造期订阅 PlanStarted。 */
    private final ChainEventBus bus;
    /**
     * 阶段5：注入的执行上下文注册表（E1-c 解决 shadowQueue 局部变量断链）。
     *
     * <p>worker 完成路径 put shadowQueue + planningGen，主线程执行订阅者收到 PlanCompleted 后 get 领取。</p>
     */
    private final ChainExecutionContextRegistry executionContextRegistry;

    /**
     * 构造桥并订阅 {@link PlanStarted}。
     *
     * @param bus                      事件总线（与状态机共享同一实例）
     * @param executionContextRegistry 阶段5 执行上下文注册表（worker 完成时 put shadowQueue）
     */
    public ChainPlanningEventBridge(ChainEventBus bus, ChainExecutionContextRegistry executionContextRegistry) {
        this.bus = bus;
        this.executionContextRegistry = executionContextRegistry;
        bus.subscribe(PlanStarted.class, this::onPlanStarted);
    }

    /**
     * 收到 PlanStarted 进态广播：主线程解析上下文 → 注册影子 traverser worker。
     *
     * <p>契约：仅主线程 drain 调用。本方法只读世界（种子解析）+ 注册 worker，
     * 不切态、不写 ChainSession 主线程语义状态。</p>
     *
     * @param event 规划启动事件（携带 gen + origin/dimension/sideHit/hitOffset）
     */
    private void onPlanStarted(PlanStarted event) {
        final UUID playerUUID = event.getPlayerUUID();
        // 轮次关联和代际一起在主线程冻结，worker 不得读取任何后来状态。
        final long serverRoundId = event.getServerRoundId();
        // 规划代际经事件注入闭包，根治"worker 实时读 gen 会死"的时序竞态（见类注释 gen 传递链）
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

        // P2-C 防护：只读配置性字段，绝不读 executionStatus/isChainKeyPressed 做决策
        ChainPlayerState playerState = MyMod.chainStateService == null ? null : MyMod.chainStateService.getPlayerState(playerUUID);
        if (playerState == null) {
            bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    "shadow-state-missing"));
            return;
        }
        final ChainMode mode = playerState.getSelectedMode();
        final ChainSubMode subMode = playerState.getSelectedSubMode();
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
        if (definition == null) {
            bus.publish(buildPlanCancelled(playerUUID, serverRoundId, planningGen,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    "shadow-mode-undefined"));
            return;
        }

        final ChainTarget origin = new ChainTarget(event.getX(), event.getY(), event.getZ());
        // 种子解析：破坏路径（BlockBreakObserved）在 drainer 推迟到下一 tick drain 时方块已被原版 removeBlock 成空气，
        // 必须用事件携带的 seedBlock/seedMeta（破坏时刻捕获）构造种子；右键/左键路径块仍在世界，走兜底 WorldBlockSeedResolver。
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

        // 影子会话：仅供 runtime 工厂装配 traverser/matcher 用，阶段 4 不消费其 pendingBreakTargets
        // 新链路自己的 queue 阶段 5 才消费，阶段 4 只为 traverser 推进
        final int requestedRadius = playerState.getRequestedChainRadius();
        final int requestedMaxBlocks = playerState.getRequestedChainMaxBlocks();
        final ChainSession shadowSession = new ChainSession(
                playerUUID, mode, subMode, origin,
                event.getSideHit(), event.getHitX(), event.getHitY(), event.getHitZ(),
                requestedRadius, requestedMaxBlocks, modeExtension);
        final ChainPlanningRuntimeFactory.PlanningDiagnostics diagnostics =
                ChainPlanningRuntimeFactory.PlanningDiagnostics.production(playerUUID, serverRoundId, planningGen,
                        String.valueOf(mode), String.valueOf(subMode));
        Object seedRegistryName = Block.blockRegistry.getNameForObject(seedSnapshot.getSampleBlock());
        diagnostics.logPlanStarted(seedRegistryName == null ? "minecraft:unknown" : String.valueOf(seedRegistryName),
                seedSnapshot.getSampleMeta(), origin, Thread.currentThread().getName(), player.inventory.currentItem,
                MinecraftAutoToolSwapInventoryPort.describeStack(player.inventory.getCurrentItem()));
        // CHAIN 的拓扑能力在 PlanStarted 主线程冻结；AREA/INTERACT/SPECIAL 不捕获工具能力，
        // 继续按世界、空间和各子模式结构宽进。worker 后续只读该不可变副本。
        final PlanningToolCapabilitySnapshot capabilitySnapshot =
                ChainPlanningRuntimeFactory.usesFrozenToolCapabilities(mode)
                        ? PlanningToolCapabilitySnapshot.capture(
                                player, Config.autoToolPrioritySelectors, true)
                        : null;
        // 阶段8 块3：删旧 shadowSession.beginPlanning()（ChainSession 委托方法已删，新链路无需 plannerRunning 标志）。
        // 新链路 worker 活性由状态机 generation 判定，session 仅作配置载体 + traversalTargets 装配。
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
        // 影子 queue：阶段 4 只为 traverser 推进，目标消费留阶段 5
        final ConcurrentLinkedQueue<ChainTarget> shadowQueue = new ConcurrentLinkedQueue<ChainTarget>();
        traverser.seed(searchContext);

        // C 流式登记（修复边搜边破坏丢失）：worker 启动前先把 context 提前登记进 registry，
        // 让主线程执行订阅者 onServerTick 立即可见此 context——边搜边消费。
        // 构造时 planningComplete=false：worker 未完成时 queue 瞬时为空 != 执行完成，主线程消费订阅者
        // 据此跳过 publish ExecutionFinished（守 isCompleted 流式语义）。
        // 注意 put 早于 registerPre：若 registerPre 抛 RejectedExecutionException，PlanCancelled 路径
        // 由执行桥 onPlanCancelled 订阅者清 registry（见 ChainExecutionEventBridge）。
        final ChainExecutionContext context = new ChainExecutionContext(
                playerUUID, serverRoundId, planningGen, shadowQueue, shadowSession, diagnostics);
        executionContextRegistry.put(context);

        // 影子 worker 注册（对齐旧 worker 结构，但去掉切态/stopExecution/syncState，改为 publish）
        try {
            ParallelTickSubscription subscription = MyMod.ensureParallelTickExecutor().registerPre(
                    "shadow-plan-" + playerUUID,
                    control -> runShadowSlice(control, playerUUID, serverRoundId, planningGen, traverser, searchContext,
                            matcher, shadowQueue, shadowSession, context, diagnostics));
            context.attachPlanningSubscription(subscription);
        } catch (RejectedExecutionException e) {
            // worker pool 20 槽已满（SynchronousQueue 无法交接 + 池达 MAX_WORKER_THREADS），
            // 影子 worker 未注册成功；此代际已 PLANNING 但无人推进，必须主动 publish PlanCancelled，
            // 否则会卡 PLANNING 直到阶段7 看门狗兜底——此处收口让状态机干净回 IDLE
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
     * 影子 worker 单分片执行：推进 traverser，完成/取消 publish 派生事件。
     *
     * <p>守 I1：worker 只读世界（traverser 只读）+ 只 publish，绝不 setExecutionStatus/写 session/syncPlayerState。
     * 新链路活性由状态机 generation 判定，陈旧 gen 的 PlanCompleted 会被状态机 genCheck 丢弃。</p>
     *
     * @param control        并行 tick 控制
     * @param playerUUID     玩家 UUID
     * @param planningGen    本次规划代际（事件注入，非实时读状态机）
     * @param traverser      预算化遍历器
     * @param searchContext   搜索上下文（承载 confirmedCount）
     * @param matcher        目标匹配器
     * @param shadowQueue    影子 queue（阶段 4 只消费不入执行，阶段 5 才接执行）
     * @param shadowSession  影子会话（阶段8 块2 起注入 ChainExecutionContext 供真实破坏桥解析 mode/subMode）
     * @param context        C 流式登记的执行上下文（worker 完成时 markPlanningComplete 而非再 put）
     * @return 分片结果
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

        // P2-C：不检查 isSessionActive/isChainKeyPressed/isExecuting——新链路不依赖旧 ChainSession/ChainPlayerState 状态
        // 陈旧 gen 由状态机 genCheck 兜底丢弃

        if (control.shouldYield()) {
            return ParallelTaskResult.YIELDED;
        }

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
                        // 阶段 4：影子 queue 仅推进 traverser 用，不驱动执行
                        shadowQueue.add(target);
                        searchContext.incrementConfirmedCount();
                    }
                });
        if (traversalResult == TraversalStepResult.TERMINATED) {
            publishWorkerCancellation(context, diagnostics, playerUUID, serverRoundId, planningGen,
                    "shadow-traversal-terminated");
            return ParallelTaskResult.TERMINATED;
        }
        // B 方案：每分片发一次 PlanProgress 喂看门狗（天然节流：每分片≈64 工作单位），
        // 让 PLANNING 阶段两次状态机转移之间有真实工作推进信号，避免长规划被误判卡死。
        // 仅在非 TERMINATED 路径发（TERMINATED 已 publish PlanCancelled，不算推进）。
        // ChainSearchContext 无 getProcessedCount，processedCount 传 confirmedCount（诊断字段，
        // 看门狗只读 serverTick/nanos 刷新，不读这两个值，语义略不精确但无功能影响）。
        final int confirmedCount = searchContext.getConfirmedCount();
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

        boolean shouldContinue = traversalResult == TraversalStepResult.CONTINUE
                || traversalResult == TraversalStepResult.YIELDED;
        if (!shouldContinue) {
            // C 流式执行修复：worker 完成路径不再 registry.put（context 已在 onPlanStarted 提前登记）。
            // PlanCompleted 入队成功返回后，context 才最后暴露 planningComplete=true；异常由桥统一
            // 走单次 PlanCancelled，避免 publication 失败留下 COMPLETED 幽灵状态。
            boolean completed = tryCompletePlanningOrCancel(context, confirmedCount, new Runnable() {
                @Override
                public void run() {
                    // 生产 Runnable 只做 publication；诊断必须放在 publication 成功之后，
                    // 防止诊断异常被误判为 PlanCompleted publication 失败。
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
        try {
            return context.tryCompletePlanningAndPublish(confirmedCount, completionPublication);
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
