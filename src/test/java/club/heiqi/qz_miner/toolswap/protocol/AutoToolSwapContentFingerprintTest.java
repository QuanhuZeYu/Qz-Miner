package club.heiqi.qz_miner.toolswap.protocol;

import org.junit.Assert;
import org.junit.Test;

/** 严格内容指纹字段边界的纯 JVM 合同。 */
public class AutoToolSwapContentFingerprintTest {

    /** roleKey 的 NUL 必须被拒绝，动态内容中的 NUL 仍属于合法且可区分的内容。 */
    @Test
    public void roleKeyNulIsRejectedWhileDynamicNulRemainsDistinct() {
        AutoToolSwapContentFingerprint dynamicNul = AutoToolSwapContentFingerprint.fromContent("a", "b\0c");
        Assert.assertEquals(dynamicNul, AutoToolSwapContentFingerprint.fromContent("a", "b\0c"));
        Assert.assertFalse(dynamicNul.sameContent(AutoToolSwapContentFingerprint.fromContent("a", "b\0d")));

        try {
            AutoToolSwapContentFingerprint.fromContent("a\0b", "c");
            Assert.fail("roleKey containing NUL must be rejected");
        } catch (IllegalArgumentException expected) {
            // 合同断言
        }
    }
}
