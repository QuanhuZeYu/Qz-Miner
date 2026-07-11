package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

/** 不可变方块搜索候选。 */
public final class BlockCandidate {
    private final String registry;
    private final String localizedName;
    private final List<BlockVariant> variants;
    private final ItemStack representative;

    public BlockCandidate(String registry, String localizedName, List<BlockVariant> variants, ItemStack representative) {
        this.registry = registry;
        this.localizedName = localizedName == null ? registry : localizedName;
        this.variants = Collections.unmodifiableList(new ArrayList<BlockVariant>(variants));
        this.representative = representative;
    }

    public String registry() { return registry; }
    public String localizedName() { return localizedName; }
    public List<BlockVariant> variants() { return variants; }
    public ItemStack representative() { return representative; }
}
