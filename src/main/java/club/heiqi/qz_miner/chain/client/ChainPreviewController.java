package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.interaction.InteractionRayTrace;
import club.heiqi.qz_miner.chain.planner.AxisAlignedTunnelDirection;
import club.heiqi.qz_miner.chain.planner.BlockSeedSnapshot;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainPlanningRuntime;
import club.heiqi.qz_miner.chain.planner.ChainPlanningRuntimeFactory;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.ChainTraversalSupport;
import club.heiqi.qz_miner.chain.planner.BudgetedChainTraverser;
import club.heiqi.qz_miner.chain.planner.TraversalStepResult;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

/**
 * 客户端连锁预览控制器。
 *
 * 当前阶段只负责：
 * 1. 侦测玩家当前瞄准方块
 * 2. 启动/取消客户端并行预览任务
 * 3. 保存最小预览结果，供后续渲染层使用
 */
@SideOnly(Side.CLIENT)
public class ChainPreviewController {

    private final ChainPreviewState previewState = new ChainPreviewState();
    private final PreviewOriginLease previewOriginLease = new PreviewOriginLease();
    private volatile ChainTarget currentTarget;
    private ParallelTickSubscription previewTaskSubscription;
    private int specialPreviewRequestId;
    private BlockSeedSnapshot previewSeedSnapshot;
    private World previewSeedWorld;
    private int previewConcreteFace;
    private long lastInvalidationCycleGeneration = Long.MIN_VALUE;
    private long lastInvalidationServerRoundId = Long.MIN_VALUE;
    private long lastInvalidationActionSequence = Long.MIN_VALUE;
    private AutoToolSwapAction lastInvalidationAction;

    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    public ChainPreviewState getPreviewState() {
        return previewState;
    }

    /**
     * 在客户端生命周期结束时停止当前预览任务并清空状态。
     */
    public void stopPreviewForLifecycle() {
        stopPreview();
    }

    /**
     * 本地成功破坏当前预览 origin 后立即建立租约，覆盖服务端 phase 投影尚未可见的窗口。
     *
     * <p>入口只核对既有 frozen seed、world 与坐标身份，不回读已变为空气的 origin。</p>
     */
    public void onLocalBlockDestroyed(int x, int y, int z) {
        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.theWorld;
        if (world == null || currentTarget == null || !previewState.isActive()
                || previewSeedSnapshot == null || previewSeedWorld == null
                || world != previewSeedWorld
                || !currentTarget.equals(previewSeedSnapshot.getOrigin())
                || currentTarget.getX() != x || currentTarget.getY() != y || currentTarget.getZ() != z) {
            return;
        }
        ClientPhaseProjection projection = ClientProxy.clientPhaseProjection;
        if (projection == null) {
            return;
        }
        previewOriginLease.acquire(projection.getCurrentPhase(), projection.getCurrentGeneration());
    }

