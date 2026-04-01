package club.heiqi.qz_miner.event;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 玩家登出事件
 * 当玩家登出服务器时触发此事件
 */
public class PlayerLogoutEvent extends BaseEvent {
    private final EntityPlayerMP player;
    
    /**
     * 构造函数
     * @param source 事件源
     * @param player 登出的玩家
     */
    public PlayerLogoutEvent(Object source, EntityPlayerMP player) {
        super(source);
        this.player = player;
    }
    
    /**
     * 获取登出的玩家
     * @return 玩家
     */
    public EntityPlayerMP getPlayer() {
        return player;
    }
    
    @Override
    public String toString() {
        return String.format("PlayerLogoutEvent{player=%s, uuid=%s}",
            player.getDisplayName(), player.getUniqueID());
    }
}