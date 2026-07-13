package club.heiqi.qz_miner.autotool;

/** 协调 window 0 热栏交换、确认、恢复与超时，不直接修改库存数组。 */
public final class VanillaInventoryTransactionBridge<T> implements VanillaInventoryTransactionObserver.Observer {
    public enum Status { ACTIVE, IDLE, PAUSED }
    public interface Listener { void onStatusChanged(Status status); }
    public interface ClickTransport {
        boolean canClick();
        void click(int sourceContainerSlot, int anchorHotbarIndex);
    }

    private static final int TIMEOUT_TICKS = 40;
    private final ToolSwapTransaction<T> transaction = new ToolSwapTransaction<T>();
    private final ClickTransport transport;
    private final Listener listener;
    private int expectedSlot = -1;
    private int expectedAnchor = -1;
    private int waitingTicks;

    public VanillaInventoryTransactionBridge(ClickTransport transport, Listener listener) {
        this.transport = transport;
        this.listener = listener;
    }

    /** 发起背包槽 9..35 到热栏 0..8 的原版 mode 2 交换。 */
    public boolean beginSwap(int sourceSlot, int anchorIndex, T sourceIdentity, T anchorIdentity, T latestDesired) {
        if (!validSlots(sourceSlot, anchorIndex) || !transport.canClick()) return false;
        ToolSwapTransaction.Outcome outcome = transaction.beginSelect(
                sourceSlot, anchorIndex, sourceIdentity, anchorIdentity, latestDesired);
        if (outcome.result != ToolSwapTransaction.Result.INTENT) return false;
        return send(sourceSlot, anchorIndex);
    }

    /** 请求恢复；调用方提供交换后两个位置的身份。 */
    public boolean requestRestore(T sourceAtAnchor, T anchorAtSource, T latestDesired) {
        if (!transport.canClick()) return false;
        ToolSwapTransaction.Outcome outcome = transaction.beginRestore(sourceAtAnchor, anchorAtSource, latestDesired);
        if (outcome.result != ToolSwapTransaction.Result.INTENT) return false;
        return send(outcome.intent.sourceContainerSlot, outcome.intent.anchorHotbarIndex);
    }

    /** 每客户端 tick 推进等待确认超时。 */
    public void tickTimeout() {
        ToolSwapTransaction.State state = transaction.state();
        if (state != ToolSwapTransaction.State.WAIT_SELECT_CONFIRM
                && state != ToolSwapTransaction.State.WAIT_RESTORE_CONFIRM) return;
        if (++waitingTicks >= TIMEOUT_TICKS) {
            transaction.timeout();
            clearObservation();
            notifyStatus(Status.PAUSED);
        }
    }

    /** 断线、卸载世界等生命周期边界无条件清理。 */
    public void resetLifecycle() {
        transaction.reset();
        expectedSlot = expectedAnchor = -1;
        waitingTicks = 0;
        VanillaInventoryTransactionObserver.clear(this);
        notifyStatus(Status.IDLE);
    }

    @Override public void onClickPacket(int windowId, int slot, int button, int mode, short actionNumber) {
        if (windowId != 0 || slot != expectedSlot || button != expectedAnchor || mode != 2) return;
        transaction.bindAction(actionNumber);
        expectedSlot = expectedAnchor = -1;
    }

    @Override public void onConfirmTransaction(int windowId, short actionNumber, boolean accepted) {
        if (windowId != 0) return;
        ToolSwapTransaction.Outcome outcome = accepted
                ? transaction.onConfirmAccepted(actionNumber) : transaction.onConfirmRejected(actionNumber);
        if (outcome.result == ToolSwapTransaction.Result.ACCEPTED) {
            notifyStatus(transaction.state() == ToolSwapTransaction.State.ACTIVE ? Status.ACTIVE : Status.IDLE);
        } else if (outcome.result == ToolSwapTransaction.Result.REJECTED) {
            waitingTicks = 0;
        }
    }

    @Override public void onWindowItems(int windowId) {
        if (windowId != 0) return;
        ToolSwapTransaction.Outcome outcome = transaction.onWindowItems();
        if (outcome.result == ToolSwapTransaction.Result.RESYNCED) {
            notifyStatus(transaction.state() == ToolSwapTransaction.State.IDLE ? Status.IDLE : Status.PAUSED);
        }
    }

    public ToolSwapTransaction.State state() { return transaction.state(); }
    public T latestDesired() { return transaction.latestDesired(); }

    private boolean send(int slot, int anchor) {
        expectedSlot = slot;
        expectedAnchor = anchor;
        waitingTicks = 0;
        VanillaInventoryTransactionObserver.setActive(this);
        try {
            transport.click(slot, anchor);
            return true;
        } catch (RuntimeException exception) {
            transaction.timeout();
            clearObservation();
            notifyStatus(Status.PAUSED);
            return false;
        }
    }
    private void clearObservation() {
        expectedSlot = expectedAnchor = -1;
        waitingTicks = 0;
        VanillaInventoryTransactionObserver.clear(this);
    }
    private static boolean validSlots(int slot, int anchor) { return slot >= 9 && slot <= 35 && anchor >= 0 && anchor <= 8; }
    private void notifyStatus(Status status) { if (listener != null) listener.onStatusChanged(status); }
}
