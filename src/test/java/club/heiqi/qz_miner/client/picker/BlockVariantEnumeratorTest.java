package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** 方块变体枚举只信任物品子类型实际暴露的 ItemStack。 */
public class BlockVariantEnumeratorTest {
    @Test
    public void sparseExposedMetadataIsDeduplicatedFilteredAndSorted() {
        ExposingBlock block = new ExposingBlock(8, 0, 4, 8, 32767, -1, 16, 4);
        Item item = new ItemBlock(block);

        BlockCandidate candidate = BlockVariantEnumerator.enumerateBlock("test:sparse", block, item);

        Assert.assertEquals(Arrays.asList(0, 4, 8), metadata(candidate));
        Assert.assertSame(block.exposed.get(0), candidate.variants().get(2).stack());
        Assert.assertSame(block.exposed.get(1), candidate.variants().get(0).stack());
        Assert.assertSame(block.exposed.get(2), candidate.variants().get(1).stack());
        Assert.assertSame(block.exposed.get(1), candidate.representative());
        Assert.assertEquals(8, block.exposed.size());
    }

    @Test
    public void emptyAndFailingSubBlocksProduceVisualPlaceholder() {
        ExposingBlock empty = new ExposingBlock();
        Item emptyItem = new ItemBlock(empty);
        BlockCandidate emptyCandidate = BlockVariantEnumerator.enumerateBlock("test:empty", empty, emptyItem);
        Assert.assertTrue(emptyCandidate.variants().isEmpty());
        Assert.assertNull(emptyCandidate.representative());
        Assert.assertEquals("test:empty", emptyCandidate.localizedName());

        ExposingBlock failing = new ExposingBlock();
        failing.failure = new IllegalStateException("expected");
        BlockCandidate failedCandidate = BlockVariantEnumerator.enumerateBlock(
                "test:failing", failing, new ItemBlock(failing));
        Assert.assertTrue(failedCandidate.variants().isEmpty());
        Assert.assertNull(failedCandidate.representative());
        Assert.assertEquals("test:failing", failedCandidate.localizedName());
    }

    @Test
    public void blockWithoutItemBlockKeepsLogicalMetaZeroWithoutItemIdentity() {
        BlockCandidate candidate = BlockVariantEnumerator.enumerateBlock("minecraft:air", Blocks.air);

        Assert.assertEquals(Collections.singletonList(0), metadata(candidate));
        Assert.assertNull(candidate.variants().get(0).stack());
        Assert.assertNull(candidate.representative());
        Assert.assertFalse(candidate.localizedName().isEmpty());
    }

    @Test
    public void realLitRedstoneOreKeepsStableRegistryAndEncodableVariants() {
        BlockCandidate candidate = BlockVariantEnumerator.enumerateBlock(
                "minecraft:lit_redstone_ore", Blocks.lit_redstone_ore);

        Assert.assertEquals("minecraft:lit_redstone_ore", candidate.registry());
        Assert.assertFalse(candidate.localizedName().isEmpty());
        Assert.assertTrue(metadata(candidate).contains(Integer.valueOf(0)));
    }

    private static List<Integer> metadata(BlockCandidate candidate) {
        List<Integer> result = new ArrayList<Integer>();
        for (BlockVariant variant : candidate.variants()) result.add(Integer.valueOf(variant.metadata()));
        return result;
    }

    /** 测试方块按给定顺序暴露物品子类型，并保留原始 stack 供身份断言。 */
    private static final class ExposingBlock extends Block {
        private final int[] metadata;
        private final List<ItemStack> exposed = new ArrayList<ItemStack>();
        private RuntimeException failure;

        private ExposingBlock(int... metadata) {
            super(Material.rock);
            this.metadata = metadata;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void getSubBlocks(Item item, CreativeTabs tab, List list) {
            if (failure != null) throw failure;
            for (int value : metadata) {
                ItemStack stack = new ItemStack(item, 1, value);
                exposed.add(stack);
                list.add(stack);
            }
        }
    }
}
