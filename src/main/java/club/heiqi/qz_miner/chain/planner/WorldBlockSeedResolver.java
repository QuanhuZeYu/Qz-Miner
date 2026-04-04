package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;

/**
 * 默认世界方块种子解析器。
 */
public class WorldBlockSeedResolver implements BlockSeedResolver {

    @Override
    public BlockSeedSnapshot resolve(EntityPlayerMP player, ChainTarget origin) {
        Block sampleBlock = player.worldObj.getBlock(origin.getX(), origin.getY(), origin.getZ());
        if (sampleBlock == null || sampleBlock == Blocks.air) {
            return null;
        }

        int sampleMeta = player.worldObj.getBlockMetadata(origin.getX(), origin.getY(), origin.getZ());
        TileEntity sampleTileEntity = player.worldObj.getTileEntity(origin.getX(), origin.getY(), origin.getZ());
        return new BlockSeedSnapshot(origin, sampleBlock, sampleMeta, sampleTileEntity);
    }
}
