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

/** 客户端采样输入与目标，按身份变化启动或取消预算化预览，并向状态容器发布规划结果。 */
@SideOnly(Side.CLIENT)
public class ChainPreviewController {

    private final ChainPreviewState previewState = new ChainPreviewState();
    private final PreviewOriginLease previewOriginLease = new PreviewOriginLease();
    private final RemotePreviewRequest remotePreviewRequest = new RemotePreviewRequest();
    private volatile ChainTarget currentTarget;
    private ParallelTickSubscription previewTaskSubscription;
    private int specialPreviewRequestId;
    private int remotePreviewMaxTargets;
    private BlockSeedSnapshot previewSeedSnapshot;
    private World previewSeedWorld;
    private volatile int previewConcreteFace;
    private volatile ChainMode previewMode;
    private volatile ChainSubMode previewSubMode;
    /** 当前已接受的输入快照，值变化决定换代。 */
    private PreviewInputSnapshot currentInputSnapshot;
    /** 仅目标抖动使用的稳定窗口，语义变化即时换代。 */
    private final PreviewTargetDebounce targetDebounce = new PreviewTargetDebounce();

    /** 目标抖动默认稳定窗口（tick）。 */
    static final int TARGET_DEBOUNCE_TICKS = 2;

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

        if (checkRemotePreviewTimeout(System.nanoTime())) {
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

        ChainMode selectedMode = MyMod.chainStateService.getClientState().getSelectedMode();
        ChainSubMode selectedSubMode = MyMod.chainStateService.getClientState().getSelectedSubMode();
        if (selectedSubMode == ChainSubMode.AREA_CUBOID_CLEAR) {
            stopPreview();
            return;
        }

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
        // 标量采样与快照比较决定换代，仅目标抖动经过稳定窗口。
        ChainPreviewVisualSettings inputSettings = ChainPreviewVisualSettings.current();
        int inputRadius = getEffectivePreviewRadius();
        int inputMaxTargets = getEffectivePreviewMaxTargets();
        long inputConfigRevision = PreviewInputSnapshot.currentConfigRevision();
        long inputObjectGroupRevision = PreviewInputSnapshot.currentObjectGroupRevision();
        PreviewInputSnapshot currentSnapshot = currentInputSnapshot;
        boolean semanticChanged = PreviewInputSnapshot.semanticIdentityDiffers(
            currentSnapshot,
            world,
            concreteFace,
            selectedMode,
            selectedSubMode,
            inputRadius,
            inputMaxTargets,
            inputSettings,
            inputConfigRevision,
            inputObjectGroupRevision);
        boolean targetChanged = PreviewInputSnapshot.targetDiffers(currentSnapshot, target);
        if (!semanticChanged && !targetChanged) {
            targetDebounce.reset();
            return;
        }
        if (!semanticChanged) {
            // 仅目标抖动：连续 TARGET_DEBOUNCE_TICKS 稳定后才换 generation（首 tick 由 current==null 走立即启动）。
            if (!targetDebounce.shouldAccept(target, TARGET_DEBOUNCE_TICKS)) {
                return;
            }
        } else {
            targetDebounce.reset();
        }
        currentInputSnapshot = new PreviewInputSnapshot(
            world,
            target,
            concreteFace,
            selectedMode,
            selectedSubMode,
            inputRadius,
            inputMaxTargets,
            inputSettings,
            inputConfigRevision,
            inputObjectGroupRevision);
        startPreview(world, target, concreteFace, selectedMode, selectedSubMode);
    }

    private void startPreview(
            World world,
            ChainTarget target,
            int concreteFace,
            ChainMode selectedMode,
            ChainSubMode selectedSubMode) {
        Block sampleBlock = world.getBlock(target.getX(), target.getY(), target.getZ());
        int sampleMeta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        TileEntity sampleTileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
        startPreview(world, target, new BlockSeedSnapshot(target, sampleBlock, sampleMeta, sampleTileEntity),
                concreteFace, selectedMode, selectedSubMode, true);
    }

