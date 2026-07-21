package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 通过运行时类层级识别 TiC HarvestTool 族的旧式采掘适配器。 */
public final class TConstructToolHarvestCompatAdapter implements ToolHarvestCompatAdapter {

    private static final String HARVEST_TOOL_TYPE = "tconstruct.library.tools.HarvestTool";

    @Override
    public Result evaluate(Item item, ItemStack stack, Block target) {
        if (item == null || stack == null || target == null) {
            return Result.UNRESOLVED;
        }

        ClassNameCompatSupport.HierarchyMatch hierarchyMatch =
                ClassNameCompatSupport.matchesHierarchyName(item.getClass(), HARVEST_TOOL_TYPE);
        if (hierarchyMatch == ClassNameCompatSupport.HierarchyMatch.NO_MATCH) {
            return Result.NOT_APPLICABLE;
        }
        if (hierarchyMatch != ClassNameCompatSupport.HierarchyMatch.MATCH) {
            return Result.UNRESOLVED;
        }

        try {
            return item.canHarvestBlock(target, stack) ? Result.ALLOW : Result.DENY;
        } catch (SecurityException ignored) {
            return Result.UNRESOLVED;
        } catch (RuntimeException | LinkageError ignored) {
            return Result.UNRESOLVED;
        }
    }
}
