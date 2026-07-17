package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolCandidateOrder;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;

/**
 * 自动工具客户端的单一有状态 reducer。
 *
 * <p>cycle、协议身份、动作等待、库存期望、关闭原因、重传与重武装均只在本类写入。
 * 外部以不可变 {@link Event} 输入事实，并执行不可变 {@link Effect}；本类不接触 Minecraft、Forge
 * 网络或客户端库存写入。</p>
 */
public final class AutoToolSwapClientReducer {

    public static final int MATCH_INTERVAL_TICKS = 10;
    public static final int TRANSACTION_TIMEOUT_TICKS = 40;
    public static final int RETRANSMIT_TICKS = 20;
    public static final int MAX_CONSECUTIVE_RESTORE_REJECTIONS = 3;
    public static final int MAX_DIAGNOSTIC_MESSAGES = 64;
    public static final int TARGET_ABSENT_CONFIRM_TICKS = 2;

    private static final NonceAllocator PROCESS_NONCE_ALLOCATOR = new ProcessNonceAllocator();
    private static final AutoToolSwapClientProtocolValidator VALIDATOR =
            new AutoToolSwapClientProtocolValidator();
    private static final DiagnosticSink NO_DIAGNOSTIC_SINK = new DiagnosticSink() {
        @Override
        public void log(String message) {
        }
    };

    /** 有界原因探针的日志出口；实现不得回调 reducer。 */
    public interface DiagnosticSink {
        void log(String message);
    }

    /** 客户端唯一显式业务状态枚举。 */
    public enum State {
        IDLE,
        PREPARING,
        FROZEN,
        RESTORING,
        WAIT_RELEASE,
        ORPHANED
    }

    /** reducer 的不可变输入基类。 */
    public abstract static class Event {
        private Event() {}
    }

    /** 逻辑连锁键边沿及其同刻 Minecraft 事实。 */
    public static final class KeyStateEvent extends Event {
        private final boolean down;
        private final ToolSwapContext context;
        private final long worldGeneration;

        public KeyStateEvent(boolean down, ToolSwapContext context) {
            this(down, context, 0L);
        }

        public KeyStateEvent(boolean down, ToolSwapContext context, long worldGeneration) {
            this.down = down;
            this.context = context;
            this.worldGeneration = worldGeneration;
        }
    }

    /** 单次客户端 END tick 事实。 */
    public static final class TickEvent extends Event {
        private final ToolSwapContext context;
        private final boolean physicallyDown;

        public TickEvent(ToolSwapContext context, boolean physicallyDown) {
            this.context = context;
            this.physicallyDown = physicallyDown;
        }
    }

    /** 配置热更新。 */
    public static final class ConfigEvent extends Event {
        private final boolean enabled;
        private final boolean takeoverEnabled;
        private final List<ToolSelector> selectors;

        public ConfigEvent(boolean enabled, List<ToolSelector> selectors) {
            this(enabled, true, selectors);
        }

        public ConfigEvent(boolean enabled, boolean takeoverEnabled, List<ToolSelector> selectors) {
            this.enabled = enabled;
            this.takeoverEnabled = takeoverEnabled;
            this.selectors = immutableSelectors(selectors);
        }
    }

    /** 本地主线程确认的方块破坏事实。 */
    public static final class LocalBlockDestroyedEvent extends Event {
        private final boolean physicallyDownAndWorldActive;
        private final long worldGeneration;

        public LocalBlockDestroyedEvent(boolean physicallyDownAndWorldActive) {
            this(physicallyDownAndWorldActive, 0L);
        }

        public LocalBlockDestroyedEvent(boolean physicallyDownAndWorldActive, long worldGeneration) {
            this.physicallyDownAndWorldActive = physicallyDownAndWorldActive;
            this.worldGeneration = worldGeneration;
        }
    }

    /** 已通过 ClientProxy lifecycle gate 的 round 原始回包。 */
    public static final class RoundResultEvent extends Event {
        private final int protocolVersion;
        private final long clientNonce;
        private final long serverRoundId;
        private final int resultCode;
        private final int roundState;
        private final long nextActionSequence;
        private final long serverTick;
        private final boolean rawValid;

        public RoundResultEvent(int protocolVersion, long clientNonce, long serverRoundId,
                int resultCode, int roundState, long nextActionSequence, long serverTick, boolean rawValid) {
            this.protocolVersion = protocolVersion;
            this.clientNonce = clientNonce;
            this.serverRoundId = serverRoundId;
            this.resultCode = resultCode;
            this.roundState = roundState;
            this.nextActionSequence = nextActionSequence;
            this.serverTick = serverTick;
            this.rawValid = rawValid;
        }
    }

    /** 已通过 ClientProxy lifecycle gate 的动作原始回包。 */
    public static final class ActionResultEvent extends Event {
        private final int protocolVersion;
        private final long serverRoundId;
        private final long actionSequence;
        private final int actionCode;
        private final int resultCode;
        private final int roundState;
        private final int anchorSlot;
        private final int candidateSlot;
        private final long nextActionSequence;
        private final long serverTick;
        private final boolean rawValid;

        public ActionResultEvent(int protocolVersion, long serverRoundId, long actionSequence,
                int actionCode, int resultCode, int roundState, int anchorSlot, int candidateSlot,
                long nextActionSequence, long serverTick, boolean rawValid) {
            this.protocolVersion = protocolVersion;
            this.serverRoundId = serverRoundId;
            this.actionSequence = actionSequence;
            this.actionCode = actionCode;
            this.resultCode = resultCode;
            this.roundState = roundState;
            this.anchorSlot = anchorSlot;
            this.candidateSlot = candidateSlot;
            this.nextActionSequence = nextActionSequence;
            this.serverTick = serverTick;
            this.rawValid = rawValid;
        }
    }

    /** 已通过 ClientProxy lifecycle gate 的专用 phase 原始回包。 */
    public static final class RoundPhaseEvent extends Event {
        private final int protocolVersion;
        private final long serverRoundId;
        private final long phaseSequence;
        private final int phaseOrdinal;
        private final int generation;
        private final long serverTick;
        private final boolean rawValid;

        public RoundPhaseEvent(int protocolVersion, long serverRoundId, long phaseSequence,
                int phaseOrdinal, int generation, long serverTick, boolean rawValid) {
            this.protocolVersion = protocolVersion;
            this.serverRoundId = serverRoundId;
            this.phaseSequence = phaseSequence;
            this.phaseOrdinal = phaseOrdinal;
            this.generation = generation;
            this.serverTick = serverTick;
            this.rawValid = rawValid;
        }
    }

    /** 已通过 ClientProxy lifecycle gate 的接替请求原始字段。 */
    public static final class TakeoverRequestEvent extends Event {
        private final int protocolVersion;
        private final long serverRoundId;
        private final long actionSequence;
        private final int generation;
        private final int targetX;
        private final int targetY;
        private final int targetZ;
        private final int targetBlockId;
        private final int targetBlockMetadata;
        private final long serverTick;
        private final long deadlineTick;
        private final boolean rawValid;

        public TakeoverRequestEvent(int protocolVersion, long serverRoundId, long actionSequence,
                int generation, int targetX, int targetY, int targetZ, int targetBlockId,
                int targetBlockMetadata, long serverTick, long deadlineTick, boolean rawValid) {
            this.protocolVersion = protocolVersion;
            this.serverRoundId = serverRoundId;
            this.actionSequence = actionSequence;
            this.generation = generation;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.targetBlockId = targetBlockId;
            this.targetBlockMetadata = targetBlockMetadata;
            this.serverTick = serverTick;
            this.deadlineTick = deadlineTick;
            this.rawValid = rawValid;
        }
    }

    /** adapter 完成一次 effect 后的结果。 */
    public static final class EffectResultEvent extends Event {
        private final Effect effect;
        private final boolean submitted;
        private final ToolSwapContext capturedContext;

        public EffectResultEvent(Effect effect, boolean submitted, ToolSwapContext capturedContext) {
            this.effect = effect;
            this.submitted = submitted;
            this.capturedContext = capturedContext;
        }
    }

    /** lifecycle 硬复位；不得产生跨连接网络 effect。 */
    public static final class ResetEvent extends Event {
        public ResetEvent() {}
    }

    /** reducer 输出的不可变 runtime effect。 */
    public static final class Effect {
        /** adapter 可执行的 effect 种类。 */
        public enum Type { CAPTURE, BEGIN_ROUND, SEND_INTENT, FRESH_KEY }

        private final Type type;
        private final ToolSwapCapturePlan capturePlan;
        private final int anchorSlot;
        private final int candidateSlot;
        private final long clientNonce;
        private final AutoToolSwapIntent intent;
        private final boolean retry;
        private final boolean freshKeyAfterSubmit;
        private final long createdTick;
        private final int targetBlockId;
        private final int targetBlockMetadata;

        private Effect(Type type, ToolSwapCapturePlan capturePlan, int anchorSlot, int candidateSlot,
                long clientNonce, AutoToolSwapIntent intent, boolean retry,
                boolean freshKeyAfterSubmit, long createdTick, int targetBlockId, int targetBlockMetadata) {
            this.type = type;
            this.capturePlan = capturePlan;
            this.anchorSlot = anchorSlot;
            this.candidateSlot = candidateSlot;
            this.clientNonce = clientNonce;
            this.intent = intent;
            this.retry = retry;
            this.freshKeyAfterSubmit = freshKeyAfterSubmit;
            this.createdTick = createdTick;
            this.targetBlockId = targetBlockId;
            this.targetBlockMetadata = targetBlockMetadata;
        }

        public Type type() { return type; }
        public ToolSwapCapturePlan capturePlan() { return capturePlan; }
        public int anchorSlot() { return anchorSlot; }
        public int candidateSlot() { return candidateSlot; }
        public long clientNonce() { return clientNonce; }
        public AutoToolSwapIntent intent() { return intent; }
        public boolean retry() { return retry; }
        public int targetBlockId() { return targetBlockId; }
        public int targetBlockMetadata() { return targetBlockMetadata; }
    }

    /** 包内测试用 nonce 分配契约。 */
    interface NonceAllocator {
        long allocate();
    }

