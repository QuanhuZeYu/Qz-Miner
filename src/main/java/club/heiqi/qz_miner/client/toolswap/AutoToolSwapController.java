package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolCandidateOrder;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/**
 * 自动工具换位的纯产品状态核心。
 *
 * <p>controller 不接触网络、Minecraft 单例或库存写入。adapter 负责在主线程捕获快照、发送 Qz intent，
 * 再把协议结算和本地库存观察回送。本类仅维持一个可逆 ledger 与一个等待结算的动作。</p>
 */
public final class AutoToolSwapController {

    public static final int MATCH_INTERVAL_TICKS = 10;
    public static final int INVENTORY_SYNC_TIMEOUT_TICKS = 40;
    public static final int MAX_CONSECUTIVE_RESTORE_REJECTIONS = 3;

    private final List<ToolSwapCommand> commands = new ArrayList<ToolSwapCommand>();
    private AutoToolSwapState state = AutoToolSwapState.IDLE;
    private ToolSwapTransactionState transactionState = ToolSwapTransactionState.IDLE;
    private boolean keyDown;
    private long generation;
    private int anchorSlot;
    private long nextMatchTick;
    private long inventoryVerifyStartedTick;
    private boolean configuredEnabled;
    private List<ToolSelector> configuredSelectors;
    private boolean cycleEnabled;
    private List<ToolSelector> cycleSelectors = Collections.emptyList();
    private boolean roundAccepted;
    private boolean freezeRequested;
    private boolean closeRequested;
    private CloseReason closeReason = CloseReason.NONE;
    private boolean deferredRoundPending;
    private boolean rematchAfterRestore;
    private Integer pendingAnchor;
    private ToolSwapCommand.Type queuedCommandType;
    private AutoToolSwapAction pendingAction;
    private AutoToolSwapAction verifyingAction;
    private Ledger ledger;
    private ToolSwapContext lastContext;
    private int consecutiveRestoreRejections;

    public AutoToolSwapController(boolean enabled, List<ToolSelector> selectors, int ignoredTimeoutTicks) {
        configuredEnabled = enabled;
        configuredSelectors = immutableSelectors(selectors);
    }

    public AutoToolSwapState state() { return state; }
    public ToolSwapTransactionState transactionState() { return transactionState; }
    public long generation() { return generation; }
    public boolean hasLedger() { return ledger != null; }
    public int protectedAnchorSlot() { return ledger == null ? -1 : ledger.anchorSlot; }
    public int protectedCandidateSlot() { return ledger == null ? -1 : ledger.candidateSlot; }

    /** 按键边沿前的只读库存捕获计划。 */
    public ToolSwapCapturePlan capturePlanForKeyState(boolean down, ToolSwapLightContext context, boolean preFrozen) {
        if (context == null || down == keyDown) return ToolSwapCapturePlan.NONE;
        if (!down) return needsProtectedCapture() ? ToolSwapCapturePlan.PROTECTED : ToolSwapCapturePlan.NONE;
        return context.guiOpen || preFrozen ? ToolSwapCapturePlan.NONE : ToolSwapCapturePlan.FULL;
    }

    /** tick 前的只读库存捕获计划。 */
    public ToolSwapCapturePlan capturePlanForTick(ToolSwapLightContext context) {
        if (context == null || state == AutoToolSwapState.IDLE || state == AutoToolSwapState.WAIT_RELEASE) {
            return ToolSwapCapturePlan.NONE;
        }
        if (needsProtectedCapture() || context.guiOpen) return ToolSwapCapturePlan.PROTECTED;
        if (state == AutoToolSwapState.PREPARING && context.tick >= nextMatchTick) {
            return ToolSwapCapturePlan.FULL;
        }
        return ToolSwapCapturePlan.NONE;
    }

