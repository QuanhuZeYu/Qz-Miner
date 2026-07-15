package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁按键事件：玩家按下连锁触发键。
 */
public final class ChainKeyPressed extends ChainEvent {

    /** 是否为按下（true）而非松开（false）。 */
    private final boolean pressed;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param pressed        是否按下
     */
    public ChainKeyPressed(UUID playerUUID, int generation, long serverTick, long timestampNanos, boolean pressed) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos, pressed);
    }

    /** 构造带服务端轮次关联的按键事件。 */
    public ChainKeyPressed(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                           boolean pressed) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.pressed = pressed;
    }

    /**
     * @return 是否按下
     */
    public boolean isPressed() {
        return pressed;
    }
}
