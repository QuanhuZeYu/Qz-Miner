package club.heiqi.qz_miner.client.toolswap.protocol;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 客户端工具换位协议核心的不可变观测快照。 */
public final class AutoToolSwapClientProtocolSnapshot {

    private final AutoToolSwapClientProtocolPhase phase;
    private final long pendingNonce;
    private final long serverRoundId;
    private final long nextActionSequence;
    private final AutoToolSwapIntent inFlight;
    private final long lastPhaseSequence;
    private final AutoToolSwapRoundState serverRoundState;

    AutoToolSwapClientProtocolSnapshot(AutoToolSwapClientProtocolPhase phase, long pendingNonce,
            long serverRoundId, long nextActionSequence, AutoToolSwapIntent inFlight,
            long lastPhaseSequence, AutoToolSwapRoundState serverRoundState) {
        this.phase = phase;
        this.pendingNonce = pendingNonce;
        this.serverRoundId = serverRoundId;
        this.nextActionSequence = nextActionSequence;
        this.inFlight = inFlight;
        this.lastPhaseSequence = lastPhaseSequence;
        this.serverRoundState = serverRoundState;
    }

    /** @return 本地协议生命周期阶段。 */
    public AutoToolSwapClientProtocolPhase phase() {
        return phase;
    }

    /** @return 当前 round 的 client nonce；无活跃 round 时为 0。 */
    public long pendingNonce() {
        return pendingNonce;
    }

    /** @return 已确认的服务端 round id；未确认时为 0。 */
    public long serverRoundId() {
        return serverRoundId;
    }

    /** @return 服务端声明的下一动作序号。 */
    public long nextActionSequence() {
        return nextActionSequence;
    }

    /** @return 唯一尚未结算的不可变请求；没有时为 null。 */
    public AutoToolSwapIntent inFlight() {
        return inFlight;
    }

    /** @return 最后接受的专用 phase 序号。 */
    public long lastPhaseSequence() {
        return lastPhaseSequence;
    }

    /** @return 服务端最近确认的 round 状态；尚未确认时为 null。 */
    public AutoToolSwapRoundState serverRoundState() {
        return serverRoundState;
    }
}