    private final NonceAllocator nonceAllocator;
    private final DiagnosticSink diagnosticSink;
    private final EnumSet<DiagnosticClass> emittedDiagnosticClasses =
            EnumSet.noneOf(DiagnosticClass.class);
    private State state = State.IDLE;
    private boolean configuredEnabled;
    private boolean configuredTakeoverEnabled;
    private List<ToolSelector> configuredSelectors;
    private boolean keyDown;
    private long clientTick;
    private long generation;
    private int anchorSlot;
    private long nextMatchTick;
    private boolean cycleEnabled;
    private boolean cycleTakeoverEnabled;
    private List<ToolSelector> cycleSelectors = Collections.emptyList();
    private boolean freezeRequested;
    private boolean closeRequested;
    private CloseCause closeCause = CloseCause.NONE;
    private boolean deferredRoundPending;
    private boolean rematchAfterRestore;
    private Integer pendingAnchor;
    private AutoToolSwapAction pendingAction;
    private SwapExpectation swapExpectation;
    private RoundContext round;
    private PendingTransmission transmission;
    private ToolSwapContext lastContext;
    private int consecutiveRestoreRejections;
    private long preEdgeDestroyTick = -1L;
    private long preEdgeWorldGeneration = -1L;
    private int diagnosticMessageCount;
    private DiagnosticReason restoreReason;
    private DiagnosticReason closeDiagnosticReason;
    private AutoToolSwapTakeoverRequest pendingTakeoverRequest;
    private TakeoverExpectation takeoverExpectation;
    private ToolSwapTargetIdentity latestTarget = ToolSwapTargetIdentity.ABSENT;
    private ToolSwapTargetIdentity matchedTarget;
    private int consecutiveAbsentTargetTicks;
    private boolean targetRematchPending;
    private boolean targetRestoreRequested;

    public AutoToolSwapClientReducer(boolean enabled, List<ToolSelector> selectors) {
        this(enabled, true, selectors, PROCESS_NONCE_ALLOCATOR, NO_DIAGNOSTIC_SINK);
    }

