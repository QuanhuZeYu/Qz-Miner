package club.heiqi.qz_miner.chain.planner;

import ic2.core.crop.TileEntityCrop;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;

/**
 * 作物交互匹配器。
 */
public class CropBlockMatcher implements ChainBlockMatcher {

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        Block block = player.worldObj.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air || block.getMaterial().isLiquid()) {
            return false;
        }

        if (block instanceof BlockCrops) {
            return true;
        }

        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        return tileEntity instanceof TileEntityCrop;
    }
}
