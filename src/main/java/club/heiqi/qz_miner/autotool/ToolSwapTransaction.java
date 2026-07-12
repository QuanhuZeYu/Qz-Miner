package club.heiqi.qz_miner.autotool;

/** 基于活栈身份与后置条件的交换事务；不保存 ItemStack 快照。 */
public final class ToolSwapTransaction<T> {
    public enum Result { SWAPPED, RESTORED, ALREADY_RESTORED, CONFLICT }
    public interface Slots<T> { T get(int slot); void swap(int first, int second); }

    private final int originalSlot;
    private final int selectedSlot;
    private final T originalIdentity;
    private final T selectedIdentity;
    private boolean restored;

    private ToolSwapTransaction(int originalSlot, int selectedSlot, T originalIdentity, T selectedIdentity) {
        this.originalSlot = originalSlot; this.selectedSlot = selectedSlot;
        this.originalIdentity = originalIdentity; this.selectedIdentity = selectedIdentity;
    }

    /** 校验活栈身份后原地交换，不复制物品。 */
    public static <T> Start<T> begin(Slots<T> slots, int originalSlot, int selectedSlot) {
        T original = slots.get(originalSlot); T selected = slots.get(selectedSlot);
        if (original == null || selected == null || originalSlot == selectedSlot) return new Start<T>(null, Result.CONFLICT);
        ToolSwapTransaction<T> tx = new ToolSwapTransaction<T>(originalSlot, selectedSlot, original, selected);
        slots.swap(originalSlot, selectedSlot);
        if (slots.get(originalSlot) != selected || slots.get(selectedSlot) != original)
            return new Start<T>(null, Result.CONFLICT);
        return new Start<T>(tx, Result.SWAPPED);
    }

    /** 仅在身份后置条件仍成立时恢复；冲突不覆盖。 */
    public Result restore(Slots<T> slots) {
        if (restored) return Result.ALREADY_RESTORED;
        if (slots.get(originalSlot) != selectedIdentity || slots.get(selectedSlot) != originalIdentity) return Result.CONFLICT;
        slots.swap(originalSlot, selectedSlot);
        if (slots.get(originalSlot) != originalIdentity || slots.get(selectedSlot) != selectedIdentity) return Result.CONFLICT;
        restored = true;
        return Result.RESTORED;
    }

    /** 开始事务的结果及可选事务句柄。 */
    public static final class Start<T> {
        public final ToolSwapTransaction<T> transaction; public final Result result;
        Start(ToolSwapTransaction<T> transaction, Result result) { this.transaction = transaction; this.result = result; }
    }
}
