package club.heiqi.qz_miner.client.toolswap.protocol;

import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/**
 * 未接线的纯客户端自动工具换位协议状态核心。
 *
 * <p>运行态 adapter 负责线程收口、连接 token 和实际收发包；本类只消费已经捕获的原始字段，
 * 以 nonce、round、action 序号和专用 phase 序号完成严格归因。</p>
 */
public final class AutoToolSwapClientProtocolState {

    private static final AutoToolSwapClientNonceAllocator PROCESS_NONCE_ALLOCATOR = new ProcessNonceAllocator();

    private final AutoToolSwapClientNonceAllocator nonceAllocator;
    private AutoToolSwapClientProtocolPhase phase = AutoToolSwapClientProtocolPhase.IDLE;
    private long pendingNonce;
    private long serverRoundId;
    private long nextActionSequence = AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE;
    private AutoToolSwapIntent inFlight;
    private long lastPhaseSequence;
    private AutoToolSwapRoundState serverRoundState;
    private AutoToolSwapRoundResult acceptedRoundResult;
    private AutoToolSwapClientProtocolSettlement lastSettlement;

    /** 使用进程级线程安全且永不回绕的 nonce 分配器创建核心。 */
    public AutoToolSwapClientProtocolState() {
        this(PROCESS_NONCE_ALLOCATOR);
    }

    /** 包内构造入口仅用于确定性 nonce 边界测试。 */
    AutoToolSwapClientProtocolState(AutoToolSwapClientNonceAllocator nonceAllocator) {
        if (nonceAllocator == null) {
            throw new IllegalArgumentException("nonceAllocator must not be null");
        }
        this.nonceAllocator = nonceAllocator;
    }

    /** @return 当前不可变协议观测快照。 */
    public AutoToolSwapClientProtocolSnapshot snapshot() {
        return new AutoToolSwapClientProtocolSnapshot(phase, pendingNonce, serverRoundId, nextActionSequence,
                inFlight, lastPhaseSequence, serverRoundState);
    }

    /**
     * 在空闲态创建等待服务端确认的 round。
     *
     * @return 新 nonce；不可开始或分配器永久耗尽时返回 0。
     */
    public long beginRound() {
        if (phase != AutoToolSwapClientProtocolPhase.IDLE) {
            return 0L;
        }
        long nonce = nonceAllocator.allocate();
        if (nonce <= 0L) {
            return 0L;
        }
        pendingNonce = nonce;
        phase = AutoToolSwapClientProtocolPhase.WAIT_ROUND;
        return nonce;
    }

    /**
     * 接受 round 激活的原始回执，并拒绝所有非当前 nonce 或畸形字段。
     *
     * @return 接受或幂等重放后的快照；字段不合法或不能归因时返回 null。
     */
    public AutoToolSwapClientProtocolSnapshot onRoundResult(int protocolVersion, long clientNonce,
            long responseServerRoundId, int resultCode, int responseRoundState,
            long responseNextActionSequence, long serverTick, boolean rawValid) {
        AutoToolSwapRoundResult result = decodeRoundResult(protocolVersion, responseServerRoundId, resultCode,
                responseRoundState, responseNextActionSequence, serverTick, rawValid);
        if (result == null || clientNonce <= 0L || clientNonce != pendingNonce) {
            return null;
        }
        if (acceptedRoundResult != null) {
            return acceptedRoundResult.equals(result) ? snapshot() : null;
        }
        if (phase != AutoToolSwapClientProtocolPhase.WAIT_ROUND) {
            return null;
        }
        if (result.outcome() == AutoToolSwapResultCode.REJECTED) {
            reset();
            return snapshot();
        }
        if (result.outcome() == AutoToolSwapResultCode.SYNC_FAILED
                || result.roundState() == AutoToolSwapRoundState.ORPHANED) {
            applyRoundResult(result, AutoToolSwapClientProtocolPhase.ORPHANED);
            return snapshot();
        }
        if (result.roundState() == AutoToolSwapRoundState.FINISHED) {
            applyRoundResult(result, AutoToolSwapClientProtocolPhase.FINISHED);
            return snapshot();
        }
        if (result.outcome() != AutoToolSwapResultCode.ACCEPTED) {
            return null;
        }
        applyRoundResult(result, AutoToolSwapClientProtocolPhase.OPEN);
        return snapshot();
    }

