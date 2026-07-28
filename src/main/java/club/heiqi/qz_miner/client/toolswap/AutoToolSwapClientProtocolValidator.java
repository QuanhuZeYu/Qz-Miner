package club.heiqi.qz_miner.client.toolswap;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/**
 * 自动工具客户端协议的无状态信任边界。
 *
 * <p>本类只校验 raw 标记、wire enum、范围和单包内部结构；nonce、round、action 与 phaseSequence
 * 的历史关联全部由 {@link AutoToolSwapClientReducer} 判断。</p>
 */
public final class AutoToolSwapClientProtocolValidator {

    /** 校验并解码 round 回执的单包字段。 */
    public ValidatedRoundResult validateRoundResult(int protocolVersion, long serverRoundId,
            int resultCode, int roundState, long nextActionSequence, long serverTick, boolean rawValid) {
        if (!rawValid || protocolVersion != AutoToolSwapProtocol.PROTOCOL_VERSION
                || serverRoundId < AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || nextActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE || serverTick < 0L) {
            return null;
        }
        try {
            AutoToolSwapRoundResult result = new AutoToolSwapRoundResult(serverRoundId,
                    AutoToolSwapResultCode.fromWireCode(resultCode),
                    AutoToolSwapRoundState.fromWireCode(roundState), nextActionSequence, serverTick);
            return new ValidatedRoundResult(result);
        } catch (IllegalArgumentException invalidWireEnum) {
            return null;
        }
    }

    /** 校验并解码动作回执的单包字段。 */
    public ValidatedActionResult validateActionResult(int protocolVersion, long serverRoundId,
            long actionSequence, int actionCode, int resultCode, int roundState, int anchorSlot, int candidateSlot,
            long nextActionSequence, long serverTick, boolean rawValid) {
        ValidatedRoundResult round = validateRoundResult(protocolVersion, serverRoundId, resultCode,
                roundState, nextActionSequence, serverTick, rawValid);
        if (round == null || serverRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || actionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE
                || round.result().roundState() == AutoToolSwapRoundState.PENDING_KEY
                || !AutoToolSwapProtocol.isInventorySlot(anchorSlot)
                || !AutoToolSwapProtocol.isInventorySlot(candidateSlot)) {
            return null;
        }
        try {
            return new ValidatedActionResult(AutoToolSwapAction.fromWireCode(actionCode), round.result());
        } catch (IllegalArgumentException invalidWireEnum) {
            return null;
        }
    }

    /** 校验并解码专用 phase 投影的单包字段。 */
    public ValidatedPhase validatePhase(int protocolVersion, long serverRoundId, long phaseSequence,
            int phaseOrdinal, int generation, long serverTick, boolean rawValid) {
        ChainPhase[] phases = ChainPhase.values();
        if (!rawValid || protocolVersion != AutoToolSwapProtocol.PROTOCOL_VERSION
                || serverRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID || phaseSequence <= 0L
                || phaseOrdinal < 0 || phaseOrdinal >= phases.length || generation < 0 || serverTick < 0L) {
            return null;
        }
        return new ValidatedPhase(serverRoundId, phaseSequence, phases[phaseOrdinal], generation, serverTick);
    }

    /** 已通过单包结构校验的 round 结果。 */
    public static final class ValidatedRoundResult {
        private final AutoToolSwapRoundResult result;

        private ValidatedRoundResult(AutoToolSwapRoundResult result) {
            this.result = result;
        }

        public AutoToolSwapRoundResult result() {
            return result;
        }
    }

    /** 已通过单包结构校验的动作结果。 */
    public static final class ValidatedActionResult {
        private final AutoToolSwapAction action;
        private final AutoToolSwapRoundResult result;

        private ValidatedActionResult(AutoToolSwapAction action, AutoToolSwapRoundResult result) {
            this.action = action;
            this.result = result;
        }

        public AutoToolSwapAction action() {
            return action;
        }

        public AutoToolSwapRoundResult result() {
            return result;
        }
    }

    /** 已通过单包结构校验的专用 phase。 */
    public static final class ValidatedPhase {
        private final long serverRoundId;
        private final long phaseSequence;
        private final ChainPhase phase;
        private final int generation;
        private final long serverTick;

        private ValidatedPhase(long serverRoundId, long phaseSequence, ChainPhase phase,
                int generation, long serverTick) {
            this.serverRoundId = serverRoundId;
            this.phaseSequence = phaseSequence;
            this.phase = phase;
            this.generation = generation;
            this.serverTick = serverTick;
        }

        public long serverRoundId() { return serverRoundId; }
        public long phaseSequence() { return phaseSequence; }
        public ChainPhase phase() { return phase; }
        public int generation() { return generation; }
        public long serverTick() { return serverTick; }
    }
}
