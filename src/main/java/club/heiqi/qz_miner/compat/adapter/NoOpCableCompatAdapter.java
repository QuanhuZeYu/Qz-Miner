package club.heiqi.qz_miner.compat.adapter;

import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 不可用线缆适配器。
 */
public final class NoOpCableCompatAdapter implements CableCompatAdapter {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean isCable(TileEntity tileEntity) {
        return false;
    }

    @Override
    public int getCableMetaTileId(TileEntity tileEntity) {
        return -1;
    }

    @Override
    public List<ForgeDirection> getConnectedSides(TileEntity tileEntity) {
        return Collections.emptyList();
    }

    @Override
    public boolean isCableStack(ItemStack stack) {
        return false;
    }

    @Override
    public List<ForgeDirection> captureConnectedSides(TileEntity tileEntity) {
        return Collections.emptyList();
    }

    @Override
    public CableReplacementResult replaceCableWithoutConnections(EntityPlayerMP player, TileEntity tileEntity, ItemStack replacementStack, int replacementSlotIndex, int protectedMainHandSlot) {
        return CableReplacementResult.failure();
    }

    @Override
    public boolean reconnectCableSides(TileEntity tileEntity, List<ForgeDirection> connectedSides) {
        return false;
    }
}
