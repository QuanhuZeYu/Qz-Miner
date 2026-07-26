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
        Assert.assertEquals(4096, ToolSwapTargetIdentity.present(4096, 0).blockId());
        Assert.assertEquals(32767, ToolSwapTargetIdentity.present(32767, 15).blockId());
        Assert.assertEquals(16777216,
                ToolSwapTargetIdentity.present(16777216, 0).blockId());
        Assert.assertEquals(Integer.MAX_VALUE,
                ToolSwapTargetIdentity.present(Integer.MAX_VALUE, Integer.MAX_VALUE).blockId());
        assertValidMetadata(0);
        assertValidMetadata(15);
        assertValidMetadata(16);
        assertValidMetadata(24902);
        assertValidMetadata(65535);
        assertValidMetadata(16777216);
        assertValidMetadata(Integer.MAX_VALUE);
    }

    @Test
    public void invalidPresentIdentityAndNullContextsFailClosed() {
        assertInvalid(0, 0);
        assertInvalid(-1, 0);
        assertInvalid(1, -1);
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
            Assert.assertTrue(expected.getMessage().contains("blockId=" + blockId));
            Assert.assertTrue(expected.getMessage().contains("metadata=" + metadata));
            Assert.assertTrue(expected.getMessage().contains("blockIdMax=" + Integer.MAX_VALUE));
            Assert.assertTrue(expected.getMessage().contains("metadataMax=" + Integer.MAX_VALUE));
        }
    }

    private static void assertValidMetadata(int metadata) {
        Assert.assertEquals(metadata, ToolSwapTargetIdentity.present(1, metadata).metadata());
    }
}
