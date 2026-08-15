package club.heiqi.qz_miner.client.picker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** 方块搜索排序、预算和不可变性测试。 */
public class BlockSearchIndexTest {
    @Test
    public void emptyQueryIsEmptyAndRankingIsDeterministic() {
        BlockSearchIndex index = new BlockSearchIndex(Arrays.asList(
                candidate("mod:stone_bricks", "Bricks"), candidate("minecraft:stone", "Stone"),
                candidate("mod:other", "Stone Plate"), candidate("mod:stone", "Other")));
        Assert.assertTrue(index.search("", 64).candidates().isEmpty());
        List<BlockCandidate> result = index.search("stone", 64).candidates();
        Assert.assertEquals(Arrays.asList("minecraft:stone", "mod:other", "mod:stone", "mod:stone_bricks"),
                registries(result));
    }

    @Test
    public void resultIsLimitedTo64AndReportsTruncation() {
        List<BlockCandidate> values = new ArrayList<BlockCandidate>();
        for (int i = 0; i < 70; i++) values.add(candidate(String.format("mod:block%02d", i), "Block"));
        BlockSearchIndex.Result result = new BlockSearchIndex(values).search("mod:", 64);
        Assert.assertEquals(64, result.candidates().size());
        Assert.assertTrue(result.truncated());
    }

    @Test
    public void modIdEqualsRanksBetweenRegistryAndLocalized() {
        BlockSearchIndex index = new BlockSearchIndex(Arrays.asList(
                candidate("mod:stone", "Stone"), candidate("modding:stone", "Stone"),
                candidate("another:block", "mod"), candidate("galactic:mod_core", "Xx")));
        List<BlockCandidate> result = index.search("mod", 64).candidates();
        Assert.assertEquals(Arrays.asList("mod:stone", "modding:stone", "another:block", "galactic:mod_core"),
                registries(result));
    }

    @Test
    public void modIdStartsWithFindsWholeNamespace() {
        BlockSearchIndex index = new BlockSearchIndex(Arrays.asList(
                candidate("GalaxySpace:venus_block", "Venus"), candidate("minecraft:stone", "Stone")));
        List<BlockCandidate> result = index.search("galax", 64).candidates();
        Assert.assertEquals(Arrays.asList("GalaxySpace:venus_block"), registries(result));
    }

    @Test
    public void namespaceContainsRanksAbovePathContains() {
        BlockSearchIndex index = new BlockSearchIndex(Arrays.asList(
                candidate("minecraft:space_block", "Brick"), candidate("galaxy_space:venus_block", "Venus")));
        List<BlockCandidate> result = index.search("space", 64).candidates();
        Assert.assertEquals(Arrays.asList("galaxy_space:venus_block", "minecraft:space_block"), registries(result));
    }

    @Test
    public void existingRankingOrderIsUnchangedByModIdDimension() {
        BlockSearchIndex index = new BlockSearchIndex(Arrays.asList(
                candidate("mod:stone_bricks", "Bricks"), candidate("minecraft:stone", "Stone"),
                candidate("mod:other", "Stone Plate"), candidate("mod:stone", "Other")));
        List<BlockCandidate> result = index.search("stone", 64).candidates();
        Assert.assertEquals(Arrays.asList("minecraft:stone", "mod:other", "mod:stone", "mod:stone_bricks"),
                registries(result));
    }

    private static BlockCandidate candidate(String registry, String name) {
        return new BlockCandidate(registry, name, Collections.<BlockVariant>emptyList(), null);
    }

    private static List<String> registries(List<BlockCandidate> values) {
        List<String> result = new ArrayList<String>();
        for (BlockCandidate value : values) result.add(value.registry());
        return result;
    }
}
