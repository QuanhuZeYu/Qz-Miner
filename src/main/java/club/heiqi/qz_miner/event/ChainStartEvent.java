package club.heiqi.qz_miner.event;

import net.minecraft.entity.player.EntityPlayerMP;
import org.joml.Vector3i;

/**
 * 连锁开始事件
 * 当连锁操作开始时触发此事件
 */
public class ChainStartEvent extends BaseEvent {
    private final EntityPlayerMP player;
    private final Vector3i startPos;
    private final String modeName;
    
    /**
     * 构造函数
     * @param source 事件源
     * @param player 玩家
     * @param startPos 起始位置
     * @param modeName 模式名称
     */
    public ChainStartEvent(Object source, EntityPlayerMP player, Vector3i startPos, String modeName) {
        super(source);
        this.player = player;
        this.startPos = startPos;
        this.modeName = modeName;
    }
    
    /**
     * 获取玩家
     * @return 玩家
     */
    public EntityPlayerMP getPlayer() {
        return player;
    }
    
    /**
     * 获取起始位置
     * @return 起始位置
     */
    public Vector3i getStartPos() {
        return startPos;
    }
    
    /**
     * 获取模式名称
     * @return 模式名称
     */
    public String getModeName() {
        return modeName;
    }
    
    @Override
    public String toString() {
        return String.format("ChainStartEvent{player=%s, startPos=%s, modeName=%s}",
            player.getDisplayName(), startPos, modeName);
    }
}