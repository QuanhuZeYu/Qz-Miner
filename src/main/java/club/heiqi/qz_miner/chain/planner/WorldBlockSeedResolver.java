package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
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
        TileEntity sampleTileEntity = null;
        TileIdentityToken sampleTileIdentity;
        try {
            sampleTileEntity = player.worldObj.getTileEntity(origin.getX(), origin.getY(), origin.getZ());
            // resolver 只在主线程调用：读取一次 live TE 后立即纯值化；对象仅留给既有特殊模式兼容字段。
            sampleTileIdentity = CompatAdapters.captureTileIdentity(sampleTileEntity);
        } catch (RuntimeException | LinkageError failure) {
            sampleTileIdentity = TileIdentityToken.unresolved();
            sampleTileEntity = null;
        }
        return new BlockSeedSnapshot(origin, sampleBlock, sampleMeta, sampleTileIdentity, sampleTileEntity);
    }
}
