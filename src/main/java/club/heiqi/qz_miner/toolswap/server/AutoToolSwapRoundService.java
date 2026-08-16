package club.heiqi.qz_miner.toolswap.server;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapActionResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/**
 * 严格 5.3 自动工具 wire projection/control facade。
 *
 * <p>本服务只拥有 round nonce、普通 action sequence、exact result cache、phase 与 closure。
 * 库存 mutation 和 physical ledger 始终由 {@link AutoToolSwapServerBatchService} 独占。</p>
 */
public final class AutoToolSwapRoundService {

    public static final long NO_PHASE_SEQUENCE = 0L;

    private static final RoundIdAllocator PROCESS_ROUND_ID_ALLOCATOR =
            new RoundIdAllocator(AutoToolSwapProtocol.NO_SERVER_ROUND_ID);
    private static final DiagnosticSink PRODUCTION_DIAGNOSTIC_SINK = new DiagnosticSink() {
        @Override public void log(String message) { MyMod.LOG.debug(message); }
    };
    private static final DiagnosticSink NO_DIAGNOSTIC_SINK = new DiagnosticSink() {
        @Override public void log(String message) { }
    };

    private final Map<UUID, RoundRecord> rounds = new HashMap<UUID, RoundRecord>();
    private final RoundIdAllocator roundIdAllocator;
    private final long firstActionSequence;
    private final long firstPhaseSequence;
    private final DiagnosticSink diagnosticSink;