    /** 以启动时捕获的 seed 启动或刷新预览，保留 origin 破坏前的身份。 */
    private void startPreview(World world, ChainTarget target, BlockSeedSnapshot seedSnapshot,
            int concreteFace, ChainMode selectedMode, ChainSubMode selectedSubMode,
            boolean replaceSeedLease) {
        resetPreview(replaceSeedLease);

        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        if (player == null) {
            return;
        }

        currentTarget = target;
        previewConcreteFace = AxisAlignedTunnelDirection.normalizeFace(concreteFace);
        previewMode = selectedMode;
        previewSubMode = selectedSubMode;
        if (replaceSeedLease) {
            previewSeedSnapshot = seedSnapshot;
            previewSeedWorld = world;
        }
        // begin 冻结本代语义类别，沿远端、本地大模式与扩展子模式分别标记。
        final boolean remotePreviewPath = ChainSubModeRegistry.usesRemotePreview(selectedSubMode);
        final int semanticClass = remotePreviewPath
            ? ChainPreviewSemanticClass.REMOTE_PREDICTED
            : ChainPreviewSemanticClass.resolveLocal(selectedSubMode);
        final int generation = previewState.begin(target, semanticClass);
        MyMod.chainStateService.getClientState().setPreviewActive(true);

        final Block sampleBlock = seedSnapshot.getSampleBlock();
        final int sampleMeta = seedSnapshot.getSampleMeta();
        final TileEntity sampleTileEntity = seedSnapshot.getSampleTileEntity();
        final int previewRadius = getEffectivePreviewRadius();
        final int previewMaxTargets = getEffectivePreviewMaxTargets();
        final ChainPreviewState.TruncationReason limitReason = isPreviewLimitClampedByHardCap(
            currentServerChainMaxBlocks(), Config.clientPreviewMaxTargets, Config.clientPreviewMaxTargetsHardCap)
                ? ChainPreviewState.TruncationReason.HARD_CAP
                : ChainPreviewState.TruncationReason.MAX_TARGETS;
        if (!ChainSubModeRegistry.canStartPreview(selectedSubMode, world, target, sampleTileEntity)) {
            previewState.setCompleted(generation, true);
            return;
        }
        if (remotePreviewPath) {
            startRemotePreview(
                selectedMode,
                selectedSubMode,
                target,
                previewRadius,
                previewMaxTargets,
                generation);
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
            previewState.setCompleted(generation, true);
            return;
        }
        final ChainSearchContext searchContext = runtime.getSearchContext();
        final BudgetedChainTraverser traverser = runtime.getTraverser();
        final ChainBlockMatcher blockMatcher = runtime.getMatcher();
        final int frozenConcreteFace = previewConcreteFace;

        if (shouldProjectOriginBeforeTraversal(selectedSubMode) && blockMatcher.matches(player, target)) {
            if (!previewState.addPreviewTarget(generation, target)) {
                return;
            }
            searchContext.incrementConfirmedCount();
            if (searchContext.getConfirmedCount() >= searchContext.getMaxTargets()) {
                previewState.reportTruncation(
                    generation, limitReason, 0, searchContext.getConfirmedCount());
                previewState.setCompleted(generation, true);
                return;
            }
        }
        traverser.seed(searchContext);

        try {
            previewTaskSubscription = MyMod.ensureParallelTickExecutor().registerClientPre(
                "chain-preview-" + target.getX() + "-" + target.getY() + "-" + target.getZ(),
                control -> {
                    if (control.isCancelRequested() || !isPreviewStillValid(
                            generation, target, frozenConcreteFace, selectedMode, selectedSubMode)) {
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
                            && isPreviewStillValid(
                                generation, target, frozenConcreteFace, selectedMode, selectedSubMode)
                            && blockMatcher.matches(player, matchedTarget),
                        matchedTarget -> {
                            if (!control.isCancelRequested()
                                    && isPreviewStillValid(
                                        generation, target, frozenConcreteFace, selectedMode, selectedSubMode)) {
                                previewState.addPreviewTarget(generation, matchedTarget);
                            }
                        });
                    if (traversalResult == TraversalStepResult.TERMINATED) {
                        return ParallelTaskResult.TERMINATED;
                    }

                    if (control.isCancelRequested() || !isPreviewStillValid(
                            generation, target, frozenConcreteFace, selectedMode, selectedSubMode)) {
                        return ParallelTaskResult.TERMINATED;
                    }

                    previewState.incrementScannedCount(generation);
                    boolean shouldContinue = traversalResult == TraversalStepResult.CONTINUE
                        || traversalResult == TraversalStepResult.YIELDED;

                    if (!shouldContinue) {
                        if (searchContext.getConfirmedCount() >= searchContext.getMaxTargets()) {
                            previewState.reportTruncation(
                                generation, limitReason, 0, searchContext.getConfirmedCount());
                        }
                        previewState.setCompleted(generation, true);
                    }

                    if (shouldContinue && control.shouldYield()) {
                        return ParallelTaskResult.YIELDED;
                    }

                    return ChainTraversalSupport.toParallelTaskResult(traversalResult);
                });
        } catch (RuntimeException failure) {
            if (previewState.getGeneration() == generation) {
                resetPreview(true);
            }
            MyMod.LOG.warn("[ChainPreview] Failed to register preview planner; retrying on next tick", failure);
            return;
        }

        MyMod.LOG.debug("[ChainPreview] Started preview for target ({}, {}, {})", target.getX(), target.getY(), target.getZ());
    }

