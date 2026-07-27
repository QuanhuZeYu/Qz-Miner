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
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;

/**
 * v4 工具换位 wire projection/control facade。
 *
 * <p>本服务只拥有 round、普通 action sequence、takeover request watermark、exact result cache、
 * phase 与 closure。它不读取库存、不执行库存 mutation，也不持有 physical ledger；普通
 * CHAIN/AREA 的唯一库存 owner 是 {@link AutoToolSwapServerBatchService}。</p>
 */
public final class AutoToolSwapRoundService {

    public static final long NO_PHASE_SEQUENCE = 0L;

    /** 仅供旧 surface 观察的 dormant takeover gate。 */
    public enum TakeoverGateState { WAITING, APPLIED, DECLINED, SKIP_TARGET, STOP }

    /** 旧空手 fallback surface 的纯投影结果。 */
    public enum EmptyHandFallbackLeaseMatch { ABSENT, MATCH, INVALIDATED }

    public static final class EmptyHandFallbackLeaseMatchResult {
        private final EmptyHandFallbackLeaseMatch outcome;
        private final EmptyHandFallbackLeaseToken token;

        private EmptyHandFallbackLeaseMatchResult(EmptyHandFallbackLeaseMatch outcome,
                EmptyHandFallbackLeaseToken token) {
            this.outcome = outcome;
            this.token = token;
        }

        public EmptyHandFallbackLeaseMatch outcome() { return outcome; }
        public EmptyHandFallbackLeaseToken token() { return token; }
    }

    /** 旧 pure-value lease 的 compare-and-clear token；不携带库存写权。 */
    public static final class EmptyHandFallbackLeaseToken {
        private final Object endpointIdentity;
        private final long serverRoundId;
        private final int generation;
        private final long leaseId;
        private final EmptyHandFallbackLease leaseIdentity;

        private EmptyHandFallbackLeaseToken(Object endpointIdentity, long serverRoundId, int generation,
                long leaseId, EmptyHandFallbackLease leaseIdentity) {
            this.endpointIdentity = endpointIdentity;
            this.serverRoundId = serverRoundId;
            this.generation = generation;
            this.leaseId = leaseId;
            this.leaseIdentity = leaseIdentity;
        }
    }

    /** 旧 fallback 使用的不含坐标目标能力键。 */
    public static final class TargetCapabilityKey {
        private final int blockId;
        private final int metadata;

        private TargetCapabilityKey(int blockId, int metadata) {
            if (blockId <= 0 || blockId > AutoToolSwapProtocol.MAX_BLOCK_ID
                    || metadata < 0 || metadata > AutoToolSwapProtocol.MAX_BLOCK_METADATA) {
                throw new IllegalArgumentException("target capability key is outside the supported domain");
            }
            this.blockId = blockId;
            this.metadata = metadata;
        }

        public static TargetCapabilityKey of(int blockId, int metadata) {
            return new TargetCapabilityKey(blockId, metadata);
        }

        public boolean sameCapability(TargetCapabilityKey other) {
            return other != null && blockId == other.blockId && metadata == other.metadata;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof TargetCapabilityKey
                    && sameCapability((TargetCapabilityKey) other);
        }

        @Override
        public int hashCode() { return 31 * blockId + metadata; }
    }

    /** 旧 fallback 使用的 36 槽 pure-value 身份；不授予 mutation 权限。 */
    public static final class InventoryFingerprint {
        private final AutoToolSwapStackState[] slots;

        private InventoryFingerprint(AutoToolSwapStackState[] slots) {
            if (slots == null || slots.length != AutoToolSwapProtocol.INVENTORY_SLOT_COUNT) {
                throw new IllegalArgumentException("inventory fingerprint must contain exactly 36 slots");
            }
            this.slots = new AutoToolSwapStackState[slots.length];
            for (int slot = 0; slot < slots.length; slot++) {
                if (slots[slot] == null) {
                    throw new IllegalArgumentException("inventory fingerprint slots must not be null");
                }
                this.slots[slot] = slots[slot];
            }
        }

        public static InventoryFingerprint fromSlots(AutoToolSwapStackState[] slots) {
            return new InventoryFingerprint(slots);
        }