    /** 生命周期硬重置，不跨连接发送恢复或关闭。 */
    public void reset() {
        generation = incrementGeneration(generation);
        commands.clear();
        state = AutoToolSwapState.IDLE;
        transactionState = ToolSwapTransactionState.IDLE;
        keyDown = false;
        anchorSlot = 0;
        nextMatchTick = 0L;
        inventoryVerifyStartedTick = 0L;
        roundAccepted = false;
        queuedCommandType = null;
        pendingAction = null;
        verifyingAction = null;
        ledger = null;
        lastContext = null;
        consecutiveRestoreRejections = 0;
        deferredRoundPending = false;
        resetCycleFlags();
    }

    /** @return 并清空待 adapter 执行的命令。 */
    public List<ToolSwapCommand> drainCommands() {
        List<ToolSwapCommand> result = Collections.unmodifiableList(new ArrayList<ToolSwapCommand>(commands));
        commands.clear();
        queuedCommandType = null;
        return result;
    }

    /** 输入真实按键电平；每次上升沿先建立服务端 round。 */
    public void onKeyState(boolean down, ToolSwapContext context, boolean preFrozen) {
        remember(context);
        if (down == keyDown) return;
        keyDown = down;
        if (!down) {
            cancelDeferredRound();
            if (state == AutoToolSwapState.WAIT_RELEASE) {
                finishWaitRelease();
                return;
            }
            requestClose(CloseReason.RELEASE_GATED);
            return;
        }
        if (state != AutoToolSwapState.IDLE) {
            if (closeRequested) requestClose(CloseReason.RELEASE_GATED);
            return;
        }
        startCycle(context, preFrozen);
    }

    public void onKeyState(boolean down, ToolSwapContext context) {
        onKeyState(down, context, false);
    }

    /** 推进 10 tick 匹配水位、GUI/re-anchor 与库存双门。 */
    public void onTick(ToolSwapContext context) {
        remember(context);
        if (transactionState == ToolSwapTransactionState.INVENTORY_SYNC_VERIFY) {
            onInventoryObserved(context.inventory, context.tick);
            return;
        }
        if (state == AutoToolSwapState.ABORTED_SYNC || state == AutoToolSwapState.IDLE
                || state == AutoToolSwapState.WAIT_RELEASE) return;
        if (context.guiOpen) {
            if (ledger != null || pendingAction == AutoToolSwapAction.SWAP) {
                rematchAfterRestore = state == AutoToolSwapState.PREPARING;
                prepareRestore();
            }
            drive(context);
            return;
        }
        if (context.selectedHotbarSlot != anchorSlot) requestReanchor(context.selectedHotbarSlot);
        if (state == AutoToolSwapState.PREPARING && pendingAction == null && ledger == null
                && context.tick >= nextMatchTick && roundAccepted) evaluate(context);
        drive(context);
    }

    /** 配置关闭设置收尾义务，配置快照仅在下一个真实上升沿采用。 */
    public void onConfigChanged(boolean enabled, List<ToolSelector> selectors) {
        boolean wasEnabled = configuredEnabled;
        configuredEnabled = enabled;
        configuredSelectors = immutableSelectors(selectors);
        if (wasEnabled && !enabled) {
            cancelDeferredRound();
            if (state != AutoToolSwapState.IDLE) requestClose(CloseReason.RELEASE_GATED);
        }
    }

    /** 本地首块成功，按当前 generation 单向冻结。 */
    public void onLocalBlockDestroyed(long eventGeneration) {
        requestFreeze(eventGeneration);
    }

    /** 专用 round phase 的活动态冻结，不使用通用 projection 推测。 */
    public void onDedicatedPhase(ChainPhase phase) {
        if (phase == ChainPhase.PLANNING || phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING) {
            requestFreeze(generation);
        } else if (phase == ChainPhase.IDLE) {
            requestClose(keyDown ? CloseReason.NATURAL_REARM : CloseReason.RELEASE_GATED);
        }
    }

