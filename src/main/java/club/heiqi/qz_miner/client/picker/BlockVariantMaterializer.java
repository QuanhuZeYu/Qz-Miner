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

/**
 * 单 registry 候选物化器（无状态、无缓存）：{@code getSubBlocks} + 变体名称 + 创造栏标签。
 *
 * <p>契约出处：（ADR 原稿在工作站、不在仓内） §1.7 D-9（{@code BlockVariantEnumerator.enumerateBlock}
 * 方法体原样搬入分片层）；（改造设计原稿在工作站、不在仓内）。</p>
 *
 * <p><b>为什么单独成类</b>：缓存与物化解耦——{@link BlockVariantShardCache} 负责「何时物化、物化几次、
 * 何时淘汰」，本类负责「一个方块怎么物化」，因此三件昂贵动作（{@code getSubBlocks} / 逐变体
 * {@code getDisplayName} / 创造栏捕获）可以独立单测，且全部延后到首个真正需要该分片的请求。</p>
 *
 * <p><b>失败降级</b>：{@code getSubBlocks} 抛异常/链接错误 → 空变体候选（占位）；单个名称/创造栏异常 →
 * 该字段降级，不丢候选。任何路径都不得产出非法 raw（选择器只消费 canonical selector 语义）。</p>
 *
 * <p><b>线程</b>：只在客户端主线程调用（触碰 {@code Item.getItemFromBlock}、{@code StatCollector}）。</p>
 */
public final class BlockVariantMaterializer {

    private static final int MAX_FAILURE_LOGS = 8;

    /** 创造栏标签捕获失败日志计数；限流降级风格与旧枚举一致。 */
    private static volatile int tabFailureLogs;

    private BlockVariantMaterializer() {
    }

    /**
     * 物化单个 registry 的候选（含全部变体）。
     *
     * @param registry registry 键（非 null）
     * @param block    该 registry 对应的方块（非 null）
     * @return 候选；无 ItemBlock 身份时退化为逻辑 meta 0 候选
     */
    public static BlockCandidate materialize(String registry, Block block) {
        Item item = Item.getItemFromBlock(block);
        if (!(item instanceof ItemBlock)) {
            return blockOnlyCandidate(registry, block);
        }
        return materialize(registry, block, item);
    }

    /**
     * 物化单个 registry 的候选（显式指定物品身份；测试与对拍入口）。
     *
     * @param registry registry 键
     * @param block    方块
     * @param item     该方块的物品身份
     * @return 候选
     */
    static BlockCandidate materialize(String registry, Block block, Item item) {
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
     *
     * @param item 方块物品
     * @return 创造栏标签；无有效创造栏时 null
     */
    static String creativeTabLabelOf(Item item) {
        try {
            CreativeTabs tab = item.getCreativeTab();
            if (tab == null || tab == CreativeTabs.tabAllSearch) {
                return null;
            }
            String label = tab.getTranslatedTabLabel();
            if (label == null || label.trim().isEmpty()) {
                return null;
            }
            return label;
        } catch (RuntimeException e) {
            if (tabFailureLogs++ < MAX_FAILURE_LOGS) {
                MyMod.LOG.warn("Block picker creative tab degraded", e);
            }
            return null;
        } catch (LinkageError e) {
            if (tabFailureLogs++ < MAX_FAILURE_LOGS) {
                MyMod.LOG.warn("Block picker creative tab linkage degraded", e);
            }
            return null;
        }
    }

    /** @return 失败/无身份的占位候选（空变体，仅 registry 名） */
    static BlockCandidate placeholder(String registry) {
        return new BlockCandidate(registry, registry, Collections.<BlockVariant>emptyList(), null);
    }

    /** 显示名降级链：displayName → translateToLocal(unlocalized+".name") → unlocalized。 */
    private static String safeName(ItemStack stack) {
        String unlocalized = safeUnlocalizedName(stack);
        try {
            String display = stack.getDisplayName();
            if (!isUntranslated(display, stack, unlocalized)) {
                return display;
            }
            String retried = StatCollector.translateToLocal(unlocalized + ".name");
            if (retried != null && !retried.trim().isEmpty()) {
                return retried;
            }
        } catch (RuntimeException e) {
            // 名称捕获异常静默降级，避免热路径物化被单方块拖垮
        } catch (LinkageError e) {
            // 同上
        }
        if (!unlocalized.isEmpty()) {
            return unlocalized;
        }
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
        if (display == null || display.trim().isEmpty()) {
            return true;
        }
        if (display.equals(unlocalized)) {
            return true;
        }
        if (display.equals(unlocalized + ".name")) {
            return true;
        }
        try {
            if (display.equals(stack.getItem().getUnlocalizedNameInefficiently(stack))) {
                return true;
            }
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
                    || localized.equals(untranslated) || localized.equals(untranslated + ".name")) {
                return registry;
            }
            return localized;
        } catch (RuntimeException e) {
            return registry;
        } catch (LinkageError e) {
            return registry;
        }
    }
}
