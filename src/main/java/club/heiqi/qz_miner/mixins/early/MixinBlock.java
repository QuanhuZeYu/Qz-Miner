package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.Config;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Block.class)
public class MixinBlock {

    /**
     * 重定向对 EntityPlayer.addExhaustion(float) 的调用，修改传入的参数值。
     *
     * @param player 正在挖掘方块的玩家实体
     * @param originalExhaustion 原本应该消耗的体力值 (0.025F)
     */
    @Redirect(
            method = "harvestBlock(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;IIII)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/EntityPlayer;addExhaustion(F)V",
                    remap = true
            )
    )
    private void redirectAddExhaustion(EntityPlayer player, float originalExhaustion) {
        // 调用原方法，但传入新的参数值
        if (Config.addExhaustion > 0) {
            player.addExhaustion((float) Config.addExhaustion);
        }
        else {
            float toAdd = (float) -Config.addExhaustion;
            player.getFoodStats().addStats((int) toAdd, (float) (toAdd*0.1));
        }
    }
}