    /** 服务端接受当前 round 后才允许发动作。 */
    public void onRoundAccepted() {
        if (transactionState != ToolSwapTransactionState.ROUND_PENDING) return;
        transactionState = ToolSwapTransactionState.IDLE;
        roundAccepted = true;
        if (lastContext != null) drive(lastContext);
    }

    /** 当前 nonce 被拒绝，不能让局部 ledger 发送 swap。 */
    public void onRoundRejected() {
        if (transactionState != ToolSwapTransactionState.ROUND_PENDING) return;
        transactionState = ToolSwapTransactionState.IDLE;
        roundAccepted = false;
        commands.clear();
        queuedCommandType = null;
        ledger = null;
        pendingAction = null;
        deferredRoundPending = false;
        state = keyDown ? AutoToolSwapState.WAIT_RELEASE : AutoToolSwapState.IDLE;
        resetCycleFlags();
    }

    /** adapter 在发送 SWAP/RESTORE 前以新鲜保护槽快照复核。 */
    public boolean onActionPreflight(ToolSwapCommand command, ToolSwapContext context) {
        if (context == null || !context.inventoryTransactionSafe) {
            onActionNotStarted(actionFor(command));
            return false;
        }
        return onActionPreflight(command, context.inventory);
    }

    /** 纯快照入口供无 Minecraft 依赖的状态测试复核 ledger 布局。 */
    public boolean onActionPreflight(ToolSwapCommand command, ToolSwapInventorySnapshot inventory) {
        if (command == null || command.generation != generation || !isSwapOrRestore(command.type)
                || ledger == null || pendingAction == null || transactionState != ToolSwapTransactionState.IDLE) {
            return false;
        }
        boolean restore = pendingAction == AutoToolSwapAction.RESTORE;
        if (restore) {
            if (!hasTrustedProtectedSlots(inventory)) return false;
            if (ledger.matchesSwapped(inventory, generation)) return true;
            protocolOrphaned();
            return false;
        }
        if (hasTrustedProtectedSlots(inventory) && ledger.matchesStrictRestored(inventory, generation)) return true;
        onActionNotStarted(AutoToolSwapAction.SWAP);
        return false;
    }

    /** adapter 成功将 immutable intent 交给 transport。 */
    public void onActionStarted(AutoToolSwapAction action) {
        if (action == null || transactionState != ToolSwapTransactionState.IDLE) return;
        transactionState = ToolSwapTransactionState.ACTION_RESULT_PENDING;
        pendingAction = action;
    }

    /** preflight 后尚未开始发送的动作取消。 */
    public void onActionNotStarted(AutoToolSwapAction action) {
        if (action != AutoToolSwapAction.SWAP || transactionState != ToolSwapTransactionState.IDLE
                || pendingAction != AutoToolSwapAction.SWAP) return;
        discardUnstartedSwap(lastContext == null ? 0L : lastContext.tick);
    }

    /** 协议拒绝创建控制 intent 时，按协议阶段收敛本地义务。 */
    public void onControlActionNotStarted(AutoToolSwapAction action, boolean protocolClosing) {
        onActionNotStarted(action);
        if (action != AutoToolSwapAction.FREEZE || !protocolClosing
                || transactionState != ToolSwapTransactionState.IDLE) return;
        freezeRequested = false;
        requestClose(CloseReason.RELEASE_GATED);
    }

