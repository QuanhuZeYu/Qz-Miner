package club.heiqi.qz_miner.compat.adapter;

import club.heiqi.qz_miner.MyMod;
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
        // 降级可观测性约定：两类都不可解析时记 warn，避免静默失效导致矿石匹配为 0 且无报错
        // （GS 注：本条不对应 NORTH_STAR 任一不变量的强制要求，仅为可观测性实践）
        if (this.blockType == null && this.tileEntityType == null) {
            MyMod.LOG.warn(
                "[Compat][Ore] Ore adapter degraded - both classes unresolved: block={}, tile={}",
                blockClassName, tileEntityClassName);
        }
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
