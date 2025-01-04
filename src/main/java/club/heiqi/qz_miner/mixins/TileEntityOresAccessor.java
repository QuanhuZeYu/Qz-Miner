package club.heiqi.qz_miner.mixins;

import gregtech.common.blocks.TileEntityOres;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = TileEntityOres.class,remap = false)
public interface TileEntityOresAccessor {
    @Accessor("shouldFortune")
    static boolean getShouldFortune() {
        throw new AssertionError();
    }

    @Accessor("shouldFortune")
    static void setShouldFortune(boolean shouldFortune) {
        throw new AssertionError();
    }

    @Accessor("shouldSilkTouch")
    static boolean getShouldSilkTouch() {
        throw new AssertionError();
    }

    @Accessor("shouldSilkTouch")
    static void setShouldSilkTouch(boolean shouldSilkTouch) {
        throw new AssertionError();
    }
}