    /** 协议层已精确归因的单次结算。 */
    public void onActionSettled(AutoToolSwapAction action, AutoToolSwapResultCode result,
            AutoToolSwapRoundState serverRoundState, long tick) {
        if (transactionState != ToolSwapTransactionState.ACTION_RESULT_PENDING || action != pendingAction
                || result == null || serverRoundState == null) return;
        transactionState = ToolSwapTransactionState.IDLE;
        if (result == AutoToolSwapResultCode.SYNC_FAILED) {
            protocolOrphaned();
            return;
        }
        if (action == AutoToolSwapAction.SWAP) {
            if (result == AutoToolSwapResultCode.APPLIED) beginInventoryVerify(action, tick);
            else if (result == AutoToolSwapResultCode.REJECTED) {
                discardUnstartedSwap(tick);
                if (serverRoundState == AutoToolSwapRoundState.FROZEN) {
                    freezeRequested = false;
                    state = AutoToolSwapState.FROZEN;
                } else if (serverRoundState == AutoToolSwapRoundState.CLOSING) {
                    freezeRequested = false;
                    requestClose(CloseReason.RELEASE_GATED);
                } else if (serverRoundState == AutoToolSwapRoundState.OPEN) {
                    state = freezeRequested ? AutoToolSwapState.FROZEN : AutoToolSwapState.PREPARING;
                    if (lastContext != null) drive(lastContext);
                } else {
                    protocolOrphaned();
                }
            } else protocolOrphaned();
            return;
        }
        if (action == AutoToolSwapAction.RESTORE) {
            if (result == AutoToolSwapResultCode.APPLIED) {
                consecutiveRestoreRejections = 0;
                beginInventoryVerify(action, tick);
            }
            else if (result == AutoToolSwapResultCode.REJECTED) {
                if (++consecutiveRestoreRejections >= MAX_CONSECUTIVE_RESTORE_REJECTIONS) {
                    protocolOrphaned();
                } else {
                    pendingAction = AutoToolSwapAction.RESTORE;
                    state = AutoToolSwapState.RESTORING;
                }
            } else protocolOrphaned();
            return;
        }
        if (action == AutoToolSwapAction.FREEZE && (result == AutoToolSwapResultCode.ACCEPTED
                || result == AutoToolSwapResultCode.APPLIED)) {
            pendingAction = null;
            freezeRequested = false;
            state = AutoToolSwapState.FROZEN;
            return;
        }
        if (action == AutoToolSwapAction.FREEZE && result == AutoToolSwapResultCode.REJECTED
                && serverRoundState == AutoToolSwapRoundState.CLOSING) {
            pendingAction = null;
            freezeRequested = false;
            requestClose(CloseReason.RELEASE_GATED);
            return;
        }
        if (action == AutoToolSwapAction.CLOSE) {
            if (result == AutoToolSwapResultCode.RESTORE_REQUIRED) {
                pendingAction = AutoToolSwapAction.RESTORE;
                state = AutoToolSwapState.RESTORING;
            } else if (result == AutoToolSwapResultCode.ACCEPTED || result == AutoToolSwapResultCode.APPLIED) {
                pendingAction = null;
                finishClose(serverRoundState == AutoToolSwapRoundState.FINISHED);
            } else protocolOrphaned();
            return;
        }
        protocolOrphaned();
    }

    /** APPLIED 后每 tick 仅使用受保护槽观察本地库存。 */
    public void onInventoryObserved(ToolSwapInventorySnapshot inventory, long tick) {
        if (transactionState != ToolSwapTransactionState.INVENTORY_SYNC_VERIFY || ledger == null) return;
        long elapsed = tick - inventoryVerifyStartedTick;
        if (!hasTrustedProtectedSlots(inventory)) {
            if (elapsed >= INVENTORY_SYNC_TIMEOUT_TICKS) protocolOrphaned();
            return;
        }
        boolean target = verifyingAction == AutoToolSwapAction.SWAP
                ? ledger.matchesSwapped(inventory, generation) : ledger.matchesRestored(inventory, generation);
        boolean source = verifyingAction == AutoToolSwapAction.SWAP
                ? ledger.matchesStrictRestored(inventory, generation) : ledger.matchesSwapped(inventory, generation);
        if (target) {
            transactionState = ToolSwapTransactionState.IDLE;
            if (verifyingAction == AutoToolSwapAction.SWAP) {
                ledger.markSwapConfirmed();
                pendingAction = null;
                finishSwap();
            } else {
                ledger = null;
                pendingAction = null;
                finishRestore();
            }
            verifyingAction = null;
            if (lastContext != null) drive(lastContext);
            return;
        }
        if (!source || elapsed >= INVENTORY_SYNC_TIMEOUT_TICKS) protocolOrphaned();
    }

