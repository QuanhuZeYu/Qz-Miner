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
        assertValidExtendedId(AutoToolSwapProtocol.MAX_BLOCK_ID);
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
    public void zeroNegativeAndAbove24BitBlockIdsFailClosed() {
        assertInvalidBlockId(0);
        assertInvalidBlockId(-1);
        assertInvalidBlockId(AutoToolSwapProtocol.MAX_BLOCK_ID + 1);
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
            // 合同断言
        }
    }
}
