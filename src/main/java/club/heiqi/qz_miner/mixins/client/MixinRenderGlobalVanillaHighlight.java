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
 * B2.5 原版方块高亮协同：预览激活且瞄准方块就是当前预览目标时，取消原版黑色选择框，
 * 避免"双重指示"。
 *
 * <p>只注入 1.7.10 黑色描边唯一入口
 * {@code RenderGlobal.drawSelectionBox(EntityPlayer, MovingObjectPosition, int, float)} 的 HEAD：
 * index != 0、非方块命中、renderer 缺失、门控未通过（开关关闭 / 预览未激活 / 瞄准目标不匹配）
 * 全部直接 return，不 cancel、不写任何 GL 状态——默认档（suppressVanillaHighlight=false）
 * 逐字等于原版。破坏动画、粒子、实体描边位于其它方法，不受本注入影响。</p>
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
