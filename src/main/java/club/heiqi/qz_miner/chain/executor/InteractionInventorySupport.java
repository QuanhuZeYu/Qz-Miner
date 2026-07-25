package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * 服务端直调物品交互后的共享库存归一与 listener 同步。
 */
final class InteractionInventorySupport {

    private InteractionInventorySupport() {}

    /**
     * 归一交互后的真实当前槽，并通过玩家当前容器执行标准 listener 同步。
     *
     * <p>本方法不抑制数量 listener，也不回滚物品逻辑产生的合法容器替换。</p>
     *
     * @param player 当前服务端玩家
     * @param target 当前交互目标
     * @return 归一与同步是否完整成功
     */
    static boolean normalizeAndSync(EntityPlayerMP player, ChainTarget target) {
        if (player == null || player.inventory == null || target == null) {
            return false;
        }
        try {
            int currentItem = player.inventory.currentItem;
            ItemStack currentStackAfterUse = player.inventory.getCurrentItem();
            if (currentStackAfterUse != null && currentStackAfterUse.stackSize <= 0) {
                player.inventory.mainInventory[currentItem] = null;
            }
            player.inventory.markDirty();
            if (player.openContainer != null) {
                player.openContainer.detectAndSendChanges();
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            MyMod.LOG.error(
                "[InteractionInventorySupport] Failed post-interaction inventory sync for player {} at ({}, {}, {})",
                player.getUniqueID(), target.getX(), target.getY(), target.getZ(), failure);
            return false;
        }
    }
}
