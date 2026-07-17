package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.network.PacketAutoToolSwapIntent;
import club.heiqi.qz_miner.network.PacketAutoToolSwapRoundStart;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 已由 ClientProxy 接入运行态的 Qz-Miner C2S packet 客户端传输实现。 */
@SideOnly(Side.CLIENT)
public final class QzAutoToolSwapClientTransport implements AutoToolSwapClientTransport {

    /** {@inheritDoc} */
    @Override
    public boolean sendRoundStart(long clientNonce) {
        if (MyMod.networkMain == null) {
            return false;
        }
        MyMod.networkMain.network.sendToServer(new PacketAutoToolSwapRoundStart(clientNonce));
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean sendIntent(AutoToolSwapIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        if (MyMod.networkMain == null) {
            return false;
        }
        MyMod.networkMain.network.sendToServer(new PacketAutoToolSwapIntent(intent));
        return true;
    }
}
