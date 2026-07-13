package club.heiqi.qz_miner.autotool;

/** 自动工具控制器的可观测纯逻辑状态。 */
public final class AutoToolControllerState<T> {
    public enum Status { IDLE, ACTIVE, PENDING_SELECT, PENDING_RESTORE, PAUSED }

    Status status = Status.IDLE;
    int originalHotbarSlot = -1;
    int activeToolSlot = -1;
    int pendingToolSlot = -1;
    T activeTarget;
    T latestDesiredTarget;
    boolean inventorySwapActive;
    boolean endAfterRestore;

    public Status status() { return status; }
    public int originalHotbarSlot() { return originalHotbarSlot; }
    public int activeToolSlot() { return activeToolSlot; }
    public T activeTarget() { return activeTarget; }
    public T latestDesiredTarget() { return latestDesiredTarget; }
    public boolean inventorySwapActive() { return inventorySwapActive; }
}
