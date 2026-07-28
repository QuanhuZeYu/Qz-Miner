package club.heiqi.qz_miner.toolswap.server;

import club.heiqi.qz_miner.toolswap.minecraft.AutoToolSwapStackStateFactory;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
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
     * 直接交换两个不同的个人库存槽位。mutation 与可重试 publication 严格分离。
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
    }

    /** 一次性轮转三个互异槽位；正常返回即为唯一 mutation commit。 */
    @Override
    public void rotateInventorySlotsAtomically(int anchorSlot, int oldCandidateSlot, int newCandidateSlot) {
        requireInventorySlot(anchorSlot);
        requireInventorySlot(oldCandidateSlot);
        requireInventorySlot(newCandidateSlot);
        if (anchorSlot == oldCandidateSlot || anchorSlot == newCandidateSlot
                || oldCandidateSlot == newCandidateSlot) {
            throw new IllegalArgumentException("inventory slots must be distinct");
        }
        rotateMainInventorySlots(player.inventory.mainInventory, anchorSlot, oldCandidateSlot, newCandidateSlot);
    }

    /** 每次都重新标脏并通过原版完整 window 0 publication 重发库存与 cursor。 */
    @Override
    public void syncInventoryDifference() {
        player.inventory.markDirty();
        player.sendContainerToPlayer(player.inventoryContainer);
    }

    /**
     * 将物品栈压缩为不含完整 NBT 的诊断摘要。
     *
     * @param stack 待描述物品栈
     * @return registry/meta/damage/max/contentHash 组成的单行安全摘要
     */
    public static String describeStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "empty";
        }
        AutoToolSwapStackState state = AutoToolSwapStackStateFactory.capture(stack);
        String contentHash = Long.toHexString(state.contentFingerprint().firstLong());
        Object registryName = Item.itemRegistry.getNameForObject(stack.getItem());
        return "registry=" + (registryName == null ? "minecraft:unknown" : String.valueOf(registryName))
                + ",meta=" + (stack.getItem().getHasSubtypes() ? stack.getItemDamage() : 0)
                + ",damage=" + stack.getItemDamage()
                + ",maxDamage=" + stack.getMaxDamage()
                + ",remaining=" + state.remainingDurability()
                + ",contentHash=" + contentHash
                + ",energy=unavailable";
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

    /** 按 A&lt;-D,C&lt;-A,D&lt;-C 轮转引用。 */
    static void rotateMainInventorySlots(ItemStack[] mainInventory, int anchorSlot,
            int oldCandidateSlot, int newCandidateSlot) {
        ItemStack anchor = mainInventory[anchorSlot];
        ItemStack oldCandidate = mainInventory[oldCandidateSlot];
        mainInventory[anchorSlot] = mainInventory[newCandidateSlot];
        mainInventory[oldCandidateSlot] = anchor;
        mainInventory[newCandidateSlot] = oldCandidate;
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
