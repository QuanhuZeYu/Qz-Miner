package club.heiqi.qz_miner.chain.state;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.network.PacketChainStateSync;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

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
            state.clearRuntimeState("remove-player-state");
        }
    }

    public ChainClientState getClientState() {
        return clientState;
    }

    public void setPlayerChainKeyPressed(UUID playerUUID, boolean pressed) {
        ChainPlayerState state = getOrCreatePlayerState(playerUUID);
        state.setChainKeyPressed(pressed);

        if (!pressed && state.isExecuting()) {
            stopPlayerExecution(playerUUID, "key-released");
            return;
        }

        syncPlayerState(playerUUID);
        MyMod.LOG.debug("[ChainState] Player {} chain key pressed={}", playerUUID, pressed);
    }

    public void setPlayerSelectedMode(UUID playerUUID, ChainMode mode) {
        ChainPlayerState state = getOrCreatePlayerState(playerUUID);
        state.setSelectedMode(mode);

        if (state.isExecuting()) {
            stopPlayerExecution(playerUUID, "mode-changed");
            return;
        }

        syncPlayerState(playerUUID);
        MyMod.LOG.debug("[ChainState] Player {} selected mode={}", playerUUID, mode);
    }

    public void setClientSelectedMode(ChainMode mode) {
        clientState.setSelectedMode(mode);
        MyMod.LOG.debug("[ChainState] Client selected mode={}", mode);
    }

    public void setClientChainKeyPressed(boolean pressed) {
        clientState.setChainKeyPressed(pressed);
        clientState.setPreviewActive(pressed);
        MyMod.LOG.debug("[ChainState] Client chain key pressed={}", pressed);
    }

    public void syncPlayerState(UUID playerUUID) {
        if (MyMod.networkMain == null || MyMod.playerManager == null) {
            return;
        }

        ChainPlayerState state = getPlayerState(playerUUID);
        if (state == null) {
            return;
        }

        EntityPlayer player = MyMod.playerManager.getPlayer(playerUUID);
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }

        MyMod.LOG.debug(
            "[ChainSync] Sending chain state sync to player {} pressed={} executing={} mode={} status={} queuedTargets={} pendingDrops={}",
            playerUUID,
            state.isChainKeyPressed(),
            state.isExecuting(),
            state.getSelectedMode(),
            state.getExecutionStatus(),
            state.getPendingBreakTargets().size(),
            state.getPendingDrops().size());

        MyMod.networkMain.network.sendTo(
            new PacketChainStateSync(
                state.isChainKeyPressed(),
                state.isExecuting(),
                state.getSelectedMode(),
                state.getExecutionStatus()),
            (EntityPlayerMP) player);
    }

    public void stopPlayerExecution(UUID playerUUID, String reason) {
        ChainPlayerState state = getPlayerState(playerUUID);
        if (state == null) {
            return;
        }

        if (!state.isExecuting()
            && state.getPlannerSubscription() == null
            && state.getExecutorSubscription() == null
            && state.getPendingBreakTargets().isEmpty()) {
            return;
        }

        MyMod.LOG.debug("[ChainState] Stopping player execution for {} reason={} status={} queuedTargets={} pendingDrops={}",
            playerUUID,
            reason,
            state.getExecutionStatus(),
            state.getPendingBreakTargets().size(),
            state.getPendingDrops().size());
        state.clearRuntimeState(reason);
        syncPlayerState(playerUUID);
    }

    private void onPlayerStateChanged(PlayerStateEvent event) {
        UUID playerUUID = event.player.getUniqueID();
        switch (event.reason) {
            case LOGIN:
            case RESPAWN:
            case DIMENSION_CHANGE:
            case CLONE:
                getOrCreatePlayerState(playerUUID);
                syncPlayerState(playerUUID);
                break;
            case LOGOUT:
                removePlayerState(playerUUID);
                break;
            default:
                break;
        }
    }
}
