package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;

/**
 * 默认方块挖掘匹配器。
 */
public class HarvestableBlockMatcher implements ChainBlockMatcher {

    @Override
    public boolean matches(EntityPlayerMP player, ChainTarget target) {
        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air || block == Blocks.bedrock || block.getMaterial().isLiquid()) {
            return false;
        }

        int meta = player.worldObj.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        if (player.capabilities.isCreativeMode) {
            return true;
        }
        return block.canHarvestBlock(player, meta);
    }
}
