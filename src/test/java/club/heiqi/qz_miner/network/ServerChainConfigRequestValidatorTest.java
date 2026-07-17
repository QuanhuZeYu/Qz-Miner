package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.network.ServerChainConfigRequestValidator.Result;

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
    }

    private static void assertRejected(int radius, int maxBlocks) {
        Result result = ServerChainConfigRequestValidator.validateAndClamp(radius, maxBlocks, 64, 4096);

        Assert.assertFalse(result.accepted);
        Assert.assertEquals(0, result.radius);
        Assert.assertEquals(0, result.maxBlocks);
    }
}
