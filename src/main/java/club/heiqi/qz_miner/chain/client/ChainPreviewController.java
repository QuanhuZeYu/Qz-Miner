package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.MyMod;
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
    private static final int MAX_SCAN_PER_SLICE = 64;

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
        final int diameter = PREVIEW_RADIUS * 2 + 1;
        final int totalSize = diameter * diameter * diameter;

        previewTaskSubscription = MyMod.parallelTickExecutor.registerClientPre(
            "chain-preview-" + target.getX() + "-" + target.getY() + "-" + target.getZ(),
            context -> {
                int processed = 0;
                while (context.hasTimeLeft() && processed < MAX_SCAN_PER_SLICE) {
                    int currentIndex = previewState.incrementScannedCount() - 1;
                    if (currentIndex >= totalSize) {
                        previewState.setCompleted(true);
                        return false;
                    }

                    if (!isPreviewStillValid(generation, target)) {
                        return false;
                    }

                    int localX = currentIndex % diameter;
                    int localY = (currentIndex / diameter) % diameter;
                    int localZ = currentIndex / (diameter * diameter);

                    int worldX = target.getX() + localX - PREVIEW_RADIUS;
                    int worldY = target.getY() + localY - PREVIEW_RADIUS;
                    int worldZ = target.getZ() + localZ - PREVIEW_RADIUS;

                    Block block = world.getBlock(worldX, worldY, worldZ);
                    int meta = world.getBlockMetadata(worldX, worldY, worldZ);
                    if (block == sampleBlock && meta == sampleMeta) {
                        previewState.addPreviewTarget(new ChainTarget(worldX, worldY, worldZ));
                    }

                    processed++;
                }

                return true;
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
