package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolCandidateOrder;
import club.heiqi.qz_miner.toolswap.ToolSelector;

/**
 * 新版自动工具换位的单一纯状态核心。
 *
 * <p>本类不读写库存、不分配 transaction id、不访问游戏单例。adapter 只按命令调用原版事务，
 * 再把 packet id、ACK、后续 tick 和槽快照回送。任何时刻最多保留一个 ledger 与一个 in-flight。</p>
 */
public final class AutoToolSwapController {

    public static final int MATCH_INTERVAL_TICKS = 10;

    private final int transactionTimeoutTicks;
    private final List<ToolSwapCommand> commands = new ArrayList<ToolSwapCommand>();

    private AutoToolSwapState state = AutoToolSwapState.IDLE;
    private ToolSwapTransactionState transactionState = ToolSwapTransactionState.IDLE;
    private boolean keyDown;
    private long generation;
    private int anchorSlot;
    private long nextMatchTick;
    private long transactionStartedTick;
    private long ackTick;
    private Integer transactionId;
    private Operation operation;
    private Ledger ledger;
    private ToolSwapContext lastContext;

    private boolean configuredEnabled;
    private List<ToolSelector> configuredSelectors;
    private boolean cycleEnabled;
    private List<ToolSelector> cycleSelectors = Collections.emptyList();

    private boolean freezeRequested;
    private boolean closeRequested;
    private boolean closeToWaitRelease;
    private boolean rematchAfterRestore;
    private Integer pendingAnchor;

    public AutoToolSwapController(boolean enabled, List<ToolSelector> selectors, int transactionTimeoutTicks) {
        if (transactionTimeoutTicks <= 0) {
            throw new IllegalArgumentException("transactionTimeoutTicks must be positive");
        }
        this.configuredEnabled = enabled;
        this.configuredSelectors = immutableSelectors(selectors);
        this.transactionTimeoutTicks = transactionTimeoutTicks;
    }

    public AutoToolSwapState state() {
        return state;
    }

    public ToolSwapTransactionState transactionState() {
        return transactionState;
    }

    public long generation() {
        return generation;
    }

    public boolean hasLedger() {
        return ledger != null;
    }

    /** @return 并清空待 adapter 执行的命令 */
    public List<ToolSwapCommand> drainCommands() {
        List<ToolSwapCommand> drained = Collections.unmodifiableList(new ArrayList<ToolSwapCommand>(commands));
        commands.clear();
        return drained;
    }

    /**
     * 输入按键电平；只有 false→true 的真实边沿会创建新 generation。
     */
    public void onKeyState(boolean down, ToolSwapContext context) {
        remember(context);
        if (down == keyDown) {
            return;
        }
        keyDown = down;
        if (!down) {
            if (state == AutoToolSwapState.WAIT_RELEASE) {
                state = AutoToolSwapState.IDLE;
            } else if (state != AutoToolSwapState.IDLE && state != AutoToolSwapState.ABORTED_SYNC) {
                requestClose(false);
            }
            return;
        }
        if (state != AutoToolSwapState.IDLE) {
            if (closeRequested) {
                // 收口中的快速重按不能被误认为新轮；收口后保持等待，直到下一次完整松开。
                closeToWaitRelease = true;
            }
            return;
        }
        startCycle(context);
    }

    /** 推进 tick、10 tick 水位、GUI/re-anchor 与事务超时。 */
    public void onTick(ToolSwapContext context) {
        remember(context);
        if (transactionState != ToolSwapTransactionState.IDLE
                && transactionState != ToolSwapTransactionState.SYNC_ISOLATION
                && context.tick - transactionStartedTick >= transactionTimeoutTicks) {
            abortSynchronization();
            return;
        }
        if (transactionState == ToolSwapTransactionState.WAIT_SYNC_TICK && context.tick > ackTick) {
            transactionState = ToolSwapTransactionState.VERIFY_SLOTS;
            commands.add(command(ToolSwapCommand.Type.VERIFY_SLOTS));
        }
        if (!isCycleLive()) {
            return;
        }
        if (!context.chainActive) {
            requestClose(keyDown);
            return;
        }
        if (context.selectedHotbarSlot != anchorSlot) {
            requestReanchor(context.selectedHotbarSlot);
        }
        if (context.guiOpen) {
            if (ledger != null || operation != null) {
                rematchAfterRestore = true;
                beginRestoreWhenPossible();
            }
            return;
        }
        if (state == AutoToolSwapState.PREPARING && transactionState == ToolSwapTransactionState.IDLE
                && context.tick >= nextMatchTick) {
            evaluate(context);
        }
    }