    /** adapter 在 transport 异常、超时或协议失配时调用。 */
    public void protocolOrphaned() {
        commands.clear();
        queuedCommandType = null;
        state = AutoToolSwapState.ABORTED_SYNC;
        transactionState = ToolSwapTransactionState.PROTOCOL_ORPHANED;
        roundAccepted = false;
        pendingAction = null;
        verifyingAction = null;
        deferredRoundPending = false;
        resetCycleFlags();
    }

    /** 在自然关闭后的后续 tick 消费一次重武装资格，不暴露新的公开状态。 */
    boolean beginDeferredRound(ToolSwapContext context) {
        if (!deferredRoundPending) return false;
        deferredRoundPending = false;
        if (context == null || !keyDown || state != AutoToolSwapState.IDLE
                || transactionState != ToolSwapTransactionState.IDLE || !configuredEnabled) {
            return false;
        }
        remember(context);
        startCycle(context, false);
        return queuedCommandType == ToolSwapCommand.Type.BEGIN_ROUND;
    }

    /** 物理松键、生命周期或其他 fail-closed 条件使自然重武装资格永久失效。 */
    void cancelDeferredRound() {
        deferredRoundPending = false;
    }

    /** 在捕获完整库存前廉价确认 deferred 资格；物理松键立即单调取消。 */
    boolean prepareDeferredRoundCapture(boolean physicallyDown) {
        if (!deferredRoundPending) return false;
        if (!keyDown || !physicallyDown) {
            cancelDeferredRound();
            return false;
        }
        return true;
    }

    private void startCycle(ToolSwapContext context, boolean preFrozen) {
        deferredRoundPending = false;
        generation = incrementGeneration(generation);
        cycleEnabled = configuredEnabled;
        cycleSelectors = configuredSelectors;
        anchorSlot = context.selectedHotbarSlot;
        nextMatchTick = context.tick;
        roundAccepted = false;
        freezeRequested = preFrozen;
        closeRequested = false;
        closeReason = CloseReason.NONE;
        rematchAfterRestore = false;
        pendingAnchor = null;
        ledger = null;
        pendingAction = null;
        transactionState = ToolSwapTransactionState.IDLE;
        consecutiveRestoreRejections = 0;
        if (!cycleEnabled || !context.breakCapable || context.creative || !context.chainActive) {
            state = AutoToolSwapState.WAIT_RELEASE;
            resetCycleFlags();
            return;
        }
        transactionState = ToolSwapTransactionState.ROUND_PENDING;
        state = preFrozen ? AutoToolSwapState.FROZEN : AutoToolSwapState.PREPARING;
        queue(ToolSwapCommand.Type.BEGIN_ROUND);
    }

    private void evaluate(ToolSwapContext context) {
        nextMatchTick = advanceWatermark(nextMatchTick, context.tick);
        if (!context.inventory.isFullCandidateScan()) return;
        ToolCandidate held = context.inventory.candidateAt(anchorSlot);
        if (held != null && held.isUsableInHand()) return;
        for (ToolCandidate candidate : ToolCandidateOrder.sort(context.inventory.candidates(), cycleSelectors)) {
            if (candidate.slot() != anchorSlot && beginSwap(context, candidate.slot())) return;
        }
    }

    private boolean beginSwap(ToolSwapContext context, int candidateSlot) {
        SlotSnapshot anchor = context.inventory.slot(anchorSlot);
        SlotSnapshot candidate = context.inventory.slot(candidateSlot);
        if (anchor == null || candidate == null || candidate.isEmpty()) return false;
        ledger = new Ledger(generation, anchorSlot, candidateSlot, anchor, candidate);
        pendingAction = AutoToolSwapAction.SWAP;
        return true;
    }

