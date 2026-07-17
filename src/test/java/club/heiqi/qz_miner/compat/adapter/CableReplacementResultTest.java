package club.heiqi.qz_miner.compat.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.Test;

/** 线缆替换结果 DTO 测试。 */
public class CableReplacementResultTest {

    /** 成功结果持有并返回防御性副本。 */
    @Test
    public void successfulResultCopiesReturnedStack() {
        ItemStack source = new ItemStack(Items.iron_ingot, 1, 0);
        CableReplacementResult result = CableReplacementResult.success(source);
        source.stackSize = 9;
        ItemStack returned = result.getReturnedStack();
        assertTrue(result.isSuccessful());
        assertEquals(1, returned.stackSize);
        assertNotSame(returned, result.getReturnedStack());
    }

    /** 失败结果不携带返还物。 */
    @Test
    public void failureCarriesNoReturnedStack() {
        assertFalse(CableReplacementResult.failure().isSuccessful());
        assertEquals(null, CableReplacementResult.failure().getReturnedStack());
    }
}