    /**
     * 配置提交。关闭立即收口；启用与 selector 变化只在下一真实上升沿捕获。
     */
    public void onConfigChanged(boolean enabled, List<ToolSelector> selectors) {
        boolean wasEnabled = configuredEnabled;
        configuredEnabled = enabled;
        configuredSelectors = immutableSelectors(selectors);
        if (wasEnabled && !enabled && isCycleLive()) {
            requestClose(keyDown);
        }
    }

    /** 本地首块成功信号，按 generation 单向冻结。 */
    public void onLocalBlockDestroyed(long eventGeneration) {
        requestFreeze(eventGeneration);
    }

    /** 可归因服务端 phase 投影，按 generation 单向冻结。 */
    public void onServerActivity(long eventGeneration) {
        requestFreeze(eventGeneration);
    }

    /** adapter 在原版点击得到 transaction id 后回送。 */
    public void onPacketIdAssigned(long eventGeneration, int packetId, long tick) {
        if (!isCurrent(eventGeneration) || transactionState != ToolSwapTransactionState.WAIT_PACKET_ID) {
            return;
        }
        transactionId = Integer.valueOf(packetId);
        transactionState = ToolSwapTransactionState.WAIT_ACK;
        transactionStartedTick = tick;
    }

    /** adapter 回送 S32；负 ACK 立即进入同步隔离且绝不继续点击。 */
    public void onTransactionAck(long eventGeneration, int packetId, boolean accepted, long tick) {
        if (!isCurrent(eventGeneration) || transactionState != ToolSwapTransactionState.WAIT_ACK
                || transactionId == null || transactionId.intValue() != packetId) {
            return;
        }
        if (!accepted) {
            abortSynchronization();
            return;
        }
        ackTick = tick;
        transactionState = ToolSwapTransactionState.WAIT_SYNC_TICK;
    }

    /**
     * 正 ACK 后至少下一 tick 的两槽核对。角色比较忽略 dynamic fingerprint。
     */
    public void onSlotsObserved(long eventGeneration, int packetId, ToolSwapInventorySnapshot inventory) {
        if (!isCurrent(eventGeneration) || transactionState != ToolSwapTransactionState.VERIFY_SLOTS
                || transactionId == null || transactionId.intValue() != packetId || operation == null) {
            return;
        }
        if (inventory == null) {
            abortSynchronization();
            return;
        }
        boolean matches = operation.restore ? ledger.matchesRestored(inventory, generation)
                : ledger.matchesSwapped(inventory, generation);
        if (!matches) {
            abortSynchronization();
            return;
        }
        boolean restored = operation.restore;
        clearTransaction();
        if (restored) {
            ledger = null;
            finishRestore();
        } else {
            finishSwap();
        }
    }

    /** 同步隔离由 adapter 确认稳定后显式解除；本轮不会恢复匹配。 */
    public void onSynchronizationRecovered() {
        if (state != AutoToolSwapState.ABORTED_SYNC) {
            return;
        }
        ledger = null;
        clearTransaction();
        state = keyDown ? AutoToolSwapState.WAIT_RELEASE : AutoToolSwapState.IDLE;
    }

    private void startCycle(ToolSwapContext context) {
        generation = incrementGeneration(generation);
        cycleEnabled = configuredEnabled;
        cycleSelectors = configuredSelectors;
        freezeRequested = false;
        closeRequested = false;
        rematchAfterRestore = false;
        pendingAnchor = null;
        anchorSlot = context.selectedHotbarSlot;
        nextMatchTick = context.tick;
        if (!cycleEnabled || !context.breakCapable || context.creative || !context.chainActive) {
            state = AutoToolSwapState.WAIT_RELEASE;
            return;
        }
        state = AutoToolSwapState.PREPARING;
        if (!context.guiOpen) {
            evaluate(context);
        }
    }

