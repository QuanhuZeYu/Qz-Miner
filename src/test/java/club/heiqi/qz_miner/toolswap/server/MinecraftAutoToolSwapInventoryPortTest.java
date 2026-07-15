package club.heiqi.qz_miner.toolswap.server;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

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
        ItemStack[] mainInventory = new ItemStack[36];
        mainInventory[2] = first;
        mainInventory[19] = second;
        int selectedSlot = 2;

        MinecraftAutoToolSwapInventoryPort.swapMainInventorySlots(mainInventory, 2, 19);

        Assert.assertSame(second, mainInventory[2]);
        Assert.assertSame(first, mainInventory[19]);
        Assert.assertEquals(2, selectedSlot);
    }
}
