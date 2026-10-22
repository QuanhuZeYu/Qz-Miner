package club.heiqi.qz_miner.client.settings;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.config.ConfigKey;

/** 只记录既有 S2C 已通过身份校验这一事实；数值继续读取原 ChainClientState。 */
public final class ClientServerConfigFacts {
    private static long confirmedConnection = -1L;
    private ClientServerConfigFacts() { }
    public static void received(ClientConnectionLifecycle.Token token) {
        confirmedConnection = token.connectionGeneration();
    }
    public static void clear() { confirmedConnection = -1L; }
    public static boolean confirmed() {
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        return token.isConnectionActive() && token.connectionGeneration() == confirmedConnection;
    }
    public static String value(ConfigKey<?> key) {
        if (!confirmed() || MyMod.chainStateService == null) return "";
        if (key == QzMinerConfigSchema.CHAIN_RADIUS) {
            return String.valueOf(MyMod.chainStateService.getClientState().getServerChainRadius());
        }
        if (key == QzMinerConfigSchema.CHAIN_MAX_BLOCKS) {
            return String.valueOf(MyMod.chainStateService.getClientState().getServerChainMaxBlocks());
        }
        return "";
    }
}
