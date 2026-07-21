package club.heiqi.qz_miner.chain.state;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.ChainDropReleaseHelper;
import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

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
        cleanupAutoToolSwapRound(playerUUID);
        GregTechCableSessionState.clear(playerUUID);
        ChainPlayerState state = playerStates.remove(playerUUID);
        if (state != null) {
            flushPlayerDrops(state, null, reason);
            state.clearObjectGroupRules();
            state.clearRuntimeState(reason);
        }
    }

    public void cleanupPlayerState(UUID playerUUID, String reason, boolean removeState) {
        cleanupPlayerState(playerUUID, null, reason, removeState);
    }

    public void cleanupPlayerState(UUID playerUUID, EntityPlayer player, String reason, boolean removeState) {
        cleanupAutoToolSwapRound(playerUUID);
        ChainPlayerState state = getPlayerState(playerUUID);
        if (state == null) {
            return;
        }

        GregTechCableSessionState.clear(playerUUID);

        flushPlayerDrops(state, player, reason);

        if (removeState) {
            removePlayerState(playerUUID, reason);
            return;
        }

        state.clearRuntimeState(reason);
        state.resetAcceptedTunnelDirectionSource();
        // 阶段8 块3：删旧 syncPlayerState 调用（八字段同步链已删）。
        // 客户端 config 由 ChainConfigProjectionBridge 订阅 LOGIN/PlanCompleted 下发，无需此处兜底。
    }

    public ChainClientState getClientState() {
        return clientState;
    }

    /**
     * 经玩家级状态服务缓冲无法直接交付的物品，保持与会话生命周期解耦。
     *
     * @param playerUUID 玩家 UUID
     * @param stack 待缓冲物品
     */
    public void bufferPlayerDrop(UUID playerUUID, ItemStack stack) {
        if (playerUUID != null && stack != null && stack.stackSize > 0) {
            getOrCreatePlayerState(playerUUID).getDropBuffer().add(stack);
        }
    }

    public void setPlayerChainKeyPressed(UUID playerUUID, boolean pressed) {
        ChainPlayerState state = getOrCreatePlayerState(playerUUID);
        state.setChainKeyPressed(pressed);

        // 阶段8 块2：删旧 stopPlayerExecution 收口（G1 掉落窗口保护）。
        // 旧逻辑：松键+isExecuting → stopPlayerExecution（setExecuting false）会破坏 G1 掉落窗口——
        // RUNNING 时松键 setExecuting false 致 ChainDropCollector:32 跳过收集。
        // 新链路 ARMED 模型：RUNNING 时松键不中断连锁，跑完为止（已裁定可接受）。
        // 阶段8 块3：删旧 syncPlayerState 调用（八字段同步链已删，chainKeyPressed 是服务端内部信号，不同步回客户端）。
        MyMod.LOG.debug("[ChainState] Player {} chain key pressed={}", playerUUID, pressed);
    }

    public void setPlayerSelectedMode(UUID playerUUID, ChainMode mode) {
        ChainPlayerState state = getOrCreatePlayerState(playerUUID);
        state.setSelectedMode(mode);

        // 阶段8 块2：删旧 isExecuting + stopPlayerExecution 收口（理由同上，保护 G1 掉落窗口）。
        // 新链路：RUNNING 时切模式不中断连锁。
        // 阶段8 块3：删旧 syncPlayerState 调用（mode 是客户端本地选择，不需服务端同步回客户端）。
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

        // 阶段8 块2：删旧 isExecuting + stopPlayerExecution 收口（保护 G1 掉落窗口）。
        // 新链路：RUNNING 时切子模式不中断连锁。
        // 阶段8 块3：删旧 syncPlayerState 调用（subMode 是客户端本地选择，不需服务端同步回客户端）。
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

    /**
     * 阶段8 块3：删除旧 syncPlayerState（八字段同步链已删）。
     *
     * <p>原方法组装 {@code PacketChainStateSync} 下发 8 字段（chainKeyPressed/executing/mode/subMode/
     * executionStatus/radius/maxBlocks/matchedCount）到客户端。阶段8 块3 后：</p>
     * <ul>
     *   <li>phase 由 {@code ClientPhaseProjection} 承载（阶段6 投影主线，块3 G2 夺权）。</li>
     *   <li>radius/maxBlocks/matchedCount 由 {@code ChainConfigProjectionBridge} 订阅 PlanCompleted/LOGIN 下发新 config 包。</li>
     *   <li>mode/subMode/chainKeyPressed 是客户端本地选择/服务端内部信号，不同步回客户端。</li>
     * </ul>
     */

    private void onPlayerStateChanged(PlayerStateEvent event) {
        UUID playerUUID = event.player.getUniqueID();
        switch (event.reason) {
            case LOGIN:
                cleanupAutoToolSwapRound(playerUUID);
                getOrCreatePlayerState(playerUUID);
                // 阶段8 块3：删旧 syncPlayerState（八字段链已删）。
                // 客户端 config 由 ChainConfigProjectionBridge 订阅 LOGIN 下发基础 config 包。
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

    /** 丢弃服务端工具换位账本，不创建 endpoint 或访问库存。 */
    private void cleanupAutoToolSwapRound(UUID playerUUID) {
        if (playerUUID != null && MyMod.autoToolSwapRoundService != null) {
            MyMod.autoToolSwapRoundService.cleanup(playerUUID);
        }
    }
}
