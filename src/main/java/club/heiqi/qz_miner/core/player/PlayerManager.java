package club.heiqi.qz_miner.core.player;

import club.heiqi.qz_miner.event.EventManager;
import club.heiqi.qz_miner.event.PlayerLoginEvent;
import club.heiqi.qz_miner.event.PlayerLogoutEvent;
import club.heiqi.qz_miner.log.QzLogManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家管理器
 * 管理玩家会话，维护在线玩家列表
 * 监听玩家登录/登出事件，并发布自定义事件
 */
public class PlayerManager {
    private static final String TAG = "PlayerManager";
    
    // 在线玩家映射：UUID -> EntityPlayerMP
    private final Map<UUID, EntityPlayerMP> onlinePlayers = new ConcurrentHashMap<>();
    
    // 是否已注册
    private boolean registered = false;
    
    /**
     * 注册玩家管理器到Forge事件总线
     */
    public void register() {
        if (registered) {
            QzLogManager.warn("玩家管理器已经注册");
            return;
        }
        
        QzLogManager.methodEnter(TAG, "register");
        
        FMLCommonHandler.instance().bus().register(this);
        registered = true;
        
        QzLogManager.info("玩家管理器注册成功");
        QzLogManager.methodExit(TAG, "register");
    }
    
    /**
     * 注销玩家管理器
     */
    public void unregister() {
        if (!registered) {
            return;
        }
        
        QzLogManager.methodEnter(TAG, "unregister");
        
        FMLCommonHandler.instance().bus().unregister(this);
        registered = false;
        
        // 清空玩家列表
        onlinePlayers.clear();
        
        QzLogManager.info("玩家管理器注销成功");
        QzLogManager.methodExit(TAG, "unregister");
    }
    
    /**
     * 处理玩家登录事件
     * @param event Forge玩家登录事件
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID uuid = player.getUniqueID();
        
        QzLogManager.methodEnter(TAG, "onPlayerLogin");
        QzLogManager.info("玩家登录: {} ({})", player.getDisplayName(), uuid);
        
        // 添加到在线玩家列表
        onlinePlayers.put(uuid, player);
        
        // 发布自定义登录事件
        PlayerLoginEvent loginEvent = new PlayerLoginEvent(this, player);
        EventManager.post(loginEvent);
        
        QzLogManager.debug("当前在线玩家数量: {}", onlinePlayers.size());
        QzLogManager.methodExit(TAG, "onPlayerLogin");
    }
    
    /**
     * 处理玩家登出事件
     * @param event Forge玩家登出事件
     */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID uuid = player.getUniqueID();
        
        QzLogManager.methodEnter(TAG, "onPlayerLogout");
        QzLogManager.info("玩家登出: {} ({})", player.getDisplayName(), uuid);
        
        // 从在线玩家列表移除
        onlinePlayers.remove(uuid);
        
        // 发布自定义登出事件
        PlayerLogoutEvent logoutEvent = new PlayerLogoutEvent(this, player);
        EventManager.post(logoutEvent);
        
        QzLogManager.debug("当前在线玩家数量: {}", onlinePlayers.size());
        QzLogManager.methodExit(TAG, "onPlayerLogout");
    }
    
    /**
     * 获取在线玩家
     * @param uuid 玩家UUID
     * @return 玩家实体，如果不存在返回null
     */
    public EntityPlayerMP getPlayer(UUID uuid) {
        return onlinePlayers.get(uuid);
    }
    
    /**
     * 检查玩家是否在线
     * @param uuid 玩家UUID
     * @return 是否在线
     */
    public boolean isPlayerOnline(UUID uuid) {
        return onlinePlayers.containsKey(uuid);
    }
    
    /**
     * 获取所有在线玩家
     * @return 在线玩家映射（不可修改）
     */
    public Map<UUID, EntityPlayerMP> getOnlinePlayers() {
        return onlinePlayers;
    }
    
    /**
     * 获取在线玩家数量
     * @return 在线玩家数量
     */
    public int getOnlinePlayerCount() {
        return onlinePlayers.size();
    }
    
    /**
     * 根据玩家名称查找玩家
     * @param playerName 玩家名称
     * @return 玩家实体，如果不存在返回null
     */
    public EntityPlayerMP findPlayerByName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        
        for (EntityPlayerMP player : onlinePlayers.values()) {
            if (player.getDisplayName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        
        return null;
    }
    
    /**
     * 检查玩家管理器是否已注册
     * @return 是否已注册
     */
    public boolean isRegistered() {
        return registered;
    }
    
    /**
     * 清空所有玩家（用于测试或重置）
     */
    public void clearAllPlayers() {
        QzLogManager.methodEnter(TAG, "clearAllPlayers");
        
        onlinePlayers.clear();
        
        QzLogManager.info("已清空所有在线玩家");
        QzLogManager.methodExit(TAG, "clearAllPlayers");
    }
    
    @Override
    public String toString() {
        return String.format("PlayerManager{registered=%b, onlinePlayers=%d}",
            registered, onlinePlayers.size());
    }
}