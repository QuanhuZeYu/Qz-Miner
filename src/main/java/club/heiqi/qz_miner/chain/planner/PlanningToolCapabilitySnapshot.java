package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolCandidateOrder;
import club.heiqi.qz_miner.toolswap.ToolHarvestEligibility;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/** CHAIN 在 PlanStarted 主线程冻结的采掘能力集合；worker 不再读取实时玩家库存。 */
public final class PlanningToolCapabilitySnapshot {

    /** 命中候选的稳定优先级分类。 */
    public enum MatchKind {
        CURRENT_HAND,
        INVENTORY_TOOL,
        EMPTY_HAND,
        NONE
    }

    private final boolean creative;
    private final ItemStack currentHand;
    private final List<ItemStack> inventoryTools;

    private PlanningToolCapabilitySnapshot(boolean creative, ItemStack currentHand,
            List<ItemStack> inventoryTools) {
        this.creative = creative;
        this.currentHand = copy(currentHand);
        List<ItemStack> frozen = new ArrayList<ItemStack>(inventoryTools.size());
        for (ItemStack stack : inventoryTools) frozen.add(copy(stack));
        this.inventoryTools = Collections.unmodifiableList(frozen);
    }

    /** 在主线程捕获玩家当前手持、selector 排序后的全部背包栈和空手虚拟候选。 */
    public static PlanningToolCapabilitySnapshot capture(EntityPlayer player,
            List<ToolSelector> selectors, boolean includeInventoryTools) {
        if (player == null || player.inventory == null || player.inventory.mainInventory == null) {
            return new PlanningToolCapabilitySnapshot(false, null, Collections.<ItemStack>emptyList());
        }
        return capture(player.inventory.mainInventory, player.inventory.currentItem,
                player.capabilities.isCreativeMode, selectors, includeInventoryTools);
    }

    /** 数组入口用于证明捕获后与原库存变化隔离。 */
    static PlanningToolCapabilitySnapshot capture(ItemStack[] inventory, int selectedSlot,
            boolean creative, List<ToolSelector> selectors, boolean includeInventoryTools) {
        ItemStack held = validSlot(inventory, selectedSlot) ? inventory[selectedSlot] : null;
        if (!includeInventoryTools || inventory == null) {
            return new PlanningToolCapabilitySnapshot(creative, held, Collections.<ItemStack>emptyList());
        }

        Map<Integer, ItemStack> copiesBySlot = new HashMap<Integer, ItemStack>();
        List<ToolCandidate> identities = new ArrayList<ToolCandidate>();
        int limit = Math.min(36, inventory.length);
        for (int slot = 0; slot < limit; slot++) {
            if (slot == selectedSlot || inventory[slot] == null || inventory[slot].getItem() == null) continue;
            ItemStack frozen = copy(inventory[slot]);
            ToolCandidate identity = ToolHarvestEligibility.snapshotIdentity(slot, frozen);
            if (identity != null) {
                copiesBySlot.put(Integer.valueOf(slot), frozen);
                identities.add(identity);
            }
        }
        List<ItemStack> ordered = new ArrayList<ItemStack>();
        for (ToolCandidate candidate : ToolCandidateOrder.sort(identities, selectors)) {
            ordered.add(copiesBySlot.get(Integer.valueOf(candidate.slot())));
        }
        return new PlanningToolCapabilitySnapshot(creative, held, ordered);
    }

    /** 包级纯 JVM 接缝：调用方传入已经按 selector/槽位排序的真实候选。 */
    static PlanningToolCapabilitySnapshot fromOrderedStacks(ItemStack currentHand,
            List<ItemStack> orderedInventoryTools, boolean creative) {
        if (orderedInventoryTools == null) {
            throw new IllegalArgumentException("orderedInventoryTools must not be null");
        }
        return new PlanningToolCapabilitySnapshot(creative, currentHand, orderedInventoryTools);
    }

    /** 按当前手持、背包全部可用工具、空手的顺序选择首个冻结能力。 */
    public MatchKind select(Block target, int metadata) {
        if (target == null) return MatchKind.NONE;
        if (creative) return MatchKind.CURRENT_HAND;
        if (ToolHarvestEligibility.isEligible(currentHand, target, metadata)) {
            return MatchKind.CURRENT_HAND;
        }
        for (ItemStack stack : inventoryTools) {
            if (ToolHarvestEligibility.isEligible(stack, target, metadata)) {
                return MatchKind.INVENTORY_TOOL;
            }
        }
        return ToolHarvestEligibility.canHarvestWithEmptyHand(target, metadata)
                ? MatchKind.EMPTY_HAND : MatchKind.NONE;
    }

    /** @return 冻结集合中是否至少有一个候选可规划收获目标。 */
    public boolean canPlanHarvest(Block target, int metadata) {
        return select(target, metadata) != MatchKind.NONE;
    }

    /** @return selector 排序后的冻结背包真实候选数。 */
    int inventoryToolCount() {
        return inventoryTools.size();
    }

    private static boolean validSlot(ItemStack[] inventory, int slot) {
        return inventory != null && slot >= 0 && slot < inventory.length;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
