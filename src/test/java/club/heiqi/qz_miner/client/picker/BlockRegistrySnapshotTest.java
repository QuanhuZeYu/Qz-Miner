package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import club.heiqi.config.ui.editor.SearchPickerCategories;

/** 清单快照：只读注册名清单 + modId 前缀统计，零变体物化（ADR A-05 / 设计 N1）。 */
public class BlockRegistrySnapshotTest {

    @Test
    public void snapshotCarriesRegistryOrderWithoutMaterializingVariants() {
        CountingBlock first = new CountingBlock(0, 4, 8);
        CountingBlock second = new CountingBlock(0);
        Map<String, Block> ordered = new LinkedHashMap<String, Block>();
        ordered.put("test:first", first);
        ordered.put("test:second", second);

        BlockRegistrySnapshot snapshot = BlockRegistrySnapshot.of(ordered);

        Assert.assertEquals(2, snapshot.size());
        Assert.assertEquals("test:first", snapshot.registry(0));
        Assert.assertEquals("test:second", snapshot.registry(1));
        Assert.assertSame(first, snapshot.block(0));
        Assert.assertEquals(0, snapshot.indexOf("test:first"));
        Assert.assertEquals(1, snapshot.indexOf("test:second"));
        Assert.assertEquals(-1, snapshot.indexOf("test:missing"));
        Assert.assertEquals(-1, snapshot.indexOf(null));
        Assert.assertSame(second, snapshot.blockFor("test:second"));
        Assert.assertNull(snapshot.blockFor("test:missing"));
        Assert.assertTrue(snapshot.contains("test:first"));
        Assert.assertEquals(Arrays.asList("test:first", "test:second"), snapshot.registries());
        // N1：建快照后模板方块的 getSubBlocks 调用数 = 0（清单与物化解耦）
        Assert.assertEquals(0, first.subBlocksCalls);
        Assert.assertEquals(0, second.subBlocksCalls);
    }

    @Test
    public void modCategoriesDeriveFromRegistryNamesWithoutMaterialization() {
        CountingBlock block = new CountingBlock(0);
        Map<String, Block> ordered = new LinkedHashMap<String, Block>();
        ordered.put("minecraft:stone", block);
        ordered.put("minecraft:dirt", block);
        ordered.put("gt:copper", block);
        ordered.put("legacy:bare", block);

        BlockRegistrySnapshot snapshot = BlockRegistrySnapshot.of(ordered);

        List<String> keys = new ArrayList<String>();
        for (SearchPickerCategories.Category category : snapshot.modCategories()) {
            keys.add(category.key());
        }
        Assert.assertEquals(Arrays.asList("gt", "legacy", "minecraft"), keys);
        Assert.assertEquals(2, snapshot.modCount("minecraft"));
        Assert.assertEquals(1, snapshot.modCount("gt"));
        Assert.assertEquals(0, snapshot.modCount("missing"));
        Assert.assertEquals(0, snapshot.modCount(null));
        Assert.assertEquals(3, snapshot.modCategoryCount());
        Assert.assertEquals("minecraft", snapshot.modId(0));
        Assert.assertEquals("gt", snapshot.modId(2));
        Assert.assertEquals(0, block.subBlocksCalls);
        try {
            snapshot.modCategories().add(new SearchPickerCategories.Category("x", "y", 1));
            Assert.fail("modCategories must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    /**
     * 生产 capture 路径在 headless 单测 JVM 下的安全性 + 结构性不变量。
     *
     * <p>注意：单测 JVM 没有客户端引导，{@code Block.blockRegistry} 为空（实测 size=0，
     * {@code Blocks.air} 亦为 null），因此本用例只断言「不抛异常 + 下标/名字自洽」；
     * 真实清单内容属真机（runClient）观测面，不在单测内伪造。</p>
     */
    @Test
    public void captureIsSafeOnHeadlessRegistryAndKeepsIndexInvariants() {
        BlockRegistrySnapshot snapshot = BlockRegistrySnapshot.capture();

        Assert.assertNotNull(snapshot);
        Assert.assertEquals(snapshot.modCategories().size(), snapshot.modCategoryCount());
        for (int i = 0; i < snapshot.size(); i++) {
            String registry = snapshot.registry(i);
            Assert.assertTrue(BlockRegistrySnapshot.isValidRegistry(registry));
            Assert.assertEquals("清单不得有重复 registry", i, snapshot.indexOf(registry));
            Assert.assertNotNull(snapshot.block(i));
            Assert.assertSame(snapshot.block(i), snapshot.blockFor(registry));
        }
        Assert.assertEquals(-1, snapshot.indexOf("test:absent"));
    }

    @Test
    public void invalidRegistryNamesAreRejectedByTheSharedPredicate() {
        Assert.assertTrue(BlockRegistrySnapshot.isValidRegistry("minecraft:stone"));
        Assert.assertTrue(BlockRegistrySnapshot.isValidRegistry("test:block_1"));
        Assert.assertFalse(BlockRegistrySnapshot.isValidRegistry(null));
        Assert.assertFalse(BlockRegistrySnapshot.isValidRegistry(""));
        Assert.assertFalse(BlockRegistrySnapshot.isValidRegistry("stone"));
        Assert.assertFalse(BlockRegistrySnapshot.isValidRegistry(":stone"));
        Assert.assertFalse(BlockRegistrySnapshot.isValidRegistry("minecraft:"));
        Assert.assertFalse(BlockRegistrySnapshot.isValidRegistry("tile.air"));
    }

    /** 计数 getSubBlocks 调用的测试方块（N1/N2 的观测面）。 */
    private static final class CountingBlock extends Block {
        private final int[] metadata;
        private int subBlocksCalls;

        private CountingBlock(int... metadata) {
            super(Material.rock);
            this.metadata = metadata;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void getSubBlocks(Item item, CreativeTabs tab, List list) {
            subBlocksCalls++;
            for (int value : metadata) {
                list.add(new ItemStack(item, 1, value));
            }
        }
    }
}
