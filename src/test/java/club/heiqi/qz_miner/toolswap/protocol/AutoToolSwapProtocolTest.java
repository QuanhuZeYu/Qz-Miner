package club.heiqi.qz_miner.toolswap.protocol;

import org.junit.Assert;
import org.junit.Test;

/** 严格 5.2 自动工具 control 协议。 */
public class AutoToolSwapProtocolTest {
    @Test
    public void onlyFreezeAndCloseKeepCodesThreeAndFour() {
        Assert.assertArrayEquals(new AutoToolSwapAction[] {
                AutoToolSwapAction.FREEZE, AutoToolSwapAction.CLOSE }, AutoToolSwapAction.values());
        Assert.assertEquals(3, AutoToolSwapAction.FREEZE.wireCode());
        Assert.assertEquals(4, AutoToolSwapAction.CLOSE.wireCode());
        Assert.assertEquals(AutoToolSwapAction.FREEZE, AutoToolSwapAction.fromWireCode(3));
        Assert.assertEquals(AutoToolSwapAction.CLOSE, AutoToolSwapAction.fromWireCode(4));
        assertUnknown(1);
        assertUnknown(2);
        assertUnknown(5);
        assertUnknown(6);
        assertUnknown(7);
    }

    @Test
    public void intentIdentityStillIncludesRoundSequenceAndFixedFramePlaceholders() {
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent first = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                7L, 3L, AutoToolSwapAction.FREEZE, 0, 0, empty, empty);
        AutoToolSwapIntent same = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                7L, 3L, AutoToolSwapAction.FREEZE, 0, 0, empty, empty);
        AutoToolSwapIntent close = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                7L, 3L, AutoToolSwapAction.CLOSE, 0, 0, empty, empty);
        Assert.assertEquals(first, same);
        Assert.assertNotEquals(first, close);
        Assert.assertEquals(3L, first.actionSequence());
    }

    private static void assertUnknown(int code) {
        try {
            AutoToolSwapAction.fromWireCode(code);
            Assert.fail("legacy action code must be rejected: " + code);
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("unknown"));
        }
    }
}
