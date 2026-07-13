package club.heiqi.qz_miner.mixins.client;

import club.heiqi.qz_miner.autotool.VanillaInventoryTransactionObserver;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在实际 C0E 构造时发布不可变点击字段。 */
@Mixin(value = C0EPacketClickWindow.class, remap = false)
public class MixinC0EPacketClickWindow {
    @Inject(method = "<init>(IIIILnet/minecraft/item/ItemStack;S)V", at = @At("RETURN"))
    private void observeConstructed(int windowId, int slotId, int usedButton, int mode,
            net.minecraft.item.ItemStack clickedItem, short actionNumber, CallbackInfo ci) {
        // 构造发生在 PlayerControllerMP.windowClick 的客户端主线程调用栈。
        VanillaInventoryTransactionObserver.clickPacket(windowId, slotId, usedButton, mode, actionNumber);
    }
}
