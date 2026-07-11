package club.heiqi.qz_miner.objectgroup;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/** 模式扩展映射、选组区间和冻结语义回归。 */
public class ModeExtensionSnapshotTest {
    @Test
    public void exactlySevenSubModesHaveStableMappings() {
        Assert.assertEquals(1L, ObjectGroupMode.maskFor(ChainSubMode.CHAIN_BASE));
        Assert.assertEquals(2L, ObjectGroupMode.maskFor(ChainSubMode.CHAIN_ORE));
        Assert.assertEquals(4L, ObjectGroupMode.maskFor(ChainSubMode.CHAIN_LOGGING));
        Assert.assertEquals(8L, ObjectGroupMode.maskFor(ChainSubMode.AREA_SAME_BLOCK));
        Assert.assertEquals(16L, ObjectGroupMode.maskFor(ChainSubMode.AREA_ORE));
        Assert.assertEquals(32L, ObjectGroupMode.maskFor(ChainSubMode.INTERACT_BASE));
        Assert.assertEquals(64L, ObjectGroupMode.maskFor(ChainSubMode.INTERACT_CROP));
        Assert.assertEquals(0L, ObjectGroupMode.maskFor(ChainSubMode.AREA_TUNNEL));
    }

    @Test
    public void resolvesSeedAndFreezesAllMembersAndMetadataIntervals() {
        ObjectGroup group = new ObjectGroup("mixed", Collections.singletonList(ObjectGroupMode.CHAIN_ORE), 2L,
                Arrays.asList(ObjectGroupSelector.set("minecraft:stone", Arrays.asList(1, 4, 8)),
                        ObjectGroupSelector.wildcard("minecraft:log")));
        ObjectGroupRuleSet rules = new ObjectGroupRuleSet(Collections.singletonList(group));
        ModeExtensionSnapshot frozen = rules.resolve(2L, "minecraft:stone", 4);
        Assert.assertTrue(frozen.matches("minecraft:stone", 1));
        Assert.assertTrue(frozen.matches("minecraft:stone", 8));
        Assert.assertFalse(frozen.matches("minecraft:stone", 2));
        Assert.assertTrue(frozen.matches("minecraft:log", 15));

        ObjectGroup replacement = new ObjectGroup("new", Collections.singletonList(ObjectGroupMode.CHAIN_ORE), 2L,
                Collections.singletonList(ObjectGroupSelector.single("minecraft:dirt", 0)));
        rules = new ObjectGroupRuleSet(Collections.singletonList(replacement));
        Assert.assertTrue(rules.resolve(2L, "minecraft:dirt", 0).matches("minecraft:dirt", 0));
        Assert.assertTrue(frozen.matches("minecraft:log", 7));
    }

    @Test
    public void noModeOrSeedMatchReturnsEmptyExtension() {
        ObjectGroup group = new ObjectGroup("logs", Collections.singletonList(ObjectGroupMode.CHAIN_BASE), 1L,
                Collections.singletonList(ObjectGroupSelector.wildcard("minecraft:log")));
        ObjectGroupRuleSet rules = new ObjectGroupRuleSet(Collections.singletonList(group));
        Assert.assertTrue(rules.resolve(2L, "minecraft:log", 0).isEmpty());
        Assert.assertTrue(rules.resolve(1L, "minecraft:stone", 0).isEmpty());
    }
}
