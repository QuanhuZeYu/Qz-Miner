package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/** 当前 context 仅因共享 deadline 尚未获得目标事务窗口。 */
public final class ExecutionDeferred extends ChainEvent {

    public ExecutionDeferred(UUID playerUUID, long serverRoundId, int generation,
            long serverTick, long timestampNanos) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
    }
}
