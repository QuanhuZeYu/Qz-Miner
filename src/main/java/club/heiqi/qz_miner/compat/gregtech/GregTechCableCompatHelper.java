package club.heiqi.qz_miner.compat.gregtech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IConnectable;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaPipeEntity;
import gregtech.api.metatileentity.implementations.MTECable;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * GregTech 线缆兼容辅助。
 */
public final class GregTechCableCompatHelper {

    private GregTechCableCompatHelper() {}

    /**
     * 判断目标坐标是否为 GT 线缆。
     */
    public static boolean isCable(TileEntity tileEntity) {
        return getCable(tileEntity) != null;
    }

    /**
     * 获取 GT 线缆元实体。
     */
    public static MTECable getCable(TileEntity tileEntity) {
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTileEntity)) {
            return null;
        }

        IMetaTileEntity metaTileEntity = gregTechTileEntity.getMetaTileEntity();
        if (!(metaTileEntity instanceof MTECable cable)) {
            return null;
        }
        return cable;
    }

    /**
     * 获取 GT 线缆宿主方块。
     */
    public static BaseMetaPipeEntity getCableBase(TileEntity tileEntity) {
        return tileEntity instanceof BaseMetaPipeEntity baseMetaPipeEntity && isCable(tileEntity)
            ? baseMetaPipeEntity
            : null;
    }

    /**
     * 获取当前线缆已连接方向。
     */
    public static List<ForgeDirection> getConnectedSides(TileEntity tileEntity) {
        BaseMetaPipeEntity baseMetaPipeEntity = getCableBase(tileEntity);
        if (baseMetaPipeEntity == null) {
            return Collections.emptyList();
        }

        List<ForgeDirection> connectedSides = new ArrayList<ForgeDirection>();
        byte connections = baseMetaPipeEntity.mConnections;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if ((connections & side.flag) != 0) {
                connectedSides.add(side);
            }
        }
        return connectedSides;
    }

    /**
     * 读取线缆元方块 ID。
     */
    public static int getCableMetaTileId(TileEntity tileEntity) {
        if (!(tileEntity instanceof IGregTechTileEntity gregTechTileEntity)) {
            return -1;
        }
        return gregTechTileEntity.getMetaTileID();
    }

    /**
     * 判断物品栈是否为 GT 线缆。
     */
    public static boolean isCableStack(ItemStack stack) {
        return createCableFromStack(stack) != null;
    }

    /**
     * 从线缆物品栈解析临时元实体。
     */
    public static MTECable createCableFromStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }

        IMetaTileEntity metaTileEntity = gregtech.common.blocks.ItemMachines.getMetaTileEntity(stack);
        return metaTileEntity instanceof MTECable cable ? cable : null;
    }

    /**
     * 复刻 GT 原生线缆替换逻辑，保持旧连接状态。
     */
    public static boolean replaceCableKeepingConnections(EntityPlayerMP player, BaseMetaPipeEntity baseMetaPipeEntity, ItemStack replacementStack, int replacementSlotIndex) {
        if (player == null || baseMetaPipeEntity == null || replacementStack == null) {
            return false;
        }

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

        byte oldConnections = oldCable.mConnections;
        short oldMetaId = (short) baseMetaPipeEntity.getMetaTileID();
        short newMetaId = (short) replacementStack.getItemDamage();

        IMetaTileEntity newMetaTileEntity = handCable.newMetaEntity(baseMetaPipeEntity);
        if (!(newMetaTileEntity instanceof MTECable newCable)) {
            return false;
        }

        newCable.mConnections = oldConnections;

        baseMetaPipeEntity.setMetaTileID(newMetaId);
        baseMetaPipeEntity.setMetaTileEntity(newCable);
        baseMetaPipeEntity.mConnections = oldConnections;
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

        if (!player.capabilities.isCreativeMode) {
            ItemStack oldCableStack = new ItemStack(replacementStack.getItem(), 1, oldMetaId);
            boolean addedToInventory = false;

            for (int i = 0; i < player.inventory.mainInventory.length; i++) {
                ItemStack slot = player.inventory.mainInventory[i];
                if (slot != null
                    && slot.getItem() == oldCableStack.getItem()
                    && slot.getItemDamage() == oldCableStack.getItemDamage()
                    && slot.stackSize < slot.getMaxStackSize()) {
                    slot.stackSize++;
                    addedToInventory = true;
                    break;
                }
            }

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

        return true;
    }
}
