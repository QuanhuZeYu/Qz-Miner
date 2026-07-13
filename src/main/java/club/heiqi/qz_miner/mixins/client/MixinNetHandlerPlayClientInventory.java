package club.heiqi.qz_miner.mixins.client;

import club.heiqi.qz_miner.autotool.VanillaInventoryTransactionObserver;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 观察原版客户端库存确认与完整窗口重同步。 */
@Mixin(value = NetHandlerPlayClient.class, remap = false)
public class MixinNetHandlerPlayClientInventory {
    @Inject(method = "handleConfirmTransaction", at = @At("RETURN"))
    private void observeConfirm(S32PacketConfirmTransaction packet, CallbackInfo ci) {
        // 原版 PacketThreadUtil 已在方法入口前把处理收口到客户端主线程。
        VanillaInventoryTransactionObserver.confirm(packet.func_148889_c(), packet.func_148890_d(), packet.func_148888_e());
    }

    @Inject(method = "handleWindowItems", at = @At("RETURN"))
    private void observeWindowItems(S30PacketWindowItems packet, CallbackInfo ci) {
        // 原版 PacketThreadUtil 已在方法入口前把处理收口到客户端主线程。
        VanillaInventoryTransactionObserver.windowItems(packet.func_148911_c());
    }
}