    public AutoToolSwapRoundService() {
        this(PROCESS_ROUND_ID_ALLOCATOR, AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                NO_PHASE_SEQUENCE, PRODUCTION_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(long initialRoundCounter) {
        this(new RoundIdAllocator(initialRoundCounter), AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(long initialRoundCounter, DiagnosticSink diagnosticSink) {
        this(new RoundIdAllocator(initialRoundCounter), AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                NO_PHASE_SEQUENCE, diagnosticSink);
    }

    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence) {
        this(new RoundIdAllocator(initialRoundCounter), firstActionSequence,
                NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence, long firstPhaseSequence) {
        this(new RoundIdAllocator(initialRoundCounter), firstActionSequence,
                firstPhaseSequence, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(RoundIdAllocator allocator, long firstActionSequence) {
        this(allocator, firstActionSequence, NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    private AutoToolSwapRoundService(RoundIdAllocator allocator, long firstActionSequence,
            long firstPhaseSequence, DiagnosticSink diagnosticSink) {
        if (allocator == null || firstActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE
                || firstPhaseSequence < NO_PHASE_SEQUENCE || diagnosticSink == null) {
            throw new IllegalArgumentException("round allocator and sequence counters must be valid");
        }
        this.roundIdAllocator = allocator;
        this.firstActionSequence = firstActionSequence;
        this.firstPhaseSequence = firstPhaseSequence;
        this.diagnosticSink = diagnosticSink;
    }

    /** 建立尚未分配服务端 round id 的 PENDING projection。 */
    public synchronized AutoToolSwapRoundResult beginRound(UUID playerId, Object endpoint, long clientNonce,
            long serverTick) {
        requireServerTick(serverTick);
        if (playerId == null || endpoint == null || clientNonce == 0L) {
            return detached(AutoToolSwapProtocol.NO_SERVER_ROUND_ID, AutoToolSwapRoundState.PENDING_KEY,
                    AutoToolSwapResultCode.REJECTED, firstActionSequence, serverTick);
        }
        RoundRecord existing = rounds.get(playerId);
        if (existing != null && (!isTerminal(existing.state) || existing.pendingPublication != null)) {
            if (!existing.matchesEndpoint(endpoint) || existing.clientNonce != clientNonce) {
                return result(existing, AutoToolSwapResultCode.REJECTED, serverTick);
            }
            return existing.state == AutoToolSwapRoundState.PENDING_KEY && existing.beginResult != null
                    ? existing.beginResult : result(existing, AutoToolSwapResultCode.ACCEPTED, serverTick);
        }
        RoundRecord created = new RoundRecord(endpoint, clientNonce, firstActionSequence, firstPhaseSequence);
        rounds.put(playerId, created);
        created.beginResult = result(created, AutoToolSwapResultCode.ACCEPTED, serverTick);
        return created.beginResult;
    }

    /** 为匹配 endpoint 的 PENDING projection 分配不可复用 round id。 */
    public synchronized AutoToolSwapRoundResult activatePendingRound(UUID playerId, Object endpoint,
            long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint)) {
            return detached(AutoToolSwapProtocol.NO_SERVER_ROUND_ID, AutoToolSwapRoundState.PENDING_KEY,
                    AutoToolSwapResultCode.REJECTED, firstActionSequence, serverTick);
        }
        if (record.activationResult != null) return record.activationResult;
        if (record.state != AutoToolSwapRoundState.PENDING_KEY) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        long roundId = roundIdAllocator.nextRoundId();
        if (roundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID) {
            orphan(record);
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        record.serverRoundId = roundId;
        record.state = AutoToolSwapRoundState.OPEN;
        record.keyDown = true;
        record.activationResult = result(record, AutoToolSwapResultCode.ACCEPTED, serverTick);
        return record.activationResult;
    }

    public synchronized AutoToolSwapRoundSnapshot snapshot(UUID playerId) {
        return snapshotOf(rounds.get(playerId));
    }

    public synchronized AutoToolSwapRoundSnapshot snapshot(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        return record != null && record.matchesEndpoint(endpoint) ? snapshotOf(record) : null;
    }

    public synchronized long currentRoundId(UUID playerId) { return currentRoundIdOf(rounds.get(playerId)); }

    public synchronized long currentRoundId(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        return record != null && record.matchesEndpoint(endpoint)
                ? currentRoundIdOf(record) : AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
    }

    public synchronized long nextPhaseSequence(UUID playerId, Object endpoint, long serverRoundId) {
        return observeChainPhase(playerId, endpoint, serverRoundId, false, false);
    }

    /** 投影阶段、冻结和关闭，不触碰 local physical owner。 */
    public synchronized long observeChainPhase(UUID playerId, Object endpoint, long serverRoundId,
            boolean freezeSwap, boolean closeRound) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.serverRoundId == 0L
                || record.serverRoundId != serverRoundId || isTerminal(record.state)) return NO_PHASE_SEQUENCE;
        if (freezeSwap && record.state == AutoToolSwapRoundState.OPEN) record.state = AutoToolSwapRoundState.FROZEN;
        if (closeRound) {
            record.keyDown = false;
            record.state = AutoToolSwapRoundState.CLOSING;
        }
        if (record.phaseSequence == Long.MAX_VALUE) {
            orphan(record);
            return NO_PHASE_SEQUENCE;
        }
        return ++record.phaseSequence;
    }

    /** 松键终结 wire projection；local finalizer 已由调用方的独立服务端屏障先完成。 */
    public synchronized long onKeyReleased(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint)) return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        record.keyDown = false;
        if (record.state != AutoToolSwapRoundState.PENDING_KEY && record.state != AutoToolSwapRoundState.ORPHANED) {
            record.state = AutoToolSwapRoundState.FINISHED;
        }
        return record.serverRoundId;
    }

    /** 只结算严格 5.3 的 FREEZE/CLOSE control intent；inventory 参数永不读取。 */
    public synchronized AutoToolSwapRoundResult handleIntent(UUID playerId, Object endpoint,
            AutoToolSwapIntent intent, AutoToolSwapInventoryPort inventory, long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || endpoint == null || intent == null || !record.matchesEndpoint(endpoint)
                || intent.protocolVersion() != AutoToolSwapProtocol.PROTOCOL_VERSION
                || record.serverRoundId == 0L || intent.serverRoundId() != record.serverRoundId) {
            return rejectedForMissingOrMismatched(record, intent, serverTick);
        }
        if (record.lastActionResult != null && record.lastActionResult.intent().equals(intent)) {
            return record.lastActionResult.roundResult();
        }
        if (record.pendingPublication != null) {
            return record.pendingPublication.intent.equals(intent) ? record.pendingPublication.roundResult
                    : result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        if (intent.actionSequence() != record.nextActionSequence || record.nextActionSequence == Long.MAX_VALUE) {
            if (record.nextActionSequence == Long.MAX_VALUE) orphan(record);
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }

        AutoToolSwapResultCode outcome = AutoToolSwapResultCode.REJECTED;
        if (intent.action() == AutoToolSwapAction.FREEZE) {
            if (record.state == AutoToolSwapRoundState.OPEN || record.state == AutoToolSwapRoundState.FROZEN) {
                record.state = AutoToolSwapRoundState.FROZEN;
                outcome = AutoToolSwapResultCode.ACCEPTED;
            }
        } else if (intent.action() == AutoToolSwapAction.CLOSE) {
            // PacketKeyState(false) may have already finished the projection; CLOSE remains idempotently accepted.
            if (record.state != AutoToolSwapRoundState.PENDING_KEY
                    && record.state != AutoToolSwapRoundState.ORPHANED) {
                record.keyDown = false;
                record.state = AutoToolSwapRoundState.FINISHED;
                outcome = AutoToolSwapResultCode.ACCEPTED;
            }
        }
        AutoToolSwapRoundResult published = detached(record.serverRoundId, record.state, outcome,
                record.nextActionSequence + 1L, serverTick);
        record.pendingPublication = new PendingPublication(intent, published);
        logIntent(playerId, record, intent, outcome);
        return published;
    }

    /** sender 正常返回后的 exact publication 确认点。 */
    public synchronized boolean confirmIntentResultPublication(UUID playerId, Object endpoint,
            AutoToolSwapIntent intent, AutoToolSwapRoundResult publishedResult) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || intent == null || publishedResult == null) {
            return false;
        }
        if (record.lastActionResult != null && record.lastActionResult.intent().equals(intent)
                && record.lastActionResult.roundResult().equals(publishedResult)) return true;
        PendingPublication pending = record.pendingPublication;
        if (pending == null || !pending.intent.equals(intent) || !pending.roundResult.equals(publishedResult)) {
            return false;
        }
        record.nextActionSequence = publishedResult.nextActionSequence();
        record.lastActionResult = new AutoToolSwapActionResult(intent, publishedResult);
        record.pendingPublication = null;
        return true;
    }

    public synchronized void cleanup(UUID playerId) { rounds.remove(playerId); }
    public synchronized void clearAll() { rounds.clear(); }

    private AutoToolSwapRoundResult rejectedForMissingOrMismatched(RoundRecord record,
            AutoToolSwapIntent intent, long serverTick) {
        if (record != null) return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        long roundId = intent == null ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : intent.serverRoundId();
        return detached(roundId, AutoToolSwapRoundState.ORPHANED, AutoToolSwapResultCode.REJECTED,
                firstActionSequence, serverTick);
    }

    private void logIntent(UUID playerId, RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapResultCode outcome) {
        try {
            diagnosticSink.log("[AutoToolSwapDiag] projection player=" + playerId + " round="
                    + record.serverRoundId + " actionSeq=" + intent.actionSequence() + " action="
                    + intent.action() + " state=" + record.state + " outcome=" + outcome + " inventory=unread");
        } catch (RuntimeException ignored) {
        } catch (LinkageError ignored) {
        }
    }

    private static boolean isTerminal(AutoToolSwapRoundState state) {
        return state == AutoToolSwapRoundState.FINISHED || state == AutoToolSwapRoundState.ORPHANED;
    }

    private static void orphan(RoundRecord record) {
        record.state = AutoToolSwapRoundState.ORPHANED;
        record.keyDown = false;
    }

    private static long currentRoundIdOf(RoundRecord record) {
        return record == null || record.serverRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || isTerminal(record.state) && record.pendingPublication == null
                        ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : record.serverRoundId;
    }

    private static AutoToolSwapRoundResult result(RoundRecord record, AutoToolSwapResultCode outcome,
            long serverTick) {
        return detached(record.serverRoundId, record.state, outcome, record.nextActionSequence, serverTick);
    }

    private static AutoToolSwapRoundResult detached(long roundId, AutoToolSwapRoundState state,
            AutoToolSwapResultCode outcome, long nextActionSequence, long serverTick) {
        return new AutoToolSwapRoundResult(roundId, outcome, state, nextActionSequence, serverTick);
    }

    private static AutoToolSwapRoundSnapshot snapshotOf(RoundRecord record) {
        return record == null ? null : new AutoToolSwapRoundSnapshot(record.clientNonce, record.serverRoundId,
                record.state, record.nextActionSequence, record.phaseSequence, record.keyDown,
                record.pendingPublication != null);
    }

    private static void requireServerTick(long serverTick) {
        if (serverTick < 0L) throw new IllegalArgumentException("serverTick must not be negative");
    }

    interface DiagnosticSink { void log(String message); }

    static final class RoundIdAllocator {
        private long lastIssuedRoundId;

        RoundIdAllocator(long initialRoundCounter) {
            if (initialRoundCounter < AutoToolSwapProtocol.NO_SERVER_ROUND_ID) {
                throw new IllegalArgumentException("round counter must be non-negative");
            }
            lastIssuedRoundId = initialRoundCounter;
        }

        synchronized long nextRoundId() {
            return lastIssuedRoundId == Long.MAX_VALUE
                    ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : ++lastIssuedRoundId;
        }
    }

    private static final class RoundRecord {
        private final WeakReference<Object> endpointReference;
        private final long clientNonce;
        private long serverRoundId;
        private AutoToolSwapRoundState state = AutoToolSwapRoundState.PENDING_KEY;
        private long nextActionSequence;
        private long phaseSequence;
        private boolean keyDown;
        private AutoToolSwapRoundResult beginResult;
        private AutoToolSwapRoundResult activationResult;
        private AutoToolSwapActionResult lastActionResult;
        private PendingPublication pendingPublication;

        private RoundRecord(Object endpoint, long clientNonce, long firstActionSequence, long firstPhaseSequence) {
            endpointReference = new WeakReference<Object>(endpoint);
            this.clientNonce = clientNonce;
            nextActionSequence = firstActionSequence;
            phaseSequence = firstPhaseSequence;
        }

        private boolean matchesEndpoint(Object endpoint) {
            return endpoint != null && endpointReference.get() == endpoint;
        }
    }

    private static final class PendingPublication {
        private final AutoToolSwapIntent intent;
        private final AutoToolSwapRoundResult roundResult;

        private PendingPublication(AutoToolSwapIntent intent, AutoToolSwapRoundResult roundResult) {
            this.intent = intent;
            this.roundResult = roundResult;
        }
    }
}