    /**
     * 已验证工具布局首次可见后的精确预览失效入口。
     *
     * <p>调用发生在 ClientTick END 主线程。只有当前仍活跃的预览会被重启；直接复用同 origin，
     * 因此不受 PLANNING/RUNNING/FINISHING phase lock 阻挡。旧 worker 由 subscription 取消和
     * preview generation 双重隔离。</p>
     */
    public void onToolLayoutVerified(long cycleGeneration, long serverRoundId,
            long actionSequence, AutoToolSwapAction action) {
        if (!isPreviewRefreshAction(action) || currentTarget == null || !previewState.isActive()
                || previewSeedSnapshot == null || previewSeedWorld == null
                || MyMod.chainStateService == null
                || !MyMod.chainStateService.getClientState().isChainKeyPressed()
                || !Config.clientEnablePreviewRender) {
            return;
        }
        if (cycleGeneration == lastInvalidationCycleGeneration
                && serverRoundId == lastInvalidationServerRoundId
                && actionSequence == lastInvalidationActionSequence
                && action == lastInvalidationAction) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.theWorld;
        if (world == null || minecraft.thePlayer == null || world != previewSeedWorld
                || !currentTarget.equals(previewSeedSnapshot.getOrigin())) return;

        ChainTarget origin = currentTarget;
        lastInvalidationCycleGeneration = cycleGeneration;
        lastInvalidationServerRoundId = serverRoundId;
        lastInvalidationActionSequence = actionSequence;
        lastInvalidationAction = action;
        startPreview(world, origin, previewSeedSnapshot, previewConcreteFace, false);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.theWorld;
        EntityPlayer player = minecraft.thePlayer;
        if (world == null || player == null || MyMod.chainStateService == null) {
            stopPreview();
            return;
        }

        if (!Config.clientEnablePreviewRender) {
            stopPreview();
            return;
        }

        if (!MyMod.chainStateService.getClientState().isChainKeyPressed()) {
            stopPreview();
            return;
        }

        if (shouldLockCurrentPreview()) {
            if (currentTarget == null) {
                ChainTarget lockedTarget = previewState.getOrigin();
                if (lockedTarget != null) {
                    currentTarget = lockedTarget;
                }
            }
            return;
        }

        ChainSubMode selectedSubMode = MyMod.chainStateService.getClientState().getSelectedSubMode();
        MovingObjectPosition lookHit = resolveLookHit(minecraft, player, selectedSubMode);
        ChainTarget target = getCurrentLookTarget(lookHit);
        if (target == null) {
            stopPreview();
            return;
        }

        TunnelDirectionSource acceptedSource = MyMod.chainStateService.getClientState()
                .getAcceptedTunnelDirectionSource();
        int concreteFace = resolveConcreteFace(
                selectedSubMode, acceptedSource, player, lookHit, target);
        if (shouldRestartPreview(previewSeedWorld, world, currentTarget, target,
                previewConcreteFace, concreteFace)) {
            startPreview(world, target, concreteFace);
        }
    }

    private void startPreview(World world, ChainTarget target, int concreteFace) {
        Block sampleBlock = world.getBlock(target.getX(), target.getY(), target.getZ());
        int sampleMeta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        TileEntity sampleTileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
        startPreview(world, target, new BlockSeedSnapshot(target, sampleBlock, sampleMeta, sampleTileEntity),
                concreteFace, true);
    }

