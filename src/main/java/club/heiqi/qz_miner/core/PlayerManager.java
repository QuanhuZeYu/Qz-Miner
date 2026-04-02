package club.heiqi.qz_miner.core;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public final class PlayerManager {

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
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
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
        QzEvents.post(new PlayerStateEvent(player, Reason.LOGIN));
    }

    /**
     * 玩家离开游戏。
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayer player = event.player;
        players.remove(player.getUniqueID());
        QzEvents.post(new PlayerStateEvent(player, Reason.LOGOUT));
    }

    /**
     * 玩家重生。
     */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        EntityPlayer player = event.player;
        players.put(player.getUniqueID(), player);
        QzEvents.post(new PlayerStateEvent(player, Reason.RESPAWN));
    }

    /**
     * 玩家切换维度。
     */
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        EntityPlayer player = event.player;
        players.put(player.getUniqueID(), player);
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
        QzEvents.post(new PlayerStateEvent(newPlayer, Reason.CLONE));
    }
}