    private void evaluate(ToolSwapContext context) {
        nextMatchTick = advanceWatermark(nextMatchTick, context.tick);
        ToolCandidate current = context.inventory.candidateAt(anchorSlot);
        if (current != null && current.isUsableInHand()) {
            return;
        }
        if (ledger != null || operation != null) {
            rematchAfterRestore = true;
            beginRestoreWhenPossible();
            return;
        }
        List<ToolCandidate> ordered = ToolCandidateOrder.sort(context.inventory.candidates(), cycleSelectors);
        for (ToolCandidate candidate : ordered) {
            if (candidate.slot() != anchorSlot) {
                beginSwap(context.inventory, candidate.slot(), context.tick);
                return;
            }
        }
    }

    private void beginSwap(ToolSwapInventorySnapshot inventory, int candidateSlot, long tick) {
        if (transactionState != ToolSwapTransactionState.IDLE || ledger != null) {
            throw new IllegalStateException("single ledger/transaction invariant violated");
        }
        SlotSnapshot anchor = inventory.slot(anchorSlot);
        SlotSnapshot candidate = inventory.slot(candidateSlot);
        if (anchor == null || candidate == null) {
            abortSynchronization();
            return;
        }
        ledger = new Ledger(generation, anchorSlot, candidateSlot, anchor, candidate);
        operation = new Operation(false);
        transactionState = ToolSwapTransactionState.WAIT_PACKET_ID;
        transactionStartedTick = tick;
        commands.add(command(ToolSwapCommand.Type.BEGIN_SWAP));
    }

    private void beginRestoreWhenPossible() {
        if (ledger == null) {
            if (operation == null) {
                finishRestore();
            }
            return;
        }
        state = AutoToolSwapState.RESTORING;
        if (transactionState != ToolSwapTransactionState.IDLE) {
            return;
        }
        ToolSwapInventorySnapshot inventory = lastContext == null ? null : lastContext.inventory;
        if (inventory == null || !ledger.matchesSwapped(inventory, generation)) {
            abortSynchronization();
            return;
        }
        operation = new Operation(true);
        transactionState = ToolSwapTransactionState.WAIT_PACKET_ID;
        transactionStartedTick = lastContext.tick;
        commands.add(command(ToolSwapCommand.Type.BEGIN_RESTORE));
    }

    private void finishSwap() {
        if (closeRequested || rematchAfterRestore || pendingAnchor != null
                || (lastContext != null && lastContext.guiOpen)) {
            beginRestoreWhenPossible();
        } else if (freezeRequested) {
            state = AutoToolSwapState.FROZEN;
        } else {
            state = AutoToolSwapState.PREPARING;
        }
    }

    private void finishRestore() {
        if (closeRequested) {
            state = closeToWaitRelease && keyDown ? AutoToolSwapState.WAIT_RELEASE : AutoToolSwapState.IDLE;
            resetCycleFlags();
            return;
        }
        if (pendingAnchor != null) {
            anchorSlot = pendingAnchor.intValue();
            pendingAnchor = null;
        }
        boolean rematch = rematchAfterRestore;
        rematchAfterRestore = false;
        if (freezeRequested) {
            state = AutoToolSwapState.FROZEN;
        } else {
            state = AutoToolSwapState.PREPARING;
            if (rematch && lastContext != null && !lastContext.guiOpen) {
                nextMatchTick = lastContext.tick;
            }
        }
    }

    private void requestReanchor(int newAnchor) {
        if (state != AutoToolSwapState.PREPARING && state != AutoToolSwapState.FROZEN) {
            return;
        }
        if (ledger != null || operation != null) {
            pendingAnchor = Integer.valueOf(newAnchor);
            rematchAfterRestore = state == AutoToolSwapState.PREPARING;
            beginRestoreWhenPossible();
        } else {
            anchorSlot = newAnchor;
            if (state == AutoToolSwapState.PREPARING) {
                nextMatchTick = lastContext.tick;
            }
        }
    }