    /** 以已捕获 seed 启动或刷新预览；刷新不得重读已破坏 origin。 */
    private void startPreview(World world, ChainTarget target, BlockSeedSnapshot seedSnapshot,
            int concreteFace, boolean replaceSeedLease) {
        resetPreview(replaceSeedLease);

        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        if (player == null) {
            return;
        }

        currentTarget = target;
        previewConcreteFace = AxisAlignedTunnelDirection.normalizeFace(concreteFace);
        if (replaceSeedLease) {
            previewSeedSnapshot = seedSnapshot;
            previewSeedWorld = world;
            clearInvalidationIdentity();
        }
        previewState.begin(target);
        MyMod.chainStateService.getClientState().setPreviewActive(true);

        final int generation = previewState.getGeneration();
        final Block sampleBlock = seedSnapshot.getSampleBlock();
        final int sampleMeta = seedSnapshot.getSampleMeta();
        final TileEntity sampleTileEntity = seedSnapshot.getSampleTileEntity();
        final int previewRadius = getEffectivePreviewRadius();
        final int previewMaxTargets = getEffectivePreviewMaxTargets();
        final ChainMode selectedMode = MyMod.chainStateService.getClientState().getSelectedMode();
        final ChainSubMode selectedSubMode = MyMod.chainStateService.getClientState().getSelectedSubMode();
        if (!ChainSubModeRegistry.canStartPreview(selectedSubMode, world, target, sampleTileEntity)) {
            previewState.setCompleted(true);
            return;
        }
        if (ChainSubModeRegistry.usesRemotePreview(selectedSubMode)) {
            startRemotePreview(selectedMode, selectedSubMode, target, previewRadius, previewMaxTargets);
            return;
        }
        ModeExtensionSnapshot modeExtension = ModeExtensionSnapshot.EMPTY;
        if (MyMod.chainStateService.getClientState().isObjectGroupSyncAccepted()) {
            modeExtension = MyMod.chainStateService.getClientState().getServerObjectGroups().resolve(
                    ObjectGroupMode.maskFor(selectedSubMode),
                    club.heiqi.qz_miner.chain.planner.ObjectGroupBlockPredicate.registryName(sampleBlock), sampleMeta);
        }
        final ChainSession previewSession = new ChainSession(
            player.getUniqueID(),
            selectedMode,
            selectedSubMode,
            target,
            previewConcreteFace, 0.0F, 0.0F, 0.0F,
            -1, -1, modeExtension);
        final ChainPlanningRuntime runtime = ChainPlanningRuntimeFactory.createForPreview(
            world,
            player,
            previewSession,
            seedSnapshot,
            previewRadius,
            previewMaxTargets);
        if (runtime == null) {
            previewState.setCompleted(true);
            return;
        }
        final ChainSearchContext searchContext = runtime.getSearchContext();
        final BudgetedChainTraverser traverser = runtime.getTraverser();
        final ChainBlockMatcher blockMatcher = runtime.getMatcher();
        final int frozenConcreteFace = previewConcreteFace;

        if (blockMatcher.matches(player, target)) {
            previewState.addPreviewTarget(target);
            searchContext.incrementConfirmedCount();
            if (searchContext.getConfirmedCount() >= searchContext.getMaxTargets()) {
                previewState.setCompleted(true);
                return;
            }
        }
        traverser.seed(searchContext);

        previewTaskSubscription = MyMod.ensureParallelTickExecutor().registerClientPre(
            "chain-preview-" + target.getX() + "-" + target.getY() + "-" + target.getZ(),
            control -> {
                if (control.isCancelRequested() || !isPreviewStillValid(generation, target, frozenConcreteFace)) {
                    return ParallelTaskResult.TERMINATED;
                }

                if (control.shouldYield()) {
                    return ParallelTaskResult.YIELDED;
                }

                TraversalStepResult traversalResult = ChainTraversalSupport.step(
                    traverser,
                    searchContext,
                    control,
                    matchedTarget -> !control.isCancelRequested()
                        && isPreviewStillValid(generation, target, frozenConcreteFace)
                        && blockMatcher.matches(player, matchedTarget),
                    matchedTarget -> {
                        if (!control.isCancelRequested()
                                && isPreviewStillValid(generation, target, frozenConcreteFace)) {
                            previewState.addPreviewTarget(matchedTarget);
                        }
                    });
                if (traversalResult == TraversalStepResult.TERMINATED) {
                    return ParallelTaskResult.TERMINATED;
                }

                if (control.isCancelRequested() || !isPreviewStillValid(generation, target, frozenConcreteFace)) {
                    return ParallelTaskResult.TERMINATED;
                }

                previewState.incrementScannedCount();
                boolean shouldContinue = traversalResult == TraversalStepResult.CONTINUE || traversalResult == TraversalStepResult.YIELDED;

                if (!shouldContinue) {
                    previewState.setCompleted(true);
                }

                if (shouldContinue && control.shouldYield()) {
                    return ParallelTaskResult.YIELDED;
                }

                return ChainTraversalSupport.toParallelTaskResult(traversalResult);
            });

        MyMod.LOG.debug("[ChainPreview] Started preview for target ({}, {}, {})", target.getX(), target.getY(), target.getZ());
    }

