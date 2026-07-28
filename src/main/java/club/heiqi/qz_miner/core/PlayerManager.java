package club.heiqi.qz_miner.core;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.PlayerStateEvent.Reason;
import club.heiqi.qz_miner.event.QzEvents;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CloseCause;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;

/**
 * 玩家管理器。
 *
 * 由 vanilla 玩家生命周期 Mixin 维护最新的玩家 endpoint 映射表。
 * 通过 {@link PlayerStateEvent} 向模组内部和外部模组广播玩家状态变更。
 */
public final class PlayerManager {

    /**
     * 全局唯一实例，供 Mixin 调用。
     */
    private static PlayerManager instance;

    /**
     * 所有当前在线玩家的映射表，以 UUID 为键。
     */
    private final Map<UUID, EntityPlayer> players = new ConcurrentHashMap<>();

    /**
     * 创建玩家管理器，供 vanilla 生命周期 Mixin 调用。
     */
    public PlayerManager() {
        instance = this;
    }

    /**
     * 仅服务端停止事件主线程调用的同步清理入口。
     *
     * <p>先完成玩家生命周期清理，再由调用方关闭 dispatcher，避免 stop 后 FIFO 拒绝导致清理丢失。</p>
     */
    public static void clearAllPlayersOnServerStopping() {
        clearAllPlayersOnServerThread();
    }

    /**
     * 在服务端主线程清空当前追踪的玩家。
     */
    private static void clearAllPlayersOnServerThread() {
        if (instance == null || instance.players.isEmpty()) {
            return;
        }

        MyMod.LOG.info("[PlayerManager] Exiting to main menu, clearing {} player(s)", instance.players.size());
        for (Map.Entry<UUID, EntityPlayer> entry : instance.players.entrySet()) {
            EntityPlayer player = entry.getValue();
            finalizeAutoToolSwap(entry.getKey(), player, null, CloseCause.SERVER_STOP);
            QzEvents.post(new PlayerStateEvent(player, Reason.LOGOUT));
        }
        instance.players.clear();
    }

    /**
     * 获取所有当前已知的在线玩家。
     *
     * @return 只读的玩家映射表
     */
    public Map<UUID, EntityPlayer> getPlayers() {
        return Collections.unmodifiableMap(players);
    }

    /**
     * 根据 UUID 获取玩家。
     *
     * @param uuid 玩家 UUID
     * @return 玩家实例，如果不存在则返回 null
     */
    public EntityPlayer getPlayer(UUID uuid) {
        return players.get(uuid);
    }

    /**
     * 判断指定玩家是否被管理器追踪。
     *
     * @param uuid 玩家 UUID
     * @return 是否存在
     */
    public boolean hasPlayer(UUID uuid) {
        return players.containsKey(uuid);
    }

    /**
     * 获取当前追踪的玩家数量。
     *
     * @return 玩家数量
     */
    public int size() {
        return players.size();
    }

    /** vanilla 登录流程完全提交后登记 endpoint。重复提交不重复发布 LOGIN。 */
    public static void onVanillaLoginCommitted(EntityPlayerMP player) {
        if (instance == null || player == null) {
            return;
        }
        UUID uuid = player.getUniqueID();
        EntityPlayer previous = instance.players.get(uuid);
        if (previous == player) {
            return;
        }
        if (previous != null) {
            finalizeAutoToolSwap(uuid, previous, player, CloseCause.LOGOUT);
            QzEvents.post(new PlayerStateEvent(previous, Reason.LOGOUT));
        }
        instance.players.put(uuid, player);
        MyMod.LOG.info("[PlayerManager] Player logged in: {} (UUID: {}), online players: {}",
                player.getCommandSenderName(), uuid, instance.players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.LOGIN));
    }

    /** respawn 移除旧 endpoint 前完成 local physical restore。 */
    public static void beforeVanillaRespawn(EntityPlayerMP player) {
        finalizeTrackedEndpoint(player, null, CloseCause.RESPAWN);
    }

    /** vanilla respawn 返回新实例后原子替换 endpoint。 */
    public static void onVanillaRespawnCommitted(EntityPlayerMP previous, EntityPlayerMP player) {
        if (instance == null || previous == null || player == null) {
            return;
        }
        UUID uuid = player.getUniqueID();
        boolean replaced = instance.players.replace(uuid, previous, player);
        if (!replaced) {
            replaced = instance.players.putIfAbsent(uuid, player) == null;
        }
        if (!replaced) {
            return;
        }
        MyMod.LOG.debug("[PlayerManager] Player respawned: {} (UUID: {}), online players: {}",
                player.getCommandSenderName(), uuid, instance.players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.RESPAWN));
    }

    /** 跨维度修改 world/dimension 前完成 local physical restore。 */
    public static void beforeVanillaDimensionChange(EntityPlayerMP player) {
        finalizeTrackedEndpoint(player, null, CloseCause.DIMENSION_CHANGE);
    }

    /** vanilla 跨维度流程完全提交后发布一次生命周期变更。 */
    public static void onVanillaDimensionChangeCommitted(EntityPlayerMP player) {
        if (!isCurrentEndpoint(player)) {
            return;
        }
        MyMod.LOG.debug("[PlayerManager] Player changed dimension: {} (UUID: {}), online players: {}",
                player.getCommandSenderName(), player.getUniqueID(), instance.players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.DIMENSION_CHANGE));
    }

    /** vanilla 断开开始时按 endpoint identity 摘除；旧连接不得误删新连接。 */
    public static void onVanillaDisconnect(EntityPlayerMP player, IChatComponent reason) {
        if (instance == null || player == null) {
            return;
        }
        UUID uuid = player.getUniqueID();
        if (instance.players.get(uuid) != player) {
            return;
        }
        finalizeAutoToolSwap(uuid, player, null, CloseCause.LOGOUT);
        if (!instance.players.remove(uuid, player)) {
            return;
        }
        String reasonText = reason == null ? "unknown" : reason.getUnformattedText();
        MyMod.LOG.info("[PlayerManager] Player disconnected: {} (UUID: {}), reason: {}, online players: {}",
                player.getCommandSenderName(), uuid, reasonText, instance.players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.LOGOUT));
    }

    private static boolean isCurrentEndpoint(EntityPlayer player) {
        return instance != null && player != null
                && instance.players.get(player.getUniqueID()) == player;
    }

    private static void finalizeTrackedEndpoint(EntityPlayer player, Object alternateEndpoint, CloseCause cause) {
        if (!isCurrentEndpoint(player)) {
            return;
        }
        finalizeAutoToolSwap(player.getUniqueID(), player, alternateEndpoint, cause);
    }

    /** endpoint replace/remove 前的统一 local physical restore 屏障。 */
    private static void finalizeAutoToolSwap(UUID playerId, Object preferredEndpoint,
            Object alternateEndpoint, CloseCause cause) {
        if (MyMod.autoToolSwapServerBatchService == null) return;
        MyMod.autoToolSwapServerBatchService.finalizePlayer(playerId, preferredEndpoint,
                alternateEndpoint, cause, Math.max(0L, ChainTickSource.currentServerTick()));
    }
}
