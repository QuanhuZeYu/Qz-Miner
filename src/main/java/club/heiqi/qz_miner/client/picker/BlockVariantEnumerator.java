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
import net.minecraft.util.StatCollector;

import club.heiqi.qz_miner.MyMod;

/** 在配置页打开的客户端主线程枚举已注册方块及 metadata。 */
public final class BlockVariantEnumerator {
    private static final int MAX_FAILURE_LOGS = 8;

    /** 创造栏标签捕获失败日志计数；enumerate() 开头重置，维持限流降级风格。 */
    private static volatile int tabFailureLogs;

    private BlockVariantEnumerator() { }

    /** 遍历当前客户端的 Block registry；不访问 world、worker 或 NEI。 */
    public static List<BlockCandidate> enumerate() {
        tabFailureLogs = 0;
        List<BlockCandidate> result = new ArrayList<BlockCandidate>();
        int failures = 0;
        for (Object value : Block.blockRegistry) {
            if (!(value instanceof Block)) continue;
            Block block = (Block) value;
            String registry = null;
            try {
                Object key = Block.blockRegistry.getNameForObject(block);
                registry = key == null ? null : key.toString();
                if (!isValidRegistry(registry)) continue;
                result.add(enumerateBlock(registry, block));
            } catch (RuntimeException e) {
                if (failures++ < MAX_FAILURE_LOGS) MyMod.LOG.warn("Block picker degraded for {}", registry, e);
                if (isValidRegistry(registry)) result.add(placeholder(registry));
            } catch (LinkageError e) {
                if (failures++ < MAX_FAILURE_LOGS) MyMod.LOG.warn("Block picker linkage degraded for {}", registry, e);
                if (isValidRegistry(registry)) result.add(placeholder(registry));
            }
        }
        if (failures > MAX_FAILURE_LOGS) {
            MyMod.LOG.warn("Block picker suppressed {} additional enumeration failures", failures - MAX_FAILURE_LOGS);
        }
        if (tabFailureLogs > MAX_FAILURE_LOGS) {
            MyMod.LOG.warn("Block picker suppressed {} additional creative tab capture failures",
                    tabFailureLogs - MAX_FAILURE_LOGS);
        }
        return Collections.unmodifiableList(result);
    }

    static BlockCandidate enumerateBlock(String registry, Block block) {
        Item item = Item.getItemFromBlock(block);
        if (!(item instanceof ItemBlock)) return blockOnlyCandidate(registry, block);
        return enumerateBlock(registry, block, item);
    }

    /** 仅把方块物品实际暴露的非负 int metadata 转为选择器变体。 */
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
            if (stack != null && stack.getItemDamage() >= 0
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
        return new BlockCandidate(registry, BlockCandidate.modIdOf(registry), creativeTabLabelOf(item),
                representative == null ? registry : safeName(representative), variants, representative);
    }

    /**
     * 捕获方块物品创造栏的本地化标签。tabAllSearch（搜索页）或 null 视为无有效创造栏，
     * 任何 tab 相关 API 异常/链接错误降级为 null 并限流告警。
     */
    static String creativeTabLabelOf(Item item) {
        try {
            CreativeTabs tab = item.getCreativeTab();
            if (tab == null || tab == CreativeTabs.tabAllSearch) return null;
            String label = tab.getTranslatedTabLabel();
            if (label == null || label.trim().isEmpty()) return null;
            return label;
        } catch (RuntimeException e) {
            if (tabFailureLogs++ < MAX_FAILURE_LOGS) MyMod.LOG.warn("Block picker creative tab degraded", e);
            return null;
        } catch (LinkageError e) {
            if (tabFailureLogs++ < MAX_FAILURE_LOGS) MyMod.LOG.warn("Block picker creative tab linkage degraded", e);
            return null;
        }
    }

    private static String safeName(ItemStack stack) {
        String unlocalized = safeUnlocalizedName(stack);
        try {
            String display = stack.getDisplayName();
            if (!isUntranslated(display, stack, unlocalized)) return display;
            String retried = StatCollector.translateToLocal(unlocalized + ".name");
            if (retried != null && !retried.trim().isEmpty()) return retried;
        } catch (RuntimeException e) {
            // 名称捕获异常静默降级，避免热路径枚举被单方块拖垮
        } catch (LinkageError e) {
            // 同上
        }
        if (!unlocalized.isEmpty()) return unlocalized;
        String lastResort = safeUnlocalizedName(stack);
        return lastResort.isEmpty() ? unlocalized : lastResort;
    }

    /** 读取未翻译名；任何异常降级为空串。 */
    private static String safeUnlocalizedName(ItemStack stack) {
        try {
            String name = stack.getUnlocalizedName();
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            return "";
        } catch (LinkageError e) {
            return "";
        }
    }

    /** displayName 是否仍是未翻译形态：空值、unlocalized 原形、.name 后缀或 Item 端未翻译输出。 */
    private static boolean isUntranslated(String display, ItemStack stack, String unlocalized) {
        if (display == null || display.trim().isEmpty()) return true;
        if (display.equals(unlocalized)) return true;
        if (display.equals(unlocalized + ".name")) return true;
        try {
            if (display.equals(stack.getItem().getUnlocalizedNameInefficiently(stack))) return true;
        } catch (RuntimeException e) {
            // Item 端未翻译输出不可用时仅依赖前三条判断
        } catch (LinkageError e) {
            // 同上
        }
        return false;
    }

    /** 无物品身份的方块仍以逻辑 meta 0 参与指定状态选择。 */
    private static BlockCandidate blockOnlyCandidate(String registry, Block block) {
        String name = safeBlockName(registry, block);
        return new BlockCandidate(registry, name,
                Collections.singletonList(new BlockVariant(0, name, null)), null);
    }

    private static String safeBlockName(String registry, Block block) {
        try {
            String localized = block.getLocalizedName();
            String untranslated = block.getUnlocalizedName();
            if (localized == null || localized.trim().isEmpty()
                    || localized.equals(untranslated) || localized.equals(untranslated + ".name")) return registry;
            return localized;
        } catch (RuntimeException e) {
            return registry;
        } catch (LinkageError e) {
            return registry;
        }
    }

    private static boolean isValidRegistry(String registry) {
        if (registry == null || registry.isEmpty()) return false;
        int separator = registry.indexOf(':');
        return separator > 0 && separator < registry.length() - 1;
    }

    private static BlockCandidate placeholder(String registry) {
        return new BlockCandidate(registry, registry, Collections.<BlockVariant>emptyList(), null);
    }
}
