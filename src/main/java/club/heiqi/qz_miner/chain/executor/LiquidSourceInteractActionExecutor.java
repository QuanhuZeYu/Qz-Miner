package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.interaction.InteractionRayTrace;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainLiquidRules;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import cpw.mods.fml.common.eventhandler.Event;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * 让当前手持物按正常右键语义作用于精确计划液体 source 的服务端交互执行器。
 */
public final class LiquidSourceInteractActionExecutor implements ChainActionExecutor {

    private static final BlockInteractActionExecutor PERMISSION_EXECUTOR =
            new BlockInteractActionExecutor();

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.INTERACT;
    }

    /**
     * 依次复用通用交互权限门、重验同种 live source，并确认当前手持栈可用。
     */
    @Override
    public boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (!PERMISSION_EXECUTOR.canExecute(player, session, target)) {
            return false;
        }
        try {
            String seedFluidName = resolveSeedFluidName(session);
            if (seedFluidName == null) {
                return false;
            }
            Block candidateBlock = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
            int candidateMetadata = player.worldObj.getBlockMetadata(
                    target.getX(), target.getY(), target.getZ());
            if (!ChainLiquidRules.matchesSource(
                    seedFluidName,
                    player.worldObj,
                    target.getX(),
                    target.getY(),
                    target.getZ(),
                    candidateBlock,
                    candidateMetadata)) {
                return false;
            }
            ItemStack currentStack = player.getCurrentEquippedItem();
            return hasUsableCurrentStack(currentStack);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error(
                "[LiquidSourceInteractActionExecutor] Failed source/item check for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), failure);
            return false;
        }
    }

    /**
     * 在可回滚虚拟姿态内验证精确液体射线，再复用正常空气右键与 Item 使用语义。
     */
    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || session == null || session.getRequest() == null || target == null
                || player.worldObj == null || player.theItemInWorldManager == null) {
            return false;
        }

        boolean interactionSucceeded = false;
        ServerPlayerPoseTransaction poseTransaction = null;
        try {
            if (!canExecute(player, session, target)) {
                return false;
            }

            // 每个目标再次读取真实当前槽；canExecute 与 Item 调用之间不租赁旧物品引用。
            ItemStack currentStack = player.getCurrentEquippedItem();
            if (!hasUsableCurrentStack(currentStack)) {
                return false;
            }

            TargetInteractionPose targetPose = TargetInteractionPose.forTarget(
                    target.getX(), target.getY(), target.getZ(), session.getRequest().getInteractFace());
            poseTransaction = ServerPlayerPoseTransaction.capture(player);
            poseTransaction.apply(targetPose);

            MovingObjectPosition hit = InteractionRayTrace.trace(
                    player, player.theItemInWorldManager.getBlockReachDistance(), true);
            if (!isExactTargetHit(hit, target)) {
                return false;
            }

            PlayerInteractEvent event = ForgeEventFactory.onPlayerInteract(
                    player,
                    PlayerInteractEvent.Action.RIGHT_CLICK_AIR,
                    0,
                    0,
                    0,
                    -1,
                    player.worldObj);
            if (event.useItem == Event.Result.DENY) {
                return false;
            }
            interactionSucceeded = player.theItemInWorldManager.tryUseItem(
                    player, player.worldObj, currentStack);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error(
                "[LiquidSourceInteractActionExecutor] Failed targeted liquid interaction for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), failure);
            interactionSucceeded = false;
        } finally {
            if (poseTransaction != null) {
                try {
                    poseTransaction.close();
                } catch (RuntimeException | LinkageError failure) {
                    MyMod.LOG.error(
                        "[LiquidSourceInteractActionExecutor] Failed to restore virtual pose for player {} at ({}, {}, {})",
                        player.getUniqueID(), target.getX(), target.getY(), target.getZ(), failure);
                    interactionSucceeded = false;
                }
            }
            if (!InteractionInventorySupport.normalizeAndSync(player, target)) {
                interactionSucceeded = false;
            }
        }
        return interactionSucceeded;
    }

    private static String resolveSeedFluidName(ChainSession session) {
        Block seedBlock = session.getRequest().getSeedBlock();
        int seedMetadata = session.getRequest().getSeedMeta();
        if (!ChainLiquidRules.isSeedSource(seedBlock, seedMetadata)) {
            return null;
        }
        return ChainLiquidRules.fluidIdentity(seedBlock);
    }

    private static boolean hasUsableCurrentStack(ItemStack currentStack) {
        return currentStack != null && currentStack.stackSize > 0;
    }

    private static boolean isExactTargetHit(MovingObjectPosition hit, ChainTarget target) {
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.blockX == target.getX()
                && hit.blockY == target.getY()
                && hit.blockZ == target.getZ();
    }
}
