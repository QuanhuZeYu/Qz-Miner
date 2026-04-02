package club.heiqi.qz_miner.event;

/**
 * 事件优先级。
 *
 * 数值越小优先级越高，越先被调用。
 */
public enum EventPriority {
    /**
     * 最低优先级，最后执行。
     */
    LOWEST(5),
    /**
     * 低优先级。
     */
    LOW(4),
    /**
     * 默认优先级。
     */
    NORMAL(3),
    /**
     * 高优先级。
     */
    HIGH(2),
    /**
     * 最高优先级，最先执行。
     */
    HIGHEST(1);

    private final int value;

    EventPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}