    public AutoToolSwapClientReducer(boolean enabled, boolean takeoverEnabled, List<ToolSelector> selectors) {
        this(enabled, takeoverEnabled, selectors, PROCESS_NONCE_ALLOCATOR, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapClientReducer(boolean enabled, List<ToolSelector> selectors, DiagnosticSink diagnosticSink) {
        this(enabled, true, selectors, PROCESS_NONCE_ALLOCATOR, diagnosticSink);
    }

    AutoToolSwapClientReducer(boolean enabled, boolean takeoverEnabled, List<ToolSelector> selectors,
            DiagnosticSink diagnosticSink) {
        this(enabled, takeoverEnabled, selectors, PROCESS_NONCE_ALLOCATOR, diagnosticSink);
    }

    AutoToolSwapClientReducer(boolean enabled, List<ToolSelector> selectors, NonceAllocator nonceAllocator) {
        this(enabled, true, selectors, nonceAllocator, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapClientReducer(boolean enabled, List<ToolSelector> selectors, NonceAllocator nonceAllocator,
            DiagnosticSink diagnosticSink) {
        this(enabled, true, selectors, nonceAllocator, diagnosticSink);
    }

    AutoToolSwapClientReducer(boolean enabled, boolean takeoverEnabled, List<ToolSelector> selectors,
            NonceAllocator nonceAllocator, DiagnosticSink diagnosticSink) {
        if (nonceAllocator == null) throw new IllegalArgumentException("nonceAllocator must not be null");
        if (diagnosticSink == null) throw new IllegalArgumentException("diagnosticSink must not be null");
        configuredEnabled = enabled;
        configuredTakeoverEnabled = takeoverEnabled;
        configuredSelectors = immutableSelectors(selectors);
        this.nonceAllocator = nonceAllocator;
        this.diagnosticSink = diagnosticSink;
    }

    /** 将单个事实事件归约为不可变 effect 列表。 */
    public List<Effect> reduce(Event event) {
        if (event == null) return noEffects();
        if (event instanceof KeyStateEvent) return onKeyState((KeyStateEvent) event);
        if (event instanceof TickEvent) return onTick((TickEvent) event);
        if (event instanceof ConfigEvent) return onConfig((ConfigEvent) event);
        if (event instanceof LocalBlockDestroyedEvent) return onLocalDestroy((LocalBlockDestroyedEvent) event);
        if (event instanceof RoundResultEvent) return onRoundResult((RoundResultEvent) event);
        if (event instanceof ActionResultEvent) return onActionResult((ActionResultEvent) event);
        if (event instanceof RoundPhaseEvent) return onRoundPhase((RoundPhaseEvent) event);
        if (event instanceof TakeoverRequestEvent) return onTakeoverRequest((TakeoverRequestEvent) event);
        if (event instanceof EffectResultEvent) return onEffectResult((EffectResultEvent) event);
        if (event instanceof ResetEvent) {
            reset();
            return noEffects();
        }
        throw new IllegalArgumentException("unknown reducer event");
    }

    public State state() { return state; }
    public long clientTick() { return clientTick; }
    public long generation() { return generation; }
    public boolean isKeyDown() { return keyDown; }
    public boolean hasSwapExpectation() { return swapExpectation != null; }
    public int protectedAnchorSlot() { return swapExpectation == null ? -1 : swapExpectation.anchorSlot; }
    public int protectedCandidateSlot() { return swapExpectation == null ? -1 : swapExpectation.candidateSlot; }
    public boolean isRoundPending() { return round != null && !round.accepted; }
    public boolean isActionPending() { return round != null && round.inFlight != null; }
    public boolean isInventorySyncPending() {
        return swapExpectation != null && swapExpectation.verifyingAction != null
                || takeoverExpectation != null && takeoverExpectation.verifying;
    }
    public boolean isOrphaned() { return state == State.ORPHANED; }
    public long pendingNonce() { return round == null ? 0L : round.clientNonce; }
    public long serverRoundId() { return round == null ? 0L : round.serverRoundId; }
    public long nextActionSequence() {
        return round == null ? AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE : round.nextActionSequence;
    }
    public long lastPhaseSequence() { return round == null ? 0L : round.lastPhaseSequence; }
    public AutoToolSwapRoundState serverRoundState() { return round == null ? null : round.serverRoundState; }
    public AutoToolSwapIntent inFlightIntent() { return round == null ? null : round.inFlight; }
    public int pendingTargetBlockId() {
        return pendingTakeoverRequest == null ? 0 : pendingTakeoverRequest.targetBlockId();
    }
    public int pendingTargetBlockMetadata() {
        return pendingTakeoverRequest == null ? 0 : pendingTakeoverRequest.targetBlockMetadata();
    }

    /** adapter 采样 light context 时使用的只读逻辑键事实。 */
    public boolean chainActive() {
        return keyDown && state != State.ORPHANED
                && (deferredRoundPending && state == State.IDLE
                        || round != null && round.accepted && !closeRequested);
    }

    /** 按键边沿所需库存采样范围。 */
    public ToolSwapCapturePlan capturePlanForKeyState(boolean down, ToolSwapLightContext context,
            long worldGeneration) {
        if (context == null || down == keyDown) return ToolSwapCapturePlan.NONE;
        if (!down) return needsProtectedCapture() ? ToolSwapCapturePlan.PROTECTED : ToolSwapCapturePlan.NONE;
        boolean preFrozen = preEdgeDestroyTick == clientTick && preEdgeWorldGeneration == worldGeneration;
        return context.guiOpen || preFrozen ? ToolSwapCapturePlan.NONE : ToolSwapCapturePlan.FULL;
    }

    /** tick 所需库存采样范围，包含 deferred rearm 的完整快照门。 */
    public ToolSwapCapturePlan capturePlanForTick(ToolSwapLightContext context, boolean physicallyDown) {
        if (context == null) return ToolSwapCapturePlan.NONE;
        if (pendingTakeoverRequest != null && round != null && round.inFlight == null
                && transmission == null && takeoverExpectation == null) {
            return ToolSwapCapturePlan.FULL_TARGET;
        }
        if (takeoverExpectation != null && takeoverExpectation.verifying) {
            return ToolSwapCapturePlan.FULL;
        }
        if (deferredRoundPending && keyDown && physicallyDown && state == State.IDLE) {
            return ToolSwapCapturePlan.FULL;
        }
        if (state == State.IDLE || state == State.WAIT_RELEASE || state == State.ORPHANED) {
            return ToolSwapCapturePlan.NONE;
        }
        if (needsProtectedCapture() || context.guiOpen) return ToolSwapCapturePlan.PROTECTED;
        if (closeRequested || freezeRequested) return ToolSwapCapturePlan.NONE;
        if (state == State.PREPARING && context.targetIdentity.isPresent()
                && !context.targetIdentity.equals(matchedTarget)) return ToolSwapCapturePlan.FULL;
        if (state == State.PREPARING && context.targetIdentity.isPresent()
                && context.tick >= nextMatchTick) return ToolSwapCapturePlan.FULL;
        return ToolSwapCapturePlan.NONE;
    }

    private List<Effect> onKeyState(KeyStateEvent event) {
        if (event.down == keyDown || event.context == null && event.down) return noEffects();
        if (event.context != null) remember(event.context);
        boolean preFrozen = event.down && preEdgeDestroyTick == clientTick
                && preEdgeWorldGeneration == event.worldGeneration;
        clearPreEdgeDestroyLatch();
        keyDown = event.down;
        if (!event.down) {
            deferredRoundPending = false;
            markRoundClosing();
            if (state == State.WAIT_RELEASE) {
                finishWaitRelease();
                return noEffects();
            }
            requestClose(CloseCause.RELEASE_GATED, DiagnosticReason.RELEASE);
            return drive();
        }
        if (state != State.IDLE) {
            if (closeRequested) requestClose(CloseCause.RELEASE_GATED, DiagnosticReason.RELEASE);
            return noEffects();
        }
        return startCycle(event.context, preFrozen, false);
    }

    private List<Effect> onTick(TickEvent event) {
        List<Effect> effects = new ArrayList<Effect>();
        if (event.context != null) {
            remember(event.context);
            if (tracksPreparingTarget()) observePreparingTarget(event.context);
            if (takeoverExpectation != null && takeoverExpectation.verifying) {
                observeTakeoverInventory(event.context.inventory, event.context.tick);
            } else if (isInventorySyncPending()) {
                observeInventory(event.context.inventory, event.context.tick);
            } else if (pendingTakeoverRequest != null) {
                effects.addAll(prepareTakeoverDecision(event.context));
            } else if (state != State.IDLE && state != State.WAIT_RELEASE && state != State.ORPHANED) {
                advanceCycle(event.context);
            }
            effects.addAll(drive());
        }
        if (deferredRoundPending) {
            deferredRoundPending = false;
            if (event.physicallyDown && keyDown && configuredEnabled && state == State.IDLE
                    && event.context != null && event.context.inventory.isFullCandidateScan()) {
                effects.addAll(startCycle(event.context, false, true));
            }
        }
        effects.addAll(retryOrOrphan());
        if (clientTick != Long.MAX_VALUE) clientTick++;
        clearPreEdgeDestroyLatch();
        return immutableEffects(effects);
    }

    private List<Effect> onConfig(ConfigEvent event) {
        boolean wasEnabled = configuredEnabled;
        configuredEnabled = event.enabled;
        configuredTakeoverEnabled = event.takeoverEnabled;
        if (!event.takeoverEnabled) cycleTakeoverEnabled = false;
        configuredSelectors = event.selectors;
        if (wasEnabled && !event.enabled) {
            deferredRoundPending = false;
            markRoundClosing();
            if (state != State.IDLE) requestClose(CloseCause.RELEASE_GATED, DiagnosticReason.CONFIG_DISABLED);
        }
        return drive();
    }

    private List<Effect> onLocalDestroy(LocalBlockDestroyedEvent event) {
        diagnose(DiagnosticClass.LOCAL_DESTROY, DiagnosticReason.LOCAL_DESTROY, lastContext,
                "local-destroy", 0L, event.worldGeneration,
                Boolean.toString(event.physicallyDownAndWorldActive));
        if (!event.physicallyDownAndWorldActive) {
            clearPreEdgeDestroyLatch();
            return noEffects();
        }
        if (!keyDown) {
            preEdgeDestroyTick = clientTick;
            preEdgeWorldGeneration = event.worldGeneration;
            return noEffects();
        }
        requestFreeze(generation);
        return drive();
    }

    private List<Effect> onRoundResult(RoundResultEvent event) {
        AutoToolSwapClientProtocolValidator.ValidatedRoundResult validated = VALIDATOR.validateRoundResult(
                event.protocolVersion, event.serverRoundId, event.resultCode, event.roundState,
                event.nextActionSequence, event.serverTick, event.rawValid);
        if (validated == null || event.clientNonce <= 0L || round == null
                || event.clientNonce != round.clientNonce) return noEffects();
        AutoToolSwapRoundResult result = validated.result();
        if (round.acceptedResult != null) {
            return round.acceptedResult.equals(result) ? noEffects() : noEffects();
        }
        if (round.accepted || transmission == null || transmission.effect.type != Effect.Type.BEGIN_ROUND) {
            return noEffects();
        }
        if (result.outcome() == AutoToolSwapResultCode.REJECTED) {
            transmission = null;
            round = null;
            onRoundRejected();
            return noEffects();
        }
        if (result.serverRoundId() == AutoToolSwapProtocol.NO_SERVER_ROUND_ID) return noEffects();
        if (result.outcome() == AutoToolSwapResultCode.SYNC_FAILED) {
            transmission = null;
            orphan();
            return noEffects();
        }
        if (result.outcome() != AutoToolSwapResultCode.ACCEPTED
                || result.roundState() == AutoToolSwapRoundState.PENDING_KEY
                || result.roundState() == AutoToolSwapRoundState.FINISHED
                || result.roundState() == AutoToolSwapRoundState.ORPHANED) return noEffects();
        transmission = null;
        round.accepted = true;
        round.serverRoundId = result.serverRoundId();
        round.nextActionSequence = result.nextActionSequence();
        round.serverRoundState = result.roundState();
        round.acceptedResult = result;
        if (closeRequested || result.roundState() == AutoToolSwapRoundState.CLOSING) round.closing = true;
        if (!round.closing && result.roundState() == AutoToolSwapRoundState.FROZEN) {
            freezeRequested = false;
            clearPreparingTargetTracking();
            if (pendingAction == null) state = State.FROZEN;
        }
        return noEffects();
    }

    private List<Effect> onActionResult(ActionResultEvent event) {
        AutoToolSwapClientProtocolValidator.ValidatedActionResult validated = VALIDATOR.validateActionResult(
                event.protocolVersion, event.serverRoundId, event.actionSequence, event.actionCode, event.resultCode,
                event.roundState, event.anchorSlot, event.candidateSlot, event.nextActionSequence,
                event.serverTick, event.rawValid);
        if (validated == null || round == null) {
            diagnose(DiagnosticClass.ACTION_RESULT_IGNORED, DiagnosticReason.PROTOCOL_ORPHAN, lastContext,
                    "action-result-ignored", event.serverRoundId, event.actionSequence,
                    Integer.toString(event.actionCode));
            return noEffects();
        }
        AutoToolSwapRoundResult result = validated.result();
        if (validated.action() == AutoToolSwapAction.RESTORE || validated.action() == AutoToolSwapAction.CLOSE
                || validated.action() == AutoToolSwapAction.ABANDON) {
            diagnose(validated.action() == AutoToolSwapAction.RESTORE
                            ? DiagnosticClass.ACTION_RESULT_RESTORE
                            : validated.action() == AutoToolSwapAction.ABANDON
                                    ? DiagnosticClass.ACTION_RESULT_ABANDON : DiagnosticClass.ACTION_RESULT_CLOSE,
                    validated.action() == AutoToolSwapAction.RESTORE
                            ? DiagnosticReason.ACTION_RESULT_RESTORE
                            : validated.action() == AutoToolSwapAction.ABANDON
                                    ? DiagnosticReason.ACTION_RESULT_ABANDON : DiagnosticReason.ACTION_RESULT_CLOSE,
                    lastContext, "action-result", event.serverRoundId, event.actionSequence,
                    validated.action() + "/" + result.outcome() + "/" + result.roundState());
        }
        if (round.inFlight == null) {
            if (!isDuplicateSettlement(event, validated.action(), result)) {
                diagnose(DiagnosticClass.ACTION_RESULT_IGNORED, DiagnosticReason.PROTOCOL_ORPHAN, lastContext,
                        "action-result-without-flight", event.serverRoundId, event.actionSequence,
                        validated.action().name());
            }
            return noEffects();
        }
        AutoToolSwapIntent intent = round.inFlight;
        if (event.serverRoundId != round.serverRoundId || intent.serverRoundId() != event.serverRoundId
                || intent.actionSequence() != event.actionSequence || intent.action() != validated.action()
                || intent.anchorSlot() != event.anchorSlot || intent.candidateSlot() != event.candidateSlot
                || event.actionSequence == Long.MAX_VALUE
                || event.nextActionSequence != event.actionSequence + 1L) {
            diagnose(DiagnosticClass.ACTION_RESULT_IGNORED, DiagnosticReason.PROTOCOL_ORPHAN, lastContext,
                    "action-result-identity-mismatch", event.serverRoundId, event.actionSequence,
                    validated.action().name());
            return noEffects();
        }
        round.inFlight = null;
        transmission = null;
        round.nextActionSequence = event.nextActionSequence;
        round.serverRoundState = result.roundState();
        round.lastSettlementIntent = intent;
        round.lastSettlementResult = result;
        if (result.outcome() == AutoToolSwapResultCode.SYNC_FAILED
                || result.roundState() == AutoToolSwapRoundState.ORPHANED) {
            orphan();
            return noEffects();
        }
        if (result.roundState() == AutoToolSwapRoundState.CLOSING) round.closing = true;
        settleProductAction(intent.action(), result.outcome(), result.roundState());
        if (intent.action() == AutoToolSwapAction.CLOSE
                && result.roundState() == AutoToolSwapRoundState.FINISHED
                && (result.outcome() == AutoToolSwapResultCode.ACCEPTED
                        || result.outcome() == AutoToolSwapResultCode.APPLIED)) {
            round = null;
        }
        return noEffects();
    }

    private List<Effect> onRoundPhase(RoundPhaseEvent event) {
        AutoToolSwapClientProtocolValidator.ValidatedPhase phase = VALIDATOR.validatePhase(
                event.protocolVersion, event.serverRoundId, event.phaseSequence, event.phaseOrdinal,
                event.generation, event.serverTick, event.rawValid);
        if (phase == null || round == null || !round.accepted || phase.serverRoundId() != round.serverRoundId
                || phase.phaseSequence() <= round.lastPhaseSequence || state == State.ORPHANED) {
            diagnose(DiagnosticClass.ROUND_PHASE_IGNORED, DiagnosticReason.PROTOCOL_ORPHAN, lastContext,
                    "round-phase-ignored", event.serverRoundId, event.phaseSequence,
                    Integer.toString(event.phaseOrdinal));
            return noEffects();
        }
        round.lastPhaseSequence = phase.phaseSequence();
        round.lastPhase = phase.phase();
        round.chainGeneration = phase.generation();
        diagnose(phase.phase() == ChainPhase.IDLE ? DiagnosticClass.ROUND_PHASE_IDLE
                        : DiagnosticClass.ROUND_PHASE_ACTIVE,
                phase.phase() == ChainPhase.IDLE ? DiagnosticReason.NATURAL_IDLE : DiagnosticReason.ROUND_PHASE,
                lastContext, "round-phase", event.serverRoundId, event.phaseSequence, phase.phase().name());
        if (phase.phase() == ChainPhase.PLANNING || phase.phase() == ChainPhase.RUNNING
                || phase.phase() == ChainPhase.FINISHING) {
            requestFreeze(generation);
        } else if (phase.phase() == ChainPhase.IDLE) {
            requestClose(keyDown ? CloseCause.NATURAL_REARM : CloseCause.RELEASE_GATED,
                    DiagnosticReason.NATURAL_IDLE);
            markRoundClosing();
        }
        return noEffects();
    }

    /** 严格关联当前 v3/FROZEN/phase generation，只登记事实等待下一 ClientTick。 */
    private List<Effect> onTakeoverRequest(TakeoverRequestEvent event) {
        AutoToolSwapTakeoverRequest request = VALIDATOR.validateTakeoverRequest(event.protocolVersion,
                event.serverRoundId, event.actionSequence, event.generation, event.targetX, event.targetY,
                event.targetZ, event.targetBlockId, event.targetBlockMetadata, event.serverTick,
                event.deadlineTick, event.rawValid);
        if (request == null || round == null || !round.accepted || state != State.FROZEN
                || round.serverRoundId != request.serverRoundId()
                || round.nextActionSequence != request.actionSequence()
                || round.chainGeneration != request.generation()
                || round.lastPhase == null || round.lastPhase == ChainPhase.IDLE
                || round.inFlight != null || transmission != null || takeoverExpectation != null) {
            diagnose(DiagnosticClass.TAKEOVER_REQUEST_IGNORED, DiagnosticReason.TAKEOVER_REQUEST_IGNORED,
                    lastContext, "takeover-request", event.serverRoundId, event.actionSequence,
                    Integer.toString(event.generation));
            return noEffects();
        }
        if (pendingTakeoverRequest != null) {
            diagnose(DiagnosticClass.TAKEOVER_REQUEST_IGNORED, DiagnosticReason.TAKEOVER_REQUEST_IGNORED,
                    lastContext, "takeover-request-duplicate", event.serverRoundId, event.actionSequence,
                    Integer.toString(event.generation));
            return pendingTakeoverRequest.sameGate(request) ? noEffects() : noEffects();
        }
        pendingTakeoverRequest = request;
        return noEffects();
    }

    private List<Effect> onEffectResult(EffectResultEvent event) {
        if (event.effect == null) return noEffects();
        if (event.effect.type == Effect.Type.CAPTURE) {
            return onCapturedAction(event.effect, event.capturedContext);
        }
        if (event.effect.type == Effect.Type.FRESH_KEY) return noEffects();
        if (!event.submitted) {
            orphan();
            return noEffects();
        }
        if (event.effect.retry) {
            if (transmission != null && transmission.matches(event.effect)) {
                transmission = transmission.markRetransmitted();
            }
            return noEffects();
        }
        transmission = new PendingTransmission(event.effect, event.effect.createdTick, false);
        if (event.effect.type == Effect.Type.SEND_INTENT && round != null) {
            round.inFlight = event.effect.intent;
        }
        if (event.effect.type == Effect.Type.BEGIN_ROUND && event.effect.freshKeyAfterSubmit) {
            return oneEffect(freshKeyEffect());
        }
        return noEffects();
    }

    private List<Effect> onCapturedAction(Effect captureEffect, ToolSwapContext context) {
        AutoToolSwapAction action = pendingAction;
        if (action == null || captureEffect.intent == null || captureEffect.intent.action() != action) {
            return noEffects();
        }
        if (action == AutoToolSwapAction.RESTORE && targetRestoreRequested && context != null
                && context.targetIdentity.isPresent()) {
            latestTarget = context.targetIdentity;
            consecutiveAbsentTargetTicks = 0;
            if (swapExpectation != null && context.targetIdentity.equals(swapExpectation.targetIdentity)
                    && cancelUnsubmittedTargetRestore()) {
                targetRematchPending = false;
                return noEffects();
            }
            targetRematchPending = true;
        }
        if (!preflight(action, context)) return noEffects();
        AutoToolSwapIntent intent = beginIntent(action, captureEffect.anchorSlot, captureEffect.candidateSlot,
                context.inventory.slot(captureEffect.anchorSlot), context.inventory.slot(captureEffect.candidateSlot));
        return intent == null ? handleUnavailableAction(action) : oneEffect(intentEffect(intent, false));
    }

    private List<Effect> startCycle(ToolSwapContext context, boolean preFrozen, boolean freshKeyAfterSubmit) {
        deferredRoundPending = false;
        emittedDiagnosticClasses.clear();
        diagnosticMessageCount = 0;
        generation = incrementGeneration(generation);
        cycleEnabled = configuredEnabled;
        cycleTakeoverEnabled = configuredTakeoverEnabled;
        cycleSelectors = configuredSelectors;
        anchorSlot = context.selectedHotbarSlot;
        nextMatchTick = context.tick;
        freezeRequested = preFrozen;
        closeRequested = false;
        closeCause = CloseCause.NONE;
        rematchAfterRestore = false;
        pendingAnchor = null;
        pendingAction = null;
        swapExpectation = null;
        restoreReason = null;
        closeDiagnosticReason = null;
        consecutiveRestoreRejections = 0;
        latestTarget = context.targetIdentity;
        matchedTarget = null;
        consecutiveAbsentTargetTicks = 0;
        targetRematchPending = context.targetIdentity.isPresent();
        targetRestoreRequested = false;
        if (!cycleEnabled || !context.breakCapable || context.creative || !context.chainActive) {
            diagnose(!cycleEnabled ? DiagnosticClass.CYCLE_DISABLED : DiagnosticClass.CYCLE_INELIGIBLE,
                    !cycleEnabled ? DiagnosticReason.CYCLE_DISABLED : DiagnosticReason.CYCLE_INELIGIBLE,
                    context);
            state = State.WAIT_RELEASE;
            resetCycleFlags();
            return noEffects();
        }
        long nonce = nonceAllocator.allocate();
        if (nonce <= 0L) {
            orphan();
            return noEffects();
        }
        round = new RoundContext(nonce);
        state = preFrozen ? State.FROZEN : State.PREPARING;
        if (preFrozen) clearPreparingTargetTracking();
        return oneEffect(roundEffect(nonce, false, freshKeyAfterSubmit));
    }

    private void advanceCycle(ToolSwapContext context) {
        if (closeRequested || freezeRequested) return;
        if (context.guiOpen) {
            if (swapExpectation != null || pendingAction == AutoToolSwapAction.SWAP) {
                diagnose(DiagnosticClass.ADVANCE_GUI, DiagnosticReason.GUI_OPEN, context);
                rematchAfterRestore = state == State.PREPARING;
                prepareRestore(DiagnosticReason.GUI_OPEN);
            }
            return;
        }
        if (context.selectedHotbarSlot != anchorSlot) {
            diagnose(DiagnosticClass.ADVANCE_REANCHOR, DiagnosticReason.SELECTED_SLOT_REANCHOR, context);
            requestReanchor(context.selectedHotbarSlot);
        }
        if (state == State.PREPARING && pendingAction == null && swapExpectation == null
                && context.targetIdentity.isPresent() && context.targetIdentity.equals(latestTarget)
                && (targetRematchPending || context.tick >= nextMatchTick)
                && round != null && round.accepted) evaluate(context);
    }

    /** PREPARING 及其目标专用恢复期间持续接收 latest-target-wins 事实。 */
    private boolean tracksPreparingTarget() {
        return !closeRequested && !freezeRequested
                && (state == State.PREPARING || state == State.RESTORING && targetRestoreRequested);
    }

    /** 观察单次 END tick 目标；ABSENT 仅在连续两次后成为有效变化。 */
    private void observePreparingTarget(ToolSwapContext context) {
        ToolSwapTargetIdentity observed = context.targetIdentity;
        if (observed.isPresent()) {
            consecutiveAbsentTargetTicks = 0;
            if (!observed.equals(latestTarget)) {
                latestTarget = observed;
                reconcileLatestTarget(context.tick);
            }
            return;
        }
        if (consecutiveAbsentTargetTicks < TARGET_ABSENT_CONFIRM_TICKS) {
            consecutiveAbsentTargetTicks++;
        }
        if (consecutiveAbsentTargetTicks == TARGET_ABSENT_CONFIRM_TICKS
                && latestTarget.isPresent()) {
            latestTarget = ToolSwapTargetIdentity.ABSENT;
            reconcileLatestTarget(context.tick);
        }
    }

    /** 将最新目标与唯一库存期望对齐，不取消已经提交的库存事务。 */
    private void reconcileLatestTarget(long tick) {
        if (swapExpectation == null) {
            if (!latestTarget.isPresent()) {
                matchedTarget = null;
                targetRematchPending = false;
            } else if (!latestTarget.equals(matchedTarget)) {
                targetRematchPending = true;
                nextMatchTick = tick;
            }
            return;
        }
        boolean matchesBorrowedTarget = latestTarget.isPresent()
                && latestTarget.equals(swapExpectation.targetIdentity);
        if (matchesBorrowedTarget && targetRestoreRequested) {
            if (cancelUnsubmittedTargetRestore()) {
                targetRematchPending = false;
            } else {
                // 已提交 RESTORE 不可取消；完成后仍须对最终目标重新 FULL。
                targetRematchPending = true;
            }
            return;
        }
        if (matchesBorrowedTarget) {
            targetRematchPending = false;
            return;
        }
        targetRematchPending = true;
        if (pendingAction == AutoToolSwapAction.SWAP && !isActionPending()
                && !isInventorySyncPending() && transmission == null) {
            discardUnstartedSwapForTargetChange(tick);
            return;
        }
        if (pendingAction == null && !isActionPending() && !isInventorySyncPending()) {
            prepareRestore(DiagnosticReason.TARGET_CHANGED);
        }
    }

    /** 仅撤销尚未发送、且唯一原因是目标变化的 RESTORE。 */
    private boolean cancelUnsubmittedTargetRestore() {
        if (!targetRestoreRequested || pendingAction != AutoToolSwapAction.RESTORE
                || isActionPending() || isInventorySyncPending() || transmission != null) return false;
        pendingAction = null;
        targetRestoreRequested = false;
        restoreReason = null;
        state = State.PREPARING;
        return true;
    }

    private void evaluate(ToolSwapContext context) {
        if (closeRequested || freezeRequested) return;
        nextMatchTick = advanceWatermark(nextMatchTick, context.tick);
        if (!context.inventory.isFullCandidateScan() || !context.targetIdentity.isPresent()
                || !context.targetIdentity.equals(latestTarget)) return;
        matchedTarget = context.targetIdentity;
        targetRematchPending = false;
        ToolCandidate held = context.inventory.candidateAt(anchorSlot);
        if (held != null && held.isUsableInHand()) {
            diagnose(DiagnosticClass.HELD_USABLE, DiagnosticReason.HELD_USABLE, context);
            return;
        }
        for (ToolCandidate candidate : ToolCandidateOrder.sort(context.inventory.candidates(), cycleSelectors)) {
            if (candidate.slot() == anchorSlot) continue;
            SlotSnapshot anchor = context.inventory.slot(anchorSlot);
            SlotSnapshot candidateSlot = context.inventory.slot(candidate.slot());
            if (anchor != null && candidateSlot != null && !candidateSlot.isEmpty()) {
                swapExpectation = new SwapExpectation(generation, anchorSlot, candidate.slot(), anchor,
                        candidateSlot, context.targetIdentity);
                pendingAction = AutoToolSwapAction.SWAP;
                return;
            }
        }
        diagnose(DiagnosticClass.NO_CANDIDATE, DiagnosticReason.NO_CANDIDATE, context);
    }

    /** 使用服务端目标完成 FULL_TARGET 采样并形成 TAKEOVER/DECLINE。 */
    private List<Effect> prepareTakeoverDecision(ToolSwapContext context) {
        AutoToolSwapTakeoverRequest request = pendingTakeoverRequest;
        if (request == null || round == null || round.inFlight != null || transmission != null) return noEffects();
        if (!cycleTakeoverEnabled) {
            diagnose(DiagnosticClass.TAKEOVER_DISABLED, DiagnosticReason.TAKEOVER_DISABLED, context);
            return oneEffect(intentEffect(declineIntent(request), false));
        }
        if (context == null || !context.inventoryTransactionSafe || !context.inventory.isFullCandidateScan()) {
            diagnose(DiagnosticClass.TAKEOVER_INVENTORY_UNSAFE,
                    DiagnosticReason.TAKEOVER_INVENTORY_UNSAFE, context);
            return oneEffect(intentEffect(declineIntent(request), false));
        }
        int oldCandidate = swapExpectation == null ? -1 : swapExpectation.candidateSlot;
        SlotSnapshot anchor = context.inventory.slot(anchorSlot);
        if (anchor == null) {
            diagnose(DiagnosticClass.TAKEOVER_INVENTORY_UNSAFE,
                    DiagnosticReason.TAKEOVER_INVENTORY_UNSAFE, context);
            return oneEffect(intentEffect(declineIntent(request), false));
        }
        for (ToolCandidate candidate : ToolCandidateOrder.sort(context.inventory.candidates(), cycleSelectors)) {
            if (candidate.slot() == anchorSlot || candidate.slot() == oldCandidate
                    || !candidate.isUsableInHand()) continue;
            SlotSnapshot next = context.inventory.slot(candidate.slot());
            SlotSnapshot old = oldCandidate < 0 ? null : context.inventory.slot(oldCandidate);
            if (next == null || next.isEmpty() || oldCandidate >= 0 && old == null) continue;
            takeoverExpectation = new TakeoverExpectation(anchorSlot, oldCandidate, candidate.slot(),
                    anchor, old, next, context.tick);
            AutoToolSwapIntent intent = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                    round.serverRoundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER,
                    anchorSlot, candidate.slot(), anchor.contentFingerprint(), next.contentFingerprint());
            pendingAction = AutoToolSwapAction.TAKEOVER;
            return oneEffect(intentEffect(intent, false));
        }
        diagnose(DiagnosticClass.TAKEOVER_NO_CANDIDATE, DiagnosticReason.TAKEOVER_NO_CANDIDATE, context);
        return oneEffect(intentEffect(declineIntent(request), false));
    }

    private AutoToolSwapIntent declineIntent(AutoToolSwapTakeoverRequest request) {
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        pendingAction = AutoToolSwapAction.DECLINE_TAKEOVER;
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, round.serverRoundId,
                request.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER, anchorSlot, anchorSlot,
                empty, empty);
    }

    private List<Effect> drive() {
        if (round == null || !round.accepted || transmission != null || round.inFlight != null) return noEffects();
        if (pendingTakeoverRequest != null && (closeRequested || !cycleTakeoverEnabled)
                && takeoverExpectation == null) {
            diagnose(!cycleTakeoverEnabled ? DiagnosticClass.TAKEOVER_DISABLED
                            : DiagnosticClass.TAKEOVER_REQUEST_IGNORED,
                    !cycleTakeoverEnabled ? DiagnosticReason.TAKEOVER_DISABLED
                            : DiagnosticReason.TAKEOVER_REQUEST_IGNORED,
                    lastContext);
            return oneEffect(intentEffect(declineIntent(pendingTakeoverRequest), false));
        }
        if (closeRequested) {
            if (swapExpectation != null && pendingAction != AutoToolSwapAction.RESTORE
                    && pendingAction != AutoToolSwapAction.ABANDON) {
                prepareRestore(closeDiagnosticReason == null
                        ? DiagnosticReason.PROTOCOL_ORPHAN : closeDiagnosticReason);
            }
            if (pendingAction == AutoToolSwapAction.RESTORE) return captureAction(AutoToolSwapAction.RESTORE);
            if (pendingAction == AutoToolSwapAction.ABANDON) return beginAbandonIntent();
            if (swapExpectation == null) return beginControlIntent(AutoToolSwapAction.CLOSE);
            return noEffects();
        }
        if (freezeRequested && pendingAction == null) {
            if (!round.closing && freezeAlreadyRequestedOrSettled()) {
                freezeRequested = false;
                state = State.FROZEN;
                return noEffects();
            }
            return beginControlIntent(AutoToolSwapAction.FREEZE);
        }
        if (pendingAction == AutoToolSwapAction.SWAP) return captureAction(AutoToolSwapAction.SWAP);
        if (pendingAction == AutoToolSwapAction.RESTORE) return captureAction(AutoToolSwapAction.RESTORE);
        if (pendingAction == AutoToolSwapAction.ABANDON) return beginAbandonIntent();
        return noEffects();
    }

    private List<Effect> captureAction(AutoToolSwapAction action) {
        if (swapExpectation == null) return noEffects();
        AutoToolSwapIntent marker = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                round.serverRoundId, round.nextActionSequence, action, swapExpectation.anchorSlot,
                swapExpectation.candidateSlot, AutoToolSwapContentFingerprint.canonicalEmpty(),
                AutoToolSwapContentFingerprint.canonicalEmpty());
        return oneEffect(new Effect(Effect.Type.CAPTURE, ToolSwapCapturePlan.PROTECTED,
                swapExpectation.anchorSlot, swapExpectation.candidateSlot, 0L, marker,
                false, false, clientTick, 0, 0));
    }

    private List<Effect> beginControlIntent(AutoToolSwapAction action) {
        AutoToolSwapIntent intent = beginIntent(action, AutoToolSwapProtocol.INVENTORY_FIRST_SLOT,
                AutoToolSwapProtocol.INVENTORY_FIRST_SLOT, null, null);
        return intent == null ? handleUnavailableAction(action) : oneEffect(intentEffect(intent, false));
    }

    /** 使用 ledger 对应双槽和 canonical control 指纹创建零库存写的放弃请求。 */
    private List<Effect> beginAbandonIntent() {
        if (swapExpectation == null) {
            orphan();
            return noEffects();
        }
        AutoToolSwapIntent intent = beginIntent(AutoToolSwapAction.ABANDON,
                swapExpectation.anchorSlot, swapExpectation.candidateSlot, null, null);
        return intent == null ? handleUnavailableAction(AutoToolSwapAction.ABANDON)
                : oneEffect(intentEffect(intent, false));
    }

    private AutoToolSwapIntent beginIntent(AutoToolSwapAction action, int actionAnchor, int actionCandidate,
            SlotSnapshot anchor, SlotSnapshot candidate) {
        if (!allowsAction(action) || round.inFlight != null || transmission != null
                || round.nextActionSequence == Long.MAX_VALUE) {
            if (round.nextActionSequence == Long.MAX_VALUE) orphan();
            return null;
        }
        AutoToolSwapContentFingerprint anchorFingerprint;
        AutoToolSwapContentFingerprint candidateFingerprint;
        if (action == AutoToolSwapAction.FREEZE || action == AutoToolSwapAction.CLOSE
                || action == AutoToolSwapAction.ABANDON) {
            anchorFingerprint = AutoToolSwapContentFingerprint.canonicalEmpty();
            candidateFingerprint = AutoToolSwapContentFingerprint.canonicalEmpty();
        } else if (anchor == null || candidate == null) {
            return null;
        } else {
            anchorFingerprint = anchor.contentFingerprint();
            candidateFingerprint = candidate.contentFingerprint();
        }
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, round.serverRoundId,
                round.nextActionSequence, action, actionAnchor, actionCandidate,
                anchorFingerprint, candidateFingerprint);
    }