    private void startRemotePreview(ChainMode selectedMode, ChainSubMode selectedSubMode, ChainTarget target, int previewRadius, int previewMaxTargets) {
        specialPreviewRequestId++;
        if (!ChainSubModeRegistry.requestRemotePreview(selectedSubMode, specialPreviewRequestId, target, previewRadius, previewMaxTargets)) {
            previewState.setCompleted(true);
            return;
        }
        MyMod.LOG.debug(
            "[ChainPreview] Requested remote preview for mode={} subMode={} target=({}, {}, {}) radius={} maxTargets={} requestId={}",
            selectedMode,
            selectedSubMode,
            target.getX(),
            target.getY(),
            target.getZ(),
            previewRadius,
            previewMaxTargets,
            specialPreviewRequestId);
    }

    /**
     * 应用 LootGames 扫雷预览结果。
     *
     * @param requestId 请求编号
     * @param origin 请求原点
     * @param targets 服务端返回的雷坐标
     */
    public void applyLootGamesMinesweeperPreview(int requestId, ChainTarget origin, java.util.List<ChainTarget> targets) {
        if (requestId != specialPreviewRequestId
            || currentTarget == null
            || !currentTarget.equals(origin)
            || !previewState.isActive()) {
            return;
        }

        for (ChainTarget target : targets) {
            previewState.addPreviewTarget(target);
        }
        previewState.setCompleted(true);
        MyMod.LOG.debug(
            "[ChainPreview] Applied LootGames minesweeper preview for ({}, {}, {}) targets={} requestId={}",
            origin.getX(),
            origin.getY(),
            origin.getZ(),
            targets.size(),
            requestId);
    }

    /**
     * 获取当前实际使用的预览半径。
     *
     * @return 预览半径
     */
    public int getEffectivePreviewRadius() {
        if (!Config.clientEnablePreviewRender) {
            return 1;
        }

        int serverChainRadius = MyMod.chainStateService == null
            ? Config.chainRadius
            : MyMod.chainStateService.getClientState().getServerChainRadius();
        return Math.max(1, Math.min(serverChainRadius, Config.clientPreviewMaxRadius));
    }

    /**
     * 获取当前实际使用的预览目标数量上限。
     *
     * @return 预览目标数量上限
     */
    public int getEffectivePreviewMaxTargets() {
        if (!Config.clientEnablePreviewRender) {
            return 1;
        }

        int serverChainMaxBlocks = MyMod.chainStateService == null
            ? Config.chainMaxBlocks
            : MyMod.chainStateService.getClientState().getServerChainMaxBlocks();
        return Math.max(1, Math.min(serverChainMaxBlocks, Config.clientPreviewMaxTargets));
    }

    private boolean isPreviewStillValid(int generation, ChainTarget target, int concreteFace) {
        if (MyMod.chainStateService == null || !MyMod.chainStateService.getClientState().isChainKeyPressed()) {
            return false;
        }
        if (previewState.getGeneration() != generation) {
            return false;
        }
        return target.equals(currentTarget) && concreteFace == previewConcreteFace;
    }

    /**
     * 判断当前是否应锁定已启动的预览计算。
     *
     * <p>阶段8 块3 G2 夺权：服务端阶段权威来自 {@link ClientPhaseProjection}。本地成功破坏
     * origin 后只额外租赁当前 frozen seed，覆盖 phase 尚未投影的窗口；租约不切服务端状态。</p>
     *
     * <p>F1 锁定边界：未发生本地成功破坏时 ARMED 不锁，玩家仍可自由选目标；
     * 无本地租约时 IDLE 不锁。</p>
     *
     * @return 是否锁定当前预览
     */
    private boolean shouldLockCurrentPreview() {
        if (MyMod.chainStateService == null) {
            return false;
        }

        if (currentTarget == null || !previewState.isActive()) {
            return false;
        }

        // G2 夺权：读客户端阶段投影（单玩家容器，ClientProxy 初始化；单人服务端侧可能为 null）
        ClientPhaseProjection projection = ClientProxy.clientPhaseProjection;
        if (projection == null) {
            return previewOriginLease.isActive();
        }
        ChainPhase phase = projection.getCurrentPhase();
        int generation = projection.getCurrentGeneration();
        boolean localOriginLocked = previewOriginLease.shouldLock(phase, generation);
        // F1：未触发的 ARMED/IDLE 不锁；本地租约与 active phase 任一成立即锁定。
        return localOriginLocked || phase == ChainPhase.PLANNING
            || phase == ChainPhase.RUNNING
            || phase == ChainPhase.FINISHING;
    }

