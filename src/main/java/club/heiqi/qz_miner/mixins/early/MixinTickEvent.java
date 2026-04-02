package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.parallel.ParallelTickStage;
import cpw.mods.fml.common.FMLCommonHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Forge 发布服务端 Tick 事件前后打开和关闭并行窗口。
 *
 * pre 阶段：
 * onPreServerTick HEAD -> 开始并行
 * onPreServerTick TAIL -> 结束并行
 *
 * post 阶段：
 * onPostServerTick HEAD -> 开始并行
 * onPostServerTick TAIL -> 结束并行
 */
@Mixin(value = FMLCommonHandler.class, remap = false)
public class MixinTickEvent {

    @Inject(method = "onPreServerTick", at = @At("HEAD"))
    private void onPreServerTickStart(CallbackInfo ci) {
        if (MyMod.parallelTickExecutor != null) {
            MyMod.parallelTickExecutor.beginStage(ParallelTickStage.PRE);
        }
    }

    @Inject(method = "onPreServerTick", at = @At("TAIL"))
    private void onPreServerTickEnd(CallbackInfo ci) {
        if (MyMod.parallelTickExecutor != null) {
            MyMod.parallelTickExecutor.endStage(ParallelTickStage.PRE);
        }
    }

    @Inject(method = "onPostServerTick", at = @At("HEAD"))
    private void onPostServerTickStart(CallbackInfo ci) {
        if (MyMod.parallelTickExecutor != null) {
            MyMod.parallelTickExecutor.beginStage(ParallelTickStage.POST);
        }
    }

    @Inject(method = "onPostServerTick", at = @At("TAIL"))
    private void onPostServerTickEnd(CallbackInfo ci) {
        if (MyMod.parallelTickExecutor != null) {
            MyMod.parallelTickExecutor.endStage(ParallelTickStage.POST);
        }
    }
}