    private boolean preflight(AutoToolSwapAction action, ToolSwapContext context) {
        if (context == null || !context.inventoryTransactionSafe || swapExpectation == null
                || action != pendingAction) {
            if (action == AutoToolSwapAction.SWAP) discardUnstartedSwap(lastTick());
            return false;
        }
        ToolSwapInventorySnapshot inventory = context.inventory;
        if (!hasTrustedProtectedSlots(inventory)) return false;
        if (action == AutoToolSwapAction.RESTORE) {
            if (swapExpectation.matchesSwapped(inventory, generation)) {
                swapExpectation.captureRestoreTarget(inventory);
                return true;
            }
            pendingAction = AutoToolSwapAction.ABANDON;
            return false;
        }
        if (context.targetIdentity.isPresent()
                && !context.targetIdentity.equals(swapExpectation.targetIdentity)) {
            latestTarget = context.targetIdentity;
            consecutiveAbsentTargetTicks = 0;
            discardUnstartedSwapForTargetChange(context.tick);
            return false;
        }
        if (swapExpectation.matchesStrictRestored(inventory, generation)) return true;
        discardUnstartedSwap(context.tick);
        return false;
    }

    private void settleProductAction(AutoToolSwapAction action, AutoToolSwapResultCode result,
            AutoToolSwapRoundState serverState) {
        if (action == AutoToolSwapAction.TAKEOVER) {
            if (result == AutoToolSwapResultCode.APPLIED && takeoverExpectation != null) {
                takeoverExpectation.verifying = true;
                takeoverExpectation.verifyStartedTick = clientTick;
                pendingAction = null;
            } else {
                takeoverExpectation = null;
                pendingTakeoverRequest = null;
                pendingAction = null;
                state = State.FROZEN;
            }
            return;
        }
        if (action == AutoToolSwapAction.DECLINE_TAKEOVER) {
            pendingTakeoverRequest = null;
            takeoverExpectation = null;
            pendingAction = null;
            state = State.FROZEN;
            return;
        }
        if (action == AutoToolSwapAction.SWAP) {
            if (result == AutoToolSwapResultCode.APPLIED) beginInventoryVerify(action);
            else if (result == AutoToolSwapResultCode.REJECTED) {
                discardUnstartedSwap(lastTick());
                if (serverState == AutoToolSwapRoundState.FROZEN) {
                    freezeRequested = false;
                    state = State.FROZEN;
                } else if (serverState == AutoToolSwapRoundState.CLOSING) {
                    freezeRequested = false;
                    requestClose(CloseCause.RELEASE_GATED, DiagnosticReason.PROTOCOL_ORPHAN);
                } else if (serverState != AutoToolSwapRoundState.OPEN) orphan();
            } else orphan();
            return;
        }
        if (action == AutoToolSwapAction.RESTORE) {
            if (result == AutoToolSwapResultCode.APPLIED) {
                consecutiveRestoreRejections = 0;
                beginInventoryVerify(action);
            } else if (result == AutoToolSwapResultCode.REJECTED) {
                if (++consecutiveRestoreRejections >= MAX_CONSECUTIVE_RESTORE_REJECTIONS) orphan();
                else {
                    pendingAction = AutoToolSwapAction.RESTORE;
                    state = State.RESTORING;
                }
            } else orphan();
            return;
        }
        if (action == AutoToolSwapAction.FREEZE
                && (result == AutoToolSwapResultCode.ACCEPTED || result == AutoToolSwapResultCode.APPLIED)) {
            pendingAction = null;
            freezeRequested = false;
            clearPreparingTargetTracking();
            if (serverState == AutoToolSwapRoundState.CLOSING) {
                requestClose(CloseCause.RELEASE_GATED, DiagnosticReason.PROTOCOL_ORPHAN);
                return;
            }
            state = State.FROZEN;
            return;
        }
        if (action == AutoToolSwapAction.FREEZE && result == AutoToolSwapResultCode.REJECTED
                && serverState == AutoToolSwapRoundState.CLOSING) {
            pendingAction = null;
            freezeRequested = false;
            requestClose(CloseCause.RELEASE_GATED, DiagnosticReason.PROTOCOL_ORPHAN);
            return;
        }
        if (action == AutoToolSwapAction.CLOSE) {
            if (result == AutoToolSwapResultCode.RESTORE_REQUIRED) {
                pendingAction = AutoToolSwapAction.RESTORE;
                state = State.RESTORING;
            } else if (result == AutoToolSwapResultCode.ACCEPTED || result == AutoToolSwapResultCode.APPLIED) {
                pendingAction = null;
                finishClose(serverState == AutoToolSwapRoundState.FINISHED);
            } else orphan();
            return;
        }
        if (action == AutoToolSwapAction.ABANDON) {
            if (result == AutoToolSwapResultCode.ACCEPTED
                    && serverState == AutoToolSwapRoundState.FINISHED) {
                finishAbandon();
            } else {
                orphan();
            }
            return;
        }
        orphan();
    }

