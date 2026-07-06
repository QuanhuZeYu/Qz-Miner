package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
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
        return CompatAdapters.cable().isCable(tileEntity);
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

        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return false;
        }

        // RECONNECT 阶段停用：单阶段替换已恢复连接，不再有 reconnect 任务
        GregTechCableSessionState.ExecutionPhase phase = GregTechCableSessionState.getExecutionPhase(session);
        if (phase == GregTechCableSessionState.ExecutionPhase.RECONNECT) {
            return false;
        }

        LockedCableSlot lockedCableSlot = findLockedCableSlot(player, session);
        if (lockedCableSlot == null) {
            return false;
        }

        int previousSlot = player.inventory.currentItem;
        try {
            if (lockedCableSlot.slotIndex < 9) {
                player.inventory.currentItem = lockedCableSlot.slotIndex;
            }
            // 单阶段原子替换：内部已直写 mConnections 恢复连接 + causeCableUpdate 重建网络图
            return CompatAdapters.cable().replaceCableWithoutConnections(
                player,
                tileEntity,
                lockedCableSlot.stack,
                lockedCableSlot.slotIndex);
        } finally {
            player.inventory.currentItem = previousSlot;
        }
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

    private LockedCableSlot findLockedCableSlot(EntityPlayerMP player, ChainSession session) {
        Integer lockedMetaTileId = GregTechCableSessionState.getLockedReplacementMetaTileId(session);

        if (lockedMetaTileId != null) {
            LockedCableSlot lockedSlot = findMatchingCable(player, lockedMetaTileId.intValue(), true);
            if (lockedSlot != null) {
                return lockedSlot;
            }
            lockedSlot = findMatchingCable(player, lockedMetaTileId.intValue(), false);
            if (lockedSlot != null) {
                return lockedSlot;
            }
        }

        LockedCableSlot firstCable = findFirstCable(player, true);
        if (firstCable == null) {
            firstCable = findFirstCable(player, false);
        }
        if (firstCable == null) {
            return null;
        }

        GregTechCableSessionState.lockReplacementMetaTileId(session, firstCable.metaTileId);
        return firstCable;
    }

    private LockedCableSlot findMatchingCable(EntityPlayerMP player, int metaTileId, boolean hotbarOnly) {
        int start = hotbarOnly ? 8 : player.inventory.mainInventory.length - 1;
        int endExclusive = hotbarOnly ? -1 : 8;
        for (int i = start; i > endExclusive; i--) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (!CompatAdapters.cable().isCableStack(stack)) {
                continue;
            }
            if (stack.getItemDamage() == metaTileId) {
                return new LockedCableSlot(i, stack, metaTileId);
            }
        }
        return null;
    }

    private LockedCableSlot findFirstCable(EntityPlayerMP player, boolean hotbarOnly) {
        int start = hotbarOnly ? 8 : player.inventory.mainInventory.length - 1;
        int endExclusive = hotbarOnly ? -1 : 8;
        for (int i = start; i > endExclusive; i--) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (!CompatAdapters.cable().isCableStack(stack)) {
                continue;
            }
            return new LockedCableSlot(i, stack, stack.getItemDamage());
        }
        return null;
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
