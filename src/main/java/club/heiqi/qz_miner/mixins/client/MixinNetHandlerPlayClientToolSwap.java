package club.heiqi.qz_miner.mixins.client;

import club.heiqi.qz_miner.client.toolswap.AutoToolSwapHooks;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 捕获原版库存事务真实 id、确认与 vanilla 应用后的槽同步覆盖。 */
@Mixin(value = NetHandlerPlayClient.class, remap = false)
public abstract class MixinNetHandlerPlayClientToolSwap {

    /** 同步 windowClick 出包路径：只捕获 C0E 原始字段，精确 intent 在 adapter 核对。 */
    @Inject(method = "addToSendQueue(Lnet/minecraft/network/Packet;)V", at = @At("HEAD"))
    private void qzMiner$captureClickWindow(Packet packet, CallbackInfo callback) {
        if (!(packet instanceof C0EPacketClickWindow)) {
            return;
        }
        C0EPacketClickWindow click = (C0EPacketClickWindow) packet;
        AutoToolSwapHooks.onClickWindowPacket(
                this,
                click.func_149548_c(),
                click.func_149544_d(),
                click.func_149543_e(),
                click.func_149542_h(),
                click.func_149547_f());
    }

    /** vanilla 处理确认后再路由原始字段。 */
    @Inject(method = "handleConfirmTransaction(Lnet/minecraft/network/play/server/S32PacketConfirmTransaction;)V",
            at = @At("RETURN"))
    private void qzMiner$afterConfirmTransaction(S32PacketConfirmTransaction packet, CallbackInfo callback) {
        AutoToolSwapHooks.onConfirmTransaction(
                this, packet.func_148889_c(), packet.func_148890_d(), packet.func_148888_e());
    }

    /** vanilla 应用单槽同步后再记录覆盖。 */
    @Inject(method = "handleSetSlot(Lnet/minecraft/network/play/server/S2FPacketSetSlot;)V", at = @At("RETURN"))
    private void qzMiner$afterSetSlot(S2FPacketSetSlot packet, CallbackInfo callback) {
        AutoToolSwapHooks.onSetSlot(this, packet.func_149175_c(), packet.func_149173_d());
    }

    /** vanilla 应用整窗同步后再记录覆盖。 */
    @Inject(method = "handleWindowItems(Lnet/minecraft/network/play/server/S30PacketWindowItems;)V",
            at = @At("RETURN"))
    private void qzMiner$afterWindowItems(S30PacketWindowItems packet, CallbackInfo callback) {
        AutoToolSwapHooks.onWindowItems(this, packet.func_148911_c());
    }
}