    private void observeInventory(ToolSwapInventorySnapshot inventory, long tick) {
        if (!isInventorySyncPending()) return;
        long elapsed = tick - swapExpectation.verifyStartedTick;
        if (!hasTrustedProtectedSlots(inventory)) {
            if (elapsed >= TRANSACTION_TIMEOUT_TICKS) orphan();
            return;
        }
        boolean target = swapExpectation.verifyingAction == AutoToolSwapAction.SWAP
                ? swapExpectation.matchesSwapped(inventory, generation)
                : swapExpectation.matchesRestored(inventory, generation);
        boolean source = swapExpectation.verifyingAction == AutoToolSwapAction.SWAP
                ? swapExpectation.matchesStrictRestored(inventory, generation)
                : swapExpectation.matchesSwapped(inventory, generation);
        if (target) {
            AutoToolSwapAction verified = swapExpectation.verifyingAction;
            swapExpectation.verifyingAction = null;
            pendingAction = null;
            if (verified == AutoToolSwapAction.SWAP) {
                finishSwap();
            } else {
                swapExpectation = null;
                finishRestore();
            }
        } else if (!source || elapsed >= TRANSACTION_TIMEOUT_TICKS) orphan();
    }

    /** APPLIED 后验证双槽/三槽目标布局，再滚动唯一可逆 ledger 期望。 */
    private void observeTakeoverInventory(ToolSwapInventorySnapshot inventory, long tick) {
        TakeoverExpectation expected = takeoverExpectation;
        if (expected == null || !expected.verifying) return;
        long elapsed = tick - expected.verifyStartedTick;
        if (inventory == null || !inventory.isTrusted() || !inventory.covers(expected.anchorSlot)
                || !inventory.covers(expected.newCandidateSlot)
                || expected.oldCandidateSlot >= 0 && !inventory.covers(expected.oldCandidateSlot)) {
            if (elapsed >= TRANSACTION_TIMEOUT_TICKS) orphan();
            return;
        }
        boolean target = expected.matchesTarget(inventory);
        boolean source = expected.matchesSource(inventory);
        if (target) {
            SlotSnapshot originalAnchor = swapExpectation == null
                    ? expected.anchorBefore : swapExpectation.anchorRole;
            swapExpectation = new SwapExpectation(generation, expected.anchorSlot,
                    expected.newCandidateSlot, originalAnchor, expected.newCandidateBefore,
                    ToolSwapTargetIdentity.present(pendingTakeoverRequest.targetBlockId(),
                            pendingTakeoverRequest.targetBlockMetadata()));
            takeoverExpectation = null;
            pendingTakeoverRequest = null;
            pendingAction = null;
            state = State.FROZEN;
        } else if (!source || elapsed >= TRANSACTION_TIMEOUT_TICKS) {
            orphan();
        }
    }