        public AutoToolSwapStackState slot(int inventorySlot) {
            if (!AutoToolSwapProtocol.isInventorySlot(inventorySlot)) {
                throw new IllegalArgumentException("inventorySlot must be 0..35");
            }
            return slots[inventorySlot];
        }

        public boolean sameInventory(InventoryFingerprint other) {
            if (other == null) return false;
            for (int slot = 0; slot < slots.length; slot++) {
                if (!slots[slot].sameContent(other.slots[slot])) return false;
            }
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof InventoryFingerprint
                    && sameInventory((InventoryFingerprint) other);
        }

        @Override
        public int hashCode() {
            int value = 1;
            for (AutoToolSwapStackState slot : slots) value = 31 * value + slot.hashCode();
            return value;
        }
    }

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
    private final long firstTakeoverRequestId;
    private final long firstPhaseSequence;
    private final DiagnosticSink diagnosticSink;
    private long lastEmptyHandFallbackLeaseId;

    public AutoToolSwapRoundService() {
        this(PROCESS_ROUND_ID_ALLOCATOR, AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                AutoToolSwapProtocol.FIRST_TAKEOVER_REQUEST_ID, NO_PHASE_SEQUENCE,
                PRODUCTION_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(long initialRoundCounter) {
        this(new RoundIdAllocator(initialRoundCounter), AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                AutoToolSwapProtocol.FIRST_TAKEOVER_REQUEST_ID, NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(long initialRoundCounter, DiagnosticSink diagnosticSink) {
        this(new RoundIdAllocator(initialRoundCounter), AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                AutoToolSwapProtocol.FIRST_TAKEOVER_REQUEST_ID, NO_PHASE_SEQUENCE, diagnosticSink);
    }

    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence) {
        this(new RoundIdAllocator(initialRoundCounter), firstActionSequence,
                AutoToolSwapProtocol.FIRST_TAKEOVER_REQUEST_ID, NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence, long firstPhaseSequence) {
        this(new RoundIdAllocator(initialRoundCounter), firstActionSequence,
                AutoToolSwapProtocol.FIRST_TAKEOVER_REQUEST_ID, firstPhaseSequence, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence,
            long firstPhaseSequence, long firstTakeoverRequestId) {
        this(new RoundIdAllocator(initialRoundCounter), firstActionSequence,
                firstTakeoverRequestId, firstPhaseSequence, NO_DIAGNOSTIC_SINK);
    }

    AutoToolSwapRoundService(RoundIdAllocator roundIdAllocator, long firstActionSequence) {
        this(roundIdAllocator, firstActionSequence, AutoToolSwapProtocol.FIRST_TAKEOVER_REQUEST_ID,
                NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    private AutoToolSwapRoundService(RoundIdAllocator roundIdAllocator, long firstActionSequence,
            long firstTakeoverRequestId, long firstPhaseSequence, DiagnosticSink diagnosticSink) {
        if (roundIdAllocator == null || firstActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE
                || firstTakeoverRequestId < AutoToolSwapProtocol.FIRST_TAKEOVER_REQUEST_ID
                || firstPhaseSequence < NO_PHASE_SEQUENCE || diagnosticSink == null) {
            throw new IllegalArgumentException("round allocator and sequence counters must be valid protocol values");
        }
        this.roundIdAllocator = roundIdAllocator;
        this.firstActionSequence = firstActionSequence;
        this.firstTakeoverRequestId = firstTakeoverRequestId;
        this.firstPhaseSequence = firstPhaseSequence;
        this.diagnosticSink = diagnosticSink;
    }

    /** 建立尚未分配服务端 round id 的 PENDING projection。 */
    public synchronized AutoToolSwapRoundResult beginRound(UUID playerId, Object endpoint, long clientNonce,
            long serverTick) {
        requireServerTick(serverTick);
        if (playerId == null || endpoint == null || clientNonce == 0L) {
            return detachedResult(AutoToolSwapProtocol.NO_SERVER_ROUND_ID,
                    AutoToolSwapRoundState.PENDING_KEY, AutoToolSwapResultCode.REJECTED,
                    firstActionSequence, serverTick);
        }
        RoundRecord existing = rounds.get(playerId);
        if (existing != null && (!isTerminal(existing.state) || existing.pendingPublication != null)) {
            if (!existing.matchesEndpoint(endpoint) || existing.clientNonce != clientNonce) {
                return result(existing, AutoToolSwapResultCode.REJECTED, serverTick);
            }
            return existing.state == AutoToolSwapRoundState.PENDING_KEY && existing.beginResult != null
                    ? existing.beginResult : result(existing, AutoToolSwapResultCode.ACCEPTED, serverTick);
        }
        RoundRecord created = new RoundRecord(endpoint, clientNonce, firstActionSequence,
                firstTakeoverRequestId, firstPhaseSequence);
        rounds.put(playerId, created);
        created.beginResult = result(created, AutoToolSwapResultCode.ACCEPTED, serverTick);
        return created.beginResult;
    }

    /** 为匹配 endpoint 的 PENDING projection 分配不可复用 round id。 */
    public synchronized AutoToolSwapRoundResult activatePendingRound(UUID playerId, Object endpoint,
            long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || endpoint == null || !record.matchesEndpoint(endpoint)) {
            return detachedResult(AutoToolSwapProtocol.NO_SERVER_ROUND_ID,
                    AutoToolSwapRoundState.PENDING_KEY, AutoToolSwapResultCode.REJECTED,
                    firstActionSequence, serverTick);
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

    public synchronized long currentRoundId(UUID playerId) {
        return currentRoundIdOf(rounds.get(playerId));
    }

    public synchronized long currentRoundId(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        return record != null && record.matchesEndpoint(endpoint)
                ? currentRoundIdOf(record) : AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
    }

    public synchronized long nextPhaseSequence(UUID playerId, Object endpoint, long serverRoundId) {
        return observeChainPhase(playerId, endpoint, serverRoundId, false, false);
    }

    /** 只投影阶段、冻结和关闭，不触碰 local physical owner。 */
    public synchronized long observeChainPhase(UUID playerId, Object endpoint, long serverRoundId,
            boolean freezeSwap, boolean closeRound) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.serverRoundId == 0L
                || record.serverRoundId != serverRoundId || isTerminal(record.state)) {
            return NO_PHASE_SEQUENCE;
        }
        if (freezeSwap && (record.state == AutoToolSwapRoundState.OPEN
                || record.state == AutoToolSwapRoundState.SWAPPED)) {
            record.state = AutoToolSwapRoundState.FROZEN;
        }
        if (closeRound) {
            record.keyDown = false;
            record.state = AutoToolSwapRoundState.CLOSING;
            stopPendingTakeover(record);
            clearLease(record);
        }
        if (record.phaseSequence == Long.MAX_VALUE) {
            orphan(record);
            clearLease(record);
            return NO_PHASE_SEQUENCE;
        }
        return ++record.phaseSequence;
    }

    /** 松键只关闭 wire projection；local finalizer 由 PacketKeyState 的独立屏障负责。 */
    public synchronized long onKeyReleased(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint)) {
            return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        }
        long roundId = record.serverRoundId;
        record.keyDown = false;
        stopPendingTakeover(record);
        clearLease(record);
        if (isActive(record.state)) record.state = AutoToolSwapRoundState.CLOSING;
        return roundId;
    }

    /**
     * 结算一个 v4 intent。inventory 参数只为冻结 public/source surface 而保留，永不读取。
     * 旧 SWAP/RESTORE/TAKEOVER/DECLINE 均是零库存拒绝；首个旧 SWAP 将 projection 冻结。
     */
    public synchronized AutoToolSwapRoundResult handleIntent(UUID playerId, Object endpoint,
            AutoToolSwapIntent intent, AutoToolSwapInventoryPort inventory, long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || endpoint == null || intent == null || !record.matchesEndpoint(endpoint)
                || intent.protocolVersion() != AutoToolSwapProtocol.PROTOCOL_VERSION
                || record.serverRoundId == 0L || intent.serverRoundId() != record.serverRoundId) {
            return rejectedForMissingOrMismatched(record, intent, serverTick);
        }
        boolean takeover = intent.usesTakeoverRequestId();
        if (takeover && intent.takeoverRequestId() < record.lastIssuedTakeoverRequestId) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        AutoToolSwapActionResult cached = takeover ? record.lastTakeoverResult : record.lastActionResult;
        if (cached != null && cached.intent().equals(intent)) return cached.roundResult();
        if (record.pendingPublication != null) {
            return record.pendingPublication.intent.equals(intent)
                    ? record.pendingPublication.roundResult
                    : result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        if (takeover) {
            if (!isTakeoverAttemptOpen(record, intent, serverTick)) {
                return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
            }
        } else if (intent.ordinaryActionSequence() != record.nextActionSequence) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        if (!takeover && record.nextActionSequence == Long.MAX_VALUE) {
            orphan(record);
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }

        AutoToolSwapResultCode outcome;
        TakeoverGateState gateAfter = null;
        AutoToolSwapAction action = intent.action();
        if (action == AutoToolSwapAction.SWAP) {
            if (record.state == AutoToolSwapRoundState.OPEN
                    || record.state == AutoToolSwapRoundState.SWAPPED) {
                record.state = AutoToolSwapRoundState.FROZEN;
            }
            outcome = AutoToolSwapResultCode.REJECTED;
        } else if (action == AutoToolSwapAction.RESTORE) {
            outcome = AutoToolSwapResultCode.REJECTED;
        } else if (action == AutoToolSwapAction.TAKEOVER
                || action == AutoToolSwapAction.DECLINE_TAKEOVER) {
            outcome = AutoToolSwapResultCode.REJECTED;
            gateAfter = TakeoverGateState.SKIP_TARGET;
        } else if (action == AutoToolSwapAction.FREEZE) {
            if (record.state == AutoToolSwapRoundState.OPEN
                    || record.state == AutoToolSwapRoundState.SWAPPED
                    || record.state == AutoToolSwapRoundState.FROZEN) {
                record.state = AutoToolSwapRoundState.FROZEN;
                outcome = AutoToolSwapResultCode.ACCEPTED;
            } else {
                outcome = AutoToolSwapResultCode.REJECTED;
            }
        } else if (action == AutoToolSwapAction.CLOSE
                || action == AutoToolSwapAction.ABANDON) {
            if (record.state == AutoToolSwapRoundState.PENDING_KEY || isTerminal(record.state)) {
                outcome = AutoToolSwapResultCode.REJECTED;
            } else {
                record.keyDown = false;
                record.state = AutoToolSwapRoundState.FINISHED;
                clearLease(record);
                stopPendingTakeover(record);
                outcome = AutoToolSwapResultCode.ACCEPTED;
            }
        } else {
            outcome = AutoToolSwapResultCode.REJECTED;
        }
        long nextSequence = takeover ? record.nextActionSequence : record.nextActionSequence + 1L;
        AutoToolSwapRoundResult published = detachedResult(record.serverRoundId, record.state,
                outcome, nextSequence, serverTick);
        record.pendingPublication = new PendingPublication(intent, published, gateAfter);
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
        boolean takeover = intent.usesTakeoverRequestId();
        AutoToolSwapActionResult cached = takeover ? record.lastTakeoverResult : record.lastActionResult;
        if (cached != null && cached.intent().equals(intent)
                && cached.roundResult().equals(publishedResult)) return true;
        PendingPublication pending = record.pendingPublication;
        if (pending == null || !pending.intent.equals(intent)
                || !pending.roundResult.equals(publishedResult)) return false;
        if (takeover) {
            PendingTakeover request = record.pendingTakeover;
            if (request == null || request.request.takeoverRequestId() != intent.takeoverRequestId()) return false;
            request.state = pending.takeoverGateState == null
                    ? TakeoverGateState.SKIP_TARGET : pending.takeoverGateState;
            record.lastTakeoverResult = new AutoToolSwapActionResult(intent, publishedResult);
        } else {
            record.nextActionSequence = publishedResult.nextActionSequence();
            record.lastActionResult = new AutoToolSwapActionResult(intent, publishedResult);
        }
        record.pendingPublication = null;
        return true;
    }

    /** cleanup 只丢弃 wire projection。 */
    public synchronized void cleanup(UUID playerId) {
        RoundRecord removed = rounds.remove(playerId);
        clearLease(removed);
    }

    /** clearAll 只丢弃 wire projections。 */
    public synchronized void clearAll() {
        rounds.clear();
    }

    public synchronized boolean hasEmptyHandFallbackLease(UUID playerId) {
        RoundRecord record = rounds.get(playerId);
        return record != null && record.emptyHandFallbackLease != null;
    }

    public synchronized boolean installEmptyHandFallbackLease(UUID playerId, Object endpoint,
            long serverRoundId, int generation, TargetCapabilityKey targetCapability,
            int anchorSlot, InventoryFingerprint inventoryFingerprint) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.serverRoundId != serverRoundId
                || record.state != AutoToolSwapRoundState.FROZEN || !record.keyDown
                || record.pendingTakeover != null || generation < 0
                || !AutoToolSwapProtocol.isHotbarSlot(anchorSlot) || targetCapability == null
                || inventoryFingerprint == null || !inventoryFingerprint.slot(anchorSlot).isEmpty()
                || lastEmptyHandFallbackLeaseId == Long.MAX_VALUE) return false;
        long leaseId = ++lastEmptyHandFallbackLeaseId;
        record.emptyHandFallbackLease = new EmptyHandFallbackLease(endpoint, serverRoundId, generation,
                leaseId, targetCapability, anchorSlot, inventoryFingerprint);
        return true;
    }

    public synchronized EmptyHandFallbackLeaseMatchResult matchEmptyHandFallbackLease(UUID playerId,
            Object endpoint, long serverRoundId, int generation, TargetCapabilityKey targetCapability,
            int anchorSlot, InventoryFingerprint inventoryFingerprint) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || record.emptyHandFallbackLease == null) {
            return leaseMatch(EmptyHandFallbackLeaseMatch.ABSENT, null);
        }
        EmptyHandFallbackLease lease = record.emptyHandFallbackLease;
        if (!record.matchesEndpoint(endpoint) || record.serverRoundId != serverRoundId
                || record.state != AutoToolSwapRoundState.FROZEN || !record.keyDown
                || record.pendingTakeover != null
                || !lease.matches(endpoint, serverRoundId, generation, targetCapability,
                        anchorSlot, inventoryFingerprint)) {
            clearLease(record);
            return leaseMatch(EmptyHandFallbackLeaseMatch.INVALIDATED, null);
        }
        return leaseMatch(EmptyHandFallbackLeaseMatch.MATCH,
                new EmptyHandFallbackLeaseToken(endpoint, serverRoundId, generation, lease.leaseId, lease));
    }