    void startRemotePreview(
            ChainMode selectedMode,
            ChainSubMode selectedSubMode,
            ChainTarget target,
            int previewRadius,
            int previewMaxTargets,
            int generation) {
        specialPreviewRequestId++;
        int requestId = specialPreviewRequestId;
        if (!ChainSubModeRegistry.requestRemotePreview(
                selectedSubMode, requestId, target, previewRadius, previewMaxTargets)) {
            cancelRemotePreview(generation, ChainPreviewState.CancelReason.REMOTE_UNAVAILABLE);
            return;
        }
        remotePreviewMaxTargets = previewMaxTargets;
        remotePreviewRequest.begin(requestId, System.nanoTime(), remotePreviewTimeoutMillis());
        MyMod.LOG.debug(
            "[ChainPreview] Requested remote preview for mode={} subMode={} target=({}, {}, {}) radius={} maxTargets={} requestId={}",
            selectedMode,
            selectedSubMode,
            target.getX(),
            target.getY(),
            target.getZ(),
            previewRadius,
            previewMaxTargets,
            requestId);
    }

    /**
     * 远端预览超时检查：命中即按失败取消本代预览并记录原因。
     *
     * @param nowNanos 当前时刻（调用方注入，便于无 Minecraft 的定时断言）
     * @return 本次是否发生超时取消
     */
    boolean checkRemotePreviewTimeout(long nowNanos) {
        if (!remotePreviewRequest.expire(nowNanos)) {
            return false;
        }
        return cancelRemotePreview(
            previewState.getGeneration(), ChainPreviewState.CancelReason.REMOTE_TIMEOUT);
    }

    /**
     * 以失败原因取消本代预览：State 记录 cancelReason 并失活，同时清客户端 previewActive。
     *
     * @param generation 目标代
     * @param reason 失败原因
     * @return 是否确实取消了本代预览
     */
    boolean cancelRemotePreview(int generation, ChainPreviewState.CancelReason reason) {
        if (!previewState.cancelPreview(generation, reason)) {
            return false;
        }
        clearPreviewActiveFlag();
        ChainTarget target = currentTarget;
        if (target != null) {
            MyMod.LOG.debug(
                "[ChainPreview] Remote preview cancelled: reason={} target=({}, {}, {})",
                reason, target.getX(), target.getY(), target.getZ());
        }
        return true;
    }

    private void clearPreviewActiveFlag() {
        if (MyMod.chainStateService != null) {
            MyMod.chainStateService.getClientState().setPreviewActive(false);
        }
    }

    private static long remotePreviewTimeoutMillis() {
        return Math.max(1L, (long) Config.clientPreviewRemoteTimeoutMs);
    }

    /** @return 是否存在在途远端请求，供客户端主线程采样表现投影 */
    public boolean isRemotePreviewPending() {
        return remotePreviewRequest.isPending();
    }

    /** @return 在途远端预览请求 id；无在途请求时为 0（仅客户端主线程读取） */
    public int getRemotePreviewRequestId() {
        return remotePreviewRequest.getPendingRequestId();
    }

