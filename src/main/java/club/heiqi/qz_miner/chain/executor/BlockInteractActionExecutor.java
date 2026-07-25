package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * 默认的方块右键交互执行策略。
 */
public class BlockInteractActionExecutor implements ChainActionExecutor {

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.INTERACT;
    }

    @Override
    public boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || session == null || session.getRequest() == null || target == null
                || player.worldObj == null) {
            return false;
        }

        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        int face = session.getRequest().getInteractFace();
        try {
            if (!player.worldObj.blockExists(x, y, z)
                    || !player.worldObj.canMineBlock(player, x, y, z)) {
                return false;
            }
            ItemStack currentStack = player.getCurrentEquippedItem();
            return player.canPlayerEdit(x, y, z, face, currentStack);
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error("[BlockInteractActionExecutor] Failed interaction permission check for player {} at ({}, {}, {})",
                    player.getUniqueID(), Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z), failure);
            return false;
        }
    }

    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || session == null || session.getRequest() == null || target == null
                || player.worldObj == null || player.theItemInWorldManager == null) {
            return false;
        }
        boolean interactionSucceeded = false;
        try {
            // 每个目标执行前重新读取当前主手；玩家中途换物或上一目标耗尽时不得复用旧引用。
            ItemStack currentStack = player.getCurrentEquippedItem();
            interactionSucceeded = player.theItemInWorldManager.activateBlockOrUseItem(
                player,
                player.worldObj,
                currentStack,
                target.getX(),
                target.getY(),
                target.getZ(),
                session.getRequest().getInteractFace(),
                session.getRequest().getInteractHitX(),
                session.getRequest().getInteractHitY(),
                session.getRequest().getInteractHitZ());
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error("[BlockInteractActionExecutor] Failed to interact block for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), failure);
        } finally {
            try {
                // 直调交互入口绕过 NetHandler 的库存后置步骤；只归一交互后仍处于当前槽的真实栈。
                int currentItem = player.inventory.currentItem;
                ItemStack currentStackAfterUse = player.inventory.getCurrentItem();
                if (currentStackAfterUse != null && currentStackAfterUse.stackSize <= 0) {
                    player.inventory.mainInventory[currentItem] = null;
                }
                player.inventory.markDirty();
                if (player.openContainer != null) {
                    player.openContainer.detectAndSendChanges();
                }
            } catch (RuntimeException | LinkageError failure) {
                MyMod.LOG.error(
                    "[BlockInteractActionExecutor] Failed post-interaction inventory sync for player {} at ({}, {}, {})",
                    player.getUniqueID(), target.getX(), target.getY(), target.getZ(), failure);
                interactionSucceeded = false;
            }
        }
        return interactionSucceeded;
    }
}
