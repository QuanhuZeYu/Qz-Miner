package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * 连锁状态机进态广播事件（阶段6 G1 根治）。
 *
 * <p>由 {@code ChainStateMachine.applyTransition} 在状态转移完成后 publish，供投影订阅者
 * （服务端 {@code ChainStateProjectionBridge} 下发快照、客户端 {@code ClientPhaseProjectionSubscriber}
 * 更新投影容器）接收"状态机已转移"信号。携带 {@code from} 与 {@code to} 双态（P2-1=A 决议：
 * 诊断友好，事件流即结构化日志）。</p>
 *
 * <p>与 {@link PlanStarted} 区分：PlanStarted 是 T4 ARMED→PLANNING 时由调用方在 applyTransition
 * 之外单独 publish 的"规划上下文开始"事件（携带 origin/dimension/sideHit/hitOffset），供
 * ChainPlanningEventBridge 发起影子 traverser；本事件是通用进态广播，所有转移路径都 publish，
 * 供投影订阅者精确订阅。两者订阅集互不重叠（PlanStarted 订阅者是 ChainPlanningEventBridge，
 * 本事件订阅者是 ChainStateProjectionBridge/ClientPhaseProjectionSubscriber），并行不冲突。</p>
 *
 * <p>序列化优化：内部以 {@code int ordinal} 存储 from/to（{@link ChainPhase} 枚举已是 5 态稳定），
 * 省网络字节；getter 返回 {@link ChainPhase} 枚举便于消费方使用。</p>
 *
 * <p>守 NORTH_STAR 不变量 I1/I10：</p>
 * <ul>
 *   <li><b>I1</b>：不可变事件，所有字段 {@code final}，构造后不可修改，
 *       可安全跨线程传递（主线程 drain publish → Netty 线程读 → 客户端主线程 drain）。</li>
 *   <li><b>I10</b>：本事件是状态机转移完成后的<b>广播</b>，不是外部改态入口；外部订阅者只读不可切态。</li>
 * </ul>
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
        super(playerUUID, generation, serverTick, timestampNanos);
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