    private void drive(ToolSwapContext context) {
        if (!roundAccepted || transactionState != ToolSwapTransactionState.IDLE || queuedCommandType != null) return;
        if (closeRequested) {
            if (ledger != null && pendingAction != AutoToolSwapAction.RESTORE) prepareRestore();
            if (pendingAction == null && ledger == null) queue(ToolSwapCommand.Type.SEND_CLOSE);
            else if (pendingAction == AutoToolSwapAction.RESTORE) queue(ToolSwapCommand.Type.SEND_RESTORE);
            return;
        }
        if (freezeRequested && pendingAction == null) {
            queue(ToolSwapCommand.Type.SEND_FREEZE);
            return;
        }
        if (pendingAction == AutoToolSwapAction.SWAP) queue(ToolSwapCommand.Type.SEND_SWAP);
        else if (pendingAction == AutoToolSwapAction.RESTORE) queue(ToolSwapCommand.Type.SEND_RESTORE);
    }

    private void requestFreeze(long eventGeneration) {
        if (eventGeneration != generation || state == AutoToolSwapState.IDLE
                || state == AutoToolSwapState.WAIT_RELEASE || state == AutoToolSwapState.ABORTED_SYNC) return;
        if (transactionState == ToolSwapTransactionState.IDLE
                && queuedCommandType == ToolSwapCommand.Type.SEND_SWAP) {
            cancelQueuedCommand();
            discardUnstartedSwap(lastContext == null ? 0L : lastContext.tick);
        }
        freezeRequested = true;
        if (pendingAction == null && transactionState == ToolSwapTransactionState.IDLE) state = AutoToolSwapState.FROZEN;
        if (lastContext != null) drive(lastContext);
    }

    private void requestClose(CloseReason requestedReason) {
        if (requestedReason == CloseReason.RELEASE_GATED) cancelDeferredRound();
        if (state == AutoToolSwapState.IDLE || state == AutoToolSwapState.ABORTED_SYNC) return;
        ToolSwapCommand.Type cancelled = cancelQueuedCommand();
        closeRequested = true;
        if (requestedReason == CloseReason.RELEASE_GATED || closeReason == CloseReason.NONE) {
            closeReason = requestedReason;
        }
        if (cancelled == ToolSwapCommand.Type.SEND_SWAP) {
            discardUnstartedSwap(lastContext == null ? 0L : lastContext.tick);
        } else {
            rematchAfterRestore = false;
            pendingAnchor = null;
        }
        if (ledger != null && transactionState != ToolSwapTransactionState.ACTION_RESULT_PENDING
                && transactionState != ToolSwapTransactionState.INVENTORY_SYNC_VERIFY) prepareRestore();
        if (lastContext != null) drive(lastContext);
    }

    private void prepareRestore() {
        if (ledger == null) return;
        ToolSwapCommand.Type cancelled = cancelQueuedCommand();
        if (cancelled == ToolSwapCommand.Type.SEND_SWAP) {
            discardUnstartedSwap(lastContext == null ? 0L : lastContext.tick);
            return;
        }
        if (pendingAction == AutoToolSwapAction.SWAP) {
            return;
        }
        state = AutoToolSwapState.RESTORING;
        if (transactionState == ToolSwapTransactionState.IDLE) pendingAction = AutoToolSwapAction.RESTORE;
    }

    private void finishSwap() {
        if (closeRequested || rematchAfterRestore || pendingAnchor != null
                || (lastContext != null && lastContext.guiOpen)) prepareRestore();
        else state = freezeRequested ? AutoToolSwapState.FROZEN : AutoToolSwapState.PREPARING;
    }

    private void finishRestore() {
        if (closeRequested) {
            state = AutoToolSwapState.RESTORING;
            pendingAction = null;
            if (roundAccepted && transactionState == ToolSwapTransactionState.IDLE && queuedCommandType == null) {
                queue(ToolSwapCommand.Type.SEND_CLOSE);
            }
            return;
        }
        if (pendingAnchor != null) {
            anchorSlot = pendingAnchor.intValue();
            pendingAnchor = null;
        }
        if (freezeRequested) state = AutoToolSwapState.FROZEN;
        else {
            state = AutoToolSwapState.PREPARING;
            if (rematchAfterRestore && lastContext != null && !lastContext.guiOpen) nextMatchTick = lastContext.tick;
        }
        rematchAfterRestore = false;
    }