    /**
     * 应用 LootGames 扫雷预览结果。
     *
     * @param requestId 请求编号
     * @param origin 请求原点
     * @param targets 服务端返回的雷坐标
     */
    public void applyLootGamesMinesweeperPreview(int requestId, ChainTarget origin, java.util.List<ChainTarget> targets) {
        if (!remotePreviewRequest.accept(requestId)) {
            return;
        }
        if (currentTarget == null
            || !currentTarget.equals(origin)
            || !previewState.isActive()) {
            return;
        }

        int generation = previewState.getGeneration();
        for (ChainTarget target : targets) {
            if (!previewState.addPreviewTarget(generation, target)) {
                return;
            }
        }
        if (targets.size() >= remotePreviewMaxTargets) {
            previewState.reportTruncation(
                generation, ChainPreviewState.TruncationReason.REMOTE_LIMIT, 0, targets.size());
        }
        previewState.setCompleted(generation, true);
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

        return clampPreviewMaxTargets(
            currentServerChainMaxBlocks(),
            Config.clientPreviewMaxTargets,
            Config.clientPreviewMaxTargetsHardCap);
    }

    /**
     * 预览上限收窄纯函数：服务端上限与客户端配置取小后，再受硬顶约束。
     *
     * @param serverLimit 服务端 chainMaxBlocks
     * @param configuredLimit 客户端 clientPreviewMaxTargets
     * @param hardCap clientPreviewMaxTargetsHardCap 硬顶
     * @return 生效上限，恒 >= 1
     */
    static int clampPreviewMaxTargets(int serverLimit, int configuredLimit, int hardCap) {
        int requested = Math.max(1, Math.min(serverLimit, configuredLimit));
        return Math.max(1, Math.min(requested, Math.max(1, hardCap)));
    }

    /**
     * @return 生效上限是否被 clientPreviewMaxTargetsHardCap 收窄（截断原因归因用）
     */
    static boolean isPreviewLimitClampedByHardCap(int serverLimit, int configuredLimit, int hardCap) {
        int requested = Math.max(1, Math.min(serverLimit, configuredLimit));
        return requested > Math.max(1, hardCap);
    }

    private int currentServerChainMaxBlocks() {
        return MyMod.chainStateService == null
            ? Config.chainMaxBlocks
            : MyMod.chainStateService.getClientState().getServerChainMaxBlocks();
    }

    private boolean isPreviewStillValid(
            int generation,
            ChainTarget target,
            int concreteFace,
            ChainMode mode,
            ChainSubMode subMode) {
        if (MyMod.chainStateService == null || !MyMod.chainStateService.getClientState().isChainKeyPressed()) {
            return false;
        }
        if (previewState.getGeneration() != generation) {
            return false;
        }
        return target.equals(currentTarget)
            && concreteFace == previewConcreteFace
            && mode == previewMode
            && subMode == previewSubMode;
    }

    /** 服务端活动阶段或本机成功破坏租约锁定当前种子，覆盖阶段投影抵达前的窗口。 */
    private boolean shouldLockCurrentPreview() {
        if (MyMod.chainStateService == null) {
            return false;
        }

        if (currentTarget == null || !previewState.isActive()) {
            return false;
        }

        // ClientProxy 提供本机阶段投影；缺省时使用本地租约。
        ClientPhaseProjection projection = ClientProxy.clientPhaseProjection;
        if (projection == null) {
            return previewOriginLease.isActive();
        }
        ChainPhase phase = projection.getCurrentPhase();
        int generation = projection.getCurrentGeneration();
        boolean localOriginLocked = previewOriginLease.shouldLock(phase, generation);
        // 本地租约或服务端活动阶段任一成立时保持种子。
        return localOriginLocked || phase == ChainPhase.PLANNING
            || phase == ChainPhase.RUNNING
            || phase == ChainPhase.FINISHING;
    }

    private void stopPreview() {
        // 失活清空输入快照和去抖窗口，下次按键从首 tick 建立新预览。
        currentInputSnapshot = null;
        targetDebounce.reset();
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
        previewMode = null;
        previewSubMode = null;
        remotePreviewRequest.clear();
        specialPreviewRequestId++;
        if (clearSeedLease) {
            previewSeedSnapshot = null;
            previewSeedWorld = null;
            previewOriginLease.reset();
        }
        if (MyMod.chainStateService != null) {
            MyMod.chainStateService.getClientState().setPreviewActive(false);
        }
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

    /** 输入快照值发生变化时换代；目标去抖由调用点按语义身份分别处理。 */
    static boolean shouldRestartPreview(PreviewInputSnapshot current, PreviewInputSnapshot next) {
        return current == null || next == null || !current.equals(next);
    }

    static boolean shouldProjectOriginBeforeTraversal(ChainSubMode subMode) {
        return subMode != ChainSubMode.SPECIAL_GT_CABLE_REPLACE;
    }
}
