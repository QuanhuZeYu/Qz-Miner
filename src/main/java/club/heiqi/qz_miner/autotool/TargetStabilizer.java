package club.heiqi.qz_miner.autotool;

/** 首目标即时、变更连续确认、空目标宽限的纯逻辑稳定器。 */
public final class TargetStabilizer<T> {
    private final int stableTicks;
    private final int emptyGraceTicks;
    private T committed;
    private T pending;
    private int pendingTicks;
    private int emptyTicks;
    private boolean initialized;
    private boolean frozen;

    public TargetStabilizer(int stableTicks, int emptyGraceTicks) {
        if (stableTicks < 1 || emptyGraceTicks < 0) throw new IllegalArgumentException("invalid tick threshold");
        this.stableTicks = stableTicks;
        this.emptyGraceTicks = emptyGraceTicks;
    }

    /** 接收一个 tick 的目标，null 表示空目标。 */
    public T update(T target) {
        if (frozen) return committed;
        if (target == null) {
            pending = null; pendingTicks = 0;
            emptyTicks = incrementSaturated(emptyTicks);
            if (emptyTicks > emptyGraceTicks) { committed = null; initialized = false; }
            return committed;
        }
        emptyTicks = 0;
        if (!initialized) { committed = target; initialized = true; pending = null; return committed; }
        if (target.equals(committed)) { pending = null; pendingTicks = 0; return committed; }
        if (!target.equals(pending)) { pending = target; pendingTicks = 1; }
        else pendingTicks = incrementSaturated(pendingTicks);
        if (pendingTicks >= stableTicks) { committed = pending; pending = null; pendingTicks = 0; }
        return committed;
    }

    /** 清除全部目标历史。 */
    public void reset() {
        committed = null; pending = null; pendingTicks = 0; emptyTicks = 0; initialized = false; frozen = false;
    }
    /** 冻结或恢复更新。 */
    public void freeze(boolean value) { frozen = value; }
    public T current() { return committed; }

    private static int incrementSaturated(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }
}
