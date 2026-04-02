package club.heiqi.qz_miner.chain.state;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;

/**
 * 连锁状态服务。
 *
 * 统一管理服务端玩家连锁状态和客户端本地状态。
 */
public final class ChainStateService {

    private final Map<UUID, ChainPlayerState> playerStates = new ConcurrentHashMap<>();
    private final ChainClientState clientState = new ChainClientState();

    public ChainStateService() {
        QzEvents.register(PlayerStateEvent.class, (EventListener<PlayerStateEvent>) this::onPlayerStateChanged);
    }

    public ChainPlayerState getOrCreatePlayerState(UUID playerUUID) {
        return playerStates.computeIfAbsent(playerUUID, ChainPlayerState::new);
    }

    public ChainPlayerState getPlayerState(UUID playerUUID) {
        return playerStates.get(playerUUID);
    }

    public Iterable<ChainPlayerState> getPlayerStates() {
        return playerStates.values();
    }

    public void removePlayerState(UUID playerUUID) {
        ChainPlayerState state = playerStates.remove(playerUUID);
        if (state != null) {
            state.clearRuntimeState();
        }
    }

    public ChainClientState getClientState() {
        return clientState;
    }

    public void setPlayerChainKeyPressed(UUID playerUUID, boolean pressed) {
        ChainPlayerState state = getOrCreatePlayerState(playerUUID);
        state.setChainKeyPressed(pressed);
        MyMod.LOG.debug("[ChainState] Player {} chain key pressed={}", playerUUID, pressed);
    }

    public void setClientChainKeyPressed(boolean pressed) {
        clientState.setChainKeyPressed(pressed);
        clientState.setPreviewActive(pressed);
        MyMod.LOG.debug("[ChainState] Client chain key pressed={}", pressed);
    }

    private void onPlayerStateChanged(PlayerStateEvent event) {
        UUID playerUUID = event.player.getUniqueID();
        switch (event.reason) {
            case LOGIN:
            case RESPAWN:
            case DIMENSION_CHANGE:
            case CLONE:
                getOrCreatePlayerState(playerUUID);
                break;
            case LOGOUT:
                removePlayerState(playerUUID);
                break;
            default:
                break;
        }
    }
}
