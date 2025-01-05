package club.heiqi.qz_miner.mixins;

import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import bartworks.system.material.Werkstoff;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import gregtech.GTMod;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.util.GTOreDictUnificator;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Random;

@Mixin(value = BWTileEntityMetaGeneratedOre.class, remap = false)
public abstract class MixinsBWTileEntityMetaGenOre {
    @Shadow
    protected static boolean shouldFortune;
    @Shadow
    protected static boolean shouldSilkTouch;
    @Shadow
    public boolean mNatural;
    @Shadow
    protected abstract Block GetProperBlock();

    @Inject(method = "getDrops", at = @At("HEAD"), cancellable = true, remap = false)
    public void $getDrops(int aFortune, CallbackInfoReturnable<ArrayList<ItemStack>> cir) {
        ArrayList<ItemStack> rList = new ArrayList<>();
        if (((BWTileEntityMetaGeneratedOre) ((Object) this)).mMetaData <= 0) {
            rList.add(new ItemStack(Blocks.cobblestone, 1, 0));
            cir.setReturnValue(rList);
        }
        Materials aOreMaterial = Werkstoff.werkstoffHashMap.get(((BWTileEntityMetaGeneratedOre) ((Object) this)).mMetaData)
            .getBridgeMaterial();
        if (shouldSilkTouch) {
            rList.add(new ItemStack(GetProperBlock(), 1, ((BWTileEntityMetaGeneratedOre) ((Object) this)).mMetaData));
        } else {
            switch (GTMod.gregtechproxy.oreDropSystem) {
                case Item -> {
                    rList.add(GTOreDictUnificator.get(OrePrefixes.rawOre, aOreMaterial, 1));
                }
                case FortuneItem -> {
                    // if shouldFortune and isNatural then get fortune drops
                    // if not shouldFortune or not isNatural then get normal drops
                    // if not shouldFortune and isNatural then get normal drops
                    // if shouldFortune and not isNatural then get normal drops
//                    MY_LOG.LOG.info("是否为自然生成: {}", mNatural);
                    if (Config.forceNatural) mNatural = true;
                    if (shouldFortune && this.mNatural && aFortune > 0) {
                        int aMinAmount = 1;
                        // Max applicable fortune
                        aFortune = Math.min(aFortune, Config.maxFortuneLevel);
                        long amount = (long) new Random().nextInt(aFortune) + aMinAmount;
//                        MY_LOG.LOG.info("正在使用Mixin的getDrops，应该掉落{}个", amount);
                        for (int i = 0; i < amount; i++) {
                            rList.add(GTOreDictUnificator.get(OrePrefixes.rawOre, aOreMaterial, 1));
                        }
                    } else {
                        rList.add(GTOreDictUnificator.get(OrePrefixes.rawOre, aOreMaterial, 1));
                    }
                }
                case UnifiedBlock -> {
                    // Unified ore
                    rList.add(new ItemStack(this.GetProperBlock(), 1, ((BWTileEntityMetaGeneratedOre) ((Object) this)).mMetaData));
                }
                case PerDimBlock -> {
                    // Per Dimension ore
                    rList.add(new ItemStack(this.GetProperBlock(), 1, ((BWTileEntityMetaGeneratedOre) ((Object) this)).mMetaData));
                }
                case Block -> {
                    // Regular ore
                    rList.add(new ItemStack(this.GetProperBlock(), 1, ((BWTileEntityMetaGeneratedOre) ((Object) this)).mMetaData));
                }
            }
        }
        cir.setReturnValue(rList);
    }
}