    private List<Effect> retryOrOrphan() {
        if (transmission == null) return noEffects();
        long elapsed = clientTick - transmission.sentTick;
        if (elapsed >= TRANSACTION_TIMEOUT_TICKS) {
            orphan();
            return noEffects();
        }
        if (!transmission.retransmitted && elapsed >= RETRANSMIT_TICKS) {
            Effect original = transmission.effect;
            Effect retry = new Effect(original.type, original.capturePlan, original.anchorSlot,
                    original.candidateSlot, original.clientNonce, original.intent, true,
                    original.freshKeyAfterSubmit, clientTick,
                    original.targetBlockId, original.targetBlockMetadata);
            return oneEffect(retry);
        }
        return noEffects();
    }

    private void requestFreeze(long eventGeneration) {
        if (eventGeneration != generation || state == State.IDLE || state == State.WAIT_RELEASE
                || state == State.ORPHANED) return;
        clearPreparingTargetTracking();
        if (pendingAction == AutoToolSwapAction.SWAP && round != null && round.inFlight == null
                && transmission == null) discardUnstartedSwap(lastTick());
        if (round != null && !round.closing && freezeAlreadyRequestedOrSettled()) {
            freezeRequested = false;
            if (pendingAction == null) state = State.FROZEN;
            return;
        }
        freezeRequested = true;
        if (pendingAction == null) state = State.FROZEN;
    }

    /** 同一 round 已有有效 FREEZE 时只投影本地冻结态，不再分配新动作序列。 */
    private boolean freezeAlreadyRequestedOrSettled() {
        if (round == null) return false;
        if (round.serverRoundState == AutoToolSwapRoundState.FROZEN) return true;
        if (round.inFlight != null && round.inFlight.action() == AutoToolSwapAction.FREEZE) return true;
        if (round.lastSettlementIntent == null || round.lastSettlementResult == null
                || round.lastSettlementIntent.action() != AutoToolSwapAction.FREEZE) return false;
        AutoToolSwapResultCode outcome = round.lastSettlementResult.outcome();
        return outcome == AutoToolSwapResultCode.ACCEPTED || outcome == AutoToolSwapResultCode.APPLIED;
    }

    private void requestClose(CloseCause requestedCause, DiagnosticReason reason) {
        diagnose(closeDiagnosticClass(reason), reason, lastContext);
        if (requestedCause == CloseCause.RELEASE_GATED) deferredRoundPending = false;
        if (state == State.IDLE || state == State.ORPHANED) return;
        closeRequested = true;
        if (requestedCause == CloseCause.RELEASE_GATED || closeCause == CloseCause.NONE) closeCause = requestedCause;
        if (requestedCause == CloseCause.RELEASE_GATED || closeDiagnosticReason == null) {
            closeDiagnosticReason = reason;
        }
        rematchAfterRestore = false;
        targetRematchPending = false;
        targetRestoreRequested = false;
        consecutiveAbsentTargetTicks = 0;
        pendingAnchor = null;
        if (swapExpectation != null && !isActionPending() && !isInventorySyncPending()) prepareRestore(reason);
    }

    private void markRoundClosing() {
        if (round != null) round.closing = true;
    }

    private void prepareRestore(DiagnosticReason reason) {
        if (swapExpectation == null || pendingAction == AutoToolSwapAction.SWAP && isActionPending()) return;
        if (pendingAction == AutoToolSwapAction.ABANDON) return;
        if (pendingAction == AutoToolSwapAction.SWAP && !isActionPending()) {
            discardUnstartedSwap(lastTick());
            return;
        }
        restoreReason = reason;
        targetRestoreRequested = reason == DiagnosticReason.TARGET_CHANGED;
        state = State.RESTORING;
        if (!isActionPending() && !isInventorySyncPending()) {
            diagnose(prepareRestoreDiagnosticClass(reason), reason, lastContext);
            pendingAction = AutoToolSwapAction.RESTORE;
        }
    }

    private void finishSwap() {
        if (closeRequested || rematchAfterRestore || targetRematchPending || pendingAnchor != null
                || lastContext != null && lastContext.guiOpen) {
            DiagnosticReason reason = closeRequested ? closeDiagnosticReason
                    : pendingAnchor != null ? DiagnosticReason.SELECTED_SLOT_REANCHOR
                    : lastContext != null && lastContext.guiOpen ? DiagnosticReason.GUI_OPEN
                    : targetRematchPending ? DiagnosticReason.TARGET_CHANGED : restoreReason;
            if (reason == null) reason = DiagnosticReason.PROTOCOL_ORPHAN;
            diagnose(DiagnosticClass.FINISH_SWAP_RESTORE, reason, lastContext);
            prepareRestore(reason);
        } else state = freezeRequested ? State.FROZEN : State.PREPARING;
    }

    private void finishRestore() {
        boolean finishedTargetRestore = targetRestoreRequested;
        if (closeRequested) {
            state = State.RESTORING;
            pendingAction = null;
            return;
        }
        if (pendingAnchor != null) {
            anchorSlot = pendingAnchor.intValue();
            pendingAnchor = null;
        }
        if (freezeRequested) state = State.FROZEN;
        else {
            state = State.PREPARING;
            if (finishedTargetRestore) {
                matchedTarget = null;
                targetRematchPending = latestTarget.isPresent();
                nextMatchTick = nextTick(lastTick());
            } else if (rematchAfterRestore && lastContext != null && !lastContext.guiOpen) {
                nextMatchTick = lastContext.tick;
            }
        }
        rematchAfterRestore = false;
        targetRestoreRequested = false;
        restoreReason = null;
    }

    private void finishClose(boolean exactlyFinished) {
        boolean naturalRearm = exactlyFinished && closeCause == CloseCause.NATURAL_REARM
                && keyDown && configuredEnabled;
        deferredRoundPending = naturalRearm;
        state = naturalRearm || !keyDown ? State.IDLE : State.WAIT_RELEASE;
        swapExpectation = null;
        resetCycleFlags();
    }

    /** ABANDON 成功即终止旧 round；仅自然 IDLE 保留受限的 deferred rearm。 */
    private void finishAbandon() {
        boolean naturalRearm = closeCause == CloseCause.NATURAL_REARM && keyDown && configuredEnabled;
        deferredRoundPending = naturalRearm;
        state = naturalRearm || !keyDown ? State.IDLE : State.WAIT_RELEASE;
        round = null;
        transmission = null;
        swapExpectation = null;
        pendingAction = null;
        resetCycleFlags();
    }

    private void finishWaitRelease() {
        state = State.IDLE;
        round = null;
        transmission = null;
        swapExpectation = null;
        pendingAction = null;
        deferredRoundPending = false;
        resetCycleFlags();
    }

    private void requestReanchor(int newAnchor) {
        if (state != State.PREPARING && state != State.FROZEN) return;
        if (swapExpectation != null || pendingAction != null) {
            pendingAnchor = Integer.valueOf(newAnchor);
            rematchAfterRestore = state == State.PREPARING;
            prepareRestore(DiagnosticReason.SELECTED_SLOT_REANCHOR);
        } else {
            anchorSlot = newAnchor;
            nextMatchTick = lastTick();
        }
    }

    private void beginInventoryVerify(AutoToolSwapAction action) {
        if (swapExpectation == null) {
            orphan();
            return;
        }
        swapExpectation.verifyingAction = action;
        swapExpectation.verifyStartedTick = clientTick;
        pendingAction = null;
    }

    private void discardUnstartedSwap(long tick) {
        if (pendingAction != AutoToolSwapAction.SWAP || isActionPending()) return;
        pendingAction = null;
        swapExpectation = null;
        rematchAfterRestore = false;
        if (pendingAnchor != null) {
            anchorSlot = pendingAnchor.intValue();
            pendingAnchor = null;
        }
        nextMatchTick = advanceWatermark(nextMatchTick, tick);
        if (!closeRequested && !freezeRequested) state = State.PREPARING;
    }

