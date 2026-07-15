package club.heiqi.qz_miner.client.toolswap.protocol;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/** 服务端专用 round phase 的不可变客户端投影。 */
public final class AutoToolSwapClientProtocolPhaseSnapshot {

    private final long serverRoundId;
    private final long phaseSequence;
    private final ChainPhase phase;
    private final int generation;
    private final long serverTick;

    AutoToolSwapClientProtocolPhaseSnapshot(long serverRoundId, long phaseSequence,
            ChainPhase phase, int generation, long serverTick) {
        this.serverRoundId = serverRoundId;
        this.phaseSequence = phaseSequence;
        this.phase = phase;
        this.generation = generation;
        this.serverTick = serverTick;
    }

    /** @return 归属的服务端 round id。 */
    public long serverRoundId() {
        return serverRoundId;
    }

    /** @return 服务端单调递增的专用 phase 序号。 */
    public long phaseSequence() {
        return phaseSequence;
    }

    /** @return 当前连锁阶段。 */
    public ChainPhase phase() {
        return phase;
    }

    /** @return 服务端状态机 generation。 */
    public int generation() {
        return generation;
    }

    /** @return 服务端 tick。 */
    public long serverTick() {
        return serverTick;
    }
}
