package club.heiqi.qz_miner.client.picker;

import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

/** BlockCandidate 双维度字段与旧构造器兼容性测试。 */
public class BlockCandidateTest {
    @Test
    public void legacyConstructorDerivesModIdAndLeavesCreativeTabNull() {
        BlockCandidate candidate = new BlockCandidate("minecraft:stone", "Stone",
                Collections.<BlockVariant>emptyList(), null);
        Assert.assertEquals("minecraft", candidate.modId());
        Assert.assertNull(candidate.creativeTab());
        Assert.assertEquals("minecraft:stone", candidate.registry());
        Assert.assertEquals("Stone", candidate.localizedName());
    }

    @Test
    public void fullConstructorKeepsExplicitDimensions() {
        BlockCandidate candidate = new BlockCandidate("a:b", "mod_x", "红石", "Redstone",
                Collections.<BlockVariant>emptyList(), null);
        Assert.assertEquals("mod_x", candidate.modId());
        Assert.assertEquals("红石", candidate.creativeTab());
        Assert.assertEquals("Redstone", candidate.localizedName());
    }

    @Test
    public void modIdOfHandlesEdgeRegistries() {
        Assert.assertEquals("a", BlockCandidate.modIdOf("a:b"));
        Assert.assertNull(BlockCandidate.modIdOf("nospaced"));
        Assert.assertNull(BlockCandidate.modIdOf(":b"));
        Assert.assertEquals("a", BlockCandidate.modIdOf("a:"));
        Assert.assertNull(BlockCandidate.modIdOf(null));
        Assert.assertNull(BlockCandidate.modIdOf(""));
    }
}
