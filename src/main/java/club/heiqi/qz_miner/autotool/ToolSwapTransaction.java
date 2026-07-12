package club.heiqi.qz_miner.autotool;

/** 不写库存的工具交换状态机，仅描述原版窗口点击意图。 */
public final class ToolSwapTransaction<T> {
    public enum State { IDLE, WAIT_SELECT_CONFIRM, ACTIVE, WAIT_RESTORE_CONFIRM, WAIT_RESYNC, PAUSED }
    public enum Result { INTENT, ACCEPTED, REJECTED, RESYNCED, RESET, IGNORED, CONFLICT, PAUSED }

    /** 原版 window click 所需的稳定参数。 */
    public static final class ClickIntent {
        public final int sourceContainerSlot;
        public final int anchorHotbarIndex;
        public final short actionNumber;

        ClickIntent(int sourceContainerSlot, int anchorHotbarIndex, short actionNumber) {
            this.sourceContainerSlot = sourceContainerSlot;
            this.anchorHotbarIndex = anchorHotbarIndex;
            this.actionNumber = actionNumber;
        }
    }

    /** 一次状态机调用的结果与可选点击意图。 */
    public static final class Outcome {
        public final Result result;
        public final ClickIntent intent;

        Outcome(Result result, ClickIntent intent) { this.result = result; this.intent = intent; }
    }

    private State state = State.IDLE;
    private int sourceContainerSlot = -1;
    private int anchorHotbarIndex = -1;
    private short actionNumber;
    private T sourceIdentity;
    private T anchorIdentity;
    private T latestDesired;
    private boolean releasePending;

    /** 开始选择；原手允许为空，冲突只返回结果。 */
    public Outcome beginSelect(int sourceSlot, int anchorIndex, short action, T source, T anchor, T desired) {
        latestDesired = desired;
        if (state != State.IDLE || sourceSlot < 0 || anchorIndex < 0 || anchorIndex > 8 || source == null) {
            return outcome(Result.CONFLICT, null);
        }
        sourceContainerSlot = sourceSlot; anchorHotbarIndex = anchorIndex; actionNumber = action;
        sourceIdentity = source; anchorIdentity = anchor; state = State.WAIT_SELECT_CONFIRM;
        return outcome(Result.INTENT, intent());
    }

    /** 仅接受当前等待中的精确 action。 */
    public Outcome onConfirmAccepted(short action) {
        if (!isWaiting() || action != actionNumber) return outcome(Result.IGNORED, null);
        state = state == State.WAIT_SELECT_CONFIRM ? State.ACTIVE : State.IDLE;
        if (state == State.IDLE) clearTransaction();
        return outcome(Result.ACCEPTED, null);
    }

    /** 拒绝后必须等待完整 WindowItems(S30) 重同步。 */
    public Outcome onConfirmRejected(short action) {
        if (!isWaiting() || action != actionNumber) return outcome(Result.IGNORED, null);
        state = State.WAIT_RESYNC;
        return outcome(Result.REJECTED, null);
    }

    /** 完整窗口同步解除拒绝等待；松键期间不再发起新选择。 */
    public Outcome onWindowItems() {
        if (state != State.WAIT_RESYNC) return outcome(Result.IGNORED, null);
        state = releasePending ? State.IDLE : State.PAUSED;
        clearTransaction();
        return outcome(Result.RESYNCED, null);
    }

    /** ACTIVE 时生成恢复点击意图；身份不符只报告冲突。 */
    public Outcome beginRestore(short action, T sourceAtAnchor, T anchorAtSource) {
        releasePending = true;
        if (state != State.ACTIVE) return outcome(Result.CONFLICT, null);
        if (sourceAtAnchor != sourceIdentity || anchorAtSource != anchorIdentity) return outcome(Result.CONFLICT, null);
        actionNumber = action; state = State.WAIT_RESTORE_CONFIRM;
        return outcome(Result.INTENT, intent());
    }

    /** 更新 latest desired；null 表示松键并保留到当前 pending 收口。 */
    public void setLatestDesired(T desired) { latestDesired = desired; releasePending = desired == null; }

    /** 等待确认超时后暂停，禁止继续发意图。 */
    public Outcome timeout() {
        if (!isWaiting()) return outcome(Result.IGNORED, null);
        state = State.PAUSED;
        return outcome(Result.PAUSED, null);
    }

    /** 无条件回到初始状态。 */
    public Outcome reset() { state = State.IDLE; clearTransaction(); latestDesired = null; return outcome(Result.RESET, null); }

    public State state() { return state; }
    public T latestDesired() { return latestDesired; }
    public boolean releasePending() { return releasePending; }

    private boolean isWaiting() { return state == State.WAIT_SELECT_CONFIRM || state == State.WAIT_RESTORE_CONFIRM; }
    private ClickIntent intent() { return new ClickIntent(sourceContainerSlot, anchorHotbarIndex, actionNumber); }
    private static Outcome outcome(Result result, ClickIntent intent) { return new Outcome(result, intent); }
    private void clearTransaction() {
        sourceContainerSlot = -1; anchorHotbarIndex = -1; sourceIdentity = null; anchorIdentity = null;
        releasePending = false;
    }
}
