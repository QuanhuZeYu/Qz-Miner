package club.heiqi.qz_miner.chain.mode;

import org.junit.Assert;
import org.junit.Test;

/** 既有 wire ordinal 0-11 与尾部追加范围交互子模式回归。 */
public class ChainSubModeOrdinalRegressionTest {
    @Test
    public void everyLegacyOrdinalRemainsExplicitlyAssigned() {
        ChainSubMode[] values = ChainSubMode.values();
        Assert.assertEquals(14, values.length);
        assertOrdinal(ChainSubMode.CHAIN_BASE, 0, values);
        assertOrdinal(ChainSubMode.CHAIN_ORE, 1, values);
        assertOrdinal(ChainSubMode.CHAIN_LOGGING, 2, values);
        assertOrdinal(ChainSubMode.AREA_SAME_BLOCK, 3, values);
        assertOrdinal(ChainSubMode.AREA_HARVESTABLE_ALL, 4, values);
        assertOrdinal(ChainSubMode.AREA_ORE, 5, values);
        assertOrdinal(ChainSubMode.AREA_TUNNEL, 6, values);
        assertOrdinal(ChainSubMode.AREA_SECTION_CLEAR, 7, values);
        assertOrdinal(ChainSubMode.INTERACT_BASE, 8, values);
        assertOrdinal(ChainSubMode.INTERACT_CROP, 9, values);
        assertOrdinal(ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER, 10, values);
        assertOrdinal(ChainSubMode.SPECIAL_GT_CABLE_REPLACE, 11, values);
    }

    @Test
    public void newInteractionModesAreAppendedAtTwelveAndThirteen() {
        ChainSubMode[] values = ChainSubMode.values();
        assertOrdinal(ChainSubMode.INTERACT_LIQUID_SOURCE, 12, values);
        assertOrdinal(ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP, 13, values);
    }

    private static void assertOrdinal(ChainSubMode mode, int ordinal, ChainSubMode[] values) {
        Assert.assertEquals(mode.name(), ordinal, mode.ordinal());
        Assert.assertSame(mode.name(), mode, values[ordinal]);
    }
}
