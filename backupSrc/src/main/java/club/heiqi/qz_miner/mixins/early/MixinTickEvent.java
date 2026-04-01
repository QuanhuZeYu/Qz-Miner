package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.FMLCommonHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FMLCommonHandler.class, remap = false)
public class MixinTickEvent {
    @Inject(method = "onPreServerTick", at = @At("HEAD"))
    public void onPreServerTickStart(CallbackInfo ci) {
        MyMod.parallelTick.preTick.set(true);
        MyMod.parallelTick.processPreTickTasks(true);
        MyMod.parallelTick.postTick.set(true);
        MyMod.parallelTick.processPostTickTasks(true);
    }

    @Inject(method = "onPreServerTick", at = @At("TAIL"))
    public void onPreServerTickEnd(CallbackInfo ci) {

    }

    @Inject(method = "onPostServerTick", at = @At("HEAD"))
    public void onPostServerTickStart(CallbackInfo ci) {
        MyMod.parallelTick.preTick.set(false);
        MyMod.parallelTick.processPreTickTasks(false);
        MyMod.parallelTick.postTick.set(false);
        MyMod.parallelTick.processPostTickTasks(false);
    }

    @Inject(method = "onPostServerTick", at = @At("TAIL"))
    public void onPostServerTickEnd(CallbackInfo ci) {

    }

    @Inject(method = "onPreClientTick", at = @At("HEAD"))
    public void onPreClientTickStart(CallbackInfo ci) {
        MyMod.parallelTick.processNormalTasks();
    }
}
