package club.heiqi.qz_miner.chain.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.ChainDropReleaseHelper;
import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.selection.CuboidSelection;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;
import club.heiqi.qz_miner.network.PacketCuboidSelectionSync;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CloseCause;
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
    // 已停止服务端的资产只供显式领取，不进入新世界的自动释放集合。
    private final Map<UUID, List<ChainPlayerDropBuffer>> suspendedPlayerDrops = new ConcurrentHashMap<>();
    private final ChainClientState clientState = new ChainClientState();

    public ChainStateService() {
        QzEvents.register(PlayerStateEvent.class, (EventListener<PlayerStateEvent>) this::onPlayerStateChanged);
    }

    public ChainPlayerState getOrCreatePlayerState(UUID playerUUID) {
        return playerStates.compute(playerUUID, (uuid, state) -> {
            if (state == null) return new ChainPlayerState(uuid);
            return state.isPendingRemoval() ? new ChainPlayerState(uuid, state.getDropBuffer()) : state;
        });
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
        finalizeAutoToolSwapLocal(playerUUID, null);
        cleanupAutoToolSwapRound(playerUUID);
        GregTechCableSessionState.clear(playerUUID);
        ChainPlayerState state = playerStates.get(playerUUID);
        if (state != null) {
            flushPlayerDrops(state, null, reason);
            retirePlayerState(state, reason);
        }
    }

    public void cleanupPlayerState(UUID playerUUID, String reason, boolean removeState) {
        cleanupPlayerState(playerUUID, null, reason, removeState);
    }

    public void cleanupPlayerState(UUID playerUUID, EntityPlayer player, String reason, boolean removeState) {
        // local owner 必须先 restore/classify + publication gate，projection 与连锁运行态才可清除。
        finalizeAutoToolSwapLocal(playerUUID, player);
        cleanupAutoToolSwapRound(playerUUID);
        ChainPlayerState state = getPlayerState(playerUUID);
        if (state == null) {
            return;
        }

        GregTechCableSessionState.clear(playerUUID);

        flushPlayerDrops(state, player, reason);

        if (removeState) {
            retirePlayerState(state, reason);
            return;
        }

        state.clearRuntimeState(reason);
        CuboidSelection clearedSelection = state.clearCuboidSelection();
        if (player instanceof EntityPlayerMP && MyMod.networkMain != null) {
            MyMod.networkMain.network.sendTo(
                    new PacketCuboidSelectionSync(clearedSelection, true, "lifecycle-clear"),
                    (EntityPlayerMP) player);
        }
        state.resetAcceptedTunnelDirectionSource();
        // 阶段8 块3：删旧 syncPlayerState 调用（八字段同步链已删）。
        // 客户端 config 由 ChainConfigProjectionBridge 订阅 LOGIN/PlanCompleted 下发，无需此处兜底。
    }

    private void retirePlayerState(ChainPlayerState state, String reason) {
        state.retireRuntimeState(reason);
        removeDeliveredDropOwner(state);
    }

    /** 仅服务端主线程调用；资产已交付后才回收退休 owner，不触碰新接管的运行态。 */
    public void removeDeliveredDropOwner(ChainPlayerState state) {
        if (state != null && state.isPendingRemoval() && state.getDropBuffer().isEmpty()) {
            playerStates.remove(state.getPlayerUUID(), state);
        }
    }

    /**
     * 服务端停止时在玩家清理之后调用。旧资产仍在内存可领取，但不带入下个存档自动投放。
     * 不持有 World/Player，也不提供 JVM 退出后的持久化保证。
     */
    public void suspendDropsOnServerStopping() {
        suspendedPlayerDrops.values().forEach(buffers -> buffers.removeIf(ChainPlayerDropBuffer::isEmpty));
        suspendedPlayerDrops.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        for (ChainPlayerState state : playerStates.values()) {
            state.retireRuntimeState("server-stopping");
            if (!state.getDropBuffer().isEmpty()) {
                suspendedPlayerDrops.computeIfAbsent(state.getPlayerUUID(), uuid -> new ArrayList<>())
                        .add(state.getDropBuffer());
            }
            playerStates.remove(state.getPlayerUUID(), state);
        }
    }

    /**
     * 运维命令在服务端主线程显式恢复停止前资产，只释放到指定在线玩家当前位置。
     * 每个 buffer 独立失败保留；已生成部分由既有 helper 从缓冲移出，重复执行不会再次生成。
     * 不使用旧世界坐标或重生点，也不合并到当前运行态。
     *
     * @return 仍未交付的聚合掉落栈数；0 表示该玩家已无停止资产待恢复
     */
    public int releaseSuspendedPlayerDrops(EntityPlayerMP player) {
        if (player == null) throw new IllegalArgumentException("recovery player must not be null");
        UUID playerUUID = player.getUniqueID();
        List<ChainPlayerDropBuffer> buffers = suspendedPlayerDrops.get(playerUUID);
        if (buffers == null) return 0;

        if (MyMod.playerManager != null && MyMod.playerManager.getPlayer(playerUUID) == player
                && player.worldObj != null && !player.worldObj.isRemote) {
            for (ChainPlayerDropBuffer buffer : buffers) {
                if (buffer.isEmpty()) continue;
                try {
                    ChainDropReleaseHelper.releaseAtPlayer(player, buffer, "operator-recover-suspended-drops");
                } catch (RuntimeException e) {
                    MyMod.LOG.warn("[ChainState] Suspended drop recovery failed for player {}, keeping pending assets",
                            playerUUID, e);
                }
            }
        }
        buffers.removeIf(ChainPlayerDropBuffer::isEmpty);
        if (buffers.isEmpty()) {
            suspendedPlayerDrops.remove(playerUUID, buffers);
            return 0;
        }
        int pendingStacks = 0;
        for (ChainPlayerDropBuffer buffer : buffers) pendingStacks += buffer.size();
        return pendingStacks;
    }

    /** 包内资产快照供生命周期核对；游戏内恢复经显式命令入口，不向外暴露可变缓冲。 */
    List<ChainPlayerDropBuffer> getSuspendedPlayerDrops(UUID playerUUID) {
        List<ChainPlayerDropBuffer> buffers = suspendedPlayerDrops.get(playerUUID);
        if (buffers == null) return Collections.emptyList();
        buffers.removeIf(ChainPlayerDropBuffer::isEmpty);
        if (buffers.isEmpty()) {
            suspendedPlayerDrops.remove(playerUUID, buffers);
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(buffers));
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
            ChainPlayerState owner = playerStates.computeIfAbsent(playerUUID, uuid -> {
                ChainPlayerState state = new ChainPlayerState(uuid);
                state.retireRuntimeState("buffer-player-drop");
                return state;
            });
            owner.getDropBuffer().add(stack);
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

        try {
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
            if (ChainDropReleaseHelper.releaseAtRememberedTarget(
                    state.getDropBuffer(), state.getPlayerUUID().toString(), reason)) {
                return;
            }
        } catch (RuntimeException e) {
            MyMod.LOG.warn("[ChainState] Drop release failed for player {} reason={}, keeping pending assets",
                    state.getPlayerUUID(), reason, e);
        }
        MyMod.LOG.warn("[ChainState] Keeping {} pending drop stack(s) for player {} reason={}",
                state.getDropBuffer().size(), state.getPlayerUUID(), reason);
    }

    /** 丢弃无库存写权的 v4 round projection。 */
    private void cleanupAutoToolSwapRound(UUID playerUUID) {
        if (playerUUID != null && MyMod.autoToolSwapRoundService != null) {
            MyMod.autoToolSwapRoundService.cleanup(playerUUID);
        }
    }

    /** 状态服务清理前的幂等 local restore 兜底。 */
    private void finalizeAutoToolSwapLocal(UUID playerUUID, Object endpoint) {
        if (playerUUID == null || MyMod.autoToolSwapServerBatchService == null) return;
        Object resolved = endpoint;
        if (resolved == null && MyMod.playerManager != null) {
            resolved = MyMod.playerManager.getPlayer(playerUUID);
        }
        MyMod.autoToolSwapServerBatchService.finalizePlayer(playerUUID, resolved, null,
                CloseCause.LIFECYCLE_CLEANUP, Math.max(0L, ChainTickSource.currentServerTick()));
    }
}
