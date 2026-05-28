package club.heiqi.qz_miner.chain.executor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.chain.state.ChainSession;

/**
 * GT 线缆替换模式会话状态。
 */
public final class GregTechCableSessionState {

    private static final Map<UUID, Integer> LOCKED_REPLACEMENT_META_TILE_IDS = new ConcurrentHashMap<UUID, Integer>();

    private GregTechCableSessionState() {}

    public static Integer getLockedReplacementMetaTileId(ChainSession session) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        return playerUUID == null ? null : LOCKED_REPLACEMENT_META_TILE_IDS.get(playerUUID);
    }

    public static void lockReplacementMetaTileId(ChainSession session, int metaTileId) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        if (playerUUID == null) {
            return;
        }
        LOCKED_REPLACEMENT_META_TILE_IDS.put(playerUUID, metaTileId);
    }

    public static void clear(ChainSession session) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        clear(playerUUID);
    }

    public static void clear(UUID playerUUID) {
        if (playerUUID == null) {
            return;
        }
        LOCKED_REPLACEMENT_META_TILE_IDS.remove(playerUUID);
    }
}
