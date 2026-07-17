package club.heiqi.qz_miner.client.picker;

import net.minecraft.item.ItemStack;

/** 方块 registry 的一个可展示 metadata 变体。 */
public final class BlockVariant {
    private final int metadata;
    private final String name;
    private final ItemStack stack;

    public BlockVariant(int metadata, String name, ItemStack stack) {
        this.metadata = metadata;
        this.name = name == null ? "" : name;
        this.stack = stack;
    }

    public int metadata() { return metadata; }
    public String name() { return name; }
    public ItemStack stack() { return stack; }
}
