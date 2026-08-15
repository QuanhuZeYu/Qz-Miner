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
import net.minecraft.util.StatCollector;

/** 方块变体枚举只信任物品子类型实际暴露的 ItemStack。 */
public class BlockVariantEnumeratorTest {
    @Test
    public void sparseExposedMetadataIsDeduplicatedFilteredAndSorted() {
        ExposingBlock block = new ExposingBlock(8, 0, 4, 8, 32767, -1, 16, 4,
                24902, 65535, 16777216, Integer.MAX_VALUE);
        Item item = new ItemBlock(block);

        BlockCandidate candidate = BlockVariantEnumerator.enumerateBlock("test:sparse", block, item);

        Assert.assertEquals(Arrays.asList(0, 4, 8, 16, 24902, 32767, 65535,
                16777216, Integer.MAX_VALUE), metadata(candidate));
        Assert.assertSame(block.exposed.get(0), candidate.variants().get(2).stack());
        Assert.assertSame(block.exposed.get(1), candidate.variants().get(0).stack());
        Assert.assertSame(block.exposed.get(2), candidate.variants().get(1).stack());
        Assert.assertSame(block.exposed.get(6), candidate.variants().get(3).stack());
        Assert.assertSame(block.exposed.get(11), candidate.variants().get(8).stack());
        Assert.assertSame(block.exposed.get(1), candidate.representative());
        Assert.assertEquals(12, block.exposed.size());
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

    @Test
    public void creativeTabCaptureUsesTranslatedLabelAndTreatsSearchOrMissingAsOther() {
        CreativeTabs fakeTab = new CreativeTabs("test_tab") {
            @Override
            public String getTranslatedTabLabel() { return "测试标签"; }
            @Override
            public Item getTabIconItem() { return null; }
        };
        ExposingBlock block = new ExposingBlock(0);
        block.setCreativeTab(fakeTab);
        BlockCandidate candidate = BlockVariantEnumerator.enumerateBlock("test:tabbed", block, new ItemBlock(block));
        Assert.assertEquals("测试标签", candidate.creativeTab());
        Assert.assertEquals("test", candidate.modId());

        block.setCreativeTab(CreativeTabs.tabAllSearch);
        Assert.assertNull(BlockVariantEnumerator.enumerateBlock("test:tabbed", block, new ItemBlock(block)).creativeTab());

        block.setCreativeTab(null);
        Assert.assertNull(BlockVariantEnumerator.enumerateBlock("test:tabbed", block, new ItemBlock(block)).creativeTab());
    }

    @Test
    public void failingTabLabelDegradesToNullWithoutLosingCandidate() {
        CreativeTabs failingTab = new CreativeTabs("failing_tab") {
            @Override
            public String getTranslatedTabLabel() { throw new IllegalStateException("expected"); }
            @Override
            public Item getTabIconItem() { return null; }
        };
        ExposingBlock block = new ExposingBlock(0);
        block.setCreativeTab(failingTab);
        BlockCandidate candidate = BlockVariantEnumerator.enumerateBlock("test:failing_tab", block, new ItemBlock(block));
        Assert.assertNull(candidate.creativeTab());
        Assert.assertEquals("test", candidate.modId());
        Assert.assertEquals("test:failing_tab", candidate.registry());
    }

    @Test
    public void blockWithoutItemBlockKeepsNullCreativeTab() {
        BlockCandidate candidate = BlockVariantEnumerator.enumerateBlock("minecraft:air", Blocks.air);
        Assert.assertNull(candidate.creativeTab());
        Assert.assertEquals("minecraft", candidate.modId());
    }

    @Test
    public void safeNamePrefersTranslatedDisplayName() {
        BlockCandidate candidate = enumerateWithNameItem(
                new NameItem("tile.test_stone", "测试石", null));

        Assert.assertEquals("测试石", candidate.variants().get(0).name());
        Assert.assertEquals("测试石", candidate.localizedName());
    }

    @Test
    public void safeNameRetriesTranslationWhenDisplayNameIsUntranslated() {
        String expected = StatCollector.translateToLocal("tile.test_stone.name");
        BlockCandidate candidate = enumerateWithNameItem(
                new NameItem("tile.test_stone", "tile.test_stone.name", null));

        Assert.assertEquals(expected, candidate.variants().get(0).name());
        Assert.assertFalse(candidate.localizedName().isEmpty());
    }

    @Test
    public void safeNameRetriesTranslationWhenDisplayNameIsEmpty() {
        String expected = StatCollector.translateToLocal("tile.test_stone.name");
        BlockCandidate candidate = enumerateWithNameItem(
                new NameItem("tile.test_stone", "", null));

        Assert.assertEquals(expected, candidate.variants().get(0).name());
        Assert.assertFalse(candidate.localizedName().isEmpty());
    }

    @Test
    public void safeNameDegradesToUnlocalizedNameWhenDisplayThrows() {
        BlockCandidate candidate = enumerateWithNameItem(
                new NameItem("tile.test_stone", "测试石", new IllegalStateException("expected")));

        Assert.assertEquals("tile.test_stone", candidate.variants().get(0).name());
        Assert.assertEquals("tile.test_stone", candidate.localizedName());
    }

    private static BlockCandidate enumerateWithNameItem(NameItem item) {
        return BlockVariantEnumerator.enumerateBlock("test:name_block", new NameExposingBlock(), item);
    }

    private static List<Integer> metadata(BlockCandidate candidate) {
        List<Integer> result = new ArrayList<Integer>();
        for (BlockVariant variant : candidate.variants()) result.add(Integer.valueOf(variant.metadata()));
        return result;
    }

    /** 可控 unlocalized / displayName / 异常的 Item 桩。 */
    private static final class NameItem extends Item {
        private final String unlocalizedName;
        private final String displayName;
        private final RuntimeException displayFailure;

        private NameItem(String unlocalizedName, String displayName, RuntimeException displayFailure) {
            this.unlocalizedName = unlocalizedName;
            this.displayName = displayName;
            this.displayFailure = displayFailure;
        }

        @Override
        public String getUnlocalizedName() { return unlocalizedName; }

        @Override
        public String getUnlocalizedName(ItemStack stack) { return unlocalizedName; }

        @Override
        public String getItemStackDisplayName(ItemStack stack) {
            if (displayFailure != null) throw displayFailure;
            return displayName;
        }
    }

    /** 用传入 item 暴露单 stack 的测试方块，绕过真实 getSubBlocks。 */
    private static final class NameExposingBlock extends Block {
        private NameExposingBlock() {
            super(Material.rock);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void getSubBlocks(Item item, CreativeTabs tab, List list) {
            list.add(new ItemStack(item, 1, 0));
        }
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
