package club.heiqi.qz_miner.mixins.GTMixin;

import club.heiqi.qz_miner.Config;
import gregtech.GTMod;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;

@Mixin(value = TileEntityOres.class, remap = false)
public abstract class MixinsTileEntityOres {
    @Shadow
    public short mMetaData;
    @Shadow
    public boolean mNatural;
    @Shadow
    protected static boolean shouldSilkTouch;
    @Shadow
    protected static boolean shouldFortune;

    @Inject(method = "getDrops", at = @At("HEAD"), cancellable = true, remap = false)
    public void $getDrops(Block aDroppedOre, int aFortune, CallbackInfoReturnable<ArrayList<ItemStack>> cir) {
        ArrayList<ItemStack> rList = new ArrayList<>();
        if (this.mMetaData <= 0) {
            rList.add(new ItemStack(Blocks.cobblestone, 1, 0));
            cir.setReturnValue(rList);
        }
        Materials aOreMaterial = GregTechAPI.sGeneratedMaterials[(this.mMetaData % 1000)];
        if (this.mMetaData < 16000) {
            boolean tIsRich = false;

            // For Sake of god of balance!

            // Dense ore

            // NetherOre
            if (GTMod.gregtechproxy.mNetherOreYieldMultiplier && !tIsRich) {
                tIsRich = (this.mMetaData >= 1000 && this.mMetaData < 2000);
            }
            // EndOre
            if (GTMod.gregtechproxy.mEndOreYieldMultiplier && !tIsRich) {
                tIsRich = (this.mMetaData >= 2000 && this.mMetaData < 3000);
            }

            // Silk Touch
            if (shouldSilkTouch) {
                rList.add(new ItemStack(aDroppedOre, 1, this.mMetaData));

            } else {
                switch (GTMod.gregtechproxy.oreDropSystem) {
                    case Item -> {
                        rList.add(GTOreDictUnificator.get(OrePrefixes.rawOre, aOreMaterial, (tIsRich ? 2 : 1)));
                    }
                    // TODO: Test
                    case FortuneItem -> {
                        // if shouldFortune and isNatural then get fortune drops
                        // if not shouldFortune or not isNatural then get normal drops
                        // if not shouldFortune and isNatural then get normal drops
                        // if shouldFortune and not isNatural then get normal drops
                        if (Config.forceNatural) mNatural = true;
                        if (shouldFortune && this.mNatural && aFortune > 0) {
                            int aMinAmount = 1;
                            // Max applicable fortune
                            aFortune = Math.min(aFortune, Config.maxFortuneLevel);
                            int amount = aMinAmount
                                + Math.max(((TileEntityOres) (Object) this).getWorldObj().rand.nextInt(aFortune * (tIsRich ? 2 : 1) + 2) - 1, 0);
                            // MY_LOG.LOG.info("正在使用Mixin的getDrops，应该掉落{}个", amount);
                            for (int i = 0; i < amount; i++) {
                                rList.add(GTOreDictUnificator.get(OrePrefixes.rawOre, aOreMaterial, 1));
                            }
                        } else {
                            for (int i = 0; i < (tIsRich ? 2 : 1); i++) {
                                rList.add(GTOreDictUnificator.get(OrePrefixes.rawOre, aOreMaterial, 1));
                            }
                        }
                    }
                    case UnifiedBlock -> {
                        // Unified ore
                        for (int i = 0; i < (tIsRich ? 2 : 1); i++) {
                            rList.add(new ItemStack(aDroppedOre, 1, this.mMetaData % 1000));
                        }
                    }
                    case PerDimBlock -> {
                        // Per Dimension ore
                        if (tIsRich) {
                            rList.add(new ItemStack(aDroppedOre, 1, this.mMetaData));
                        } else {
                            rList.add(new ItemStack(aDroppedOre, 1, this.mMetaData % 1000));
                        }
                    }
                    case Block -> {
                        // Regular ore
                        rList.add(new ItemStack(aDroppedOre, 1, this.mMetaData));
                    }
                }
            }
            cir.setReturnValue(rList);
        }
    }
}
