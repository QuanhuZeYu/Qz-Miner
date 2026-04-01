package club.heiqi.qz_miner.event;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁结束事件
 * 当连锁操作结束时触发此事件
 */
public class ChainEndEvent extends BaseEvent {
    private final EntityPlayerMP player;
    private final int blockCount;
    private final long duration;
    private final String modeName;
    
    /**
     * 构造函数
     * @param source 事件源
     * @param player 玩家
     * @param blockCount 挖掘方块数量
     * @param duration 连锁用时（毫秒）
     * @param modeName 模式名称
     */
    public ChainEndEvent(Object source, EntityPlayerMP player, int blockCount, long duration, String modeName) {
        super(source);
        this.player = player;
        this.blockCount = blockCount;
        this.duration = duration;
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
     * 获取挖掘方块数量
     * @return 挖掘方块数量
     */
    public int getBlockCount() {
        return blockCount;
    }
    
    /**
     * 获取连锁用时（毫秒）
     * @return 连锁用时
     */
    public long getDuration() {
        return duration;
    }
    
    /**
     * 获取模式名称
     * @return 模式名称
     */
    public String getModeName() {
        return modeName;
    }
    
    /**
     * 获取连锁用时（秒）
     * @return 连锁用时（秒）
     */
    public double getDurationInSeconds() {
        return duration / 1000.0;
    }
    
    @Override
    public String toString() {
        return String.format("ChainEndEvent{player=%s, blockCount=%d, duration=%.2fs, modeName=%s}",
            player.getDisplayName(), blockCount, getDurationInSeconds(), modeName);
    }
}