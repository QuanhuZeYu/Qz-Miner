package club.heiqi.qz_miner.toolswap.server;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.toolswap.AutoToolUsabilityPolicy;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapActionResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;

/**
 * 服务端工具换位 round 的事务核心。调用方应在服务端主线程使用，方法同步仅用于封闭状态竞争。
 */
public final class AutoToolSwapRoundService {

    public static final long NO_PHASE_SEQUENCE = 0L;

    /** 执行桥观察到的接替事务状态。 */
    public enum TakeoverGateState {
        WAITING,
        APPLIED,
        DECLINED,
        STOP
    }

    private static final RoundIdAllocator PROCESS_ROUND_ID_ALLOCATOR = new RoundIdAllocator(
            AutoToolSwapProtocol.NO_SERVER_ROUND_ID);
    private static final DiagnosticSink PRODUCTION_DIAGNOSTIC_SINK = new DiagnosticSink() {
        @Override
        public void log(String message) {
            // 探针保留在文件级 DEBUG，避免默认终端输出刷屏。
            MyMod.LOG.debug(message);
        }
    };
    private static final DiagnosticSink NO_DIAGNOSTIC_SINK = new DiagnosticSink() {
        @Override
        public void log(String message) {
        }
    };
    private static final String REASON_NONE = "none";
    private static final String REASON_TAKEOVER_GATE = "takeover-gate";
    private static final String REASON_ROUND_STATE = "round-state";
    private static final String REASON_INVENTORY_CONTEXT = "inventory-context";
    private static final String REASON_SELECTED_SLOT = "selected-slot";
    private static final String REASON_PENDING_ANCHOR_CHANGED = "pending-anchor-changed";
    private static final String REASON_CANDIDATE_FINGERPRINT = "candidate-fingerprint";
    private static final String REASON_CANDIDATE_LOW_RESERVE = "candidate-low-reserve";
    private static final String REASON_LEDGER_OLD_ROLE = "ledger-old-role";
    private static final String REASON_LEDGER_ACTIVE_ROLE = "ledger-active-role";
    private static final String REASON_SLOT_CONFLICT = "slot-conflict";
    private static final String REASON_INVENTORY_READ_FAILED = "inventory-read-failed";
    private static final String REASON_APPLIED = "applied";
    private static final String REASON_SYNC_FAILED = "sync-failed";
    private static final String GATE_CAUSE_DEADLINE = "deadline";
    private static final String GATE_CAUSE_KEY_RELEASE = "key-release";
    private static final String GATE_CAUSE_PHASE_CLOSE = "phase-close";
    private static final String GATE_CAUSE_ROUND_STATE = "round-state";
    private static final String GATE_CAUSE_EXTERNAL_STOP = "external-stop";

    private final Map<UUID, RoundRecord> rounds = new HashMap<UUID, RoundRecord>();
    private final RoundIdAllocator roundIdAllocator;
    private final long firstActionSequence;
    private final long firstPhaseSequence;
    private final DiagnosticSink diagnosticSink;

    /** 创建共享进程级 round id 分配器且 action sequence 从 1 开始的服务。 */
    public AutoToolSwapRoundService() {
        this(PROCESS_ROUND_ID_ALLOCATOR, AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, NO_PHASE_SEQUENCE,
                PRODUCTION_DIAGNOSTIC_SINK);
    }

    /** 测试 round id 上界时使用的包级构造。 */
    AutoToolSwapRoundService(long initialRoundCounter) {
        this(new RoundIdAllocator(initialRoundCounter), AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, NO_PHASE_SEQUENCE,
                NO_DIAGNOSTIC_SINK);
    }

    /** 测试有界动作诊断时使用的包级构造。 */
    AutoToolSwapRoundService(long initialRoundCounter, DiagnosticSink diagnosticSink) {
        this(new RoundIdAllocator(initialRoundCounter), AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, NO_PHASE_SEQUENCE,
                diagnosticSink);
    }