    private void stopPreview() {
        resetPreview(true);
    }

    /** 取消旧 generation；生命周期/新目标同时释放 seed 租约和租约内去重身份。 */
    private void resetPreview(boolean clearSeedLease) {
        if (previewTaskSubscription != null) {
            previewTaskSubscription.unregister();
            previewTaskSubscription = null;
        }

        if (previewState.isActive() || currentTarget != null) {
            MyMod.LOG.debug("[ChainPreview] Stopped preview");
        }

        previewState.clear();
        currentTarget = null;
        previewConcreteFace = 0;
        specialPreviewRequestId++;
        if (clearSeedLease) {
            previewSeedSnapshot = null;
            previewSeedWorld = null;
            previewOriginLease.reset();
            clearInvalidationIdentity();
        }
        if (MyMod.chainStateService != null) {
            MyMod.chainStateService.getClientState().setPreviewActive(false);
        }
    }

    /** 只有三种真实库存布局变化会刷新预览。 */
    private static boolean isPreviewRefreshAction(AutoToolSwapAction action) {
        return action == AutoToolSwapAction.SWAP
                || action == AutoToolSwapAction.TAKEOVER
                || action == AutoToolSwapAction.RESTORE;
    }

    /** 去重身份只在一个 seed 租约内有效。 */
    private void clearInvalidationIdentity() {
        lastInvalidationCycleGeneration = Long.MIN_VALUE;
        lastInvalidationServerRoundId = Long.MIN_VALUE;
        lastInvalidationActionSequence = Long.MIN_VALUE;
        lastInvalidationAction = null;
    }

    private ChainTarget getCurrentLookTarget(MovingObjectPosition movingObjectPosition) {
        if (movingObjectPosition == null || movingObjectPosition.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }

        return new ChainTarget(movingObjectPosition.blockX, movingObjectPosition.blockY, movingObjectPosition.blockZ);
    }

    /** 液体源预览使用包含液体的共享射线；其它模式保留原版 objectMouseOver。 */
    private MovingObjectPosition resolveLookHit(
            Minecraft minecraft, EntityPlayer player, ChainSubMode selectedSubMode) {
        if (selectedSubMode != ChainSubMode.INTERACT_LIQUID_SOURCE) {
            return minecraft.objectMouseOver;
        }
        if (minecraft.playerController == null) {
            return null;
        }
        try {
            return InteractionRayTrace.trace(
                    player, minecraft.playerController.getBlockReachDistance(), true);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** 只对 AREA_TUNNEL 解析 accepted source；HIT_FACE 缺失或非法时回退当前 look。 */
    private static int resolveConcreteFace(ChainSubMode subMode, TunnelDirectionSource source,
            EntityPlayer player, MovingObjectPosition hit, ChainTarget target) {
        if (subMode != ChainSubMode.AREA_TUNNEL) {
            return 0;
        }
        int lookFace = AxisAlignedTunnelDirection.resolveFace(player);
        if (source != TunnelDirectionSource.HIT_FACE || hit == null || target == null
                || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.blockX != target.getX() || hit.blockY != target.getY() || hit.blockZ != target.getZ()) {
            return lookFace;
        }
        return AxisAlignedTunnelDirection.resolveHitFaceOrLook(hit.sideHit, lookFace);
    }

    /** 纯身份判定：同 origin 只要 world 或 concrete face 变化也必须换 generation。 */
    static boolean shouldRestartPreview(Object currentWorld, Object nextWorld,
            ChainTarget currentTarget, ChainTarget nextTarget, int currentFace, int nextFace) {
        return currentWorld != nextWorld || currentTarget == null || !currentTarget.equals(nextTarget)
                || currentFace != nextFace;
    }
}
