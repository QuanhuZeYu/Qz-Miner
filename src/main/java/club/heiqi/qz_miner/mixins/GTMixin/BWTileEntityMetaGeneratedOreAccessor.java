package club.heiqi.qz_miner.mixins.GTMixin;

import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BWTileEntityMetaGeneratedOre.class, remap = false)
public interface BWTileEntityMetaGeneratedOreAccessor {
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
