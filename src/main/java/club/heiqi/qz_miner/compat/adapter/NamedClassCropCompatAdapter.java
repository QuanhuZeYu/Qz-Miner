package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * 通过可选类名判定作物的适配器。
 */
public final class NamedClassCropCompatAdapter implements CropCompatAdapter {

    private final Class<?> blockType;
    private final Class<?> tileEntityType;

    /**
     * 创建按类名判定的作物适配器。
     *
     * @param blockClassName 作物方块类名
     * @param tileEntityClassName 作物 TileEntity 类名
     */
    public NamedClassCropCompatAdapter(String blockClassName, String tileEntityClassName) {
        this.blockType = ClassNameCompatSupport.resolveClass(blockClassName);
        this.tileEntityType = ClassNameCompatSupport.resolveClass(tileEntityClassName);
    }

    @Override
    public boolean isAvailable() {
        return blockType != null || tileEntityType != null;
    }

    @Override
    public boolean isCropBlock(Block block, TileEntity tileEntity) {
        return ClassNameCompatSupport.isInstance(blockType, block)
            || ClassNameCompatSupport.isInstance(tileEntityType, tileEntity);
    }
}
