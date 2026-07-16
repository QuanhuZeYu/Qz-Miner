package club.heiqi.qz_miner.client.toolswap;

import org.junit.Assert;
import org.junit.Test;

/** 准星目标身份的值语义合同。 */
public class ToolSwapTargetIdentityTest {

    @Test
    public void presentIdentityUsesOnlyBlockAndMetadataValue() {
        ToolSwapTargetIdentity first = ToolSwapTargetIdentity.present(42, 3);
        ToolSwapTargetIdentity sameAtAnotherPosition = ToolSwapTargetIdentity.present(42, 3);

        Assert.assertEquals(first, sameAtAnotherPosition);
        Assert.assertEquals(first.hashCode(), sameAtAnotherPosition.hashCode());
        Assert.assertNotEquals(first, ToolSwapTargetIdentity.present(42, 4));
        Assert.assertNotEquals(first, ToolSwapTargetIdentity.present(43, 3));
        Assert.assertEquals("PRESENT(42,3)", first.toString());
        Assert.assertEquals("ABSENT", ToolSwapTargetIdentity.ABSENT.toString());
    }

    @Test
    public void invalidPresentIdentityAndNullContextsFailClosed() {
        assertInvalid(0, 0);
        assertInvalid(4096, 0);
        assertInvalid(1, -1);
        assertInvalid(1, 16);
        try {
            new ToolSwapLightContext(0L, true, false, false, true, 0, null);
            Assert.fail("null target must fail");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("targetIdentity"));
        }
    }

    private static void assertInvalid(int blockId, int metadata) {
        try {
            ToolSwapTargetIdentity.present(blockId, metadata);
            Assert.fail("invalid target must fail");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("block id/metadata"));
        }
    }
}
