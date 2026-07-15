package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/** 客户端只读 phase 投影快照。 */
public final class ToolSwapPhaseSnapshot {

    public final ChainPhase phase;
    public final int generation;

    public ToolSwapPhaseSnapshot(ChainPhase phase, int generation) {
        this.phase = phase == null ? ChainPhase.IDLE : phase;
        this.generation = generation;
    }
}