    private void finishClose(boolean exactlyFinished) {
        boolean naturalRearm = exactlyFinished && closeReason == CloseReason.NATURAL_REARM
                && keyDown && configuredEnabled;
        deferredRoundPending = naturalRearm;
        state = naturalRearm || !keyDown ? AutoToolSwapState.IDLE : AutoToolSwapState.WAIT_RELEASE;
        roundAccepted = false;
        ledger = null;
        resetCycleFlags();
    }

    /** 松键结束无 round 的等待态，不产生 CLOSE 或 RESTORE 义务。 */
    private void finishWaitRelease() {
        commands.clear();
        queuedCommandType = null;
        state = AutoToolSwapState.IDLE;
        transactionState = ToolSwapTransactionState.IDLE;
        roundAccepted = false;
        ledger = null;
        pendingAction = null;
        verifyingAction = null;
        deferredRoundPending = false;
        resetCycleFlags();
    }

    private void requestReanchor(int newAnchor) {
        if (state != AutoToolSwapState.PREPARING && state != AutoToolSwapState.FROZEN) return;
        if (ledger != null || pendingAction != null) {
            pendingAnchor = Integer.valueOf(newAnchor);
            rematchAfterRestore = state == AutoToolSwapState.PREPARING;
            prepareRestore();
        } else {
            anchorSlot = newAnchor;
            nextMatchTick = lastContext.tick;
        }
    }

    private void beginInventoryVerify(AutoToolSwapAction action, long tick) {
        verifyingAction = action;
        pendingAction = null;
        transactionState = ToolSwapTransactionState.INVENTORY_SYNC_VERIFY;
        inventoryVerifyStartedTick = tick;
    }

    private void queue(ToolSwapCommand.Type type) {
        queuedCommandType = type;
        commands.add(command(type));
    }

    /** 撤销尚未交给 adapter 的单个动作；已发送事务绝不在本地取消。 */
    private ToolSwapCommand.Type cancelQueuedCommand() {
        if (transactionState != ToolSwapTransactionState.IDLE || queuedCommandType == null) return null;
        ToolSwapCommand.Type cancelled = queuedCommandType;
        commands.clear();
        queuedCommandType = null;
        return cancelled;
    }

    /** 丢弃未发送或被服务端明确拒绝的 SWAP，并同步清理重匹配与 re-anchor 标记。 */
    private void discardUnstartedSwap(long tick) {
        if (transactionState != ToolSwapTransactionState.IDLE || pendingAction != AutoToolSwapAction.SWAP) return;
        pendingAction = null;
        ledger = null;
        rematchAfterRestore = false;
        if (pendingAnchor != null) {
            anchorSlot = pendingAnchor.intValue();
            pendingAnchor = null;
        }
        nextMatchTick = advanceWatermark(nextMatchTick, tick);
        if (!closeRequested && !freezeRequested) state = AutoToolSwapState.PREPARING;
    }

    private ToolSwapCommand command(ToolSwapCommand.Type type) {
        int protectedAnchor = ledger == null ? 0 : ledger.anchorSlot;
        int protectedCandidate = ledger == null ? 0 : ledger.candidateSlot;
        return new ToolSwapCommand(type, generation, protectedAnchor, protectedCandidate);
    }

    private boolean hasTrustedProtectedSlots(ToolSwapInventorySnapshot inventory) {
        return inventory != null && inventory.isTrusted() && ledger != null
                && inventory.covers(ledger.anchorSlot) && inventory.covers(ledger.candidateSlot);
    }

