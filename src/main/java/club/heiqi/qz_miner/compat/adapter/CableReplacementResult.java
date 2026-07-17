package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.item.ItemStack;

/**
 * 线缆替换结果及需要由主线程执行器返还的旧线缆。
 */
public final class CableReplacementResult {

    private static final CableReplacementResult FAILURE = new CableReplacementResult(false, null);
    private final boolean successful;
    private final ItemStack returnedStack;

    private CableReplacementResult(boolean successful, ItemStack returnedStack) {
        this.successful = successful;
        this.returnedStack = returnedStack == null ? null : returnedStack.copy();
    }

    /** @return 失败结果 */
    public static CableReplacementResult failure() {
        return FAILURE;
    }

    /** @param returnedStack 待返还旧线缆 @return 成功结果 */
    public static CableReplacementResult success(ItemStack returnedStack) {
        return new CableReplacementResult(true, returnedStack);
    }

    /** @return 是否替换成功 */
    public boolean isSuccessful() {
        return successful;
    }

    /** @return 待返还物品的防御性副本 */
    public ItemStack getReturnedStack() {
        return returnedStack == null ? null : returnedStack.copy();
    }
}
