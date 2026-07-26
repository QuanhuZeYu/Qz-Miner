package club.heiqi.qz_miner.toolswap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.oredict.OreDictionary;

/** 自动工具候选与保留的冻结能力内部类型共用的采掘资格规则。 */
public final class ToolHarvestEligibility {

    private ToolHarvestEligibility() {}

    /** @return 真实工具是否同时满足收获与耐久储备门；效率只作为候选事实。 */
    public static boolean isEligible(ItemStack stack, Block target, int metadata) {
        return canHarvest(stack, target, metadata)
                && AutoToolUsabilityPolicy.hasDurabilityReserve(remainingDurability(stack));
    }

    /** @return 空手是否按 Forge 通用无工具语义可收获目标。 */
    public static boolean canHarvestWithEmptyHand(Block target, int metadata) {
        return target != null && target.getMaterial().isToolNotRequired();
    }

    /** 采样目标实际效率是否高于徒手基线；该事实不直接否决候选资格。 */
    public static boolean isEffective(ItemStack stack, Block target, int metadata) {
        if (stack == null || stack.getItem() == null || target == null) {
            return false;
        }
        try {
            return stack.getItem().getDigSpeed(stack, target, metadata) > 1.0F;
        } catch (RuntimeException failure) {
            return false;
        } catch (LinkageError failure) {
            return false;
        }
    }

    /** 使用显式 Forge 工具等级或稳定 Item API 判断真实栈能否收获目标。 */
    public static boolean canHarvest(ItemStack stack, Block target, int metadata) {
        if (stack == null || stack.getItem() == null || target == null) {
            return false;
        }
        if (target.getHarvestTool(metadata) != null) {
            return ForgeHooks.canToolHarvestBlock(target, metadata, stack);
        }
        if (target.getMaterial().isToolNotRequired()) {
            return true;
        }
        // 不维护模组类白名单；稳定 Item API 对未知工具同样适用。
        try {
            return stack.getItem().canHarvestBlock(target, stack);
        } catch (RuntimeException failure) {
            return false;
        } catch (LinkageError failure) {
            return false;
        }
    }

    /** @return 剩余耐久；空栈与不可损耗物映射为无限。 */
    public static int remainingDurability(ItemStack stack) {
        return stack == null || !stack.isItemStackDamageable() ? Integer.MAX_VALUE
                : Math.max(0, stack.getMaxDamage() - stack.getItemDamage());
    }

    /** 普通可损耗物品不把 durability damage 当作 subtype。 */
    public static int stableSubtype(ItemStack stack) {
        return stack != null && stack.getItem() != null && stack.getItem().getHasSubtypes()
                ? stack.getItemDamage() : 0;
    }

    /** 捕获客户端 selector 与规划排序共用的纯候选事实。 */
    public static ToolCandidate snapshotCandidate(int slot, ItemStack stack, Block target, int metadata) {
        if (stack == null || stack.getItem() == null) return null;
        Item item = stack.getItem();
        return new ToolCandidate(slot, registryId(item), stableSubtype(stack), oreNames(stack),
                isEffective(stack, target, metadata), canHarvest(stack, target, metadata),
                remainingDurability(stack));
    }

    /** 捕获只用于 selector 排序的候选身份，不提前求值目标能力。 */
    public static ToolCandidate snapshotIdentity(int slot, ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        Item item = stack.getItem();
        return new ToolCandidate(slot, registryId(item), stableSubtype(stack), oreNames(stack),
                true, true, Integer.MAX_VALUE);
    }

    private static String registryId(Item item) {
        Object name = Item.itemRegistry.getNameForObject(item);
        return name == null ? "minecraft:unknown" : String.valueOf(name);
    }

    private static List<String> oreNames(ItemStack stack) {
        int[] ids = OreDictionary.getOreIDs(stack);
        if (ids == null || ids.length == 0) return Collections.emptyList();
        List<String> names = new ArrayList<String>(ids.length);
        for (int id : ids) {
            String name = OreDictionary.getOreName(id);
            if (name != null && name.length() > 0) names.add(name);
        }
        return Collections.unmodifiableList(names);
    }
}
