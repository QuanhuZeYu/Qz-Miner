package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 严格 5.2 自动工具客户端 round/projection reducer；不读取或推断客户端库存。 */
public final class AutoToolSwapClientReducer {

    public static final int TRANSMISSION_DEADLINE_TICKS = 120;
    public static final int RETRANSMIT_TICKS = 20;

    private static final NonceAllocator PROCESS_NONCE_ALLOCATOR = new ProcessNonceAllocator();
    private static final AutoToolSwapClientProtocolValidator VALIDATOR = new AutoToolSwapClientProtocolValidator();

    public enum State { IDLE, PREPARING, ACTIVE, WAIT_RELEASE, ORPHANED }

    public abstract static class Event { private Event() { } }

    public static final class KeyStateEvent extends Event {
        private final boolean down;
        private final ToolSwapLightContext context;
        public KeyStateEvent(boolean down, ToolSwapLightContext context) {
            this.down = down;
            this.context = context;
        }
    }

    public static final class TickEvent extends Event {
        private final ToolSwapLightContext context;
        private final boolean physicallyDown;
        public TickEvent(ToolSwapLightContext context, boolean physicallyDown) {
            this.context = context;
            this.physicallyDown = physicallyDown;
        }
    }

    public static final class ConfigEvent extends Event {
        private final boolean enabled;
        public ConfigEvent(boolean enabled) { this.enabled = enabled; }
    }

    public static final class LocalBlockDestroyedEvent extends Event {
        private final boolean physicallyDownAndWorldActive;
        public LocalBlockDestroyedEvent(boolean active) { physicallyDownAndWorldActive = active; }
    }

