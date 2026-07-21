package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.network.ServerChainConfigRequestValidator.Result;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/** C2S 请求必须整包通过服务端最终校验后才可写玩家状态。 */
public class ServerChainConfigRequestValidatorTest {

    @Test
    public void zeroValueRejectsWholeRequest() {
        assertRejected(0, 100);
        assertRejected(10, 0);
    }

    @Test
    public void negativeValueRejectsWholeRequest() {
        assertRejected(-1, 100);
        assertRejected(10, -1);
    }

    @Test
    public void oneLegalAndOneIllegalStillRejectsWholeRequest() {
        assertRejected(12, -100);
    }

    @Test
    public void integerMaxValueIsAcceptedButCannotExceedServerLimits() {
        Result result = ServerChainConfigRequestValidator.validateAndClamp(
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                64,
                4096);

        Assert.assertTrue(result.accepted);
        Assert.assertEquals(64, result.radius);
        Assert.assertEquals(4096, result.maxBlocks);
    }

    @Test
    public void legalValuesArePreservedWithinServerLimits() {
        Result result = ServerChainConfigRequestValidator.validateAndClamp(12, 300, 64, 4096);

        Assert.assertTrue(result.accepted);
        Assert.assertEquals(12, result.radius);
        Assert.assertEquals(300, result.maxBlocks);
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION, result.tunnelDirectionSource);
    }

    @Test
    public void extendedHitFaceIsAcceptedWhileUnknownVersionCodeAndRawAreRejected() {
        Result accepted = ServerChainConfigRequestValidator.validateAndClamp(
                12, 300, 64, 4096, PacketChainConfigRequest.PROTOCOL_VERSION,
                TunnelDirectionSource.HIT_FACE.wireCode(), true);
        Assert.assertTrue(accepted.accepted);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, accepted.tunnelDirectionSource);

        Assert.assertFalse(ServerChainConfigRequestValidator.validateAndClamp(
                12, 300, 64, 4096, 99, 1, true).accepted);
        Assert.assertFalse(ServerChainConfigRequestValidator.validateAndClamp(
                12, 300, 64, 4096, PacketChainConfigRequest.PROTOCOL_VERSION, 99, true).accepted);
        Assert.assertFalse(ServerChainConfigRequestValidator.validateAndClamp(
                12, 300, 64, 4096, PacketChainConfigRequest.PROTOCOL_VERSION, 1, false).accepted);
    }

    private static void assertRejected(int radius, int maxBlocks) {
        Result result = ServerChainConfigRequestValidator.validateAndClamp(radius, maxBlocks, 64, 4096);

        Assert.assertFalse(result.accepted);
        Assert.assertEquals(0, result.radius);
        Assert.assertEquals(0, result.maxBlocks);
    }
}
