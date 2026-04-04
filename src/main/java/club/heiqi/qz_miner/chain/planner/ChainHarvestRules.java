package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

/**
 * 连锁挖掘共享判定规则。
 */
public final class ChainHarvestRules {

    private ChainHarvestRules() {}

    /**
     * 判断当前工具耐久是否仍允许继续连锁。
     *
     * @param player 玩家
     * @return 是否仍允许继续连锁
     */
    public static boolean hasEnoughDurability(EntityPlayer player) {
        if (player == null || player.capabilities.isCreativeMode) {
            return true;
        }

        ItemStack equippedItem = player.getCurrentEquippedItem();
        if (equippedItem == null || !equippedItem.isItemStackDamageable()) {
            return true;
        }

        return equippedItem.getMaxDamage() - equippedItem.getItemDamage() > 1;
    }

    /**
     * 判断目标是否为玩家脚底方块。
     *
     * @param player 玩家
     * @param target 目标方块
     * @return 是否为脚底保护方块
     */
    public static boolean isStandingOnTarget(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        return target.getX() == (int) Math.floor(player.posX)
            && target.getY() == (int) Math.floor(player.posY) - 1
            && target.getZ() == (int) Math.floor(player.posZ);
    }

    /**
     * 判断目标方块当前是否允许进入连锁挖掘流程。
     *
     * @param player 玩家
     * @param target 目标方块
     * @return 是否允许挖掘
     */
    public static boolean canHarvest(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air || block == Blocks.bedrock || block.getMaterial().isLiquid()) {
            return false;
        }

        if (isStandingOnTarget(player, target)) {
            return false;
        }

        if (!hasEnoughDurability(player)) {
            return false;
        }

        if (player.capabilities.isCreativeMode) {
            return true;
        }

        int meta = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        return block.canHarvestBlock(player, meta);
    }
}
