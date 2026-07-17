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

        // RECONNECT 两阶段已停用（单阶段 replaceCableWithoutConnections 已完整恢复连接），
        // phase 恒为 REPLACE，不再有 reconnect 任务，此处仅需校验目标仍是线缆。
        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return false;
        }
        // P2-3：防御性主手校验（planner 已拦截，此处补 canExecute 入口，防会话锁与主手不一致）
        if (!CompatAdapters.cable().isCableStack(player.inventory.getCurrentItem())) {
            return false;
        }
        return true;
    }

    /**
     * 执行 GT 线缆替换。
     *
     * <p>RECONNECT 两阶段已停用：单阶段 {@code replaceCableWithoutConnections}
     * 已通过直写 {@code mConnections} 位掩码完整恢复连接，无需第二阶段 reconnect。
     * 若 phase 仍为 RECONNECT（历史残留），直接返回 false 放弃，避免重新引入 connect() 危险 API。
     * Batch 2 将清理 SessionState 中的 RECONNECT 相关字段。
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

        // 单阶段原子替换：内部直写 mConnections + causeCableUpdate
        // 删临时切手：replaceCable 不依赖 currentItem，只用 replacementStack/replacementSlotIndex
        // protectedSlot 传给 adapter 保护主手 slot 不被返还的旧线缆占用
        CableReplacementResult result = CompatAdapters.cable().replaceCableWithoutConnections(
            player, tileEntity, lockedCableSlot.stack, lockedCableSlot.slotIndex, protectedSlot);
        if (!result.isSuccessful()) {
            return false;
        }
        returnOldCable(player, result.getReturnedStack(), protectedSlot);
        return true;
    }

    /**
     * 主线程优先返还背包；无法入包时写入玩家级掉落缓冲，禁止无保障生成实体。
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
     * 不再入队 follow-up reconnect 目标。
     *
     * <p>RECONNECT 两阶段已停用：单阶段替换已完整恢复连接。
     * 保留方法签名以兼容 {@link ChainActionExecutor} 接口，Batch 2 将随接口清理一并移除。
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
     * 消耗顺序：主手外正序（slot 0→8→9→35）同种优先 → 主手最后兜底。
     * 主手 slot 在返还旧线缆时受会话锁保护，避免旧线缆占用主手。
     */
    private LockedCableSlot findLockedCableSlot(EntityPlayerMP player, ChainSession session) {
        int mainHandSlot = player.inventory.currentItem;
        ItemStack mainHandStack = player.inventory.getCurrentItem();

        // 主手非线缆 → 不应到达此处（planner 已拦截），防御性返回 null
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
            if (i == mainHandSlot) continue;  // 主手最后兜底
            ItemStack stack = player.inventory.mainInventory[i];
            if (!CompatAdapters.cable().isCableStack(stack)) continue;
            if (stack.getItemDamage() == targetMetaId) {
                return new LockedCableSlot(i, stack, targetMetaId);
            }
        }

        // 主手外无同种 → 主手兜底
        if (mainHandStack.getItemDamage() == targetMetaId) {
            return new LockedCableSlot(mainHandSlot, mainHandStack, targetMetaId);
        }

        return null;  // 料不够
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
