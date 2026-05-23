package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * 矿石兼容适配器。
 */
public interface OreCompatAdapter {

    /**
     * 判断适配器当前是否可用。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 判断方块或 TileEntity 是否视为矿石。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为矿石
     */
    boolean isOreBlock(Block block, TileEntity tileEntity);
}
