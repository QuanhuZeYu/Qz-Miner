package club.heiqi.qz_miner.mixins.GTMixin;

import gregtech.api.metatileentity.CoverableTileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CoverableTileEntity.class, remap = false)
public interface CoverableTileEntityAccessor {
    @Accessor("mID")
    short getMID();
    @Accessor("mID")
    void setMID(short mid);
}
