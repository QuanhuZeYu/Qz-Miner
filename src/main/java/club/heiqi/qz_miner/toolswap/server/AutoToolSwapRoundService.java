package club.heiqi.qz_miner.toolswap.server;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapActionResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;

/**
 * 服务端工具换位 round 的事务核心。调用方应在服务端主线程使用，方法同步仅用于封闭状态竞争。
 */
public final class AutoToolSwapRoundService {

    public static final long NO_PHASE_SEQUENCE = 0L;

    private final Map<UUID, RoundRecord> rounds = new HashMap<UUID, RoundRecord>();
    private final long firstActionSequence;
    private long lastIssuedRoundId;

    /** 创建从 round 1 和 action sequence 1 开始的服务。 */
    public AutoToolSwapRoundService() {
        this(AutoToolSwapProtocol.NO_SERVER_ROUND_ID, AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE);
    }

    /** 测试 round id 上界时使用的包级构造。 */
    AutoToolSwapRoundService(long initialRoundCounter) {
        this(initialRoundCounter, AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE);
    }

    /** 测试 action sequence 上界时使用的包级构造。 */
    AutoToolSwapRoundService(long initialRoundCounter, long firstActionSequence) {
        if (initialRoundCounter < AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || firstActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE) {
            throw new IllegalArgumentException("round and action counters must be non-negative protocol values");
        }
        this.lastIssuedRoundId = initialRoundCounter;
        this.firstActionSequence = firstActionSequence;
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

        RoundRecord created = new RoundRecord(endpoint, clientNonce, firstActionSequence);
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
        if (lastIssuedRoundId == Long.MAX_VALUE) {
            record.state = AutoToolSwapRoundState.ORPHANED;
            record.keyDown = false;
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }

        record.serverRoundId = ++lastIssuedRoundId;
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

    /** @return 当前已接受的 round id；PENDING 或不存在时返回 0。 */
    public synchronized long currentRoundId(UUID playerId) {
        RoundRecord record = rounds.get(playerId);
        return record == null ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : record.serverRoundId;
    }

    /** @return endpoint 匹配时的当前 round id，否则返回 0。 */
    public synchronized long currentRoundId(UUID playerId, Object endpoint) {
        RoundRecord record = rounds.get(playerId);
        return record != null && record.matchesEndpoint(endpoint) ? record.serverRoundId
                : AutoToolSwapProtocol.NO_SERVER_ROUND_ID;
    }

    /**
     * 为精确匹配且仍活动的 accepted round 推进阶段序号。
     *
     * @return 新阶段序号；不匹配或溢出时返回 0
     */
    public synchronized long nextPhaseSequence(UUID playerId, Object endpoint, long serverRoundId) {
        RoundRecord record = rounds.get(playerId);
        if (record == null || !record.matchesEndpoint(endpoint) || record.serverRoundId == 0L
                || record.serverRoundId != serverRoundId || isTerminal(record.state)) {
            return NO_PHASE_SEQUENCE;
        }
        if (record.phaseSequence == Long.MAX_VALUE) {
            record.state = AutoToolSwapRoundState.ORPHANED;
            record.keyDown = false;
            return NO_PHASE_SEQUENCE;
        }
        return ++record.phaseSequence;
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
        if (isActive(record.state)) {
            record.state = AutoToolSwapRoundState.CLOSING;
        }
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
        if (intent.actionSequence() != record.nextActionSequence) {
            return result(record, AutoToolSwapResultCode.REJECTED, serverTick);
        }
        if (record.nextActionSequence == Long.MAX_VALUE) {
            record.state = AutoToolSwapRoundState.ORPHANED;
            record.keyDown = false;
            return cacheWithoutAdvance(record, intent, AutoToolSwapResultCode.REJECTED, serverTick);
        }

        AutoToolSwapResultCode outcome;
        if (intent.action() == AutoToolSwapAction.SWAP) {
            outcome = applySwap(record, intent, inventory);
        } else if (intent.action() == AutoToolSwapAction.RESTORE) {
            outcome = applyRestore(record, intent, inventory);
        } else if (intent.action() == AutoToolSwapAction.FREEZE) {
            outcome = applyFreeze(record);
        } else {
            outcome = applyClose(record);
        }
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
        if (anchor == null || candidate == null || candidate.isEmpty() || candidate.remainingDurability() < 2
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
            if (!hasSafeInventoryContext(inventory) || inventory.selectedHotbarSlot() != ledger.anchorSlot) {
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
                || !ledger.originalAnchor.sameContent(currentCandidate)
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

    private static AutoToolSwapResultCode applyFreeze(RoundRecord record) {
        if (record.state != AutoToolSwapRoundState.OPEN && record.state != AutoToolSwapRoundState.SWAPPED) {
            return AutoToolSwapResultCode.REJECTED;
        }
        record.state = AutoToolSwapRoundState.FROZEN;
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

    private static void orphan(RoundRecord record) {
        record.state = AutoToolSwapRoundState.ORPHANED;
        record.keyDown = false;
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

        private RoundRecord(Object endpoint, long clientNonce, long firstActionSequence) {
            this.endpointReference = new WeakReference<Object>(endpoint);
            this.clientNonce = clientNonce;
            this.nextActionSequence = firstActionSequence;
        }

        private boolean matchesEndpoint(Object endpoint) {
            return endpoint != null && endpointReference.get() == endpoint;
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