    private boolean needsProtectedCapture() {
        return ledger != null || transactionState == ToolSwapTransactionState.INVENTORY_SYNC_VERIFY
                || pendingAction == AutoToolSwapAction.SWAP || pendingAction == AutoToolSwapAction.RESTORE;
    }

    private void resetCycleFlags() {
        cycleEnabled = false;
        cycleSelectors = Collections.emptyList();
        freezeRequested = false;
        closeRequested = false;
        closeReason = CloseReason.NONE;
        rematchAfterRestore = false;
        pendingAnchor = null;
    }

    private void remember(ToolSwapContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        lastContext = context;
    }

    private static boolean isSwapOrRestore(ToolSwapCommand.Type type) {
        return type == ToolSwapCommand.Type.SEND_SWAP || type == ToolSwapCommand.Type.SEND_RESTORE;
    }

    private static AutoToolSwapAction actionFor(ToolSwapCommand command) {
        if (command == null) return null;
        return command.type == ToolSwapCommand.Type.SEND_SWAP ? AutoToolSwapAction.SWAP
                : command.type == ToolSwapCommand.Type.SEND_RESTORE ? AutoToolSwapAction.RESTORE : null;
    }

    private static long advanceWatermark(long previous, long current) {
        long next = previous;
        do {
            if (next > Long.MAX_VALUE - MATCH_INTERVAL_TICKS) return Long.MAX_VALUE;
            next += MATCH_INTERVAL_TICKS;
        } while (next <= current);
        return next;
    }

    private static long incrementGeneration(long value) {
        if (value == Long.MAX_VALUE) throw new IllegalStateException("cycle generation overflow");
        return value + 1L;
    }

    private static List<ToolSelector> immutableSelectors(List<ToolSelector> selectors) {
        return Collections.unmodifiableList(new ArrayList<ToolSelector>(
                selectors == null ? Collections.<ToolSelector>emptyList() : selectors));
    }

    /** CLOSE 的内部归因；显式松键门一旦出现便单调覆盖自然重武装。 */
    private enum CloseReason {
        NONE,
        NATURAL_REARM,
        RELEASE_GATED
    }

    /** 单一可逆账本，严格内容用于源布局，活动工具角色允许耐久变化或耗尽。 */
    private static final class Ledger {
        private final long generation;
        private final int anchorSlot;
        private final int candidateSlot;
        private final SlotSnapshot anchorRole;
        private final SlotSnapshot candidateRole;
        private boolean swapConfirmed;

        private Ledger(long generation, int anchorSlot, int candidateSlot, SlotSnapshot anchorRole,
                SlotSnapshot candidateRole) {
            this.generation = generation;
            this.anchorSlot = anchorSlot;
            this.candidateSlot = candidateSlot;
            this.anchorRole = anchorRole;
            this.candidateRole = candidateRole;
        }

        private boolean matchesSwapped(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            return generation == currentGeneration && activeToolRoleMatches(candidateRole, inventory.slot(anchorSlot))
                    && anchorRole.sameContent(inventory.slot(candidateSlot));
        }

        private boolean matchesRestored(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            return generation == currentGeneration && anchorRole.sameContent(inventory.slot(anchorSlot))
                    && (swapConfirmed ? activeToolRoleMatches(candidateRole, inventory.slot(candidateSlot))
                            : candidateRole.sameContent(inventory.slot(candidateSlot)));
        }

        private boolean matchesStrictRestored(ToolSwapInventorySnapshot inventory, long currentGeneration) {
            return generation == currentGeneration && !candidateRole.isEmpty()
                    && anchorRole.sameContent(inventory.slot(anchorSlot))
                    && candidateRole.sameContent(inventory.slot(candidateSlot));
        }

        private void markSwapConfirmed() { swapConfirmed = true; }

        private static boolean activeToolRoleMatches(SlotSnapshot expected, SlotSnapshot observed) {
            return expected.sameRole(observed) || (observed != null && observed.isEmpty());
        }
    }
}