    /** 目标变化丢弃未提交 SWAP，并把 FULL 资格放到下一 END tick。 */
    private void discardUnstartedSwapForTargetChange(long tick) {
        if (pendingAction != AutoToolSwapAction.SWAP || isActionPending()
                || isInventorySyncPending() || transmission != null) return;
        pendingAction = null;
        swapExpectation = null;
        rematchAfterRestore = false;
        targetRematchPending = latestTarget.isPresent();
        matchedTarget = latestTarget.isPresent() ? matchedTarget : null;
        nextMatchTick = nextTick(tick);
        if (!closeRequested && !freezeRequested) state = State.PREPARING;
    }

    private List<Effect> handleUnavailableAction(AutoToolSwapAction action) {
        if (state == State.ORPHANED) return noEffects();
        if (action == AutoToolSwapAction.SWAP) discardUnstartedSwap(lastTick());
        if (action == AutoToolSwapAction.ABANDON) {
            orphan();
            return noEffects();
        }
        if (action == AutoToolSwapAction.FREEZE && round != null && round.closing) {
            freezeRequested = false;
            requestClose(CloseCause.RELEASE_GATED, DiagnosticReason.PROTOCOL_ORPHAN);
            return drive();
        }
        return noEffects();
    }

    private boolean allowsAction(AutoToolSwapAction action) {
        if (round == null || !round.accepted || action == null || state == State.ORPHANED) return false;
        if (action == AutoToolSwapAction.SWAP || action == AutoToolSwapAction.FREEZE) {
            return !round.closing;
        }
        if (action == AutoToolSwapAction.TAKEOVER) return !round.closing && pendingTakeoverRequest != null;
        if (action == AutoToolSwapAction.DECLINE_TAKEOVER) return pendingTakeoverRequest != null;
        return action == AutoToolSwapAction.RESTORE || action == AutoToolSwapAction.CLOSE
                || action == AutoToolSwapAction.ABANDON;
    }

    private boolean isDuplicateSettlement(ActionResultEvent event, AutoToolSwapAction action,
            AutoToolSwapRoundResult result) {
        if (round.lastSettlementIntent == null || round.lastSettlementResult == null) return false;
        AutoToolSwapIntent intent = round.lastSettlementIntent;
        return intent.serverRoundId() == event.serverRoundId && intent.actionSequence() == event.actionSequence
                && intent.action() == action && intent.anchorSlot() == event.anchorSlot
                && intent.candidateSlot() == event.candidateSlot && round.lastSettlementResult.equals(result);
    }

    private void onRoundRejected() {
        pendingAction = null;
        pendingTakeoverRequest = null;
        takeoverExpectation = null;
        swapExpectation = null;
        deferredRoundPending = false;
        state = keyDown ? State.WAIT_RELEASE : State.IDLE;
        resetCycleFlags();
    }

    private void orphan() {
        diagnose(DiagnosticClass.PROTOCOL_ORPHAN, DiagnosticReason.PROTOCOL_ORPHAN, lastContext);
        state = State.ORPHANED;
        if (round != null) round.inFlight = null;
        transmission = null;
        pendingAction = null;
        deferredRoundPending = false;
        resetCycleFlags();
    }

    private void reset() {
        generation = incrementGeneration(generation);
        state = State.IDLE;
        keyDown = false;
        anchorSlot = 0;
        nextMatchTick = 0L;
        round = null;
        transmission = null;
        pendingAction = null;
        swapExpectation = null;
        pendingTakeoverRequest = null;
        takeoverExpectation = null;
        lastContext = null;
        consecutiveRestoreRejections = 0;
        deferredRoundPending = false;
        latestTarget = ToolSwapTargetIdentity.ABSENT;
        matchedTarget = null;
        consecutiveAbsentTargetTicks = 0;
        targetRematchPending = false;
        targetRestoreRequested = false;
        clearPreEdgeDestroyLatch();
        resetCycleFlags();
    }

    private void resetCycleFlags() {
        cycleEnabled = false;
        cycleTakeoverEnabled = false;
        cycleSelectors = Collections.emptyList();
        freezeRequested = false;
        closeRequested = false;
        closeCause = CloseCause.NONE;
        rematchAfterRestore = false;
        pendingAnchor = null;
        restoreReason = null;
        closeDiagnosticReason = null;
        clearPreparingTargetTracking();
    }

    /** 清除只属于首块前 PREPARING 的目标重匹配资格。 */
    private void clearPreparingTargetTracking() {
        latestTarget = ToolSwapTargetIdentity.ABSENT;
        matchedTarget = null;
        consecutiveAbsentTargetTicks = 0;
        targetRematchPending = false;
        targetRestoreRequested = false;
    }

    private boolean hasTrustedProtectedSlots(ToolSwapInventorySnapshot inventory) {
        return inventory != null && inventory.isTrusted() && swapExpectation != null
                && inventory.covers(swapExpectation.anchorSlot) && inventory.covers(swapExpectation.candidateSlot);
    }

    private boolean needsProtectedCapture() {
        return swapExpectation != null || pendingAction == AutoToolSwapAction.SWAP
                || pendingAction == AutoToolSwapAction.RESTORE;
    }

    private void remember(ToolSwapContext context) {
        lastContext = context;
    }

    private void clearPreEdgeDestroyLatch() {
        preEdgeDestroyTick = -1L;
        preEdgeWorldGeneration = -1L;
    }

    private long lastTick() {
        return lastContext == null ? clientTick : lastContext.tick;
    }

    /**
     * 记录一次不读取运行态对象的有界原因诊断。
     *
     * @param diagnosticClass 每个 round 只允许出现一次的诊断类别
     * @param reason 固定原因
     * @param context 已由调用链捕获的上下文，可为空
     */
    private void diagnose(DiagnosticClass diagnosticClass, DiagnosticReason reason, ToolSwapContext context) {
        diagnose(diagnosticClass, reason, context, "none", 0L, 0L, "none");
    }

    /** 记录带协议边界原始值的有界原因诊断。 */
    private void diagnose(DiagnosticClass diagnosticClass, DiagnosticReason reason, ToolSwapContext context,
            String boundary, long incomingRoundId, long incomingSequence, String incomingValue) {
        if (diagnosticClass == null || reason == null || diagnosticMessageCount >= MAX_DIAGNOSTIC_MESSAGES
                || emittedDiagnosticClasses.contains(diagnosticClass)) return;
        emittedDiagnosticClasses.add(diagnosticClass);
        diagnosticMessageCount++;
        ToolSwapContext captured = context == null ? lastContext : context;
        int selected = captured == null ? -1 : captured.selectedHotbarSlot;
        boolean guiOpen = captured != null && captured.guiOpen;
        boolean breakCapable = captured != null && captured.breakCapable;
        boolean creative = captured != null && captured.creative;
        boolean chainActive = captured != null && captured.chainActive;
        boolean inventoryTransactionSafe = captured != null && captured.inventoryTransactionSafe;
        boolean fullCandidateScan = captured != null && captured.inventory.isFullCandidateScan();
        long nonce = round == null ? 0L : round.clientNonce;
        long roundId = round == null ? 0L : round.serverRoundId;
        String phase = round == null || round.lastPhase == null ? "none" : round.lastPhase.name();
        long phaseSequence = round == null ? 0L : round.lastPhaseSequence;
        String serverState = round == null || round.serverRoundState == null
                ? "none" : round.serverRoundState.name();
        String message = "[AutoToolSwapClientDiag] reason=" + reason.wireName
                + " class=" + diagnosticClass.name()
                + " clientTick=" + clientTick
                + " nonce=" + nonce
                + " serverRoundId=" + roundId
                + " state=" + state
                + " keyDown=" + keyDown
                + " freezeRequested=" + freezeRequested
                + " closeRequested=" + closeRequested
                + " closeCause=" + closeCause
                + " pendingAction=" + (pendingAction == null ? "none" : pendingAction.name())
                + " anchorSlot=" + anchorSlot
                + " selectedHotbarSlot=" + selected
                + " pendingAnchor=" + (pendingAnchor == null ? -1 : pendingAnchor.intValue())
                + " guiOpen=" + guiOpen
                + " breakCapable=" + breakCapable
                + " creative=" + creative
                + " chainActive=" + chainActive
                + " inventoryTransactionSafe=" + inventoryTransactionSafe
                + " fullCandidateScan=" + fullCandidateScan
                + " round.phase=" + phase
                + " round.serverState=" + serverState
                + " round.lastPhaseSequence=" + phaseSequence
                + " boundary=" + boundary
                + " incomingRoundId=" + incomingRoundId
                + " incomingSequence=" + incomingSequence
                + " incomingValue=" + incomingValue;
        try {
            diagnosticSink.log(message);
        } catch (RuntimeException ignored) {
            // 探针失败不得改变 reducer 行为。
        } catch (LinkageError ignored) {
            // 日志实现不可用时同样静默降级。
        }
    }

    private static DiagnosticClass closeDiagnosticClass(DiagnosticReason reason) {
        if (reason == DiagnosticReason.RELEASE) return DiagnosticClass.REQUEST_CLOSE_RELEASE;
        if (reason == DiagnosticReason.NATURAL_IDLE) return DiagnosticClass.REQUEST_CLOSE_NATURAL_IDLE;
        if (reason == DiagnosticReason.CONFIG_DISABLED) return DiagnosticClass.REQUEST_CLOSE_CONFIG_DISABLED;
        return DiagnosticClass.REQUEST_CLOSE_PROTOCOL;
    }

    private static DiagnosticClass prepareRestoreDiagnosticClass(DiagnosticReason reason) {
        if (reason == DiagnosticReason.GUI_OPEN) return DiagnosticClass.PREPARE_RESTORE_GUI;
        if (reason == DiagnosticReason.SELECTED_SLOT_REANCHOR) {
            return DiagnosticClass.PREPARE_RESTORE_REANCHOR;
        }
        if (reason == DiagnosticReason.RELEASE) return DiagnosticClass.PREPARE_RESTORE_RELEASE;
        if (reason == DiagnosticReason.NATURAL_IDLE) return DiagnosticClass.PREPARE_RESTORE_NATURAL_IDLE;
        if (reason == DiagnosticReason.CONFIG_DISABLED) return DiagnosticClass.PREPARE_RESTORE_CONFIG_DISABLED;
        if (reason == DiagnosticReason.TARGET_CHANGED) return DiagnosticClass.PREPARE_RESTORE_TARGET_CHANGED;
        return DiagnosticClass.PREPARE_RESTORE_PROTOCOL;
    }

    private Effect roundEffect(long nonce, boolean retry, boolean freshKeyAfterSubmit) {
        return new Effect(Effect.Type.BEGIN_ROUND, ToolSwapCapturePlan.NONE, 0, 0,
                nonce, null, retry, freshKeyAfterSubmit, clientTick, 0, 0);
    }

    private Effect intentEffect(AutoToolSwapIntent intent, boolean retry) {
        return new Effect(Effect.Type.SEND_INTENT, ToolSwapCapturePlan.NONE,
                intent.anchorSlot(), intent.candidateSlot(), 0L, intent, retry, false, clientTick, 0, 0);
    }

