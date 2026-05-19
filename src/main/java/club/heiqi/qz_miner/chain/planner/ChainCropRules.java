package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.etfuturum.EtFuturumCompatHelper;
import ic2.core.crop.TileEntityCrop;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
import net.minecraft.tileentity.TileEntity;

/**
 * 作物交互判定规则。
 */
public final class ChainCropRules {

    private ChainCropRules() {}

    /**
     * 判断当前方块或 TileEntity 是否视为可右键连锁的作物。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为作物
     */
    public static boolean isCropBlock(Block block, TileEntity tileEntity) {
        if (block == null) {
            return false;
        }

        if (block instanceof BlockCrops || tileEntity instanceof TileEntityCrop) {
            return true;
        }

        return EtFuturumCompatHelper.isCropBlock(block);
    }
}
