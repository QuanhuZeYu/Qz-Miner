package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.compat.gregtech.GregTechCableCompatHelper;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

/**
 * GT 线缆替换执行器。
 */
public class GregTechCableReplaceActionExecutor implements ChainActionExecutor {

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.SPECIAL;
    }

    @Override
    public boolean canExecute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        return GregTechCableCompatHelper.getCableBase(tileEntity) != null;
    }

    @Override
    public boolean execute(EntityPlayerMP player, ChainSession session, ChainTarget target) {
        if (player == null || session == null || target == null) {
            return false;
        }

        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        BaseMetaPipeEntity baseMetaPipeEntity = GregTechCableCompatHelper.getCableBase(tileEntity);
        if (baseMetaPipeEntity == null) {
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
            return GregTechCableCompatHelper.replaceCableKeepingConnections(
                player,
                baseMetaPipeEntity,
                lockedCableSlot.stack,
                lockedCableSlot.slotIndex);
        } finally {
            player.inventory.currentItem = previousSlot;
        }
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
            if (!GregTechCableCompatHelper.isCableStack(stack)) {
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
            if (!GregTechCableCompatHelper.isCableStack(stack)) {
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
