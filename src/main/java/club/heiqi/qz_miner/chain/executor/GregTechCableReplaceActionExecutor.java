package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.CableReplacementResult;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * GT 线缆替换执行器。
 */
public class GregTechCableReplaceActionExecutor implements ChainActionExecutor {

    @Override
    public boolean shouldWaitForPlannerCompletion(ChainSession session) {
        return true;
    }

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.SPECIAL;
    }

    @Override
    public boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        // 执行前复核目标线缆身份。
        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return false;
        }
        // 执行前复核当前主手线缆。
        if (!CompatAdapters.cable().isCableStack(player.inventory.getCurrentItem())) {
            return false;
        }
        return true;
    }

    /**
     * 主线程单阶段替换线缆，由 adapter 写回连接位掩码并更新网络；RECONNECT 状态按拒绝处理。
     */
    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || session == null || target == null) {
            return false;
        }
        if (MyMod.chainStateService == null) {
            return false;
        }

        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return false;
        }

        GregTechCableSessionState.ExecutionPhase phase = GregTechCableSessionState.getExecutionPhase(session);
        if (phase == GregTechCableSessionState.ExecutionPhase.RECONNECT) {
            return false;
        }

        LockedCableSlot lockedCableSlot = findLockedCableSlot(player, session);
        if (lockedCableSlot == null) {
            return false;
        }

        Integer protectedMainHandSlot = GregTechCableSessionState.getLockedMainHandSlot(session);
        int protectedSlot = protectedMainHandSlot == null ? -1 : protectedMainHandSlot.intValue();

        // adapter 使用指定物品栈和槽位原子替换，返还时跳过 protectedSlot，保留主手槽。
        CableReplacementResult result = CompatAdapters.cable().replaceCableWithoutConnections(
            player, tileEntity, lockedCableSlot.stack, lockedCableSlot.slotIndex, protectedSlot);
        if (!result.isSuccessful()) {
            return false;
        }
        returnOldCable(player, result.getReturnedStack(), protectedSlot);
        return true;
    }

    /**
     * 主线程优先返还背包，剩余物品进入玩家级掉落缓冲。
     */
    private void returnOldCable(EntityPlayerMP player, ItemStack returnedStack, int protectedSlot) {
        if (returnedStack == null || returnedStack.stackSize <= 0) return;
        if (addToInventory(player, returnedStack, protectedSlot)) return;
        MyMod.chainStateService.bufferPlayerDrop(player.getUniqueID(), returnedStack);
    }

    private boolean addToInventory(EntityPlayerMP player, ItemStack stack, int protectedSlot) {
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            if (i == protectedSlot) continue;
            ItemStack existing = player.inventory.mainInventory[i];
            if (existing != null && existing.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(existing, stack)
                && existing.stackSize + stack.stackSize <= Math.min(existing.getMaxStackSize(), player.inventory.getInventoryStackLimit())) {
                existing.stackSize += stack.stackSize;
                player.inventory.markDirty();
                return true;
            }
        }
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            if (i != protectedSlot && player.inventory.mainInventory[i] == null) {
                player.inventory.setInventorySlotContents(i, stack.copy());
                return true;
            }
        }
        return false;
    }

    /**
     * 单阶段替换已恢复连接，本执行器的后续队列为空。
     *
     * @return 恒为 false
     */
    @Override
    public boolean enqueueFollowUpTargets(EntityPlayerMP player, ChainSession session, ConcurrentLinkedQueue<ChainTarget> queue) {
        return false;
    }

    /**
     * 选择替换用线缆槽位。
     * 替换类型基准 = 主手线缆 metaTileId（首次锁定，后续读会话锁）。
     * 消耗顺序：主手外按槽位正序取同种线缆，再使用主手。
     * 返还旧线缆时保留会话锁定的主手 slot。
     */
    private LockedCableSlot findLockedCableSlot(EntityPlayerMP player, ChainSession session) {
        int mainHandSlot = player.inventory.currentItem;
        ItemStack mainHandStack = player.inventory.getCurrentItem();

        // 当前主手通过线缆校验后再取得会话锁。
        if (!CompatAdapters.cable().isCableStack(mainHandStack)) {
            return null;
        }
        int mainHandMetaId = mainHandStack.getItemDamage();

        // 首次锁定：把主手线缆种类 + 主手 slot 锁进会话
        Integer lockedMetaId = GregTechCableSessionState.getLockedReplacementMetaTileId(session);
        if (lockedMetaId == null) {
            GregTechCableSessionState.lockReplacementMetaTileId(session, mainHandMetaId);
            GregTechCableSessionState.lockMainHandSlot(session, mainHandSlot);
            lockedMetaId = mainHandMetaId;
        }
        int targetMetaId = lockedMetaId.intValue();

        // 正序遍历主手外（跳过 mainHandSlot），找同种线缆优先消耗
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            if (i == mainHandSlot) continue;  // 主手留到最后使用。
            ItemStack stack = player.inventory.mainInventory[i];
            if (!CompatAdapters.cable().isCableStack(stack)) continue;
            if (stack.getItemDamage() == targetMetaId) {
                return new LockedCableSlot(i, stack, targetMetaId);
            }
        }

        // 主手外耗尽后使用主手同种线缆。
        if (mainHandStack.getItemDamage() == targetMetaId) {
            return new LockedCableSlot(mainHandSlot, mainHandStack, targetMetaId);
        }

        return null;  // 缺少匹配线缆。
    }

    private static final class LockedCableSlot {

        private final int slotIndex;
        private final ItemStack stack;
        private final int metaTileId;

        private LockedCableSlot(int slotIndex, ItemStack stack, int metaTileId) {
            this.slotIndex = slotIndex;
            this.stack = stack;
            this.metaTileId = metaTileId;
        }
    }
}
