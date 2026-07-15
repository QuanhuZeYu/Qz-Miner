package club.heiqi.qz_miner.toolswap.minecraft;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 共享 Minecraft 栈状态工厂的语义合同。 */
public class AutoToolSwapStackStateFactoryTest {

    /** 空栈必须产生协议唯一的 canonical empty 状态。 */
    @Test
    public void nullStackCapturesCanonicalEmptyState() {
        Assert.assertTrue(AutoToolSwapStackStateFactory.capture(null).isEmpty());
        Assert.assertTrue(AutoToolSwapStackStateFactory.capture(null).sameContent(
                AutoToolSwapStackStateFactory.capture(null)));
    }

    /** 普通耐久 damage 不属于 subtype，而声明子类型的物品保持 metadata。 */
    @Test
    public void capturesDamageableAndSubtypeItemsWithClientCompatibleRoles() {
        Item damageable = new Item().setMaxDamage(100);
        ItemStack damaged = new ItemStack(damageable, 1, 37);
        Assert.assertEquals(0, AutoToolSwapStackStateFactory.stableSubtype(damaged));
        Assert.assertEquals(63, AutoToolSwapStackStateFactory.capture(damaged).remainingDurability());

        Item variants = new Item().setHasSubtypes(true);
        ItemStack variant = new ItemStack(variants, 1, 4);
        Assert.assertEquals(4, AutoToolSwapStackStateFactory.stableSubtype(variant));
        Assert.assertTrue(AutoToolSwapStackStateFactory.roleKey(variant).endsWith("@4"));
        Assert.assertEquals(Integer.MAX_VALUE, AutoToolSwapStackStateFactory.capture(variant).remainingDurability());
    }

    /** 栈数量、NBT 与耐久变化都必须改变严格内容，但保留相同恢复角色。 */
    @Test
    public void dynamicChangesAlterContentWithoutChangingRole() {
        Item item = new Item().setMaxDamage(100);
        ItemStack original = new ItemStack(item, 1, 10);
        ItemStack differentCount = new ItemStack(item, 2, 10);
        ItemStack differentDamage = new ItemStack(item, 1, 11);
        ItemStack differentNbt = new ItemStack(item, 1, 10);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("owner", "qz");
        differentNbt.setTagCompound(tag);

        Assert.assertFalse(AutoToolSwapStackStateFactory.capture(original).sameContent(
                AutoToolSwapStackStateFactory.capture(differentCount)));
        Assert.assertFalse(AutoToolSwapStackStateFactory.capture(original).sameContent(
                AutoToolSwapStackStateFactory.capture(differentDamage)));
        Assert.assertFalse(AutoToolSwapStackStateFactory.capture(original).sameContent(
                AutoToolSwapStackStateFactory.capture(differentNbt)));
        Assert.assertTrue(AutoToolSwapStackStateFactory.capture(original).sameRole(
                AutoToolSwapStackStateFactory.capture(differentNbt)));
    }
}
