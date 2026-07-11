package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import club.heiqi.qz_miner.MyMod;

/** 在配置页打开的客户端主线程枚举已注册方块及 metadata。 */
public final class BlockVariantEnumerator {
    private static final int MAX_FAILURE_LOGS = 8;

    private BlockVariantEnumerator() { }

    /** 遍历当前客户端的 Block registry；不访问 world、worker 或 NEI。 */
    public static List<BlockCandidate> enumerate() {
        List<BlockCandidate> result = new ArrayList<BlockCandidate>();
        int failures = 0;
        for (Object value : Block.blockRegistry) {
            if (!(value instanceof Block)) continue;
            Block block = (Block) value;
            String registry = String.valueOf(Block.blockRegistry.getNameForObject(block));
            try {
                result.add(enumerateBlock(registry, block));
            } catch (RuntimeException e) {
                if (failures++ < MAX_FAILURE_LOGS) MyMod.LOG.warn("Block picker degraded for {}", registry, e);
                result.add(placeholder(registry));
            } catch (LinkageError e) {
                if (failures++ < MAX_FAILURE_LOGS) MyMod.LOG.warn("Block picker linkage degraded for {}", registry, e);
                result.add(placeholder(registry));
            }
        }
        if (failures > MAX_FAILURE_LOGS) {
            MyMod.LOG.warn("Block picker suppressed {} additional enumeration failures", failures - MAX_FAILURE_LOGS);
        }
        return Collections.unmodifiableList(result);
    }

    static BlockCandidate enumerateBlock(String registry, Block block) {
        Item item = Item.getItemFromBlock(block);
        if (!(item instanceof ItemBlock)) return placeholder(registry);
        return enumerateBlock(registry, block, item);
    }

    /** 仅把方块物品实际暴露的 0..15 metadata 转为选择器变体。 */
    static BlockCandidate enumerateBlock(String registry, Block block, Item item) {
        List<ItemStack> supplied = new ArrayList<ItemStack>();
        try {
            block.getSubBlocks(item, CreativeTabs.tabAllSearch, supplied);
        } catch (RuntimeException e) {
            return placeholder(registry);
        } catch (LinkageError e) {
            return placeholder(registry);
        }
        Map<Integer, ItemStack> firstByMeta = new LinkedHashMap<Integer, ItemStack>();
        for (ItemStack stack : supplied) {
            if (stack != null && stack.getItemDamage() >= 0 && stack.getItemDamage() <= 15
                    && !firstByMeta.containsKey(Integer.valueOf(stack.getItemDamage()))) {
                firstByMeta.put(Integer.valueOf(stack.getItemDamage()), stack);
            }
        }
        List<BlockVariant> variants = new ArrayList<BlockVariant>();
        for (Map.Entry<Integer, ItemStack> entry : firstByMeta.entrySet()) {
            ItemStack stack = entry.getValue();
            variants.add(new BlockVariant(entry.getKey().intValue(), safeName(stack), stack));
        }
        Collections.sort(variants, Comparator.comparingInt(BlockVariant::metadata));
        ItemStack representative = variants.isEmpty() ? null : variants.get(0).stack();
        return new BlockCandidate(registry, representative == null ? registry : safeName(representative),
                variants, representative);
    }

    private static String safeName(ItemStack stack) {
        String name = stack.getDisplayName();
        return name == null || name.isEmpty() ? stack.getUnlocalizedName() : name;
    }

    private static BlockCandidate placeholder(String registry) {
        return new BlockCandidate(registry, registry, Collections.<BlockVariant>emptyList(), null);
    }
}
