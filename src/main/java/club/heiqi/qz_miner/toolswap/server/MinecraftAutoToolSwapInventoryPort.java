package club.heiqi.qz_miner.toolswap.server;

import club.heiqi.qz_miner.toolswap.minecraft.AutoToolSwapStackStateFactory;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/** 服务端主线程上的真实玩家个人库存适配。 */
public final class MinecraftAutoToolSwapInventoryPort implements AutoToolSwapInventoryPort {

    private final EntityPlayerMP player;

    /**
     * 创建绑定到单个服务端玩家的库存端口。
     *
     * @param player 服务端玩家
     */
    public MinecraftAutoToolSwapInventoryPort(EntityPlayerMP player) {
        if (player == null) {
            throw new IllegalArgumentException("player must not be null");
        }
        this.player = player;
    }

    /** @return 实体、世界和死亡状态均允许进行库存操作。 */
    @Override
    public boolean isPlayerAlive() {
        return player.worldObj != null && !player.isDead && player.isEntityAlive();
    }

    /** @return 服务端玩家是否处于创造模式。 */
    @Override
    public boolean isCreativeMode() {
        return player.capabilities.isCreativeMode;
    }

    /** @return 当前打开的容器是否为 window 0 的个人库存容器。 */
    @Override
    public boolean hasPersonalInventoryWindow0() {
        return player.openContainer == player.inventoryContainer && player.inventoryContainer.windowId == 0;
    }

    /** @return 玩家个人库存 cursor 是否为空。 */
    @Override
    public boolean isCursorEmpty() {
        return player.inventory.getItemStack() == null;
    }

    /** @return 当前只读的热键栏选中槽位。 */
    @Override
    public int selectedHotbarSlot() {
        return player.inventory.currentItem;
    }

    /**
     * 捕获玩家个人库存中的一个槽位。
     *
     * @param inventorySlot 个人库存槽位
     * @return 不持有 ItemStack 的不可变状态
     */
    @Override
    public AutoToolSwapStackState readInventorySlot(int inventorySlot) {
        requireInventorySlot(inventorySlot);
        return AutoToolSwapStackStateFactory.capture(player.inventory.mainInventory[inventorySlot]);
    }

    /**
     * 直接交换两个不同的个人库存槽位，并立即将库存标记为脏。
     *
     * @param anchorSlot 原工具所在槽位
     * @param candidateSlot 候选工具所在槽位
     */
    @Override
    public void swapInventorySlotsAtomically(int anchorSlot, int candidateSlot) {
        requireInventorySlot(anchorSlot);
        requireInventorySlot(candidateSlot);
        if (anchorSlot == candidateSlot) {
            throw new IllegalArgumentException("inventory slots must differ");
        }
        swapMainInventorySlots(player.inventory.mainInventory, anchorSlot, candidateSlot);
        player.inventory.markDirty();
    }

    /** 将已应用的库存差异交给原版容器同步。 */
    @Override
    public void syncInventoryDifference() {
        player.inventoryContainer.detectAndSendChanges();
    }

    /**
     * 交换数组中两个已校验的槽位，保留原始 ItemStack 引用。
     *
     * @param mainInventory 玩家个人库存数组
     * @param firstSlot 第一个槽位
     * @param secondSlot 第二个槽位
     */
    static void swapMainInventorySlots(ItemStack[] mainInventory, int firstSlot, int secondSlot) {
        ItemStack first = mainInventory[firstSlot];
        mainInventory[firstSlot] = mainInventory[secondSlot];
        mainInventory[secondSlot] = first;
    }

    /**
     * 验证个人库存槽位范围。
     *
     * @param inventorySlot 待验证槽位
     */
    private static void requireInventorySlot(int inventorySlot) {
        if (!AutoToolSwapProtocol.isInventorySlot(inventorySlot)) {
            throw new IllegalArgumentException("inventorySlot must be 0..35");
        }
    }
}
