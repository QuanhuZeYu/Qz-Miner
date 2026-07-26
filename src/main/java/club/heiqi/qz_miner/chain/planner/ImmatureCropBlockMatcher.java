package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 每次按 live world 重新确认“可靠未成熟”的作物匹配器。
 *
 * <p>实例不保存 World 或 TileEntity；成熟与未知状态均 fail-closed。</p>
 */
public final class ImmatureCropBlockMatcher implements ChainBlockMatcher {

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null || player.worldObj == null) {
            return false;
        }

        World world = player.worldObj;
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        try {
            Block block = world.getBlock(x, y, z);
            if (block == null || block == Blocks.air || block.getMaterial().isLiquid()) {
                return false;
            }
            int metadata = world.getBlockMetadata(x, y, z);
            TileEntity tileEntity = world.getTileEntity(x, y, z);
            return ChainCropRules.isReliablyImmature(world, x, y, z, block, metadata, tileEntity);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }
}