    public static final class RoundResultEvent extends Event {
        private final int protocolVersion;
        private final long clientNonce;
        private final long serverRoundId;
        private final int resultCode;
        private final int roundState;
        private final long nextActionSequence;
        private final long serverTick;
        private final boolean rawValid;
        public RoundResultEvent(int protocolVersion, long clientNonce, long serverRoundId, int resultCode,
                int roundState, long nextActionSequence, long serverTick, boolean rawValid) {
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
        public ActionResultEvent(int protocolVersion, long serverRoundId, long actionSequence, int actionCode,
                int resultCode, int roundState, int anchorSlot, int candidateSlot, long nextActionSequence,
                long serverTick, boolean rawValid) {
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

    public static final class RoundPhaseEvent extends Event {
        private final int protocolVersion;
        private final long serverRoundId;
        private final long phaseSequence;
        private final int phaseOrdinal;
        private final int generation;
        private final long serverTick;
        private final boolean rawValid;
        public RoundPhaseEvent(int protocolVersion, long serverRoundId, long phaseSequence, int phaseOrdinal,
                int generation, long serverTick, boolean rawValid) {
            this.protocolVersion = protocolVersion;
            this.serverRoundId = serverRoundId;
            this.phaseSequence = phaseSequence;
            this.phaseOrdinal = phaseOrdinal;
            this.generation = generation;
            this.serverTick = serverTick;
            this.rawValid = rawValid;
        }
    }

    public static final class EffectResultEvent extends Event {
        private final Effect effect;
        private final boolean submitted;
        public EffectResultEvent(Effect effect, boolean submitted) {
            this.effect = effect;
            this.submitted = submitted;
        }
    }

    public static final class ResetEvent extends Event { public ResetEvent() { } }

    public static final class Effect {
        public enum Type { BEGIN_ROUND, SEND_INTENT, FRESH_KEY }
        private final Type type;
        private final long clientNonce;
        private final AutoToolSwapIntent intent;
        private final boolean retry;
        private final boolean freshKeyAfterSubmit;
        private final long createdTick;

        private Effect(Type type, long clientNonce, AutoToolSwapIntent intent, boolean retry,
                boolean freshKeyAfterSubmit, long createdTick) {
            this.type = type;
            this.clientNonce = clientNonce;
            this.intent = intent;
            this.retry = retry;
            this.freshKeyAfterSubmit = freshKeyAfterSubmit;
            this.createdTick = createdTick;
        }

        public Type type() { return type; }
        public long clientNonce() { return clientNonce; }
        public AutoToolSwapIntent intent() { return intent; }
        public boolean retry() { return retry; }
    }

    interface NonceAllocator { long allocate(); }

    private final NonceAllocator nonceAllocator;
    private boolean configuredEnabled;
    private boolean keyDown;
    private long clientTick;
    private long generation;
    private int anchorSlot;
    private State state = State.IDLE;
    private RoundContext round;
    private PendingTransmission transmission;
    private boolean freezeRequested;
    private boolean closeRequested;
    private boolean rearmAfterClose;

    public AutoToolSwapClientReducer(boolean enabled) { this(enabled, PROCESS_NONCE_ALLOCATOR); }

    AutoToolSwapClientReducer(boolean enabled, NonceAllocator nonceAllocator) {
        if (nonceAllocator == null) throw new IllegalArgumentException("nonceAllocator must not be null");
        configuredEnabled = enabled;
        this.nonceAllocator = nonceAllocator;
    }

    public List<Effect> reduce(Event event) {
        if (event == null) return noEffects();
        if (event instanceof KeyStateEvent) return onKeyState((KeyStateEvent) event);
        if (event instanceof TickEvent) return onTick((TickEvent) event);
        if (event instanceof ConfigEvent) return onConfig((ConfigEvent) event);
        if (event instanceof LocalBlockDestroyedEvent) return onLocalDestroy((LocalBlockDestroyedEvent) event);
        if (event instanceof RoundResultEvent) return onRoundResult((RoundResultEvent) event);
        if (event instanceof ActionResultEvent) return onActionResult((ActionResultEvent) event);
        if (event instanceof RoundPhaseEvent) return onRoundPhase((RoundPhaseEvent) event);
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
    public boolean isRoundPending() { return round != null && !round.accepted; }
    public boolean isActionPending() { return round != null && round.inFlight != null; }
    public boolean isOrphaned() { return state == State.ORPHANED; }
    public long pendingNonce() { return round == null ? 0L : round.clientNonce; }
    public long serverRoundId() { return round == null ? 0L : round.serverRoundId; }
    public long nextActionSequence() {
        return round == null ? AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE : round.nextActionSequence;
    }
    public long lastPhaseSequence() { return round == null ? 0L : round.lastPhaseSequence; }
    public AutoToolSwapRoundState serverRoundState() { return round == null ? null : round.serverRoundState; }
    public AutoToolSwapIntent inFlightIntent() { return round == null ? null : round.inFlight; }
    public boolean chainActive() { return keyDown && state != State.WAIT_RELEASE && state != State.ORPHANED; }

    private List<Effect> onKeyState(KeyStateEvent event) {
        if (event.down == keyDown) return noEffects();
        keyDown = event.down;
        if (!event.down) {
            rearmAfterClose = false;
            if (state == State.WAIT_RELEASE || state == State.ORPHANED) {
                state = State.IDLE;
                return noEffects();
            }
            if (round != null) closeRequested = true;
            return noEffects();
        }
        if (state != State.IDLE || event.context == null) return noEffects();
        return startRound(event.context, false);
    }

    private List<Effect> onTick(TickEvent event) {
        ArrayList<Effect> effects = new ArrayList<Effect>();
        if (!event.physicallyDown && keyDown) {
            keyDown = false;
            rearmAfterClose = false;
            if (state == State.ORPHANED) state = State.IDLE;
            else if (round != null) closeRequested = true;
        }
        if (rearmAfterClose && round == null && keyDown && event.physicallyDown && event.context != null) {
            rearmAfterClose = false;
            effects.addAll(startRound(event.context, true));
        } else {
            Effect retry = retryOrOrphan();
            if (retry != null) effects.add(retry);
            if (effects.isEmpty()) {
                Effect control = nextControlEffect();
                if (control != null) effects.add(control);
            }
        }
        if (clientTick != Long.MAX_VALUE) clientTick++;
        return immutable(effects);
    }

    private List<Effect> onConfig(ConfigEvent event) {
        configuredEnabled = event.enabled;
        if (!event.enabled && round != null) {
            closeRequested = true;
            rearmAfterClose = false;
        }
        return noEffects();
    }

    private List<Effect> onLocalDestroy(LocalBlockDestroyedEvent event) {
        if (event.physicallyDownAndWorldActive && round != null && round.accepted) freezeRequested = true;
        return noEffects();
    }

    private List<Effect> onRoundResult(RoundResultEvent event) {
        AutoToolSwapClientProtocolValidator.ValidatedRoundResult validated = VALIDATOR.validateRoundResult(
                event.protocolVersion, event.serverRoundId, event.resultCode, event.roundState,
                event.nextActionSequence, event.serverTick, event.rawValid);
        if (validated == null || round == null || round.accepted || event.clientNonce != round.clientNonce) {
            return noEffects();
        }
        AutoToolSwapRoundResult result = validated.result();
        if (result.outcome() != AutoToolSwapResultCode.ACCEPTED
                || result.serverRoundId() == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || result.roundState() == AutoToolSwapRoundState.PENDING_KEY
                || result.roundState() == AutoToolSwapRoundState.FINISHED
                || result.roundState() == AutoToolSwapRoundState.ORPHANED) {
            orphan();
            return noEffects();
        }
        transmission = null;
        round.accepted = true;
        round.serverRoundId = result.serverRoundId();
        round.nextActionSequence = result.nextActionSequence();
        round.serverRoundState = result.roundState();
        state = State.ACTIVE;
        if (!closeRequested) freezeRequested = result.roundState() != AutoToolSwapRoundState.FROZEN;
        return noEffects();
    }

    private List<Effect> onActionResult(ActionResultEvent event) {
        AutoToolSwapClientProtocolValidator.ValidatedActionResult validated = VALIDATOR.validateActionResult(
                event.protocolVersion, event.serverRoundId, event.actionSequence, event.actionCode,
                event.resultCode, event.roundState, event.anchorSlot, event.candidateSlot,
                event.nextActionSequence, event.serverTick, event.rawValid);
        if (validated == null || round == null || round.inFlight == null) return noEffects();
        AutoToolSwapIntent intent = round.inFlight;
        AutoToolSwapRoundResult result = validated.result();
        if (event.serverRoundId != round.serverRoundId || event.actionSequence != intent.actionSequence()
                || validated.action() != intent.action() || event.anchorSlot != intent.anchorSlot()
                || event.candidateSlot != intent.candidateSlot()
                || event.nextActionSequence != intent.actionSequence() + 1L) return noEffects();
        if (result.outcome() != AutoToolSwapResultCode.ACCEPTED) {
            orphan();
            return noEffects();
        }
        round.inFlight = null;
        transmission = null;
        round.nextActionSequence = result.nextActionSequence();
        round.serverRoundState = result.roundState();
        if (intent.action() == AutoToolSwapAction.FREEZE) {
            freezeRequested = false;
            if (result.roundState() != AutoToolSwapRoundState.FROZEN) orphan();
        } else {
            if (result.roundState() != AutoToolSwapRoundState.FINISHED) {
                orphan();
            } else {
                round = null;
                closeRequested = false;
                state = State.IDLE;
            }
        }
        return noEffects();
    }

    private List<Effect> onRoundPhase(RoundPhaseEvent event) {
        AutoToolSwapClientProtocolValidator.ValidatedPhase phase = VALIDATOR.validatePhase(event.protocolVersion,
                event.serverRoundId, event.phaseSequence, event.phaseOrdinal, event.generation,
                event.serverTick, event.rawValid);
        if (phase == null || round == null || !round.accepted || phase.serverRoundId() != round.serverRoundId
                || phase.phaseSequence() <= round.lastPhaseSequence) return noEffects();
        round.lastPhaseSequence = phase.phaseSequence();
        if (phase.phase() == ChainPhase.IDLE) {
            closeRequested = true;
            rearmAfterClose = keyDown;
        } else if (phase.phase() == ChainPhase.PLANNING || phase.phase() == ChainPhase.RUNNING
                || phase.phase() == ChainPhase.FINISHING) {
            freezeRequested = true;
        }
        return noEffects();
    }

    private List<Effect> onEffectResult(EffectResultEvent event) {
        if (event.effect == null || event.effect.type == Effect.Type.FRESH_KEY) return noEffects();
        if (!event.submitted) {
            orphan();
            return noEffects();
        }
        if (event.effect.retry) {
            if (transmission != null && transmission.matches(event.effect)) {
                transmission.lastSubmittedTick = clientTick;
            }
        } else {
            transmission = new PendingTransmission(event.effect, clientTick);
            if (event.effect.type == Effect.Type.SEND_INTENT && round != null) {
                round.inFlight = event.effect.intent;
            }
        }
        return event.effect.freshKeyAfterSubmit ? one(freshKeyEffect()) : noEffects();
    }

    private List<Effect> startRound(ToolSwapLightContext context, boolean freshKey) {
        generation = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        if (!configuredEnabled || !context.breakCapable || context.creative || !context.chainActive) {
            state = State.WAIT_RELEASE;
            return noEffects();
        }
        long nonce = nonceAllocator.allocate();
        if (nonce <= 0L) {
            orphan();
            return noEffects();
        }
        anchorSlot = context.selectedHotbarSlot;
        round = new RoundContext(nonce);
        freezeRequested = true;
        closeRequested = false;
        state = State.PREPARING;
        return one(new Effect(Effect.Type.BEGIN_ROUND, nonce, null, false, freshKey, clientTick));
    }

    private Effect nextControlEffect() {
        if (round == null || !round.accepted || round.inFlight != null || transmission != null) return null;
        AutoToolSwapAction action = closeRequested ? AutoToolSwapAction.CLOSE
                : freezeRequested ? AutoToolSwapAction.FREEZE : null;
        if (action == null) return null;
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent intent = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                round.serverRoundId, round.nextActionSequence, action, anchorSlot, anchorSlot, empty, empty);
        return new Effect(Effect.Type.SEND_INTENT, 0L, intent, false, false, clientTick);
    }

    private Effect retryOrOrphan() {
        if (transmission == null) return null;
        if (clientTick - transmission.firstSubmittedTick >= TRANSMISSION_DEADLINE_TICKS) {
            orphan();
            return null;
        }
        if (clientTick - transmission.lastSubmittedTick < RETRANSMIT_TICKS) return null;
        Effect old = transmission.effect;
        return new Effect(old.type, old.clientNonce, old.intent, true, false, clientTick);
    }

    private void orphan() {
        state = State.ORPHANED;
        round = null;
        transmission = null;
        freezeRequested = false;
        closeRequested = false;
        rearmAfterClose = false;
    }

    private void reset() {
        keyDown = false;
        clientTick = 0L;
        generation = 0L;
        anchorSlot = 0;
        state = State.IDLE;
        round = null;
        transmission = null;
        freezeRequested = false;
        closeRequested = false;
        rearmAfterClose = false;
    }

    private static Effect freshKeyEffect() {
        return new Effect(Effect.Type.FRESH_KEY, 0L, null, false, false, 0L);
    }

    private static List<Effect> noEffects() { return Collections.emptyList(); }
    private static List<Effect> one(Effect effect) { return Collections.singletonList(effect); }
    private static List<Effect> immutable(List<Effect> effects) {
        return effects.isEmpty() ? noEffects() : Collections.unmodifiableList(effects);
    }

    private static final class RoundContext {
        private final long clientNonce;
        private boolean accepted;
        private long serverRoundId;
        private long nextActionSequence = AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE;
        private long lastPhaseSequence;
        private AutoToolSwapRoundState serverRoundState;
        private AutoToolSwapIntent inFlight;
        private RoundContext(long clientNonce) { this.clientNonce = clientNonce; }
    }

    private static final class PendingTransmission {
        private final Effect effect;
        private final long firstSubmittedTick;
        private long lastSubmittedTick;
        private PendingTransmission(Effect effect, long tick) {
            this.effect = effect;
            firstSubmittedTick = tick;
            lastSubmittedTick = tick;
        }
        private boolean matches(Effect other) {
            return effect.type == other.type && effect.clientNonce == other.clientNonce && effect.intent == other.intent;
        }
    }

    private static final class ProcessNonceAllocator implements NonceAllocator {
        private static final AtomicLong LAST = new AtomicLong(seed());
        @Override public long allocate() {
            for (;;) {
                long current = LAST.get();
                long next = current == Long.MAX_VALUE ? 1L : current + 1L;
                if (LAST.compareAndSet(current, next)) return next;
            }
        }
        private static long seed() {
            long value = System.nanoTime() ^ System.currentTimeMillis();
            value &= Long.MAX_VALUE;
            return value == 0L ? 1L : value;
        }
    }
}