    /** 测试 action sequence 上界时使用的包级构造。 */
    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence) {
        this(new RoundIdAllocator(initialRoundCounter), firstActionSequence, NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    /** 测试 phase sequence 上界时使用的包级构造。 */
    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence, long firstPhaseSequence) {
        this(new RoundIdAllocator(initialRoundCounter), firstActionSequence, firstPhaseSequence, NO_DIAGNOSTIC_SINK);
    }

    /** 测试注入独立 round id 分配器时使用的包级构造。 */
    AutoToolSwapRoundService(RoundIdAllocator roundIdAllocator, long firstActionSequence) {
        this(roundIdAllocator, firstActionSequence, NO_PHASE_SEQUENCE, NO_DIAGNOSTIC_SINK);
    }

    private AutoToolSwapRoundService(RoundIdAllocator roundIdAllocator, long firstActionSequence,
            long firstPhaseSequence, DiagnosticSink diagnosticSink) {
        if (roundIdAllocator == null || firstActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE
                || firstPhaseSequence < NO_PHASE_SEQUENCE || diagnosticSink == null) {
            throw new IllegalArgumentException("round allocator and sequence counters must be valid protocol values");
        }
        this.roundIdAllocator = roundIdAllocator;
        this.firstActionSequence = firstActionSequence;
        this.firstPhaseSequence = firstPhaseSequence;
        this.diagnosticSink = diagnosticSink;
    }

    /** 建立尚未分配服务端 round id 的 PENDING round。 */
    public synchronized AutoToolSwapRoundResult beginRound(UUID playerId, Object endpoint, long clientNonce,
            long serverTick) {
        requireServerTick(serverTick);
        if (playerId == null || endpoint == null || clientNonce == 0L) {
            return detachedResult(AutoToolSwapProtocol.NO_SERVER_ROUND_ID, AutoToolSwapRoundState.PENDING_KEY,
                    AutoToolSwapResultCode.REJECTED, firstActionSequence, serverTick);
        }
        RoundRecord existing = rounds.get(playerId);
        if (existing != null && !isTerminal(existing.state)) {
            if (!existing.matchesEndpoint(endpoint) || existing.clientNonce != clientNonce) {
                return result(existing, AutoToolSwapResultCode.REJECTED, serverTick);
            }
            return result(existing, AutoToolSwapResultCode.ACCEPTED, serverTick);
        }

        RoundRecord created = new RoundRecord(endpoint, clientNonce, firstActionSequence, firstPhaseSequence);
        rounds.put(playerId, created);
        created.beginResult = result(created, AutoToolSwapResultCode.ACCEPTED, serverTick);
        return created.beginResult;
    }

    /** 为匹配 endpoint 的 PENDING round 分配不可复用的非零 round id。 */
    public synchronized AutoToolSwapRoundResult activatePendingRound(UUID playerId, Object endpoint,
            long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || endpoint == null || !record.matchesEndpoint(endpoint)) {
            return detachedResult(AutoToolSwapProtocol.NO_SERVER_ROUND_ID, AutoToolSwapRoundState.PENDING_KEY,
                    AutoToolSwapResultCode.REJECTED, firstActionSequence, serverTick);
        }
        if (record.activationResult != null) {
            return record.activationResult;
        }
        if (record.state != AutoToolSwapRoundState.PENDING_KEY) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        long serverRoundId = roundIdAllocator.nextRoundId();
        if (serverRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID) {
            record.state = AutoToolSwapRoundState.ORPHANED;
            record.keyDown = false;
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }

        record.serverRoundId = serverRoundId;
        record.state = AutoToolSwapRoundState.OPEN;
        record.keyDown = true;
        record.activationResult = result(record, AutoToolSwapResultCode.ACCEPTED, serverTick);
        return record.activationResult;
    }

    /** @return 玩家当前 round 的只读快照，不存在时返回 null。 */
    public synchronized AutoToolSwapRoundSnapshot snapshot(UUID playerId) {
        return snapshotOf(rounds.get(playerId));
    }

    /** @return endpoint 仍匹配时的只读快照，否则返回 null。 */
    public synchronized AutoToolSwapRoundSnapshot snapshot(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        return record != null && record.matchesEndpoint(endpoint) ? snapshotOf(record) : null;
    }

    /** @return 当前已分配且未终止的 round id；PENDING、终态或不存在时返回 0。 */
    public synchronized long currentRoundId(UUID playerId) {
        RoundRecord record = rounds.get(playerId);
        return currentRoundIdOf(record);
    }

    /** @return endpoint 匹配时已分配且未终止的 round id，否则返回 0。 */
    public synchronized long currentRoundId(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        return record != null && record.matchesEndpoint(endpoint) ? currentRoundIdOf(record)
                : AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
    }

    /**
     * 为精确匹配且仍活动的 accepted round 推进阶段序号。
     *
     * @return 新阶段序号；不匹配或溢出时返回 0
     */
    public synchronized long nextPhaseSequence(UUID playerId, Object endpoint, long serverRoundId) {
        return observeChainPhase(playerId, endpoint, serverRoundId, false, false);
    }

    /**
     * 消费已固化 round 关联的连锁阶段广播，并在同一临界区更新 round 状态与阶段序号。
     *
     * @param playerId      玩家 UUID
     * @param endpoint      当前在线 endpoint identity
     * @param serverRoundId 事件携带的不可变服务端 round id
     * @param freezeSwap    是否立即冻结后续 SWAP
     * @param closeRound    是否收口为 CLOSING
     * @return 新阶段序号；关联不匹配、终态或溢出时返回 0
     */
    public synchronized long observeChainPhase(UUID playerId, Object endpoint, long serverRoundId,
            boolean freezeSwap, boolean closeRound) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.serverRoundId == 0L
                || record.serverRoundId != serverRoundId || isTerminal(record.state)) {
            return NO_PHASE_SEQUENCE;
        }
        AutoToolSwapRoundState previousState = record.state;
        if (freezeSwap && (record.state == AutoToolSwapRoundState.OPEN
                || record.state == AutoToolSwapRoundState.SWAPPED)) {
            record.state = AutoToolSwapRoundState.FROZEN;
        }
        if (closeRound) {
            record.keyDown = false;
            record.state = AutoToolSwapRoundState.CLOSING;
            stopPendingTakeover(playerId, record, GATE_CAUSE_PHASE_CLOSE);
        }
        if (record.phaseSequence == Long.MAX_VALUE) {
            stopPendingTakeover(playerId, record, GATE_CAUSE_ROUND_STATE);
            orphan(record);
            return NO_PHASE_SEQUENCE;
        }
        long phaseSequence = ++record.phaseSequence;
        logDiagnostic("[AutoToolSwapDiag] phase player=" + playerId
                + " round=" + serverRoundId
                + " phaseSeq=" + phaseSequence
                + " event=" + (closeRound ? "IDLE" : (freezeSwap ? "FREEZE" : "PHASE"))
                + " freeze=" + freezeSwap
                + " close=" + closeRound
                + " stateBefore=" + previousState
                + " stateAfter=" + record.state);
        return phaseSequence;
    }

    /**
     * 收口按键释放。此方法只改 round 状态，不访问库存，也不尝试自动恢复。
     *
     * @return 释放前的 round id；endpoint 不匹配或不存在时返回 0
     */
    public synchronized long onKeyReleased(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint)) {
            return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
        }
        long releasedRoundId = record.serverRoundId;
        record.keyDown = false;
        stopPendingTakeover(playerId, record, GATE_CAUSE_KEY_RELEASE);
        if (isActive(record.state)) {
            record.state = AutoToolSwapRoundState.CLOSING;
        }
        logDiagnostic("[AutoToolSwapDiag] key-release player=" + playerId
                + " round=" + releasedRoundId
                + " state=" + record.state
                + " ledger=" + (record.ledger != null));
        return releasedRoundId;
    }

    /** 按 endpoint、round 与 action sequence 幂等结算单个动作。 */
    public synchronized AutoToolSwapRoundResult handleIntent(UUID playerId, Object endpoint,
            AutoToolSwapIntent intent, AutoToolSwapInventoryPort inventory, long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || endpoint == null || intent == null || !record.matchesEndpoint(endpoint)
                || intent.protocolVersion() != AutoToolSwapProtocol.PROTOCOL_VERSION
                || intent.serverRoundId() != record.serverRoundId || record.serverRoundId == 0L) {
            return rejectedForMissingOrMismatched(record, intent, serverTick);
        }
        if (record.lastActionResult != null && record.lastActionResult.intent().equals(intent)) {
            return record.lastActionResult.roundResult();
        }
        boolean takeoverAction = intent.action() == AutoToolSwapAction.TAKEOVER
                || intent.action() == AutoToolSwapAction.DECLINE_TAKEOVER;
        if (record.pendingTakeover != null && !takeoverAction) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        if (takeoverAction && hasTakeoverDeadlineElapsed(record, serverTick)) {
            stopPendingTakeover(playerId, record, GATE_CAUSE_DEADLINE);
        }
        if (intent.actionSequence() != record.nextActionSequence) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        if (record.nextActionSequence == Long.MAX_VALUE) {
            record.state = AutoToolSwapRoundState.ORPHANED;
            record.keyDown = false;
            stopPendingTakeover(playerId, record, GATE_CAUSE_ROUND_STATE);
            return cacheWithoutAdvance(record, intent, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        if (takeoverAction && !isTakeoverAttemptOpen(record, intent, serverTick)) {
            AutoToolSwapRoundState stateBefore = record.state;
            stopPendingTakeover(record);
            if (!hasTakeoverGateDiagnostic(record, intent)) {
                InventoryDiagnosticSnapshot unavailable = InventoryDiagnosticSnapshot.unavailable();
                logActionDiagnostic(playerId, record, intent, AutoToolSwapResultCode.REJECTED,
                        REASON_TAKEOVER_GATE, stateBefore, unavailable, unavailable);
            }
            return cacheAndAdvance(record, intent, AutoToolSwapResultCode.REJECTED, serverTick);
        }

        AutoToolSwapRoundState stateBefore = record.state;
        boolean inventoryFreeAction = intent.action() == AutoToolSwapAction.ABANDON
                || intent.action() == AutoToolSwapAction.DECLINE_TAKEOVER;
        InventoryDiagnosticSnapshot before = inventoryFreeAction
                ? InventoryDiagnosticSnapshot.unavailable() : captureInventoryDiagnostic(inventory, intent);
        AutoToolSwapResultCode outcome;
        String diagnosticReason = REASON_NONE;
        if (intent.action() == AutoToolSwapAction.SWAP) {
            outcome = applySwap(record, intent, inventory);
        } else if (intent.action() == AutoToolSwapAction.RESTORE) {
            outcome = applyRestore(record, intent, inventory);
        } else if (intent.action() == AutoToolSwapAction.FREEZE) {
            outcome = applyFreeze(record);
        } else if (intent.action() == AutoToolSwapAction.ABANDON) {
            outcome = applyAbandon(record, intent);
        } else if (intent.action() == AutoToolSwapAction.TAKEOVER) {
            TakeoverSettlement settlement = applyTakeover(record, intent, inventory, serverTick);
            outcome = settlement.resultCode;
            diagnosticReason = settlement.diagnosticReason;
        } else if (intent.action() == AutoToolSwapAction.DECLINE_TAKEOVER) {
            outcome = applyDeclineTakeover(record, intent, serverTick);
        } else {
            outcome = applyClose(record);
        }
        InventoryDiagnosticSnapshot after = inventoryFreeAction
                ? InventoryDiagnosticSnapshot.unavailable() : captureInventoryDiagnostic(inventory, intent);
        logActionDiagnostic(playerId, record, intent, outcome, diagnosticReason, stateBefore, before, after);
        return cacheAndAdvance(record, intent, outcome, serverTick);
    }

    /** 丢弃一个玩家的 round 记录，不访问库存。 */
    public synchronized void cleanup(UUID playerId) {
        rounds.remove(playerId);
    }

    /** 丢弃全部 round 记录，不访问库存。 */
    public synchronized void clearAll() {
        rounds.clear();
    }

    /**
     * 在 FROZEN round 中幂等建立唯一接替请求并预留当前动作序号。
     * 调用方必须传入同一服务端主线程时刻捕获的锚点库存事实。
     */
    public synchronized AutoToolSwapTakeoverRequest prepareTakeover(UUID playerId, Object endpoint,
            long serverRoundId, int generation, int targetX, int targetY, int targetZ,
            int targetBlockId, int targetBlockMetadata, int anchorSlot, AutoToolSwapStackState anchorState,
            long serverTick, long deadlineTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.serverRoundId != serverRoundId
                || record.state != AutoToolSwapRoundState.FROZEN || !record.keyDown
                || !AutoToolSwapProtocol.isHotbarSlot(anchorSlot) || anchorState == null
                || deadlineTick <= serverTick || record.nextActionSequence == Long.MAX_VALUE) {
            return null;
        }
        AutoToolSwapTakeoverRequest request;
        try {
            request = new AutoToolSwapTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION,
                    serverRoundId, record.nextActionSequence, generation, targetX, targetY, targetZ,
                    targetBlockId, targetBlockMetadata, serverTick, deadlineTick);
        } catch (IllegalArgumentException invalidRequest) {
            return null;
        }
        if (record.pendingTakeover != null) {
            return record.pendingTakeover.request.sameGate(request) ? record.pendingTakeover.request : null;
        }
        record.pendingTakeover = new PendingTakeover(request, anchorSlot, anchorState);
        return request;
    }

    /** 查询并在 deadline 到达时收口当前等待门。 */
    public synchronized TakeoverGateState takeoverGateState(UUID playerId, Object endpoint,
            AutoToolSwapTakeoverRequest request, long serverTick) {
        requireServerTick(serverTick);
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.pendingTakeover == null
                || !record.pendingTakeover.request.sameGate(request) || record.serverRoundId != request.serverRoundId()) {
            return TakeoverGateState.STOP;
        }
        PendingTakeover pending = record.pendingTakeover;
        if (pending.state == TakeoverGateState.WAITING) {
            if (serverTick >= request.deadlineTick()) {
                stopPendingTakeover(playerId, record, GATE_CAUSE_DEADLINE);
            } else if (!record.keyDown) {
                stopPendingTakeover(playerId, record, GATE_CAUSE_KEY_RELEASE);
            } else if (record.state != AutoToolSwapRoundState.FROZEN) {
                stopPendingTakeover(playerId, record, GATE_CAUSE_ROUND_STATE);
            }
        }
        return pending.state;
    }

    /** APPLIED/STOP 已被执行桥消费后移除等待门。 */
    public synchronized void consumeTakeoverGate(UUID playerId, Object endpoint,
            AutoToolSwapTakeoverRequest request) {
        RoundRecord record = rounds.get(playerId);
        if (record != null && record.matchesEndpoint(endpoint) && record.pendingTakeover != null
                && record.pendingTakeover.request.sameGate(request)
                && record.pendingTakeover.state != TakeoverGateState.WAITING) {
            record.pendingTakeover = null;
        }
    }

    /**
     * 仅停止 endpoint 与等待门身份均精确匹配的 pending；不清除其他 round 或请求。
     *
     * @return 是否找到了精确等待门并将其收口为 STOP
     */
    synchronized boolean stopTakeoverGate(UUID playerId, Object endpoint,
            AutoToolSwapTakeoverRequest request) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.pendingTakeover == null
                || !record.pendingTakeover.request.sameGate(request)) {
            return false;
        }
        stopPendingTakeover(playerId, record, GATE_CAUSE_EXTERNAL_STOP);
        return true;
    }

    private AutoToolSwapResultCode applySwap(RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapInventoryPort inventory) {
        if (!record.keyDown || record.state != AutoToolSwapRoundState.OPEN || inventory == null
                || !AutoToolSwapProtocol.isHotbarSlot(intent.anchorSlot())
                || !AutoToolSwapProtocol.isInventorySlot(intent.candidateSlot())
                || intent.anchorSlot() == intent.candidateSlot()) {
            return AutoToolSwapResultCode.REJECTED;
        }

        AutoToolSwapStackState anchor;
        AutoToolSwapStackState candidate;
        try {
            if (!hasSafeInventoryContext(inventory) || inventory.selectedHotbarSlot() != intent.anchorSlot()) {
                return AutoToolSwapResultCode.REJECTED;
            }
            anchor = inventory.readInventorySlot(intent.anchorSlot());
            candidate = inventory.readInventorySlot(intent.candidateSlot());
        } catch (RuntimeException error) {
            return AutoToolSwapResultCode.REJECTED;
        } catch (LinkageError error) {
            return AutoToolSwapResultCode.REJECTED;
        }
        if (anchor == null || candidate == null || candidate.isEmpty()
                || !AutoToolUsabilityPolicy.hasDurabilityReserve(candidate.remainingDurability())
                || !anchor.contentFingerprint().sameContent(intent.anchorContentFingerprint())
                || !candidate.contentFingerprint().sameContent(intent.candidateContentFingerprint())) {
            return AutoToolSwapResultCode.REJECTED;
        }

        record.ledger = new SwapLedger(intent.anchorSlot(), intent.candidateSlot(), anchor, candidate);
        try {
            inventory.swapInventorySlotsAtomically(intent.anchorSlot(), intent.candidateSlot());
            record.state = AutoToolSwapRoundState.SWAPPED;
            inventory.syncInventoryDifference();
            return AutoToolSwapResultCode.APPLIED;
        } catch (RuntimeException error) {
            orphan(record);
            return AutoToolSwapResultCode.SYNC_FAILED;
        } catch (LinkageError error) {
            orphan(record);
            return AutoToolSwapResultCode.SYNC_FAILED;
        }
    }

    private AutoToolSwapResultCode applyRestore(RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapInventoryPort inventory) {
        if (record.ledger == null || inventory == null || !allowsRestore(record.state)) {
            return AutoToolSwapResultCode.REJECTED;
        }
        SwapLedger ledger = record.ledger;
        if (intent.anchorSlot() != ledger.anchorSlot || intent.candidateSlot() != ledger.candidateSlot) {
            return AutoToolSwapResultCode.REJECTED;
        }

        AutoToolSwapStackState currentAnchor;
        AutoToolSwapStackState currentCandidate;
        try {
            if (!hasSafeInventoryContext(inventory)) {
                return AutoToolSwapResultCode.REJECTED;
            }
            currentAnchor = inventory.readInventorySlot(ledger.anchorSlot);
            currentCandidate = inventory.readInventorySlot(ledger.candidateSlot);
        } catch (RuntimeException error) {
            return AutoToolSwapResultCode.REJECTED;
        } catch (LinkageError error) {
            return AutoToolSwapResultCode.REJECTED;
        }
        if (currentAnchor == null || currentCandidate == null
                || !currentAnchor.contentFingerprint().sameContent(intent.anchorContentFingerprint())
                || !currentCandidate.contentFingerprint().sameContent(intent.candidateContentFingerprint())
                || !(ledger.originalAnchor.isEmpty() || ledger.originalAnchor.sameRole(currentCandidate))
                || !(currentAnchor.isEmpty() || ledger.originalCandidate.sameRole(currentAnchor))) {
            return AutoToolSwapResultCode.REJECTED;
        }

        try {
            inventory.swapInventorySlotsAtomically(ledger.anchorSlot, ledger.candidateSlot);
            inventory.syncInventoryDifference();
            record.ledger = null;
            record.state = record.keyDown ? AutoToolSwapRoundState.OPEN : AutoToolSwapRoundState.CLOSING;
            return AutoToolSwapResultCode.APPLIED;
        } catch (RuntimeException error) {
            orphan(record);
            return AutoToolSwapResultCode.SYNC_FAILED;
        } catch (LinkageError error) {
            orphan(record);
            return AutoToolSwapResultCode.SYNC_FAILED;
        }
    }

    /** 同 round 接替：无 ledger 双槽交换，有 ledger 单次三槽轮转。 */
    private TakeoverSettlement applyTakeover(RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapInventoryPort inventory, long serverTick) {
        PendingTakeover pending = record.pendingTakeover;
        if (!isTakeoverAttemptOpen(record, intent, serverTick)) {
            if (pending != null) pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_TAKEOVER_GATE);
        }
        if (!record.keyDown || record.state != AutoToolSwapRoundState.FROZEN) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_ROUND_STATE);
        }
        if (inventory == null) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_INVENTORY_CONTEXT);
        }
        if (intent.anchorSlot() != pending.anchorSlot
                || !AutoToolSwapProtocol.isInventorySlot(intent.candidateSlot())
                || intent.candidateSlot() == pending.anchorSlot
                || record.ledger != null && intent.candidateSlot() == record.ledger.candidateSlot) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_SLOT_CONFLICT);
        }
        AutoToolSwapStackState anchor;
        AutoToolSwapStackState candidate;
        AutoToolSwapStackState oldCandidate = null;
        try {
            if (!hasSafeInventoryContext(inventory)) {
                pending.state = TakeoverGateState.STOP;
                return TakeoverSettlement.rejected(REASON_INVENTORY_CONTEXT);
            }
            if (inventory.selectedHotbarSlot() != pending.anchorSlot) {
                pending.state = TakeoverGateState.STOP;
                return TakeoverSettlement.rejected(REASON_SELECTED_SLOT);
            }
            anchor = inventory.readInventorySlot(pending.anchorSlot);
            candidate = inventory.readInventorySlot(intent.candidateSlot());
            if (record.ledger != null) oldCandidate = inventory.readInventorySlot(record.ledger.candidateSlot);
        } catch (RuntimeException error) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_INVENTORY_READ_FAILED);
        } catch (LinkageError error) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_INVENTORY_READ_FAILED);
        }
        if (anchor == null || candidate == null || record.ledger != null && oldCandidate == null) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_INVENTORY_READ_FAILED);
        }
        if (!pending.anchorState.contentFingerprint().sameContent(anchor.contentFingerprint())) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_PENDING_ANCHOR_CHANGED);
        }
        if (candidate.isEmpty()
                || !candidate.contentFingerprint().sameContent(intent.candidateContentFingerprint())) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_CANDIDATE_FINGERPRINT);
        }
        if (!AutoToolUsabilityPolicy.hasDurabilityReserve(candidate.remainingDurability())) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_CANDIDATE_LOW_RESERVE);
        }
        if (record.ledger != null && !(record.ledger.originalAnchor.isEmpty()
                || record.ledger.originalAnchor.sameRole(oldCandidate))) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_LEDGER_OLD_ROLE);
        }
        if (record.ledger != null
                && !(anchor.isEmpty() || record.ledger.originalCandidate.sameRole(anchor))) {
            pending.state = TakeoverGateState.STOP;
            return TakeoverSettlement.rejected(REASON_LEDGER_ACTIVE_ROLE);
        }

        SwapLedger oldLedger = record.ledger;
        try {
            if (oldLedger == null) {
                inventory.swapInventorySlotsAtomically(pending.anchorSlot, intent.candidateSlot());
                record.ledger = new SwapLedger(pending.anchorSlot, intent.candidateSlot(), anchor, candidate);
            } else {
                inventory.rotateInventorySlotsAtomically(pending.anchorSlot, oldLedger.candidateSlot,
                        intent.candidateSlot());
                record.ledger = new SwapLedger(pending.anchorSlot, intent.candidateSlot(),
                        oldLedger.originalAnchor, candidate);
            }
            inventory.syncInventoryDifference();
            pending.state = TakeoverGateState.APPLIED;
            return TakeoverSettlement.of(AutoToolSwapResultCode.APPLIED, REASON_APPLIED);
        } catch (RuntimeException error) {
            pending.state = TakeoverGateState.STOP;
            orphan(record);
            return TakeoverSettlement.of(AutoToolSwapResultCode.SYNC_FAILED, REASON_SYNC_FAILED);
        } catch (LinkageError error) {
            pending.state = TakeoverGateState.STOP;
            orphan(record);
            return TakeoverSettlement.of(AutoToolSwapResultCode.SYNC_FAILED, REASON_SYNC_FAILED);
        }
    }

    /** DECLINE 只结算等待门，不读写或同步库存。 */
    private static AutoToolSwapResultCode applyDeclineTakeover(RoundRecord record, AutoToolSwapIntent intent,
            long serverTick) {
        PendingTakeover pending = record.pendingTakeover;
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        if (!isTakeoverAttemptOpen(record, intent, serverTick) || !record.keyDown
                || record.state != AutoToolSwapRoundState.FROZEN
                || pending == null || !pending.anchorState.isEmpty()
                || intent.anchorSlot() != pending.anchorSlot || intent.candidateSlot() != pending.anchorSlot
                || !empty.sameContent(intent.anchorContentFingerprint())
                || !empty.sameContent(intent.candidateContentFingerprint())) {
            if (pending != null) pending.state = TakeoverGateState.STOP;
            return AutoToolSwapResultCode.REJECTED;
        }
        pending.state = TakeoverGateState.DECLINED;
        return AutoToolSwapResultCode.ACCEPTED;
    }

    /** deadline 前仅允许精确 pending sequence 的首次 TAKEOVER/DECLINE 进入库存路径。 */
    private static boolean isTakeoverAttemptOpen(RoundRecord record, AutoToolSwapIntent intent,
            long serverTick) {
        PendingTakeover pending = record.pendingTakeover;
        return pending != null && pending.state == TakeoverGateState.WAITING
                && intent.actionSequence() == pending.request.actionSequence()
                && serverTick < pending.request.deadlineTick();
    }

    /** @return 当前 pending 是否已经到达或越过服务端 deadline。 */
    private static boolean hasTakeoverDeadlineElapsed(RoundRecord record, long serverTick) {
        return record.pendingTakeover != null
                && serverTick >= record.pendingTakeover.request.deadlineTick();
    }

    private static AutoToolSwapResultCode applyFreeze(RoundRecord record) {
        if (record.state == AutoToolSwapRoundState.FROZEN) {
            return AutoToolSwapResultCode.ACCEPTED;
        }
        if (record.state != AutoToolSwapRoundState.OPEN && record.state != AutoToolSwapRoundState.SWAPPED) {
            return AutoToolSwapResultCode.REJECTED;
        }
        record.state = AutoToolSwapRoundState.FROZEN;
        return AutoToolSwapResultCode.ACCEPTED;
    }

    /** 显式放弃无法安全恢复的账本；只清事务状态，绝不读取或写入库存。 */
    private static AutoToolSwapResultCode applyAbandon(RoundRecord record, AutoToolSwapIntent intent) {
        if (record.ledger == null || !allowsRestore(record.state)) {
            return AutoToolSwapResultCode.REJECTED;
        }
        SwapLedger ledger = record.ledger;
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        if (intent.anchorSlot() != ledger.anchorSlot || intent.candidateSlot() != ledger.candidateSlot
                || !empty.sameContent(intent.anchorContentFingerprint())
                || !empty.sameContent(intent.candidateContentFingerprint())) {
            return AutoToolSwapResultCode.REJECTED;
        }
        record.ledger = null;
        record.keyDown = false;
        record.state = AutoToolSwapRoundState.FINISHED;
        return AutoToolSwapResultCode.ACCEPTED;
    }

    private static AutoToolSwapResultCode applyClose(RoundRecord record) {
        if (isTerminal(record.state) || record.state == AutoToolSwapRoundState.PENDING_KEY) {
            return AutoToolSwapResultCode.REJECTED;
        }
        if (record.ledger != null) {
            record.state = AutoToolSwapRoundState.CLOSING;
            record.keyDown = false;
            return AutoToolSwapResultCode.RESTORE_REQUIRED;
        }
        record.state = AutoToolSwapRoundState.FINISHED;
        record.keyDown = false;
        return AutoToolSwapResultCode.ACCEPTED;
    }

    private static boolean hasSafeInventoryContext(AutoToolSwapInventoryPort inventory) {
        return inventory.isPlayerAlive() && !inventory.isCreativeMode() && inventory.hasPersonalInventoryWindow0()
                && inventory.isCursorEmpty();
    }

    private static boolean allowsRestore(AutoToolSwapRoundState state) {
        return state == AutoToolSwapRoundState.SWAPPED || state == AutoToolSwapRoundState.FROZEN
                || state == AutoToolSwapRoundState.CLOSING;
    }

    private static boolean isActive(AutoToolSwapRoundState state) {
        return state == AutoToolSwapRoundState.OPEN || state == AutoToolSwapRoundState.SWAPPED
                || state == AutoToolSwapRoundState.FROZEN || state == AutoToolSwapRoundState.CLOSING;
    }

    private static boolean isTerminal(AutoToolSwapRoundState state) {
        return state == AutoToolSwapRoundState.FINISHED || state == AutoToolSwapRoundState.ORPHANED;
    }

    private static long currentRoundIdOf(RoundRecord record) {
        return record == null || record.serverRoundId == AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || isTerminal(record.state) ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : record.serverRoundId;
    }

    private static void orphan(RoundRecord record) {
        record.state = AutoToolSwapRoundState.ORPHANED;
        record.keyDown = false;
        stopPendingTakeover(record);
    }

    private static void stopPendingTakeover(RoundRecord record) {
        if (record.pendingTakeover != null) record.pendingTakeover.state = TakeoverGateState.STOP;
    }

    /** 首次收口等待门时输出固定纯值原因；既有 APPLIED/STOP 状态仅保持原停止语义。 */
    private void stopPendingTakeover(UUID playerId, RoundRecord record, String cause) {
        PendingTakeover pending = record.pendingTakeover;
        if (pending == null) {
            return;
        }
        TakeoverGateState previousState = pending.state;
        pending.state = TakeoverGateState.STOP;
        if (previousState != TakeoverGateState.WAITING || pending.stopDiagnosticLogged) {
            return;
        }
        pending.stopDiagnosticLogged = true;
        record.lastTakeoverGateDiagnosticSequence = pending.request.actionSequence();
        logDiagnostic("[AutoToolSwapDiag] takeover-gate player=" + playerId
                + " round=" + pending.request.serverRoundId()
                + " actionSeq=" + pending.request.actionSequence()
                + " state=" + pending.state
                + " cause=" + cause);
    }

    /** @return 当前 intent 是否已由同 round、同动作序号的等待门终态诊断覆盖。 */
    private static boolean hasTakeoverGateDiagnostic(RoundRecord record, AutoToolSwapIntent intent) {
        return record.lastTakeoverGateDiagnosticSequence == intent.actionSequence();
    }

    private AutoToolSwapRoundResult cacheAndAdvance(RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapResultCode outcome, long serverTick) {
        record.nextActionSequence++;
        AutoToolSwapRoundResult roundResult = result(record, outcome, serverTick);
        record.lastActionResult = new AutoToolSwapActionResult(intent, roundResult);
        return roundResult;
    }

    private AutoToolSwapRoundResult cacheWithoutAdvance(RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapResultCode outcome, long serverTick) {
        AutoToolSwapRoundResult roundResult = result(record, outcome, serverTick);
        record.lastActionResult = new AutoToolSwapActionResult(intent, roundResult);
        return roundResult;
    }

    private AutoToolSwapRoundResult rejectedForMissingOrMismatched(RoundRecord record, AutoToolSwapIntent intent,
            long serverTick) {
        if (record != null) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        long roundId = intent == null ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : intent.serverRoundId();
        return detachedResult(roundId, AutoToolSwapRoundState.ORPHANED, AutoToolSwapResultCode.REJECTED,
                firstActionSequence, serverTick);
    }

    private static AutoToolSwapRoundResult result(RoundRecord record, AutoToolSwapResultCode outcome,
            long serverTick) {
        return detachedResult(record.serverRoundId, record.state, outcome, record.nextActionSequence, serverTick);
    }

    private static AutoToolSwapRoundResult detachedResult(long serverRoundId, AutoToolSwapRoundState state,
            AutoToolSwapResultCode outcome, long nextActionSequence, long serverTick) {
        return new AutoToolSwapRoundResult(serverRoundId, outcome, state, nextActionSequence, serverTick);
    }

    private static AutoToolSwapRoundSnapshot snapshotOf(RoundRecord record) {
        if (record == null) {
            return null;
        }
        int anchorSlot = record.ledger == null ? AutoToolSwapRoundSnapshot.NO_LEDGER_SLOT : record.ledger.anchorSlot;
        int candidateSlot = record.ledger == null ? AutoToolSwapRoundSnapshot.NO_LEDGER_SLOT
                : record.ledger.candidateSlot;
        return new AutoToolSwapRoundSnapshot(record.clientNonce, record.serverRoundId, record.state,
                record.nextActionSequence, record.phaseSequence, record.keyDown, record.ledger != null,
                anchorSlot, candidateSlot);
    }

    private static void requireServerTick(long serverTick) {
        if (serverTick < 0L) {
            throw new IllegalArgumentException("serverTick must not be negative");
        }
    }

    /** 捕获诊断快照，任何诊断边界异常都只降级文本，不影响事务结果。 */
    private static InventoryDiagnosticSnapshot captureInventoryDiagnostic(AutoToolSwapInventoryPort inventory,
            AutoToolSwapIntent intent) {
        if (!(inventory instanceof DiagnosticInventory) || intent == null) {
            return InventoryDiagnosticSnapshot.unavailable();
        }
        try {
            InventoryDiagnosticSnapshot snapshot = ((DiagnosticInventory) inventory)
                    .captureDiagnosticSnapshot(intent.anchorSlot(), intent.candidateSlot());
            return snapshot == null ? InventoryDiagnosticSnapshot.unavailable() : snapshot;
        } catch (RuntimeException error) {
            return InventoryDiagnosticSnapshot.unavailable();
        } catch (LinkageError error) {
            return InventoryDiagnosticSnapshot.unavailable();
        }
    }

    /** 输出一次已结算动作的前后纯值快照。 */
    private void logActionDiagnostic(UUID playerId, RoundRecord record, AutoToolSwapIntent intent,
            AutoToolSwapResultCode outcome, String diagnosticReason, AutoToolSwapRoundState stateBefore,
            InventoryDiagnosticSnapshot before, InventoryDiagnosticSnapshot after) {
        logDiagnostic("[AutoToolSwapDiag] action player=" + playerId
                + " round=" + record.serverRoundId
                + " actionSeq=" + intent.actionSequence()
                + " action=" + intent.action()
                + " anchor=" + intent.anchorSlot()
                + " candidate=" + intent.candidateSlot()
                + " stateBefore=" + stateBefore
                + " stateAfter=" + record.state
                + " outcome=" + outcome
                + " reason=" + diagnosticReason
                + " before={" + before + "}"
                + " after={" + after + "}");
    }

    /** 诊断日志必须与业务路径隔离，测试 sink 异常也不得改变事务。 */
    private void logDiagnostic(String message) {
        try {
            diagnosticSink.log(message);
        } catch (RuntimeException ignored) {
            // 诊断探针不能改变库存事务或 round 状态。
        } catch (LinkageError ignored) {
            // 日志实现缺失时同样保持原业务结果。
        }
    }

    /** 单行诊断输出边界。 */
    interface DiagnosticSink {
        void log(String message);
    }

    /** 真实库存端口可选实现的纯值诊断边界。 */
    interface DiagnosticInventory {
        InventoryDiagnosticSnapshot captureDiagnosticSnapshot(int anchorSlot, int candidateSlot);
    }

    /** 不持有 ItemStack/NBT 的库存诊断快照。 */
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

        static InventoryDiagnosticSnapshot unavailable() {
            return new InventoryDiagnosticSnapshot(-1, "unavailable", "unavailable", "unavailable");
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

    /** TAKEOVER 内部结算值；网络仍只发布既有 result code。 */
    private static final class TakeoverSettlement {

        private final AutoToolSwapResultCode resultCode;
        private final String diagnosticReason;

        private TakeoverSettlement(AutoToolSwapResultCode resultCode, String diagnosticReason) {
            this.resultCode = resultCode;
            this.diagnosticReason = diagnosticReason;
        }

        private static TakeoverSettlement rejected(String diagnosticReason) {
            return of(AutoToolSwapResultCode.REJECTED, diagnosticReason);
        }

        private static TakeoverSettlement of(AutoToolSwapResultCode resultCode, String diagnosticReason) {
            return new TakeoverSettlement(resultCode, diagnosticReason);
        }
    }

    /** 线程安全且永久耗尽的 round id 分配器。 */
    static final class RoundIdAllocator {

        private long lastIssuedRoundId;

        RoundIdAllocator(long initialRoundCounter) {
            if (initialRoundCounter < AutoToolSwapProtocol.NO_SERVER_ROUND_ID) {
                throw new IllegalArgumentException("round counter must be a non-negative protocol value");
            }
            this.lastIssuedRoundId = initialRoundCounter;
        }

        synchronized long nextRoundId() {
            if (lastIssuedRoundId == Long.MAX_VALUE) {
                return AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
            }
            return ++lastIssuedRoundId;
        }
    }

    /** 单个玩家的内部可变状态，仅在 service monitor 下访问。 */
    private static final class RoundRecord {

        private final WeakReference<Object> endpointReference;
        private final long clientNonce;
        private long serverRoundId;
        private AutoToolSwapRoundState state = AutoToolSwapRoundState.PENDING_KEY;
        private long nextActionSequence;
        private long phaseSequence;
        private boolean keyDown;
        private SwapLedger ledger;
        private AutoToolSwapRoundResult beginResult;
        private AutoToolSwapRoundResult activationResult;
        private AutoToolSwapActionResult lastActionResult;
        private PendingTakeover pendingTakeover;
        private long lastTakeoverGateDiagnosticSequence = NO_PHASE_SEQUENCE;

        private RoundRecord(Object endpoint, long clientNonce, long firstActionSequence, long firstPhaseSequence) {
            this.endpointReference = new WeakReference<Object>(endpoint);
            this.clientNonce = clientNonce;
            this.nextActionSequence = firstActionSequence;
            this.phaseSequence = firstPhaseSequence;
        }

        private boolean matchesEndpoint(Object endpoint) {
            return endpoint != null && endpointReference.get() == endpoint;
        }
    }

    /** 单一等待门，动作序号由 request 冻结。 */
    private static final class PendingTakeover {
        private final AutoToolSwapTakeoverRequest request;
        private final int anchorSlot;
        private final AutoToolSwapStackState anchorState;
        private TakeoverGateState state = TakeoverGateState.WAITING;
        private boolean stopDiagnosticLogged;

        private PendingTakeover(AutoToolSwapTakeoverRequest request, int anchorSlot,
                AutoToolSwapStackState anchorState) {
            this.request = request;
            this.anchorSlot = anchorSlot;
            this.anchorState = anchorState;
        }
    }

    /** 写前冻结的不可变双槽事实。 */
    private static final class SwapLedger {

        private final int anchorSlot;
        private final int candidateSlot;
        private final AutoToolSwapStackState originalAnchor;
        private final AutoToolSwapStackState originalCandidate;

        private SwapLedger(int anchorSlot, int candidateSlot, AutoToolSwapStackState originalAnchor,
                AutoToolSwapStackState originalCandidate) {
            this.anchorSlot = anchorSlot;
            this.candidateSlot = candidateSlot;
            this.originalAnchor = originalAnchor;
            this.originalCandidate = originalCandidate;
        }
    }
}
