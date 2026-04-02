package club.heiqi.qz_miner.core;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.event.PlayerDisconnectEvent;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.PlayerStateEvent.Reason;
import club.heiqi.qz_miner.event.QzEvents;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent.Clone;

/**
 * 玩家管理器。
 *
 * 监听所有可能的玩家事件，维护最新的玩家状态映射表。
 * 通过 {@link PlayerStateEvent} 向模组内部和外部模组广播玩家状态变更。
 * 玩家断开连接使用 Mixin 注入的 {@link PlayerDisconnectEvent}，比 Forge 原生事件更准确。
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
     * 创建并注册玩家管理器。
     *
     * 自动向 Forge 和 FML 事件总线注册自身。
     */
    public PlayerManager() {
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        QzEvents.register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
    }

    /**
     * 清空所有玩家（由客户端 Mixin 调用，用于单人模式退出）。
     */
    public static void clearAllPlayers() {
        if (instance == null || instance.players.isEmpty()) {
            return;
        }

        MyMod.LOG.info("[PlayerManager] Exiting to main menu, clearing {} player(s)", instance.players.size());
        for (Map.Entry<UUID, EntityPlayer> entry : instance.players.entrySet()) {
            EntityPlayer player = entry.getValue();
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

    // ========== FML 事件监听 ==========

    /**
     * 玩家加入游戏。
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayer player = event.player;
        players.put(player.getUniqueID(), player);
        MyMod.LOG.info("[PlayerManager] Player logged in: {} (UUID: {}), online players: {}",
                player.getCommandSenderName(), player.getUniqueID(), players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.LOGIN));
    }

    /**
     * 玩家断开连接（通过 Mixin 注入，比 Forge 原生事件更准确）。
     */
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        EntityPlayer player = event.player;
        UUID uuid = player.getUniqueID();
        players.remove(uuid);
        MyMod.LOG.info("[PlayerManager] Player disconnected: {} (UUID: {}), reason: {}, online players: {}",
                player.getCommandSenderName(), uuid, event.reason.getUnformattedText(), players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.LOGOUT));
    }

    /**
     * 玩家重生。
     */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        EntityPlayer player = event.player;
        players.put(player.getUniqueID(), player);
        MyMod.LOG.debug("[PlayerManager] Player respawned: {} (UUID: {}), online players: {}",
                player.getCommandSenderName(), player.getUniqueID(), players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.RESPAWN));
    }

    /**
     * 玩家切换维度。
     */
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        EntityPlayer player = event.player;
        players.put(player.getUniqueID(), player);
        MyMod.LOG.debug("[PlayerManager] Player changed dimension: {} (UUID: {}), online players: {}",
                player.getCommandSenderName(), player.getUniqueID(), players.size());
        QzEvents.post(new PlayerStateEvent(player, Reason.DIMENSION_CHANGE));
    }

    // ========== Forge 事件监听 ==========

    /**
     * 玩家克隆（死亡重生或维度切换时的数据复制）。
     *
     * 此时新玩家实例已经创建但还未完全替换旧实例，
     * 需要更新映射表中的引用。
     */
    @SubscribeEvent
    public void onPlayerClone(Clone event) {
        EntityPlayer newPlayer = event.entityPlayer;
        UUID uuid = newPlayer.getUniqueID();
        players.put(uuid, newPlayer);
        MyMod.LOG.debug("[PlayerManager] Player cloned: {} (UUID: {}), wasDeath: {}, online players: {}",
                newPlayer.getCommandSenderName(), uuid, event.wasDeath, players.size());
        QzEvents.post(new PlayerStateEvent(newPlayer, Reason.CLONE));
    }
}