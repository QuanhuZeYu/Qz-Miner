package club.heiqi.qz_miner.toolswap.server;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 服务端适配器中可脱离实体运行的库存交换逻辑。 */
public class MinecraftAutoToolSwapInventoryPortTest {

    /** 构造时必须拒绝缺失的服务端玩家。 */
    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsNullPlayer() {
        new MinecraftAutoToolSwapInventoryPort(null);
    }

    /** 交换保持 ItemStack 原始引用，且不会影响调用方保存的选中索引。 */
    @Test
    public void swapRetainsStackReferencesAndDoesNotChangeSelectedIndex() {
        ItemStack first = new ItemStack(new Item());
        ItemStack second = new ItemStack(new Item());
        first.stackSize = 3;
        NBTTagCompound energy = new NBTTagCompound();
        energy.setLong("GT.ItemCharge", 4096L);
        second.setTagCompound(energy);
        ItemStack[] mainInventory = new ItemStack[36];
        mainInventory[2] = first;
        mainInventory[19] = second;
        int selectedSlot = 2;

        MinecraftAutoToolSwapInventoryPort.swapMainInventorySlots(mainInventory, 2, 19);

        Assert.assertSame(second, mainInventory[2]);
        Assert.assertSame(first, mainInventory[19]);
        Assert.assertEquals(3, mainInventory[19].stackSize);
        Assert.assertEquals(4096L, mainInventory[2].getTagCompound().getLong("GT.ItemCharge"));
        Assert.assertEquals(2, selectedSlot);
    }

    /** 空主手借出工具后原槽被占用，恢复仍只交换两个当前真实引用。 */
    @Test
    public void restoreBorrowedToolExchangesCurrentOccupantIntoOriginallyEmptyAnchor() {
        ItemStack borrowedTool = new ItemStack(new Item());
        ItemStack occupyingDrop = new ItemStack(new Item());
        ItemStack[] mainInventory = new ItemStack[36];
        mainInventory[0] = null;
        mainInventory[9] = borrowedTool;

        MinecraftAutoToolSwapInventoryPort.swapMainInventorySlots(mainInventory, 0, 9);
        mainInventory[9] = occupyingDrop;
        MinecraftAutoToolSwapInventoryPort.swapMainInventorySlots(mainInventory, 0, 9);

        Assert.assertSame(occupyingDrop, mainInventory[0]);
        Assert.assertSame(borrowedTool, mainInventory[9]);
    }
}
