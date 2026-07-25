package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainLiquidRules;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import cpw.mods.fml.common.eventhandler.Event;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * 仅让当前手持可接收容器作用于精确计划液体 source 的服务端交互执行器。
 *
 * <p>该内部能力尚未注册到任何用户可见子模式。</p>
 */
public final class LiquidSourceInteractActionExecutor implements ChainActionExecutor {

    private static final BlockInteractActionExecutor PERMISSION_EXECUTOR =
            new BlockInteractActionExecutor();

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.INTERACT;
    }

    /**
     * 依次复用通用交互权限门、重验同种 live source，并确认当前容器可接收 seed 流体。
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
            return LiquidContainerUsePolicy.canAccept(seedFluidName, currentStack);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error(
                "[LiquidSourceInteractActionExecutor] Failed source/container check for player {} at ({}, {}, {})",
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

            String seedFluidName = resolveSeedFluidName(session);
            // 每个目标再次读取真实当前槽；canExecute 与 item 调用之间不租赁旧容器引用。
            ItemStack currentStack = player.getCurrentEquippedItem();
            if (!LiquidContainerUsePolicy.canAccept(seedFluidName, currentStack)) {
                return false;
            }

            TargetInteractionPose targetPose = TargetInteractionPose.forTarget(
                    target.getX(), target.getY(), target.getZ(), session.getRequest().getInteractFace());
            poseTransaction = ServerPlayerPoseTransaction.capture(player);
            poseTransaction.apply(targetPose);

            MovingObjectPosition hit = rayTraceLiquidTarget(player);
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

    /** 完整复刻 Item 液体射线的起点、朝向、reach 与 world 参数。 */
    private static MovingObjectPosition rayTraceLiquidTarget(EntityPlayerMP player) {
        float partialTicks = 1.0F;
        float pitch = player.prevRotationPitch
                + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw
                + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        double eyeX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double eyeY = player.prevPosY + (player.posY - player.prevPosY) * partialTicks
                + (player.worldObj.isRemote
                    ? player.getEyeHeight() - player.getDefaultEyeHeight()
                    : player.getEyeHeight());
        double eyeZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
        Vec3 eye = Vec3.createVectorHelper(eyeX, eyeY, eyeZ);
        float yawCos = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float yawSin = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float pitchCos = -MathHelper.cos(-pitch * 0.017453292F);
        float pitchSin = MathHelper.sin(-pitch * 0.017453292F);
        float lookX = yawSin * pitchCos;
        float lookZ = yawCos * pitchCos;
        double reach = player.theItemInWorldManager.getBlockReachDistance();
        Vec3 end = eye.addVector(lookX * reach, pitchSin * reach, lookZ * reach);
        return player.worldObj.func_147447_a(eye, end, true, false, false);
    }

    private static boolean isExactTargetHit(MovingObjectPosition hit, ChainTarget target) {
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.blockX == target.getX()
                && hit.blockY == target.getY()
                && hit.blockZ == target.getZ();
    }
}
