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
        if (player == null || session == null || target == null) {
            return false;
        }

        return player.worldObj.blockExists(target.getX(), target.getY(), target.getZ());
    }

    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        try {
            ItemStack equippedItem = player.getCurrentEquippedItem();
            boolean activated = player.theItemInWorldManager.activateBlockOrUseItem(
                player,
                player.worldObj,
                equippedItem,
                target.getX(),
                target.getY(),
                target.getZ(),
                session.getRequest().getInteractFace(),
                session.getRequest().getInteractHitX(),
                session.getRequest().getInteractHitY(),
                session.getRequest().getInteractHitZ());
            if (!activated && equippedItem != null) {
                return player.theItemInWorldManager.tryUseItem(player, player.worldObj, equippedItem);
            }
            return activated;
        } catch (Exception e) {
            MyMod.LOG.error("[ChainExecutor] Failed to interact block for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), e);
            return false;
        }
    }
}
