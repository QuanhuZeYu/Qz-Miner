package club.heiqi.qz_miner.chain.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.ChainTraverser;
import club.heiqi.qz_miner.chain.planner.HarvestableBlockMatcher;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
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

    private static final int MAX_SCAN_PER_SLICE = 32;
    private static final int MAX_PREVIEW_TARGETS = 256;

    private final ChainPreviewState previewState = new ChainPreviewState();
    private ChainTarget currentTarget;
    private ParallelTickSubscription previewTaskSubscription;

    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    public ChainPreviewState getPreviewState() {
        return previewState;
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

        if (!MyMod.chainStateService.getClientState().isChainKeyPressed()) {
            stopPreview();
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
        final int previewRadius = getEffectivePreviewRadius();
        final ConcurrentLinkedQueue<ChainTarget> currentFrontier = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<>();
        final Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        final ChainSearchContext searchContext = new ChainSearchContext(
            world,
            target,
            sampleBlock,
            sampleMeta,
            MyMod.chainStateService.getClientState().getSelectedSubMode(),
            previewRadius,
            MAX_PREVIEW_TARGETS,
            currentFrontier,
            nextFrontier,
            visited);
        final ChainMode selectedMode = MyMod.chainStateService.getClientState().getSelectedMode();
        final ChainTraverser traverser = resolveTraverser(selectedMode);
        final ChainBlockMatcher blockMatcher = createBlockMatcher(selectedMode, searchContext);
        if (traverser == null || blockMatcher == null) {
            previewState.setCompleted(true);
            return;
        }
        traverser.seed(searchContext);

        previewTaskSubscription = MyMod.ensureParallelTickExecutor().registerClientPre(
            "chain-preview-" + target.getX() + "-" + target.getY() + "-" + target.getZ(),
            context -> {
                if (!isPreviewStillValid(generation, target)) {
                    return false;
                }

                boolean shouldContinue = traverser.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    matchedTarget -> blockMatcher.matches(player, matchedTarget),
                    previewState::addPreviewTarget);
                previewState.incrementScannedCount();

                if (!shouldContinue) {
                    previewState.setCompleted(true);
                }

                return shouldContinue;
            });

        MyMod.LOG.debug("[ChainPreview] Started preview for target ({}, {}, {})", target.getX(), target.getY(), target.getZ());
    }

    /**
     * 获取当前实际使用的预览半径。
     *
     * @return 预览半径
     */
    public int getEffectivePreviewRadius() {
        int serverChainRadius = MyMod.chainStateService == null
            ? Config.chainRadius
            : MyMod.chainStateService.getClientState().getServerChainRadius();
        return Math.max(1, Math.min(serverChainRadius, Config.clientPreviewMaxRadius));
    }

    /**
     * 根据当前模式和子模式创建预览匹配器。
     *
     * @param mode 当前模式
     * @param searchContext 搜索上下文
     * @return 预览匹配器
     */
    private ChainBlockMatcher createBlockMatcher(ChainMode mode, ChainSearchContext searchContext) {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
        if (definition != null) {
            ChainBlockMatcher matcher = definition.createMatcher(searchContext);
            if (matcher != null) {
                return matcher;
            }
        }
        return new HarvestableBlockMatcher();
    }

    /**
     * 根据当前模式选择预览遍历器。
     *
     * @param mode 当前连锁模式
     * @return 对应的遍历器
     */
    private ChainTraverser resolveTraverser(ChainMode mode) {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
        if (definition != null) {
            return definition.getTraverser();
        }
        return null;
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
