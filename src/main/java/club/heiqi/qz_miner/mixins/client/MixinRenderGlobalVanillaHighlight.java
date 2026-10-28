package club.heiqi.qz_miner.mixins.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.chain.client.ChainPreviewRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 RenderGlobal.drawSelectionBox(EntityPlayer, MovingObjectPosition, int, float) 的 HEAD
 * 按预览门控抑制原版方块选择框，使当前目标保留单一指示。
 * index、命中和 renderer 校验通过后才取消，破坏动画、粒子与实体描边沿各自入口绘制。
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalVanillaHighlight {

    /**
     * @param player       被绘制选择框的玩家
     * @param hit          玩家射线命中结果（EntityRenderer 传 mc.objectMouseOver）
     * @param index        绘制通道（原版仅 0 有效）
     * @param partialTicks 帧插值
     * @param callback     取消回调
     */
    @Inject(
        method = "drawSelectionBox(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/MovingObjectPosition;IF)V",
        at = @At("HEAD"),
        cancellable = true)
    private void qzMiner$suppressVanillaHighlight(
            EntityPlayer player,
            MovingObjectPosition hit,
            int index,
            float partialTicks,
            CallbackInfo callback) {
        if (index != 0 || hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        ChainPreviewRenderer renderer = ClientProxy.chainPreviewRenderer;
        if (renderer == null) {
            return;
        }
        if (renderer.shouldSuppressVanillaHighlight(hit.blockX, hit.blockY, hit.blockZ)) {
            callback.cancel();
        }
    }
}
