package club.heiqi.qz_miner.toolswap.minecraft;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** 将 Minecraft 物品栈捕获为服务端与客户端共用的不可变状态。 */
public final class AutoToolSwapStackStateFactory {

    private AutoToolSwapStackStateFactory() {
    }

    /**
     * 捕获栈的完整角色、内容和耐久状态。
     *
     * @param stack Minecraft 物品栈
     * @return 不持有原始栈的不可变状态
     */
    public static AutoToolSwapStackState capture(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return AutoToolSwapStackState.empty();
        }
        String roleKey = roleKey(stack);
        String dynamicFingerprint = dynamicFingerprint(stack);
        return AutoToolSwapStackState.occupied(roleKey,
                AutoToolSwapContentFingerprint.fromContent(roleKey, dynamicFingerprint), remainingDurability(stack));
    }

    /**
     * 计算不随普通耐久损耗变化的稳定子类型。
     *
     * @param stack 非空物品栈
     * @return 物品声明子类型时的 damage，否则为 0
     */
    static int stableSubtype(ItemStack stack) {
        return stack.getItem().getHasSubtypes() ? stack.getItemDamage() : 0;
    }

    /**
     * 计算用于恢复匹配的稳定角色。
     *
     * @param stack 非空物品栈
     * @return registry id 与稳定子类型组成的角色
     */
    static String roleKey(ItemStack stack) {
        return registryId(stack.getItem()) + "@" + stableSubtype(stack);
    }

    /**
     * 序列化所有会影响严格内容比对的 Minecraft 栈状态。
     *
     * @param stack 非空物品栈
     * @return NBT 的稳定文本表示
     */
    static String dynamicFingerprint(ItemStack stack) {
        NBTTagCompound serialized = new NBTTagCompound();
        stack.writeToNBT(serialized);
        return serialized.toString();
    }

    /**
     * 计算剩余可用耐久；非损耗品视为无限耐久。
     *
     * @param stack 非空物品栈
     * @return 剩余耐久
     */
    static int remainingDurability(ItemStack stack) {
        return stack.isItemStackDamageable() ? Math.max(0, stack.getMaxDamage() - stack.getItemDamage())
                : Integer.MAX_VALUE;
    }

    /**
     * 取得注册表名称，并为未注册的测试或异常物品保留既有客户端回退值。
     *
     * @param item Minecraft 物品
     * @return 注册表名称或回退名称
     */
    private static String registryId(Item item) {
        Object name = Item.itemRegistry.getNameForObject(item);
        return name == null ? "minecraft:unknown" : String.valueOf(name);
    }
}
