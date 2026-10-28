package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * 状态机转移完成后的不可变 from/to 广播，供阶段投影与生命周期订阅者读取。
 * 所有转移均广播阶段；规划入口另发 PlanStarted 携带种子和空间上下文。
 */
public final class ChainPhaseChanged extends ChainEvent {

    /** 源态 ordinal（{@link ChainPhase#ordinal()}）。 */
    private final int fromPhaseOrdinal;
    /** 目标态 ordinal（{@link ChainPhase#ordinal()}）。 */
    private final int toPhaseOrdinal;

    /**
     * @param playerUUID     触发玩家 UUID
     * @param generation     所属连锁代际（转移后的新代际）
     * @param fromPhase      源态
     * @param toPhase        目标态
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     */
    public ChainPhaseChanged(UUID playerUUID, int generation,
                              ChainPhase fromPhase, ChainPhase toPhase,
                              long serverTick, long timestampNanos) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, fromPhase, toPhase, serverTick, timestampNanos);
    }

    /** 构造带服务端轮次关联的状态机进态广播。 */
    public ChainPhaseChanged(UUID playerUUID, long serverRoundId, int generation,
                             ChainPhase fromPhase, ChainPhase toPhase,
                             long serverTick, long timestampNanos) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.fromPhaseOrdinal = fromPhase.ordinal();
        this.toPhaseOrdinal = toPhase.ordinal();
    }

    /** @return 源态 ordinal（供网络包序列化） */
    public int getFromPhaseOrdinal() {
        return fromPhaseOrdinal;
    }

    /** @return 目标态 ordinal（供网络包序列化） */
    public int getToPhaseOrdinal() {
        return toPhaseOrdinal;
    }

    /** @return 源态枚举（便于消费方使用） */
    public ChainPhase getFromPhase() {
        return ChainPhase.values()[fromPhaseOrdinal];
    }

    /** @return 目标态枚举（便于消费方使用） */
    public ChainPhase getToPhase() {
        return ChainPhase.values()[toPhaseOrdinal];
    }
}
