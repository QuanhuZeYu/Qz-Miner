package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * 通过可选类名判定矿石的适配器。
 */
public final class NamedClassOreCompatAdapter implements OreCompatAdapter {

    private final Class<?> blockType;
    private final Class<?> tileEntityType;

    /**
     * 创建按类名判定的矿石适配器。
     *
     * @param blockClassName 矿石方块类名
     * @param tileEntityClassName 矿石 TileEntity 类名
     */
    public NamedClassOreCompatAdapter(String blockClassName, String tileEntityClassName) {
        this.blockType = ClassNameCompatSupport.resolveClass(blockClassName);
        this.tileEntityType = ClassNameCompatSupport.resolveClass(tileEntityClassName);
    }

    @Override
    public boolean isAvailable() {
        return blockType != null || tileEntityType != null;
    }

    @Override
    public boolean isOreBlock(Block block, TileEntity tileEntity) {
        return ClassNameCompatSupport.isInstance(blockType, block)
            || ClassNameCompatSupport.isInstance(tileEntityType, tileEntity);
    }
}
