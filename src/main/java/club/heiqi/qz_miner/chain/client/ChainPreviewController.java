package club.heiqi.qz_miner.chain.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainSearchAlgorithm;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
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

    private static final int PREVIEW_RADIUS = 4;
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

        currentTarget = target;
        previewState.begin(target);
        MyMod.chainStateService.getClientState().setPreviewActive(true);

        final int generation = previewState.getGeneration();
        final Block sampleBlock = world.getBlock(target.getX(), target.getY(), target.getZ());
        final int sampleMeta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        final ConcurrentLinkedQueue<ChainTarget> currentFrontier = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<>();
        final Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        currentFrontier.add(target);
        visited.add(target);
        final ChainSearchContext searchContext = new ChainSearchContext(
            world,
            target,
            sampleBlock,
            sampleMeta,
            PREVIEW_RADIUS,
            MAX_PREVIEW_TARGETS,
            currentFrontier,
            nextFrontier,
            visited);

        previewTaskSubscription = MyMod.ensureParallelTickExecutor().registerClientPre(
            "chain-preview-" + target.getX() + "-" + target.getY() + "-" + target.getZ(),
            context -> {
                if (!isPreviewStillValid(generation, target)) {
                    return false;
                }

                boolean shouldContinue = ChainSearchAlgorithm.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    matchedTarget -> true,
                    previewState::addPreviewTarget);
                previewState.incrementScannedCount();

                if (!shouldContinue) {
                    previewState.setCompleted(true);
                }

                return shouldContinue;
            });

        MyMod.LOG.debug("[ChainPreview] Started preview for target ({}, {}, {})", target.getX(), target.getY(), target.getZ());
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