    public synchronized boolean compareAndClearEmptyHandFallbackLease(UUID playerId,
            EmptyHandFallbackLeaseToken token, String reason) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || token == null || !record.matchesEndpoint(token.endpointIdentity)
                || record.serverRoundId != token.serverRoundId
                || record.emptyHandFallbackLease != token.leaseIdentity
                || token.leaseIdentity.leaseId != token.leaseId
                || token.leaseIdentity.generation != token.generation) return false;
        clearLease(record);
        return true;
    }

    public synchronized void clearEmptyHandFallbackLease(UUID playerId, String reason) {
        clearLease(rounds.get(playerId));
    }

    /** Dormant 旧 surface：仍可分配 request watermark，但新 ordinary wiring 不调用。 */
    public synchronized AutoToolSwapTakeoverRequest prepareTakeover(UUID playerId, Object endpoint,
            long serverRoundId, int generation, int targetX, int targetY, int targetZ,
            int targetBlockId, int targetBlockMetadata, int anchorSlot, AutoToolSwapStackState anchorState,
            long serverTick, long deadlineTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.serverRoundId != serverRoundId
                || record.state != AutoToolSwapRoundState.FROZEN || !record.keyDown
                || !AutoToolSwapProtocol.isHotbarSlot(anchorSlot) || anchorState == null
                || deadlineTick <= serverTick || record.pendingPublication != null) return null;
        if (record.pendingTakeover != null) {
            return record.pendingTakeover.matches(serverRoundId, generation, targetX, targetY, targetZ,
                    targetBlockId, targetBlockMetadata, anchorSlot, anchorState)
                    ? record.pendingTakeover.request : null;
        }
        if (record.takeoverRequestIdsExhausted) return null;
        AutoToolSwapTakeoverRequest request;
        try {
            request = new AutoToolSwapTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION,
                    serverRoundId, record.nextTakeoverRequestId, generation,
                    targetX, targetY, targetZ, targetBlockId, targetBlockMetadata, serverTick, deadlineTick);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        record.lastIssuedTakeoverRequestId = record.nextTakeoverRequestId;
        if (record.nextTakeoverRequestId == Long.MAX_VALUE) record.takeoverRequestIdsExhausted = true;
        else record.nextTakeoverRequestId++;
        clearLease(record);
        record.pendingTakeover = new PendingTakeover(request, anchorSlot, anchorState);
        return request;
    }

    public synchronized TakeoverGateState takeoverGateState(UUID playerId, Object endpoint,
            AutoToolSwapTakeoverRequest request, long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || request == null
                || record.pendingTakeover == null
                || !record.pendingTakeover.request.sameGate(request)) return TakeoverGateState.STOP;
        PendingTakeover pending = record.pendingTakeover;
        if (pending.state == TakeoverGateState.WAITING
                && record.pendingPublication == null
                && (serverTick >= request.deadlineTick() || !record.keyDown
                        || record.state != AutoToolSwapRoundState.FROZEN)) {
            pending.state = serverTick >= request.deadlineTick()
                    ? TakeoverGateState.SKIP_TARGET : TakeoverGateState.STOP;
        }
        return pending.state;
    }

    public synchronized void consumeTakeoverGate(UUID playerId, Object endpoint,
            AutoToolSwapTakeoverRequest request) {
        RoundRecord record = rounds.get(playerId);
        if (record != null && record.matchesEndpoint(endpoint) && request != null
                && record.pendingTakeover != null
                && record.pendingTakeover.request.sameGate(request)
                && record.pendingTakeover.state != TakeoverGateState.WAITING
                && record.pendingPublication == null) record.pendingTakeover = null;
    }

    synchronized boolean stopTakeoverGate(UUID playerId, Object endpoint,
            AutoToolSwapTakeoverRequest request) {
        RoundRecord record = rounds.get(playerId);
        if (!matchingGate(record, endpoint, request)) return false;
        record.pendingTakeover.state = TakeoverGateState.STOP;
        return true;
    }

    synchronized boolean skipTakeoverGate(UUID playerId, Object endpoint,
            AutoToolSwapTakeoverRequest request, String cause) {
        RoundRecord record = rounds.get(playerId);
        if (!matchingGate(record, endpoint, request) || record.pendingPublication != null) return false;
        record.pendingTakeover.state = TakeoverGateState.SKIP_TARGET;
        return true;
    }

    private static boolean matchingGate(RoundRecord record, Object endpoint,
            AutoToolSwapTakeoverRequest request) {
        return record != null && record.matchesEndpoint(endpoint) && request != null
                && record.pendingTakeover != null
                && record.pendingTakeover.request.sameGate(request);
    }

    private static boolean isTakeoverAttemptOpen(RoundRecord record, AutoToolSwapIntent intent,
            long serverTick) {
        return record.pendingTakeover != null
                && record.pendingTakeover.state == TakeoverGateState.WAITING
                && intent.takeoverRequestId() == record.pendingTakeover.request.takeoverRequestId()
                && serverTick < record.pendingTakeover.request.deadlineTick();
    }

    private AutoToolSwapRoundResult rejectedForMissingOrMismatched(RoundRecord record,
            AutoToolSwapIntent intent, long serverTick) {
        if (record != null) return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        long roundId = intent == null ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : intent.serverRoundId();
        return detachedResult(roundId, AutoToolSwapRoundState.ORPHANED,
                AutoToolSwapResultCode.REJECTED, firstActionSequence, serverTick);
    }

    private void logIntent(UUID playerId, RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapResultCode outcome) {
        try {
            diagnosticSink.log("[AutoToolSwapDiag] projection player=" + playerId
                    + " round=" + record.serverRoundId + " actionSeq=" + intent.actionSequence()
                    + " action=" + intent.action() + " state=" + record.state + " outcome=" + outcome
                    + " inventory=unread");
        } catch (RuntimeException ignored) {
        } catch (LinkageError ignored) {
        }
    }

    private static EmptyHandFallbackLeaseMatchResult leaseMatch(EmptyHandFallbackLeaseMatch outcome,
            EmptyHandFallbackLeaseToken token) {
        return new EmptyHandFallbackLeaseMatchResult(outcome, token);
    }

    private static void clearLease(RoundRecord record) {
        if (record != null) record.emptyHandFallbackLease = null;
    }

    private static void stopPendingTakeover(RoundRecord record) {
        if (record != null && record.pendingTakeover != null
                && record.pendingTakeover.state == TakeoverGateState.WAITING) {
            record.pendingTakeover.state = TakeoverGateState.STOP;
        }
    }

    private static boolean isActive(AutoToolSwapRoundState state) {
        return state == AutoToolSwapRoundState.OPEN || state == AutoToolSwapRoundState.SWAPPED
                || state == AutoToolSwapRoundState.FROZEN || state == AutoToolSwapRoundState.CLOSING;
    }

    private static boolean isTerminal(AutoToolSwapRoundState state) {
        return state == AutoToolSwapRoundState.FINISHED || state == AutoToolSwapRoundState.ORPHANED;
    }

    private static void orphan(RoundRecord record) {
        record.state = AutoToolSwapRoundState.ORPHANED;
        record.keyDown = false;
        stopPendingTakeover(record);
    }

    private static long currentRoundIdOf(RoundRecord record) {
        return record == null || record.serverRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || isTerminal(record.state) && record.pendingPublication == null
                        ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : record.serverRoundId;
    }

    private static AutoToolSwapRoundResult result(RoundRecord record, AutoToolSwapResultCode outcome,
            long serverTick) {
        return detachedResult(record.serverRoundId, record.state, outcome,
                record.nextActionSequence, serverTick);
    }

    private static AutoToolSwapRoundResult detachedResult(long serverRoundId,
            AutoToolSwapRoundState state, AutoToolSwapResultCode outcome,
            long nextActionSequence, long serverTick) {
        return new AutoToolSwapRoundResult(serverRoundId, outcome, state, nextActionSequence, serverTick);
    }

    private static AutoToolSwapRoundSnapshot snapshotOf(RoundRecord record) {
        if (record == null) return null;
        return new AutoToolSwapRoundSnapshot(record.clientNonce, record.serverRoundId, record.state,
                record.nextActionSequence, record.lastIssuedTakeoverRequestId,
                record.takeoverRequestIdsExhausted, record.phaseSequence, record.keyDown,
                record.pendingPublication != null, false, false,
                AutoToolSwapRoundSnapshot.NO_LEDGER_SLOT, AutoToolSwapRoundSnapshot.NO_LEDGER_SLOT);
    }

    private static void requireServerTick(long serverTick) {
        if (serverTick < 0L) throw new IllegalArgumentException("serverTick must not be negative");
    }

    /** 保留给既有 inventory adapter 的诊断 source surface；projection 不调用。 */
    interface DiagnosticInventory {
        InventoryDiagnosticSnapshot captureDiagnosticSnapshot(int anchorSlot, int candidateSlot);
    }

    /** 不持有 ItemStack/NBT 的兼容诊断值。 */
    static final class InventoryDiagnosticSnapshot {
        private final int selectedSlot;
        private final String anchor;
        private final String candidate;
        private final String currentItem;

        InventoryDiagnosticSnapshot(int selectedSlot, String anchor, String candidate, String currentItem) {
            this.selectedSlot = selectedSlot;
            this.anchor = safe(anchor);
            this.candidate = safe(candidate);
            this.currentItem = safe(currentItem);
        }

        @Override
        public String toString() {
            return "selected=" + selectedSlot + ",anchor=[" + anchor + "],candidate=[" + candidate
                    + "],currentItem=[" + currentItem + "]";
        }

        private static String safe(String value) {
            return value == null ? "unavailable" : value.replace('\n', '_').replace('\r', '_');
        }
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
        private long nextTakeoverRequestId;
        private long lastIssuedTakeoverRequestId;
        private boolean takeoverRequestIdsExhausted;
        private long phaseSequence;
        private boolean keyDown;
        private AutoToolSwapRoundResult beginResult;
        private AutoToolSwapRoundResult activationResult;
        private AutoToolSwapActionResult lastActionResult;
        private AutoToolSwapActionResult lastTakeoverResult;
        private PendingPublication pendingPublication;
        private PendingTakeover pendingTakeover;
        private EmptyHandFallbackLease emptyHandFallbackLease;

        private RoundRecord(Object endpoint, long clientNonce, long firstActionSequence,
                long firstTakeoverRequestId, long firstPhaseSequence) {
            endpointReference = new WeakReference<Object>(endpoint);
            this.clientNonce = clientNonce;
            nextActionSequence = firstActionSequence;
            nextTakeoverRequestId = firstTakeoverRequestId;
            phaseSequence = firstPhaseSequence;
        }

        private boolean matchesEndpoint(Object endpoint) {
            return endpoint != null && endpointReference.get() == endpoint;
        }
    }

    private static final class PendingPublication {
        private final AutoToolSwapIntent intent;
        private final AutoToolSwapRoundResult roundResult;
        private final TakeoverGateState takeoverGateState;

        private PendingPublication(AutoToolSwapIntent intent, AutoToolSwapRoundResult roundResult,
                TakeoverGateState takeoverGateState) {
            this.intent = intent;
            this.roundResult = roundResult;
            this.takeoverGateState = takeoverGateState;
        }
    }

    private static final class PendingTakeover {
        private final AutoToolSwapTakeoverRequest request;
        private final int anchorSlot;
        private final AutoToolSwapStackState anchorState;
        private TakeoverGateState state = TakeoverGateState.WAITING;

        private PendingTakeover(AutoToolSwapTakeoverRequest request, int anchorSlot,
                AutoToolSwapStackState anchorState) {
            this.request = request;
            this.anchorSlot = anchorSlot;
            this.anchorState = anchorState;
        }

        private boolean matches(long serverRoundId, int generation,
                int targetX, int targetY, int targetZ, int blockId, int metadata,
                int currentAnchorSlot, AutoToolSwapStackState currentAnchorState) {
            return state == TakeoverGateState.WAITING && anchorSlot == currentAnchorSlot
                    && currentAnchorState != null && anchorState.sameContent(currentAnchorState)
                    && request.matchesTarget(serverRoundId, generation, targetX, targetY, targetZ,
                            blockId, metadata);
        }
    }

    private static final class EmptyHandFallbackLease {
        private final WeakReference<Object> endpointReference;
        private final long serverRoundId;
        private final int generation;
        private final long leaseId;
        private final TargetCapabilityKey targetCapability;
        private final int anchorSlot;
        private final InventoryFingerprint inventoryFingerprint;

        private EmptyHandFallbackLease(Object endpoint, long serverRoundId, int generation,
                long leaseId, TargetCapabilityKey targetCapability, int anchorSlot,
                InventoryFingerprint inventoryFingerprint) {
            endpointReference = new WeakReference<Object>(endpoint);
            this.serverRoundId = serverRoundId;
            this.generation = generation;
            this.leaseId = leaseId;
            this.targetCapability = targetCapability;
            this.anchorSlot = anchorSlot;
            this.inventoryFingerprint = inventoryFingerprint;
        }

        private boolean matches(Object endpoint, long currentRoundId, int currentGeneration,
                TargetCapabilityKey currentTarget, int currentAnchor,
                InventoryFingerprint currentInventory) {
            return endpointReference.get() == endpoint && serverRoundId == currentRoundId
                    && generation == currentGeneration && targetCapability.sameCapability(currentTarget)
                    && anchorSlot == currentAnchor && inventoryFingerprint.sameInventory(currentInventory)
                    && inventoryFingerprint.slot(anchorSlot).isEmpty();
        }
    }
}
