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

import club.heiqi.config.ui.editor.PickerQuery;

/** 清单级搜索索引：只做归一化 + rank，命中序确定、无内部上限、分类过滤一次到位。 */
public class BlockSearchIndexTest {
    @Test
    public void browseWithoutCategoryUsesIdentityOrderAndTextRanksDeterministically() {
        Fixture fixture = new Fixture(Arrays.asList(
                candidate("mod:stone_bricks", "Bricks"), candidate("minecraft:stone", "Stone"),
                candidate("mod:other", "Stone Plate"), candidate("mod:stone", "Other")));

        Assert.assertNull("浏览 lane 无分类过滤 = 清单恒等序（不建数组）",
                fixture.index.orderFor(PickerQuery.browse(0, null)));
        Assert.assertFalse("纯浏览不得构建名字索引", fixture.index.hasNames());

        int[] hits = fixture.index.orderFor(PickerQuery.text("stone", 0, null));
        Assert.assertEquals(Arrays.asList("minecraft:stone", "mod:other", "mod:stone", "mod:stone_bricks"),
                fixture.registries(hits));
        Assert.assertTrue("文本查询必须构建名字索引", fixture.index.hasNames());
    }

    /**
     * A 方案改写：旧实现 {@code Math.min(65, requestedLimit)} 硬夹 + truncated 探针被删除，
     * 命中数是真值；搜索窗口上限概念整链移除后，命中序里第 70 项（旧上限 65/64 之外）仍在索引返回
     * 结果内，调用方按窗口请求的任意 offset 寻址，不再从命中数派生 {@code truncated}。
     */
    @Test
    public void searchLaneHasNoInternalLimitAndKeepsEveryHitAddressable() {
        List<BlockCandidate> values = new ArrayList<BlockCandidate>();
        for (int i = 0; i < 70; i++) {
            values.add(candidate(String.format("mod:block%02d", Integer.valueOf(i)), "Block"));
        }
        Fixture fixture = new Fixture(values);

        int[] hits = fixture.index.orderFor(PickerQuery.text("mod:", 0, null));

        Assert.assertEquals("命中数必须为真值（旧实现硬夹 65）", 70, hits.length);
        Assert.assertEquals("第 70 项（旧上限之外）不得被夹掉", "mod:block69",
                fixture.registries(new int[] {hits[69]}).get(0));
    }

    @Test
    public void modIdEqualsRanksBetweenRegistryAndLocalized() {
        Fixture fixture = new Fixture(Arrays.asList(
                candidate("mod:stone", "Stone"), candidate("modding:stone", "Stone"),
                candidate("another:block", "mod"), candidate("galactic:mod_core", "Xx")));

        int[] hits = fixture.index.orderFor(PickerQuery.text("mod", 0, null));

        Assert.assertEquals(Arrays.asList("mod:stone", "modding:stone", "another:block", "galactic:mod_core"),
                fixture.registries(hits));
    }

    @Test
    public void modIdStartsWithFindsWholeNamespace() {
        Fixture fixture = new Fixture(Arrays.asList(
                candidate("GalaxySpace:venus_block", "Venus"), candidate("minecraft:stone", "Stone")));

        int[] hits = fixture.index.orderFor(PickerQuery.text("galax", 0, null));

        Assert.assertEquals(Arrays.asList("GalaxySpace:venus_block"), fixture.registries(hits));
    }

    @Test
    public void namespaceContainsRanksAbovePathContains() {
        Fixture fixture = new Fixture(Arrays.asList(
                candidate("minecraft:space_block", "Brick"), candidate("galaxy_space:venus_block", "Venus")));

        int[] hits = fixture.index.orderFor(PickerQuery.text("space", 0, null));

        Assert.assertEquals(Arrays.asList("galaxy_space:venus_block", "minecraft:space_block"),
                fixture.registries(hits));
    }

    @Test
    public void variantNameMatchesKeepRankFiveSemantics() {
        Fixture fixture = new Fixture(Arrays.asList(
                candidate("gt:ore", "Ore", "Copper Ore"), candidate("gt:other", "Other", "Tin Ore")));

        int[] hits = fixture.index.orderFor(PickerQuery.text("copper", 0, null));

        Assert.assertEquals(Arrays.asList("gt:ore"), fixture.registries(hits));
    }

    @Test
    public void categoryFilterRestrictsBrowseAndSearchInOnePlace() {
        Fixture fixture = new Fixture(Arrays.asList(
                candidate("minecraft:stone", "Stone"), candidate("minecraft:dirt", "Dirt"),
                candidate("gt:stone_ore", "Stone Ore")));

        Assert.assertEquals(Arrays.asList("minecraft:stone", "minecraft:dirt"),
                fixture.registries(fixture.index.orderFor(PickerQuery.browse(0, "minecraft"))));
        Assert.assertEquals(0, fixture.index.orderFor(PickerQuery.browse(0, "absent")).length);
        Assert.assertEquals(Arrays.asList("minecraft:stone"),
                fixture.registries(fixture.index.orderFor(PickerQuery.text("stone", 0, "minecraft"))));
    }

    private static BlockCandidate candidate(String registry, String localizedName, String... variantNames) {
        List<BlockVariant> variants = new ArrayList<BlockVariant>();
        for (int i = 0; i < variantNames.length; i++) {
            variants.add(new BlockVariant(i, variantNames[i], null));
        }
        return new BlockCandidate(registry, localizedName, variants, null);
    }

    /** 清单 + 分片（物化桩）+ 索引；不读真实注册表。 */
    private static final class Fixture {
        private final BlockSearchIndex index;
        private final List<String> registries = new ArrayList<String>();

        private Fixture(List<BlockCandidate> candidates) {
            final Map<String, BlockCandidate> lookup = new LinkedHashMap<String, BlockCandidate>();
            Map<String, Block> blocks = new LinkedHashMap<String, Block>();
            for (BlockCandidate candidate : candidates) {
                lookup.put(candidate.registry(), candidate);
                blocks.put(candidate.registry(), new TestBlock());
                registries.add(candidate.registry());
            }
            BlockVariantShardCache shards = new BlockVariantShardCache(64,
                    new BlockVariantShardCache.Materializer() {
                        @Override
                        public BlockCandidate materialize(String registry, Block block) {
                            BlockCandidate candidate = lookup.get(registry);
                            return candidate == null ? BlockVariantMaterializer.placeholder(registry) : candidate;
                        }
                    });
            index = new BlockSearchIndex(BlockRegistrySnapshot.of(blocks), shards);
        }

        private List<String> registries(int[] order) {
            List<String> result = new ArrayList<String>();
            for (int value : order) {
                result.add(registries.get(value));
            }
            return result;
        }
    }

    /** Block(Material) 是 protected 构造。 */
    private static final class TestBlock extends Block {
        private TestBlock() {
            super(Material.rock);
        }
    }
}
