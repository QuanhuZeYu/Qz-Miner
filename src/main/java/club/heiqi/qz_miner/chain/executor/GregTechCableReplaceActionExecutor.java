package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.List;
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

        GregTechCableSessionState.ExecutionPhase phase = GregTechCableSessionState.getExecutionPhase(session);
        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return false;
        }
        return phase != GregTechCableSessionState.ExecutionPhase.RECONNECT
            || !GregTechCableSessionState.getReconnectSides(session, target).isEmpty();
    }

    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || session == null || target == null) {
            return false;
        }

        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return false;
        }

        GregTechCableSessionState.ExecutionPhase phase = GregTechCableSessionState.getExecutionPhase(session);
        if (phase == GregTechCableSessionState.ExecutionPhase.RECONNECT) {
            List<ForgeDirection> reconnectSides = GregTechCableSessionState.getReconnectSides(session, target);
            if (reconnectSides.isEmpty()) {
                return false;
            }
            boolean reconnected = CompatAdapters.cable().reconnectCableSides(tileEntity, reconnectSides);
            GregTechCableSessionState.clearReconnectSides(session, target);
            return reconnected;
        }

        LockedCableSlot lockedCableSlot = findLockedCableSlot(player, session);
        if (lockedCableSlot == null) {
            return false;
        }

        List<ForgeDirection> connectedSides = CompatAdapters.cable().captureConnectedSides(tileEntity);

        int previousSlot = player.inventory.currentItem;
        try {
            if (lockedCableSlot.slotIndex < 9) {
                player.inventory.currentItem = lockedCableSlot.slotIndex;
            }
            boolean replaced = CompatAdapters.cable().replaceCableWithoutConnections(
                player,
                tileEntity,
                lockedCableSlot.stack,
                lockedCableSlot.slotIndex);
            if (replaced && !connectedSides.isEmpty()) {
                GregTechCableSessionState.rememberReconnectSides(session, target, connectedSides);
            }
            return replaced;
        } finally {
            player.inventory.currentItem = previousSlot;
        }
    }

    @Override
    public boolean enqueueFollowUpTargets(EntityPlayerMP player, ChainSession session, ConcurrentLinkedQueue<ChainTarget> queue) {
        if (player == null || session == null || queue == null) {
            return false;
        }
        if (GregTechCableSessionState.getExecutionPhase(session) != GregTechCableSessionState.ExecutionPhase.REPLACE) {
            return false;
        }

        List<ChainTarget> reconnectTargets = GregTechCableSessionState.getReconnectTargetsSnapshot(session);
        if (reconnectTargets.isEmpty()) {
            return false;
        }

        GregTechCableSessionState.setExecutionPhase(session, GregTechCableSessionState.ExecutionPhase.RECONNECT);
        queue.addAll(reconnectTargets);
        return true;
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