    /**
     * 创建当前唯一的不可变动作请求，不在发送时推进动作序号。
     *
     * @return 新请求；当前状态不允许、已有未结算请求或字段非法时返回 null。
     */
    public AutoToolSwapIntent beginAction(AutoToolSwapAction action, int anchorSlot, int candidateSlot,
            AutoToolSwapContentFingerprint anchorContentFingerprint,
            AutoToolSwapContentFingerprint candidateContentFingerprint) {
        if (!allowsAction(action) || inFlight != null || nextActionSequence == Long.MAX_VALUE
                || !AutoToolSwapProtocol.isInventorySlot(anchorSlot)
                || !AutoToolSwapProtocol.isInventorySlot(candidateSlot)
                || anchorContentFingerprint == null || candidateContentFingerprint == null) {
            if (nextActionSequence == Long.MAX_VALUE && canSendActions()) {
                orphan();
            }
            return null;
        }
        inFlight = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, serverRoundId,
                nextActionSequence, action, anchorSlot, candidateSlot, anchorContentFingerprint,
                candidateContentFingerprint);
        return inFlight;
    }

    /** @return 可用于同一 immutable packet 重发的唯一未结算请求；没有时为 null。 */
    public AutoToolSwapIntent inFlightIntent() {
        return inFlight;
    }

    /**
     * 精确匹配唯一 in-flight 请求后结算动作结果。
     *
     * @return 新结算或完全相同回包对应的既有结算；不匹配时返回 null。
     */
    public AutoToolSwapClientProtocolSettlement onActionResult(int protocolVersion, long responseServerRoundId,
            long actionSequence, int actionCode, int resultCode, int responseRoundState,
            int anchorSlot, int candidateSlot, long responseNextActionSequence,
            long serverTick, boolean rawValid) {
        AutoToolSwapAction action = decodeAction(actionCode);
        AutoToolSwapRoundResult result = decodeRoundResult(protocolVersion, responseServerRoundId, resultCode,
                responseRoundState, responseNextActionSequence, serverTick, rawValid);
        if (action == null || result == null) {
            return null;
        }
        if (inFlight == null && isLastSettlement(actionSequence, action, anchorSlot, candidateSlot, result)) {
            return lastSettlement;
        }
        if (inFlight == null || !matchesInFlight(responseServerRoundId, actionSequence, action,
                anchorSlot, candidateSlot) || responseNextActionSequence != actionSequence + 1L) {
            return null;
        }
        AutoToolSwapIntent settledIntent = inFlight;
        inFlight = null;
        nextActionSequence = responseNextActionSequence;
        serverRoundState = result.roundState();
        lastSettlement = new AutoToolSwapClientProtocolSettlement(settledIntent, result);
        if (result.outcome() == AutoToolSwapResultCode.SYNC_FAILED
                || result.roundState() == AutoToolSwapRoundState.ORPHANED) {
            phase = AutoToolSwapClientProtocolPhase.ORPHANED;
        } else if (result.roundState() == AutoToolSwapRoundState.FINISHED) {
            phase = AutoToolSwapClientProtocolPhase.FINISHED;
        } else if (phase == AutoToolSwapClientProtocolPhase.CLOSING
                || result.roundState() == AutoToolSwapRoundState.CLOSING) {
            phase = AutoToolSwapClientProtocolPhase.CLOSING;
        } else {
            phase = AutoToolSwapClientProtocolPhase.OPEN;
        }
        return lastSettlement;
    }

    /**
     * 接受当前 round 的专用 phase 投影；不依赖通用 generation baseline 猜测归因。
     *
     * @return 新 phase 快照；旧 round、倒序或畸形字段时返回 null。
     */
    public AutoToolSwapClientProtocolPhaseSnapshot onRoundPhase(int protocolVersion, long responseServerRoundId,
            long phaseSequence, int phaseOrdinal, int generation, long serverTick, boolean rawValid) {
        if (!rawValid || protocolVersion != AutoToolSwapProtocol.PROTOCOL_VERSION
                || !canReceivePhase() || responseServerRoundId != serverRoundId
                || phaseSequence <= lastPhaseSequence || generation < 0 || serverTick < 0L) {
            return null;
        }
        ChainPhase[] phases = ChainPhase.values();
        if (phaseOrdinal < 0 || phaseOrdinal >= phases.length) {
            return null;
        }
        lastPhaseSequence = phaseSequence;
        return new AutoToolSwapClientProtocolPhaseSnapshot(serverRoundId, phaseSequence,
                phases[phaseOrdinal], generation, serverTick);
    }

    /** 将当前 round 标记为收尾态，收尾动作仍由 {@link #beginAction} 校验。 */
    public void markClosing() {
        if (phase == AutoToolSwapClientProtocolPhase.OPEN) {
            phase = AutoToolSwapClientProtocolPhase.CLOSING;
        }
    }

    /** 放弃尚未结算的请求并进入 ORPHANED，不伪造任何恢复动作。 */
    public void abandonInFlight() {
        if (inFlight != null) {
            orphan();
        }
    }

    /** 清除当前生命周期关联的 nonce、round、action、phase 与结算缓存。 */
    public void reset() {
        phase = AutoToolSwapClientProtocolPhase.IDLE;
        pendingNonce = 0L;
        serverRoundId = AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        nextActionSequence = AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE;
        inFlight = null;
        lastPhaseSequence = 0L;
        serverRoundState = null;
        acceptedRoundResult = null;
        lastSettlement = null;
    }

    private void applyRoundResult(AutoToolSwapRoundResult result, AutoToolSwapClientProtocolPhase nextPhase) {
        serverRoundId = result.serverRoundId();
        nextActionSequence = result.nextActionSequence();
        serverRoundState = result.roundState();
        acceptedRoundResult = result;
        phase = nextPhase;
    }

    private boolean allowsAction(AutoToolSwapAction action) {
        if (action == null || !canSendActions()) {
            return false;
        }
        if (action == AutoToolSwapAction.SWAP || action == AutoToolSwapAction.FREEZE) {
            return phase == AutoToolSwapClientProtocolPhase.OPEN;
        }
        return action == AutoToolSwapAction.RESTORE || action == AutoToolSwapAction.CLOSE;
    }

    private boolean canSendActions() {
        return phase == AutoToolSwapClientProtocolPhase.OPEN || phase == AutoToolSwapClientProtocolPhase.CLOSING;
    }

    private boolean canReceivePhase() {
        return phase == AutoToolSwapClientProtocolPhase.OPEN || phase == AutoToolSwapClientProtocolPhase.CLOSING;
    }

    private boolean matchesInFlight(long responseServerRoundId, long actionSequence,
            AutoToolSwapAction action, int anchorSlot, int candidateSlot) {
        return responseServerRoundId == serverRoundId && inFlight.serverRoundId() == responseServerRoundId
                && inFlight.actionSequence() == actionSequence && inFlight.action() == action
                && inFlight.anchorSlot() == anchorSlot && inFlight.candidateSlot() == candidateSlot;
    }

    private boolean isLastSettlement(long actionSequence, AutoToolSwapAction action,
            int anchorSlot, int candidateSlot, AutoToolSwapRoundResult result) {
        if (lastSettlement == null) {
            return false;
        }
        AutoToolSwapIntent intent = lastSettlement.intent();
        return intent.serverRoundId() == serverRoundId && intent.actionSequence() == actionSequence
                && intent.action() == action && intent.anchorSlot() == anchorSlot
                && intent.candidateSlot() == candidateSlot && lastSettlement.result().equals(result);
    }

    private static AutoToolSwapAction decodeAction(int actionCode) {
        try {
            return AutoToolSwapAction.fromWireCode(actionCode);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static AutoToolSwapRoundResult decodeRoundResult(int protocolVersion, long responseServerRoundId,
            int resultCode, int responseRoundState, long responseNextActionSequence,
            long serverTick, boolean rawValid) {
        if (!rawValid || protocolVersion != AutoToolSwapProtocol.PROTOCOL_VERSION
                || responseServerRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || responseNextActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE || serverTick < 0L) {
            return null;
        }
        try {
            return new AutoToolSwapRoundResult(responseServerRoundId,
                    AutoToolSwapResultCode.fromWireCode(resultCode),
                    AutoToolSwapRoundState.fromWireCode(responseRoundState),
                    responseNextActionSequence, serverTick);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void orphan() {
        inFlight = null;
        phase = AutoToolSwapClientProtocolPhase.ORPHANED;
    }

    /** 进程内全局 nonce 分配器，Long.MAX_VALUE 后永久耗尽而不回绕。 */
    private static final class ProcessNonceAllocator implements AutoToolSwapClientNonceAllocator {

        private final AtomicLong nextNonce = new AtomicLong(AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE);

        @Override
        public long allocate() {
            for (;;) {
                long candidate = nextNonce.get();
                if (candidate <= 0L) {
                    return 0L;
                }
                long following = candidate == Long.MAX_VALUE ? 0L : candidate + 1L;
                if (nextNonce.compareAndSet(candidate, following)) {
                    return candidate;
                }
            }
        }
    }
}
