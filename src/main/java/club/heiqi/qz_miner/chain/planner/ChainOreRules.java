package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * 矿石判定规则。
 */
public final class ChainOreRules {

    private ChainOreRules() {}

    /**
     * 判断当前方块是否视为矿石。
     *
     * @param block 方块
     * @return 是否为矿石
     */
    public static boolean isOreBlock(Block block) {
        return isOreBlock(block, null);
    }

    /**
     * 判断当前方块或其 TileEntity 是否视为矿石。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为矿石
     */
    public static boolean isOreBlock(Block block, TileEntity tileEntity) {
        return CompatAdapters.isOreBlock(block, tileEntity);
    }
}
