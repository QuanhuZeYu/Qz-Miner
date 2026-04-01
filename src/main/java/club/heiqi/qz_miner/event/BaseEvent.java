package club.heiqi.qz_miner.event;

/**
 * 基础事件类
 * 所有事件的父类，提供事件的基本属性和方法
 */
public abstract class BaseEvent {
    // 事件是否被取消
    private boolean cancelled = false;
    
    // 事件发生时间
    private final long timestamp;
    
    // 事件源对象
    private final Object source;
    
    /**
     * 构造函数
     * @param source 事件源对象
     */
    public BaseEvent(Object source) {
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * 获取事件源对象
     * @return 事件源对象
     */
    public Object getSource() {
        return source;
    }
    
    /**
     * 获取事件发生时间
     * @return 事件发生时间（毫秒）
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 检查事件是否被取消
     * @return 事件是否被取消
     */
    public boolean isCancelled() {
        return cancelled;
    }
    
    /**
     * 设置事件是否被取消
     * @param cancelled 是否取消事件
     */
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
    
    /**
     * 取消事件
     */
    public void cancel() {
        this.cancelled = true;
    }
    
    /**
     * 获取事件名称
     * @return 事件名称
     */
    public String getEventName() {
        return this.getClass().getSimpleName();
    }
    
    @Override
    public String toString() {
        return String.format("%s{source=%s, timestamp=%d, cancelled=%s}",
            getEventName(), source, timestamp, cancelled);
    }
}