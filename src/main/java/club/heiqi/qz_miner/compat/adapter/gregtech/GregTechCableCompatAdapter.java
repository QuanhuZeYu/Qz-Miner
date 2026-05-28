package club.heiqi.qz_miner.compat.adapter.gregtech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.compat.adapter.CableCompatAdapter;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.api.metatileentity.implementations.MTECable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * GregTech 线缆兼容适配器。
 */
public final class GregTechCableCompatAdapter implements CableCompatAdapter {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isCable(TileEntity tileEntity) {
        return getCable(tileEntity) != null;
    }

    @Override
    public int getCableMetaTileId(TileEntity tileEntity) {
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTileEntity)) {
            return -1;
        }
        return gregTechTileEntity.getMetaTileID();
    }

    @Override
    public List<ForgeDirection> getConnectedSides(TileEntity tileEntity) {
        BaseMetaPipeEntity baseMetaPipeEntity = getCableBase(tileEntity);
        if (baseMetaPipeEntity == null) {
            return Collections.emptyList();
        }

        List<ForgeDirection> connectedSides = new ArrayList<ForgeDirection>();
        byte connections = baseMetaPipeEntity.getConnections();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if ((connections & side.flag) != 0) {
                connectedSides.add(side);
            }
        }
        return connectedSides;
    }

    @Override
    public List<ForgeDirection> captureConnectedSides(TileEntity tileEntity) {
        MTECable cable = getCable(tileEntity);
        if (cable == null) {
            return Collections.emptyList();
        }

        List<ForgeDirection> connectedSides = new ArrayList<ForgeDirection>();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (cable.isConnectedAtSide(side)) {
                connectedSides.add(side);
            }
        }
        return connectedSides;
    }

    @Override
    public boolean isCableStack(ItemStack stack) {
        return createCableFromStack(stack) != null;
    }

    @Override
    public boolean replaceCableWithoutConnections(EntityPlayerMP player, TileEntity tileEntity, ItemStack replacementStack, int replacementSlotIndex) {
        BaseMetaPipeEntity baseMetaPipeEntity = getCableBase(tileEntity);
        if (player == null || baseMetaPipeEntity == null || replacementStack == null) {
            return false;
        }

        List<ForgeDirection> oldConnectedSides = captureConnectedSides(baseMetaPipeEntity);
        MTECable oldCable = getCable(baseMetaPipeEntity);
        MTECable handCable = createCableFromStack(replacementStack);
        if (oldCable == null || handCable == null) {
            return false;
        }

        if (oldCable.getClass() == handCable.getClass()
            && oldCable.mMaterial == handCable.mMaterial
            && oldCable.mVoltage == handCable.mVoltage
            && oldCable.mAmperage == handCable.mAmperage) {
            return false;
        }

        short oldMetaId = (short) baseMetaPipeEntity.getMetaTileID();
        short newMetaId = (short) replacementStack.getItemDamage();

        IMetaTileEntity newMetaTileEntity = handCable.newMetaEntity(baseMetaPipeEntity);
        if (!(newMetaTileEntity instanceof MTECable newCable)) {
            return false;
        }

        baseMetaPipeEntity.setMetaTileID(newMetaId);
        baseMetaPipeEntity.setMetaTileEntity(newCable);
        for (ForgeDirection side : oldConnectedSides) {
            newCable.disconnect(side);
        }
        baseMetaPipeEntity.markDirty();
        baseMetaPipeEntity.issueTextureUpdate();
        baseMetaPipeEntity.issueBlockUpdate();
        baseMetaPipeEntity.issueClientUpdate();
        GregTechAPI.causeCableUpdate(baseMetaPipeEntity.getWorld(), baseMetaPipeEntity.xCoord, baseMetaPipeEntity.yCoord, baseMetaPipeEntity.zCoord);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighborTileEntity = baseMetaPipeEntity.getTileEntityAtSide(side);
            if (neighborTileEntity instanceof BaseMetaPipeEntity neighborPipeEntity) {
                neighborPipeEntity.issueClientUpdate();
                neighborPipeEntity.issueTextureUpdate();
                neighborPipeEntity.issueBlockUpdate();
                GregTechAPI.causeCableUpdate(neighborPipeEntity.getWorld(), neighborPipeEntity.xCoord, neighborPipeEntity.yCoord, neighborPipeEntity.zCoord);
            }
        }

        consumeReplacementStack(player, replacementStack, replacementSlotIndex, oldMetaId);
        return true;
    }

    @Override
    public boolean reconnectCableSides(TileEntity tileEntity, List<ForgeDirection> connectedSides) {
        MTECable cable = getCable(tileEntity);
        BaseMetaPipeEntity baseMetaPipeEntity = getCableBase(tileEntity);
        if (cable == null || baseMetaPipeEntity == null || connectedSides == null || connectedSides.isEmpty()) {
            return false;
        }

        boolean connected = false;
        for (ForgeDirection side : connectedSides) {
            if (side == null || side == ForgeDirection.UNKNOWN) {
                continue;
            }

            if (cable.connect(side) > 0) {
                connected = true;
            }
        }

        baseMetaPipeEntity.markDirty();
        baseMetaPipeEntity.issueTextureUpdate();
        baseMetaPipeEntity.issueBlockUpdate();
        baseMetaPipeEntity.issueClientUpdate();
        GregTechAPI.causeCableUpdate(baseMetaPipeEntity.getWorld(), baseMetaPipeEntity.xCoord, baseMetaPipeEntity.yCoord, baseMetaPipeEntity.zCoord);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighborTileEntity = baseMetaPipeEntity.getTileEntityAtSide(side);
            if (neighborTileEntity instanceof BaseMetaPipeEntity neighborPipeEntity) {
                neighborPipeEntity.issueClientUpdate();
                neighborPipeEntity.issueTextureUpdate();
                neighborPipeEntity.issueBlockUpdate();
                GregTechAPI.causeCableUpdate(neighborPipeEntity.getWorld(), neighborPipeEntity.xCoord, neighborPipeEntity.yCoord, neighborPipeEntity.zCoord);
            }
        }

        return connected;
    }

    private MTECable getCable(TileEntity tileEntity) {
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTileEntity)) {
            return null;
        }

        IMetaTileEntity metaTileEntity = gregTechTileEntity.getMetaTileEntity();
        if (!(metaTileEntity instanceof MTECable cable)) {
            return null;
        }
        return cable;
    }

    private BaseMetaPipeEntity getCableBase(TileEntity tileEntity) {
        return tileEntity instanceof BaseMetaPipeEntity baseMetaPipeEntity && isCable(tileEntity)
            ? baseMetaPipeEntity
            : null;
    }

    private MTECable createCableFromStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }

        Item machineItem = Item.getItemFromBlock(GregTechAPI.sBlockMachines);
        if (machineItem == null || stack.getItem() != machineItem) {
            return null;
        }
        int metaTileId = stack.getItemDamage();
        if (metaTileId < 0 || metaTileId >= GregTechAPI.METATILEENTITIES.length) {
            return null;
        }
        IMetaTileEntity metaTileEntity = GregTechAPI.METATILEENTITIES[metaTileId];
        return metaTileEntity instanceof MTECable cable ? cable : null;
    }

    private void consumeReplacementStack(EntityPlayerMP player, ItemStack replacementStack, int replacementSlotIndex, short oldMetaId) {
        if (player.capabilities.isCreativeMode) {
            return;
        }

        ItemStack oldCableStack = new ItemStack(replacementStack.getItem(), 1, oldMetaId);
        boolean addedToInventory = addOldCableToExistingStack(player, oldCableStack);

        if (!addedToInventory) {
            addedToInventory = player.inventory.addItemStackToInventory(oldCableStack);
        }
        if (!addedToInventory) {
            player.dropPlayerItemWithRandomChoice(oldCableStack, false);
        }

        replacementStack.stackSize--;
        if (replacementStack.stackSize <= 0) {
            player.inventory.setInventorySlotContents(replacementSlotIndex, null);
        }
    }

    private boolean addOldCableToExistingStack(EntityPlayerMP player, ItemStack oldCableStack) {
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack slot = player.inventory.mainInventory[i];
            if (slot != null
                && slot.getItem() == oldCableStack.getItem()
                && slot.getItemDamage() == oldCableStack.getItemDamage()
                && slot.stackSize < slot.getMaxStackSize()) {
                slot.stackSize++;
                return true;
            }
        }
        return false;
    }
}