    private Effect freshKeyEffect() {
        return new Effect(Effect.Type.FRESH_KEY, ToolSwapCapturePlan.NONE, 0, 0,
                0L, null, false, false, clientTick, 0, 0);
    }

    private static List<Effect> noEffects() {
        return Collections.emptyList();
    }

    private static List<Effect> oneEffect(Effect effect) {
        return Collections.singletonList(effect);
    }

    private static List<Effect> immutableEffects(List<Effect> effects) {
        return effects.isEmpty() ? noEffects()
                : Collections.unmodifiableList(new ArrayList<Effect>(effects));
    }

    private static long advanceWatermark(long previous, long current) {
        long next = previous;
        do {
            if (next > Long.MAX_VALUE - MATCH_INTERVAL_TICKS) return Long.MAX_VALUE;
            next += MATCH_INTERVAL_TICKS;
        } while (next <= current);
        return next;
    }

    private static long nextTick(long tick) {
        return tick == Long.MAX_VALUE ? Long.MAX_VALUE : tick + 1L;
    }

    private static long incrementGeneration(long value) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException("cycle generation overflow");
        return value + 1L;
    }

    private static List<ToolSelector> immutableSelectors(List<ToolSelector> selectors) {
        return Collections.unmodifiableList(new ArrayList<ToolSelector>(
                selectors == null ? Collections.<ToolSelector>emptyList() : selectors));
    }

    /** CLOSE 归因；释放门一旦出现便单调覆盖自然重武装。 */
    private enum CloseCause { NONE, NATURAL_REARM, RELEASE_GATED }

    /** 探针原因使用固定、可检索的 wire 名称，不参与业务判定。 */
    private enum DiagnosticReason {
        GUI_OPEN("gui-open"),
        SELECTED_SLOT_REANCHOR("selected-slot-reanchor"),
        RELEASE("release"),
        NATURAL_IDLE("natural-idle"),
        CONFIG_DISABLED("config-disabled"),
        CYCLE_DISABLED("cycle-disabled"),
        CYCLE_INELIGIBLE("cycle-ineligible"),
        HELD_USABLE("held-usable"),
        NO_CANDIDATE("no-candidate"),
        TAKEOVER_DISABLED("takeover-disabled"),
        TAKEOVER_INVENTORY_UNSAFE("takeover-inventory-unsafe"),
        TAKEOVER_NO_CANDIDATE("takeover-no-candidate"),
        TAKEOVER_REQUEST_IGNORED("takeover-request-ignored"),
        TARGET_CHANGED("target-changed"),
        PROTOCOL_ORPHAN("protocol-orphan"),
        ROUND_PHASE("round-phase"),
        LOCAL_DESTROY("local-destroy"),
        ACTION_RESULT_RESTORE("action-result-restore"),
        ACTION_RESULT_ABANDON("action-result-abandon"),
        ACTION_RESULT_CLOSE("action-result-close");

        private final String wireName;

        private DiagnosticReason(String wireName) {
            this.wireName = wireName;
        }
    }

    /** 每个 round 各自限频的诊断类别。 */
    private enum DiagnosticClass {
        ADVANCE_GUI,
        ADVANCE_REANCHOR,
        CYCLE_DISABLED,
        CYCLE_INELIGIBLE,
        HELD_USABLE,
        NO_CANDIDATE,
        TAKEOVER_DISABLED,
        TAKEOVER_INVENTORY_UNSAFE,
        TAKEOVER_NO_CANDIDATE,
        TAKEOVER_REQUEST_IGNORED,
        REQUEST_CLOSE_RELEASE,
        REQUEST_CLOSE_NATURAL_IDLE,
        REQUEST_CLOSE_CONFIG_DISABLED,
        REQUEST_CLOSE_PROTOCOL,
        PREPARE_RESTORE_GUI,
        PREPARE_RESTORE_REANCHOR,
        PREPARE_RESTORE_RELEASE,
        PREPARE_RESTORE_NATURAL_IDLE,
        PREPARE_RESTORE_CONFIG_DISABLED,
        PREPARE_RESTORE_TARGET_CHANGED,
        PREPARE_RESTORE_PROTOCOL,
        FINISH_SWAP_RESTORE,
        ROUND_PHASE_ACTIVE,
        ROUND_PHASE_IDLE,
        ROUND_PHASE_IGNORED,
        LOCAL_DESTROY,
        ACTION_RESULT_RESTORE,
        ACTION_RESULT_ABANDON,
        ACTION_RESULT_CLOSE,
        ACTION_RESULT_IGNORED,
        PROTOCOL_ORPHAN
    }

    /** 当前服务端 round 的全部客户端关联身份。 */
    private static final class RoundContext {
        private final long clientNonce;
        private long serverRoundId;
        private long nextActionSequence = AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE;
        private long lastPhaseSequence;
        private ChainPhase lastPhase;
        private boolean accepted;
        private boolean closing;
        private AutoToolSwapRoundState serverRoundState;
        private AutoToolSwapRoundResult acceptedResult;
        private AutoToolSwapIntent inFlight;
        private AutoToolSwapIntent lastSettlementIntent;
        private AutoToolSwapRoundResult lastSettlementResult;
        private int chainGeneration = -1;

        private RoundContext(long clientNonce) {
            this.clientNonce = clientNonce;
        }
    }

    /** 单一可逆库存期望，含 APPLIED 后的原版库存可见性门。 */
    private static final class SwapExpectation {
        private final long generation;
        private final int anchorSlot;
        private final int candidateSlot;
        private final SlotSnapshot anchorRole;
        private final SlotSnapshot candidateRole;
        private final ToolSwapTargetIdentity targetIdentity;
        private SlotSnapshot restoreAnchorRole;
        private AutoToolSwapAction verifyingAction;
        private long verifyStartedTick;

        private SwapExpectation(long generation, int anchorSlot, int candidateSlot,
                SlotSnapshot anchorRole, SlotSnapshot candidateRole,
                ToolSwapTargetIdentity targetIdentity) {
            if (targetIdentity == null || !targetIdentity.isPresent()) {
                throw new IllegalArgumentException("swap target identity must be present");
            }
            this.generation = generation;
            this.anchorSlot = anchorSlot;
            this.candidateSlot = candidateSlot;
            this.anchorRole = anchorRole;
            this.candidateRole = candidateRole;
            this.targetIdentity = targetIdentity;
        }

        private boolean matchesSwapped(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            return generation == currentGeneration && activeToolRoleMatches(candidateRole, inventory.slot(anchorSlot))
                    && (anchorRole.isEmpty() || anchorRole.sameRole(inventory.slot(candidateSlot)));
        }

        private boolean matchesRestored(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            SlotSnapshot expectedAnchor = restoreAnchorRole == null ? anchorRole : restoreAnchorRole;
            return generation == currentGeneration && expectedAnchor.sameRole(inventory.slot(anchorSlot))
                    && activeToolRoleMatches(candidateRole, inventory.slot(candidateSlot));
        }

        private boolean matchesStrictRestored(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            return generation == currentGeneration && !candidateRole.isEmpty()
                    && anchorRole.sameContent(inventory.slot(anchorSlot))
                    && candidateRole.sameContent(inventory.slot(candidateSlot));
        }

        private static boolean activeToolRoleMatches(SlotSnapshot expected, SlotSnapshot observed) {
            return expected.sameRole(observed) || observed != null && observed.isEmpty();
        }

        /** 固化 RESTORE 请求捕获当刻将交换到主手的真实角色。 */
        private void captureRestoreTarget(ToolSwapInventorySnapshot inventory) {
            restoreAnchorRole = inventory.slot(candidateSlot);
        }
    }

    /** 一次接替在客户端可见性门中的双槽/三槽布局期望。 */
    private static final class TakeoverExpectation {
        private final int anchorSlot;
        private final int oldCandidateSlot;
        private final int newCandidateSlot;
        private final SlotSnapshot anchorBefore;
        private final SlotSnapshot oldCandidateBefore;
        private final SlotSnapshot newCandidateBefore;
        private boolean verifying;
        private long verifyStartedTick;

        private TakeoverExpectation(int anchorSlot, int oldCandidateSlot, int newCandidateSlot,
                SlotSnapshot anchorBefore, SlotSnapshot oldCandidateBefore,
                SlotSnapshot newCandidateBefore, long createdTick) {
            this.anchorSlot = anchorSlot;
            this.oldCandidateSlot = oldCandidateSlot;
            this.newCandidateSlot = newCandidateSlot;
            this.anchorBefore = anchorBefore;
            this.oldCandidateBefore = oldCandidateBefore;
            this.newCandidateBefore = newCandidateBefore;
            this.verifyStartedTick = createdTick;
        }

        private boolean matchesSource(ToolSwapInventorySnapshot inventory) {
            return anchorBefore.sameContent(inventory.slot(anchorSlot))
                    && newCandidateBefore.sameContent(inventory.slot(newCandidateSlot))
                    && (oldCandidateSlot < 0
                            || oldCandidateBefore.sameContent(inventory.slot(oldCandidateSlot)));
        }

        private boolean matchesTarget(ToolSwapInventorySnapshot inventory) {
            if (!newCandidateBefore.sameRole(inventory.slot(anchorSlot))) return false;
            if (oldCandidateSlot < 0) {
                return anchorBefore.sameRole(inventory.slot(newCandidateSlot));
            }
            return anchorBefore.sameRole(inventory.slot(oldCandidateSlot))
                    && oldCandidateBefore.sameRole(inventory.slot(newCandidateSlot));
        }
    }

    /** 唯一等待回包或允许一次重传的发送状态。 */
    private static final class PendingTransmission {
        private final Effect effect;
        private final long sentTick;
        private final boolean retransmitted;

        private PendingTransmission(Effect effect, long sentTick, boolean retransmitted) {
            this.effect = effect;
            this.sentTick = sentTick;
            this.retransmitted = retransmitted;
        }

        private boolean matches(Effect candidate) {
            return effect.type == candidate.type && effect.clientNonce == candidate.clientNonce
                    && effect.intent == candidate.intent;
        }

        private PendingTransmission markRetransmitted() {
            return new PendingTransmission(effect, sentTick, true);
        }
    }

    /** 进程级 nonce 分配器，Long.MAX_VALUE 后永久耗尽且不回绕。 */
    private static final class ProcessNonceAllocator implements NonceAllocator {
        private final AtomicLong nextNonce = new AtomicLong(AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE);

        @Override
        public long allocate() {
            for (;;) {
                long candidate = nextNonce.get();
                if (candidate <= 0L) return 0L;
                long following = candidate == Long.MAX_VALUE ? 0L : candidate + 1L;
                if (nextNonce.compareAndSet(candidate, following)) return candidate;
            }
        }
    }
}
