package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

/** 不可变方块搜索候选。 */
public final class BlockCandidate {
    private final String registry;
    private final String modId;
    private final String creativeTab;
    private final String localizedName;
    private final List<BlockVariant> variants;
    private final ItemStack representative;

    public BlockCandidate(String registry, String localizedName, List<BlockVariant> variants, ItemStack representative) {
        this(registry, modIdOf(registry), null, localizedName, variants, representative);
    }

    public BlockCandidate(String registry, String modId, String creativeTab, String localizedName,
            List<BlockVariant> variants, ItemStack representative) {
        this.registry = registry;
        this.modId = modId;
        this.creativeTab = creativeTab;
        this.localizedName = localizedName == null ? registry : localizedName;
        this.variants = Collections.unmodifiableList(new ArrayList<BlockVariant>(variants));
        this.representative = representative;
    }

    public String registry() { return registry; }
    /** @return registry namespace（冒号前段）；registry 无冒号时返回 null */
    public String modId() { return modId; }
    /** @return 创造栏本地化标签；tabAllSearch、无创造栏或捕获失败时为 null */
    public String creativeTab() { return creativeTab; }
    public String localizedName() { return localizedName; }
    public List<BlockVariant> variants() { return variants; }
    public ItemStack representative() { return representative; }

    static String modIdOf(String registry) {
        if (registry == null) return null;
        int separator = registry.indexOf(':');
        return separator > 0 ? registry.substring(0, separator) : null;
    }
}
