package club.heiqi.qz_miner.toolswap.protocol;

import org.junit.Assert;
import org.junit.Test;

/** 接替目标请求的范围与同门身份合同。 */
public class AutoToolSwapTakeoverRequestTest {

    @Test
    public void validRequestCarriesAllIdentityAndTargetFields() {
        AutoToolSwapTakeoverRequest request = new AutoToolSwapTakeoverRequest(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                -10, 64, 20, 42, 7, 100L, 109L);
        Assert.assertEquals(9L, request.serverRoundId());
        Assert.assertEquals(3L, request.actionSequence());
        Assert.assertEquals(42, request.targetBlockId());
        Assert.assertEquals(7, request.targetBlockMetadata());
        Assert.assertTrue(request.sameGate(new AutoToolSwapTakeoverRequest(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                -10, 64, 20, 42, 7, 101L, 110L)));
        Assert.assertTrue(request.matchesTarget(9L, 4, -10, 64, 20, 42, 7));
        Assert.assertFalse(request.matchesTarget(10L, 4, -10, 64, 20, 42, 7));
        Assert.assertFalse(request.matchesTarget(9L, 5, -10, 64, 20, 42, 7));
        Assert.assertFalse(request.matchesTarget(9L, 4, -9, 64, 20, 42, 7));
        Assert.assertFalse(request.matchesTarget(9L, 4, -10, 65, 20, 42, 7));
        Assert.assertFalse(request.matchesTarget(9L, 4, -10, 64, 21, 42, 7));
        Assert.assertFalse(request.matchesTarget(9L, 4, -10, 64, 20, 43, 7));
        Assert.assertFalse(request.matchesTarget(9L, 4, -10, 64, 20, 42, 8));

        assertValidExtendedId(1);
        assertValidExtendedId(4096);
        assertValidExtendedId(32767);
        assertValidExtendedId(16777216);
        assertValidExtendedId(AutoToolSwapProtocol.MAX_BLOCK_ID);
        assertValidMetadata(0);
        assertValidMetadata(15);
        assertValidMetadata(16);
        assertValidMetadata(24902);
        assertValidMetadata(65535);
        assertValidMetadata(16777216);
        assertValidMetadata(Integer.MAX_VALUE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void protocolV2FailsClosed() {
        new AutoToolSwapTakeoverRequest(2, 9L, 3L, 4,
                0, 64, 0, 1, 0, 1L, 2L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void deadlineMustFollowServerTick() {
        new AutoToolSwapTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                0, 64, 0, 1, 0, 2L, 2L);
    }

    @Test
    public void zeroAndNegativeBlockIdsFailClosed() {
        assertInvalidBlockId(0);
        assertInvalidBlockId(-1);
    }

    @Test
    public void negativeMetadataFailsClosed() {
        assertInvalidMetadata(-1);
    }

    private static void assertValidExtendedId(int blockId) {
        AutoToolSwapTakeoverRequest request = new AutoToolSwapTakeoverRequest(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                0, 64, 0, blockId, 0, 1L, 2L);
        Assert.assertEquals(blockId, request.targetBlockId());
    }

    private static void assertInvalidBlockId(int blockId) {
        try {
            new AutoToolSwapTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                    0, 64, 0, blockId, 0, 1L, 2L);
            Assert.fail("invalid block id must fail");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("blockIdMax=" + Integer.MAX_VALUE));
            Assert.assertTrue(expected.getMessage().contains("metadataMax=" + Integer.MAX_VALUE));
        }
    }

    private static void assertValidMetadata(int metadata) {
        AutoToolSwapTakeoverRequest request = new AutoToolSwapTakeoverRequest(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                0, 64, 0, 1, metadata, 1L, 2L);
        Assert.assertEquals(metadata, request.targetBlockMetadata());
    }

    private static void assertInvalidMetadata(int metadata) {
        try {
            new AutoToolSwapTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                    0, 64, 0, 1, metadata, 1L, 2L);
            Assert.fail("invalid metadata must fail");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("blockIdMax=" + Integer.MAX_VALUE));
            Assert.assertTrue(expected.getMessage().contains("metadataMax=" + Integer.MAX_VALUE));
        }
    }
}
