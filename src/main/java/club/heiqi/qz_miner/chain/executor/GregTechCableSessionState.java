package club.heiqi.qz_miner.chain.executor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * GT 线缆替换模式会话状态。
 */
public final class GregTechCableSessionState {

    public enum ExecutionPhase {
        REPLACE,
        RECONNECT
    }

    private static final Map<UUID, Integer> LOCKED_REPLACEMENT_META_TILE_IDS = new ConcurrentHashMap<UUID, Integer>();
    private static final Map<UUID, Map<ChainTarget, List<ForgeDirection>>> PENDING_RECONNECT_SIDES = new ConcurrentHashMap<UUID, Map<ChainTarget, List<ForgeDirection>>>();
    private static final Map<UUID, ExecutionPhase> EXECUTION_PHASES = new ConcurrentHashMap<UUID, ExecutionPhase>();

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
        EXECUTION_PHASES.putIfAbsent(playerUUID, ExecutionPhase.REPLACE);
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
        PENDING_RECONNECT_SIDES.remove(playerUUID);
        EXECUTION_PHASES.remove(playerUUID);
    }

    public static void rememberReconnectSides(ChainSession session, ChainTarget target, List<ForgeDirection> connectedSides) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        if (playerUUID == null || target == null || connectedSides == null || connectedSides.isEmpty()) {
            return;
        }

        Map<ChainTarget, List<ForgeDirection>> reconnectSides = PENDING_RECONNECT_SIDES.computeIfAbsent(
            playerUUID,
            ignored -> new ConcurrentHashMap<ChainTarget, List<ForgeDirection>>());
        reconnectSides.put(target, Collections.unmodifiableList(new ArrayList<ForgeDirection>(connectedSides)));
    }

    public static List<ForgeDirection> getReconnectSides(ChainSession session, ChainTarget target) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        if (playerUUID == null || target == null) {
            return Collections.emptyList();
        }

        Map<ChainTarget, List<ForgeDirection>> reconnectSides = PENDING_RECONNECT_SIDES.get(playerUUID);
        if (reconnectSides == null) {
            return Collections.emptyList();
        }

        List<ForgeDirection> sides = reconnectSides.get(target);
        return sides == null ? Collections.<ForgeDirection>emptyList() : sides;
    }

    public static List<ChainTarget> getReconnectTargetsSnapshot(ChainSession session) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        if (playerUUID == null) {
            return Collections.emptyList();
        }

        Map<ChainTarget, List<ForgeDirection>> reconnectSides = PENDING_RECONNECT_SIDES.get(playerUUID);
        if (reconnectSides == null || reconnectSides.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<ChainTarget>(reconnectSides.keySet());
    }

    public static void clearReconnectSides(ChainSession session, ChainTarget target) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        if (playerUUID == null || target == null) {
            return;
        }

        Map<ChainTarget, List<ForgeDirection>> reconnectSides = PENDING_RECONNECT_SIDES.get(playerUUID);
        if (reconnectSides == null) {
            return;
        }

        reconnectSides.remove(target);
        if (reconnectSides.isEmpty()) {
            PENDING_RECONNECT_SIDES.remove(playerUUID);
        }
    }

    public static ExecutionPhase getExecutionPhase(ChainSession session) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        if (playerUUID == null) {
            return ExecutionPhase.REPLACE;
        }
        ExecutionPhase phase = EXECUTION_PHASES.get(playerUUID);
        return phase == null ? ExecutionPhase.REPLACE : phase;
    }

    public static void setExecutionPhase(ChainSession session, ExecutionPhase phase) {
        UUID playerUUID = session == null ? null : session.getPlayerUUID();
        if (playerUUID == null || phase == null) {
            return;
        }
        EXECUTION_PHASES.put(playerUUID, phase);
    }
}
