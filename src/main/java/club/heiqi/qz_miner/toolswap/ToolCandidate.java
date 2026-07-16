package club.heiqi.qz_miner.toolswap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 已由 adapter 捕获的纯候选事实，不持有 ItemStack 或 registry 对象。
 */
public final class ToolCandidate {

    private final int slot;
    private final String registryId;
    private final int subtype;
    private final Set<String> oreNames;
    private final boolean effective;
    private final boolean canHarvest;
    private final int remainingDurability;

    /**
     * @param slot InventoryPlayer.mainInventory 索引 0..35
     * @param registryId 已解析 registry id
     * @param subtype 物品 subtype；普通可损耗物品应由 adapter 传稳定 subtype，而非 durability damage
     * @param oreNames 只读矿辞名快照
     * @param effective 对目标是否有实际采掘效率
     * @param canHarvest 有等级要求时是否能正确收获
     * @param remainingDurability 剩余耐久；不可损耗物品可传 Integer.MAX_VALUE
     */
    public ToolCandidate(int slot, String registryId, int subtype, Collection<String> oreNames,
            boolean effective, boolean canHarvest, int remainingDurability) {
        if (slot < 0 || slot > 35 || registryId == null || registryId.length() == 0) {
            throw new IllegalArgumentException("slot must be 0..35 and registryId must not be empty");
        }
        this.slot = slot;
        this.registryId = registryId;
        this.subtype = subtype;
        this.oreNames = Collections.unmodifiableSet(new LinkedHashSet<String>(
                oreNames == null ? Collections.<String>emptyList() : new ArrayList<String>(oreNames)));
        this.effective = effective;
        this.canHarvest = canHarvest;
        this.remainingDurability = remainingDurability;
    }

    public int slot() {
        return slot;
    }

    public String registryId() {
        return registryId;
    }

    public int subtype() {
        return subtype;
    }

    public Set<String> oreNames() {
        return oreNames;
    }

    /** @return 当前主手是否满足统一能力与耐久储备门 */
    public boolean isUsableInHand() {
        return AutoToolUsabilityPolicy.canContinue(effective, canHarvest, remainingDurability);
    }

    /** @return 是否可从背包换入（至少剩余 2 点耐久） */
    public boolean isEligibleForSwap() {
        return AutoToolUsabilityPolicy.canContinue(effective, canHarvest, remainingDurability);
    }
}
