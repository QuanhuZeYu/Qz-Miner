package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** 可选工具对无显式 Forge harvestTool 目标的窄采掘语义适配器。 */
public interface ToolHarvestCompatAdapter {

    /** 适配器判定结果；除 ALLOW 外均不得放行。 */
    enum Result {
        NOT_APPLICABLE,
        ALLOW,
        DENY,
        UNRESOLVED
    }

    /**
     * 求值一个真实工具栈的可选兼容采掘语义。
     *
     * @param item 栈持有的 Item
     * @param stack 当前工具栈
     * @param target 目标方块
     * @return 四态终裁结果
     */
    Result evaluate(Item item, ItemStack stack, Block target);
}
