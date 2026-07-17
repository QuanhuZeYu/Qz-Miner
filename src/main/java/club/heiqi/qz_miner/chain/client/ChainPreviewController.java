package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
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
    private volatile ChainTarget currentTarget;
    private ParallelTickSubscription previewTaskSubscription;
    private int specialPreviewRequestId;

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

        ChainTarget target = getCurrentLookTarget(minecraft.objectMouseOver);
        if (target == null) {
            stopPreview();
            return;
        }

        if (!target.equals(currentTarget)) {
            startPreview(world, target);
        }
    }

    private void startPreview(World world, ChainTarget target) {
        stopPreview();

        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayer player = minecraft.thePlayer;
        if (player == null) {
            return;
        }

        currentTarget = target;
        previewState.begin(target);
        MyMod.chainStateService.getClientState().setPreviewActive(true);

        final int generation = previewState.getGeneration();
        final Block sampleBlock = world.getBlock(target.getX(), target.getY(), target.getZ());
        final int sampleMeta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        final TileEntity sampleTileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
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
            AxisAlignedTunnelDirection.resolveFace(player), 0.0F, 0.0F, 0.0F,
            -1, -1, modeExtension);
        final BlockSeedSnapshot seedSnapshot = new BlockSeedSnapshot(target, sampleBlock, sampleMeta, sampleTileEntity);
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
                if (control.isCancelRequested() || !isPreviewStillValid(generation, target)) {
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
                        && isPreviewStillValid(generation, target)
                        && blockMatcher.matches(player, matchedTarget),
                    matchedTarget -> {
                        if (!control.isCancelRequested() && isPreviewStillValid(generation, target)) {
                            previewState.addPreviewTarget(matchedTarget);
                        }
                    });
                if (traversalResult == TraversalStepResult.TERMINATED) {
                    return ParallelTaskResult.TERMINATED;
                }

                if (control.isCancelRequested() || !isPreviewStillValid(generation, target)) {
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

    private boolean isPreviewStillValid(int generation, ChainTarget target) {
        if (MyMod.chainStateService == null || !MyMod.chainStateService.getClientState().isChainKeyPressed()) {
            return false;
        }
        if (previewState.getGeneration() != generation) {
            return false;
        }
        return target.equals(currentTarget);
    }

    /**
     * 判断当前是否应锁定已启动的预览计算。
     *
     * <p>阶段8 块3 G2 夺权：锁定权威切换到 {@link ClientPhaseProjection}（不再读旧
     * {@code serverExecutionStatus}）。只要服务端投影阶段处于 PLANNING/RUNNING/FINISHING，
     * 就继续保留当前预览，避免客户端转动视角导致正在进行的连锁预览被切走。</p>
     *
     * <p>F1 锁定边界：ARMED 不锁——玩家已武装但尚未点火，仍可自由选目标；
     * IDLE 不锁——无活跃连锁。</p>
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
            return false;
        }
        ChainPhase phase = projection.getCurrentPhase();
        // F1：PLANNING/RUNNING/FINISHING 锁定；ARMED/IDLE 不锁
        return phase == ChainPhase.PLANNING
            || phase == ChainPhase.RUNNING
            || phase == ChainPhase.FINISHING;
    }

    private void stopPreview() {
        if (previewTaskSubscription != null) {
            previewTaskSubscription.unregister();
            previewTaskSubscription = null;
        }

        if (previewState.isActive() || currentTarget != null) {
            MyMod.LOG.debug("[ChainPreview] Stopped preview");
        }

        previewState.clear();
        currentTarget = null;
        specialPreviewRequestId++;
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
}
