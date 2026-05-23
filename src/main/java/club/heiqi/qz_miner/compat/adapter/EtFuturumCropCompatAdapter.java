package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * Et Futurum Requiem 作物兼容适配器。
 */
public final class EtFuturumCropCompatAdapter implements CropCompatAdapter {

    private final Class<?> berryBushType;
    private final Class<?> caveVinesType;

    /**
     * 创建 EFR 作物适配器。
     */
    public EtFuturumCropCompatAdapter() {
        this.berryBushType = ClassNameCompatSupport.resolveClass("ganymedes01.etfuturum.blocks.BlockBerryBush");
        this.caveVinesType = ClassNameCompatSupport.resolveClass("ganymedes01.etfuturum.blocks.BaseCaveVines");
    }

    @Override
    public boolean isAvailable() {
        return berryBushType != null || caveVinesType != null;
    }

    @Override
    public boolean isCropBlock(Block block, TileEntity tileEntity) {
        return ClassNameCompatSupport.isInstance(berryBushType, block)
            || ClassNameCompatSupport.isInstance(caveVinesType, block);
    }
}
