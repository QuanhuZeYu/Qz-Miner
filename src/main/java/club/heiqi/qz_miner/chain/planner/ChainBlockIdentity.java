package club.heiqi.qz_miner.chain.planner;

import bartworks.system.material.TileEntityMetaGeneratedBlock;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 方块身份判定工具。
 */
public final class ChainBlockIdentity {

    private ChainBlockIdentity() {}

    /**
     * 判断目标坐标上的方块是否与起点视为同类。
     *
     * @param world 当前世界
     * @param sampleBlock 起点方块
     * @param sampleMeta 起点元数据
     * @param sampleTileEntity 起点 TileEntity
     * @param target 目标位置
     * @return 是否视为同类
     */
    public static boolean matches(World world, Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity, ChainTarget target) {
        if (world == null || sampleBlock == null || target == null) {
            return false;
        }

        Block targetBlock = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (targetBlock == null || targetBlock == Blocks.air || targetBlock != sampleBlock) {
            return false;
        }

        int targetMeta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
        if (targetMeta != sampleMeta) {
            return false;
        }

        TileEntity targetTileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
        return matchesTileEntity(sampleTileEntity, targetTileEntity);
    }

    /**
     * 判断两个 TileEntity 是否可视为同类。
     *
     * @param sampleTileEntity 起点 TileEntity
     * @param targetTileEntity 目标 TileEntity
     * @return 是否可视为同类
     */
    public static boolean matchesTileEntity(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        if (sampleTileEntity == null && targetTileEntity == null) {
            return true;
        }

        if (sampleTileEntity == null || targetTileEntity == null) {
            return false;
        }

        if (sampleTileEntity instanceof IGregTechTileEntity sampleGregTechTile
            && targetTileEntity instanceof IGregTechTileEntity targetGregTechTile) {
            if (sampleGregTechTile.getMetaTileID() != targetGregTechTile.getMetaTileID()) {
                return false;
            }

            if (sampleGregTechTile.getMetaTileEntity() == null || targetGregTechTile.getMetaTileEntity() == null) {
                return sampleGregTechTile.getMetaTileEntity() == targetGregTechTile.getMetaTileEntity();
            }

            return sampleGregTechTile.getMetaTileEntity().getClass() == targetGregTechTile.getMetaTileEntity().getClass();
        }

        if (sampleTileEntity instanceof TileEntityOres sampleOres
            && targetTileEntity instanceof TileEntityOres targetOres) {
            return sampleOres.mMetaData == targetOres.mMetaData;
        }

        if (sampleTileEntity instanceof TileEntityMetaGeneratedBlock sampleMetaGeneratedBlock
            && targetTileEntity instanceof TileEntityMetaGeneratedBlock targetMetaGeneratedBlock) {
            return sampleMetaGeneratedBlock.mMetaData == targetMetaGeneratedBlock.mMetaData;
        }

        return sampleTileEntity.getClass() == targetTileEntity.getClass();
    }
}
