package club.heiqi.qz_miner.toolswap.server;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;

/**
 * 服务端工具换位库存边界。实现必须在服务端主线程提供同一时刻的玩家库存事实。
 * 本接口刻意不暴露 Minecraft、Forge 或网络类型。
 */
public interface AutoToolSwapInventoryPort {

    /** @return 玩家是否仍可接受库存操作。 */
    boolean isPlayerAlive();

    /** @return 玩家是否处于创造模式；服务端策略可据此拒绝 round。 */
    boolean isCreativeMode();

    /** @return 当前是否为玩家个人 inventory 的 window 0。 */
    boolean hasPersonalInventoryWindow0();

    /** @return 当前 cursor 是否为空；非空时不得开始库存换位。 */
    boolean isCursorEmpty();

    /** @return 当前选中的 0..8 热键栏槽位。 */
    int selectedHotbarSlot();

    /**
     * 读取个人 inventory 的 0..35 槽位状态。
     *
     * @param inventorySlot 玩家个人 inventory 槽位
     * @return 不持有 ItemStack 的不可变状态
     */
    AutoToolSwapStackState readInventorySlot(int inventorySlot);

    /**
     * 原子交换两个个人 inventory 槽位。此调用正常返回即表示交换已经应用，
     * 后续完整库存 publication 失败不得通过再次调用本方法来重试交换。
     *
     * @param anchorSlot 原工具所在的 0..35 槽位
     * @param candidateSlot 候选工具所在的 0..35 槽位
     */
    void swapInventorySlotsAtomically(int anchorSlot, int candidateSlot);

    /**
     * 原子执行三槽引用轮转：anchor&lt;-newCandidate，oldCandidate&lt;-anchor，newCandidate&lt;-oldCandidate。
     * 正常返回即表示整次轮转已经应用，不得重放。
     */
    default void rotateInventorySlotsAtomically(int anchorSlot, int oldCandidateSlot, int newCandidateSlot) {
        throw new UnsupportedOperationException("three-slot rotation is not implemented");
    }

    /**
     * 将已应用库存变更以可重复的完整个人库存 publication 同步给客户端。
     * 实现每次调用都必须重新标脏并发送完整 window 0 内容；失败只表示 publication 失败，
     * 不改变前一 mutation 已提交的事实。
     */
    void syncInventoryDifference();
}
