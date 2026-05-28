package club.heiqi.qz_miner.chain.state;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.ChainDropReleaseHelper;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
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
        removePlayerState(playerUUID, "remove-player-state");
    }

    public void removePlayerState(UUID playerUUID, String reason) {
        ChainPlayerState state = playerStates.remove(playerUUID);
        if (state != null) {
            flushPlayerDrops(state, null, reason);
            state.clearRuntimeState(reason);
        }
    }

    public void cleanupPlayerState(UUID playerUUID, String reason, boolean removeState) {
        cleanupPlayerState(playerUUID, null, reason, removeState);
    }

    public void cleanupPlayerState(UUID playerUUID, EntityPlayer player, String reason, boolean removeState) {
        ChainPlayerState state = getPlayerState(playerUUID);
        if (state == null) {
            return;
        }

        flushPlayerDrops(state, player, reason);

        if (removeState) {
            removePlayerState(playerUUID, reason);
            return;
        }

        state.clearRuntimeState(reason);
        syncPlayerState(playerUUID);
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

    /**
     * 设置服务端玩家 AREA 子模式。
     *
     * @param playerUUID 玩家 UUID
     * @param areaSubMode AREA 子模式
     */
    public void setPlayerSelectedSubMode(UUID playerUUID, ChainSubMode subMode) {
        ChainPlayerState state = getOrCreatePlayerState(playerUUID);
        state.setSelectedSubMode(subMode);

        if (state.isExecuting()) {
            stopPlayerExecution(playerUUID, "sub-mode-changed");
            return;
        }

        syncPlayerState(playerUUID);
        MyMod.LOG.debug("[ChainState] Player {} selected sub mode={}", playerUUID, state.getSelectedSubMode());
    }

    public void setClientSelectedMode(ChainMode mode) {
        clientState.setSelectedMode(mode);
        MyMod.LOG.debug("[ChainState] Client selected mode={}", mode);
    }

    /**
     * 设置客户端 AREA 子模式。
     *
     * @param areaSubMode AREA 子模式
     */
    public void setClientSelectedSubMode(ChainSubMode subMode) {
        clientState.setSelectedSubMode(subMode);
        MyMod.LOG.debug("[ChainState] Client selected sub mode={}", clientState.getSelectedSubMode());
    }

    public void setClientChainKeyPressed(boolean pressed) {
        clientState.setChainKeyPressed(pressed);
        clientState.setPreviewActive(pressed);
        MyMod.LOG.debug("[ChainState] Client chain key pressed={}", pressed);
    }

    public void setClientRequestedChainConfig(int requestedChainRadius, int requestedChainMaxBlocks) {
        clientState.setRequestedChainRadius(requestedChainRadius);
        clientState.setRequestedChainMaxBlocks(requestedChainMaxBlocks);
        MyMod.LOG.debug("[ChainState] Client requested chain config radius={} maxBlocks={}",
            clientState.getRequestedChainRadius(), clientState.getRequestedChainMaxBlocks());
    }

    public void syncPlayerState(UUID playerUUID) {
        if (MyMod.networkMain == null || MyMod.playerManager == null) {
            return;
        }

        ChainPlayerState state = getPlayerState(playerUUID);
        if (state == null) {
            return;
        }

        ChainRuntimeState runtimeState = state.getRuntimeState();

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
            runtimeState == null ? 0 : runtimeState.getPendingBreakTargets().size(),
            state.getDropBuffer().size());

        MyMod.networkMain.network.sendTo(
            new PacketChainStateSync(
                state.isChainKeyPressed(),
                state.isExecuting(),
                state.getSelectedMode(),
                state.getSelectedSubMode(),
                state.getExecutionStatus(),
                Config.chainRadius,
                Config.chainMaxBlocks,
                runtimeState == null ? 0 : runtimeState.getMatchedTargetCount()),
            (EntityPlayerMP) player);
    }

    public void stopPlayerExecution(UUID playerUUID, String reason) {
        ChainPlayerState state = getPlayerState(playerUUID);
        if (state == null) {
            return;
        }

        ChainRuntimeState runtimeState = state.getRuntimeState();

        if (!state.isExecuting()
            && (runtimeState == null
            || (runtimeState.getPlannerSubscription() == null
            && runtimeState.getPendingBreakTargets().isEmpty()
            && state.getDropBuffer().isEmpty()))) {
            return;
        }

        int queuedTargets = runtimeState == null ? 0 : runtimeState.getPendingBreakTargets().size();
        int pendingDrops = state.getDropBuffer().size();

        MyMod.LOG.debug("[ChainState] Stopping player execution for {} reason={} status={} queuedTargets={} pendingDrops={}",
            playerUUID,
            reason,
            state.getExecutionStatus(),
            queuedTargets,
            pendingDrops);
        state.stopExecutionPreservingDrops(reason);
        syncPlayerState(playerUUID);
    }

    private void onPlayerStateChanged(PlayerStateEvent event) {
        UUID playerUUID = event.player.getUniqueID();
        switch (event.reason) {
            case LOGIN:
                getOrCreatePlayerState(playerUUID);
                syncPlayerState(playerUUID);
                break;
            case RESPAWN:
                getOrCreatePlayerState(playerUUID);
                cleanupPlayerState(playerUUID, event.player, "player-respawn", false);
                break;
            case DIMENSION_CHANGE:
                getOrCreatePlayerState(playerUUID);
                cleanupPlayerState(playerUUID, event.player, "player-dimension-change", false);
                break;
            case CLONE:
                getOrCreatePlayerState(playerUUID);
                cleanupPlayerState(playerUUID, event.player, "player-clone", false);
                break;
            case LOGOUT:
                cleanupPlayerState(playerUUID, event.player, "player-logout", true);
                break;
            default:
                break;
        }
    }

    private void flushPlayerDrops(ChainPlayerState state, EntityPlayer player, String reason) {
        if (state == null || state.getDropBuffer().isEmpty()) {
            return;
        }

        EntityPlayer playerSnapshot = player;
        if (playerSnapshot == null && MyMod.playerManager != null) {
            playerSnapshot = MyMod.playerManager.getPlayer(state.getPlayerUUID());
        }

        if (playerSnapshot instanceof EntityPlayerMP
            && ChainDropReleaseHelper.releaseAtPlayer((EntityPlayerMP) playerSnapshot, state.getDropBuffer(), reason)) {
            return;
        }

        if (playerSnapshot != null
            && ChainDropReleaseHelper.releaseAtRespawnOrWorldSpawn(playerSnapshot, state.getDropBuffer(), reason)) {
            return;
        }

        if (ChainDropReleaseHelper.releaseAtRememberedTarget(state.getDropBuffer(), state.getPlayerUUID().toString(), reason)) {
            return;
        }

        ChainDropReleaseHelper.discard(state.getPlayerUUID().toString(), state.getDropBuffer(), reason + "-missing-release-context");
    }
}