    private void requestFreeze(long eventGeneration) {
        if (!isCurrent(eventGeneration) || state == AutoToolSwapState.IDLE
                || state == AutoToolSwapState.WAIT_RELEASE || state == AutoToolSwapState.ABORTED_SYNC) {
            return;
        }
        freezeRequested = true;
        if (transactionState == ToolSwapTransactionState.IDLE && state != AutoToolSwapState.RESTORING) {
            state = AutoToolSwapState.FROZEN;
        }
    }

    private void requestClose(boolean waitRelease) {
        closeRequested = true;
        closeToWaitRelease = waitRelease;
        rematchAfterRestore = false;
        pendingAnchor = null;
        if (ledger == null && operation == null) {
            state = waitRelease && keyDown ? AutoToolSwapState.WAIT_RELEASE : AutoToolSwapState.IDLE;
            resetCycleFlags();
            return;
        }
        beginRestoreWhenPossible();
    }

    private void abortSynchronization() {
        commands.clear();
        state = AutoToolSwapState.ABORTED_SYNC;
        transactionState = ToolSwapTransactionState.SYNC_ISOLATION;
        operation = null;
        transactionId = null;
    }

    private void clearTransaction() {
        transactionState = ToolSwapTransactionState.IDLE;
        operation = null;
        transactionId = null;
    }

    private void resetCycleFlags() {
        cycleEnabled = false;
        cycleSelectors = Collections.emptyList();
        freezeRequested = false;
        closeRequested = false;
        closeToWaitRelease = false;
        rematchAfterRestore = false;
        pendingAnchor = null;
    }

    private ToolSwapCommand command(ToolSwapCommand.Type type) {
        return new ToolSwapCommand(type, generation, ledger.anchorSlot, ledger.candidateSlot, transactionId);
    }

    private boolean isCurrent(long eventGeneration) {
        return eventGeneration == generation && state != AutoToolSwapState.IDLE;
    }

    private boolean isCycleLive() {
        return state == AutoToolSwapState.PREPARING || state == AutoToolSwapState.FROZEN
                || state == AutoToolSwapState.RESTORING;
    }

    private void remember(ToolSwapContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        lastContext = context;
    }

    private static long advanceWatermark(long previous, long currentTick) {
        long next = previous;
        do {
            if (next > Long.MAX_VALUE - MATCH_INTERVAL_TICKS) {
                return Long.MAX_VALUE;
            }
            next += MATCH_INTERVAL_TICKS;
        } while (next <= currentTick);
        return next;
    }

    private static long incrementGeneration(long value) {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("cycle generation overflow");
        }
        return value + 1L;
    }

    private static List<ToolSelector> immutableSelectors(List<ToolSelector> selectors) {
        return Collections.unmodifiableList(new ArrayList<ToolSelector>(
                selectors == null ? Collections.<ToolSelector>emptyList() : selectors));
    }

    private static final class Operation {
        private final boolean restore;

        private Operation(boolean restore) {
            this.restore = restore;
        }
    }

    /** 单一可逆换位账本，只比较受保护槽的角色。 */
    private static final class Ledger {
        private final long generation;
        private final int anchorSlot;
        private final int candidateSlot;
        private final SlotSnapshot anchorRole;
        private final SlotSnapshot candidateRole;

        private Ledger(long generation, int anchorSlot, int candidateSlot,
                SlotSnapshot anchorRole, SlotSnapshot candidateRole) {
            this.generation = generation;
            this.anchorSlot = anchorSlot;
            this.candidateSlot = candidateSlot;
            this.anchorRole = anchorRole;
            this.candidateRole = candidateRole;
        }

        private boolean matchesSwapped(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            return generation == currentGeneration && candidateRole.sameRole(inventory.slot(anchorSlot))
                    && anchorRole.sameRole(inventory.slot(candidateSlot));
        }

        private boolean matchesRestored(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            return generation == currentGeneration && anchorRole.sameRole(inventory.slot(anchorSlot))
                    && candidateRole.sameRole(inventory.slot(candidateSlot));
        }
    }
}
