package club.heiqi.qz_miner.toolswap.protocol;

import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

/** 服务端工具换位协议模型的纯 JVM 合同。 */
public class AutoToolSwapProtocolTest {

    @Test
    public void wireCodesAreUniqueAndUnknownCodesFailClosed() {
        Assert.assertEquals(3, AutoToolSwapProtocol.PROTOCOL_VERSION);
        Assert.assertEquals(0xFFFFFF, AutoToolSwapProtocol.MAX_BLOCK_ID);
        assertUnique(AutoToolSwapAction.values());
        assertUnique(AutoToolSwapRoundState.values());
        assertUnique(AutoToolSwapResultCode.values());

        Assert.assertEquals(AutoToolSwapAction.SWAP, AutoToolSwapAction.fromWireCode(1));
        Assert.assertEquals(AutoToolSwapAction.ABANDON, AutoToolSwapAction.fromWireCode(5));
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER, AutoToolSwapAction.fromWireCode(6));
        Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER, AutoToolSwapAction.fromWireCode(7));
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, AutoToolSwapRoundState.fromWireCode(2));
        Assert.assertEquals(AutoToolSwapResultCode.SYNC_FAILED, AutoToolSwapResultCode.fromWireCode(5));
        assertUnknownActionRejected();
        assertUnknownStateRejected();
        assertUnknownResultRejected();
    }

    @Test
    public void fingerprintUsesFullSha256ForRoleNulDynamicInput() {
        AutoToolSwapContentFingerprint fingerprint = AutoToolSwapContentFingerprint.fromContent("tool", "state");
        Assert.assertEquals(0x3773057C585DE5C2L, fingerprint.firstLong());
        Assert.assertEquals(0x8EF5F7CB8B090469L, fingerprint.secondLong());
        Assert.assertEquals(0x17428F1462691574L, fingerprint.thirdLong());
        Assert.assertEquals(0xC4644A35A66F73AEL, fingerprint.fourthLong());
        Assert.assertFalse(fingerprint.sameContent(AutoToolSwapContentFingerprint.fromContent("tool2", "state")));
        Assert.assertFalse(fingerprint.sameContent(AutoToolSwapContentFingerprint.fromContent("tool", "state2")));
        Assert.assertEquals(fingerprint, AutoToolSwapContentFingerprint.fromWire(fingerprint.firstLong(),
                fingerprint.secondLong(), fingerprint.thirdLong(), fingerprint.fourthLong()));
        Assert.assertEquals(AutoToolSwapContentFingerprint.canonicalEmpty(),
                AutoToolSwapContentFingerprint.fromContent(AutoToolSwapProtocol.CANONICAL_EMPTY_ROLE_KEY, ""));
    }

    @Test
    public void stackStateSeparatesRoleContentEmptyAndDurability() {
        AutoToolSwapContentFingerprint first = AutoToolSwapContentFingerprint.fromContent("mod:tool", "damage=1");
        AutoToolSwapContentFingerprint second = AutoToolSwapContentFingerprint.fromContent("mod:tool", "damage=2");
        AutoToolSwapStackState original = AutoToolSwapStackState.occupied("mod:tool", first, 99);
        AutoToolSwapStackState used = AutoToolSwapStackState.occupied("mod:tool", second, 98);
        AutoToolSwapStackState otherSubtype = AutoToolSwapStackState.occupied("mod:tool@1", second, 98);

        Assert.assertTrue(original.sameRole(used));
        Assert.assertFalse(original.sameContent(used));
        Assert.assertFalse(original.sameRole(otherSubtype));
        Assert.assertFalse(AutoToolSwapStackState.empty().sameRole(original));
        Assert.assertTrue(AutoToolSwapStackState.empty().sameRole(AutoToolSwapStackState.empty()));
        Assert.assertTrue(AutoToolSwapStackState.empty().isEmpty());
        try {
            AutoToolSwapStackState.occupied(AutoToolSwapProtocol.CANONICAL_EMPTY_ROLE_KEY,
                    AutoToolSwapContentFingerprint.canonicalEmpty(), 0);
            Assert.fail("empty role must be canonical");
        } catch (IllegalArgumentException expected) {
            // 合同断言
        }
    }

    @Test
    public void intentValueEqualityCoversEveryFieldAndResultsAreValueObjects() {
        AutoToolSwapIntent baseline = intent(1, 7L, 3L, AutoToolSwapAction.SWAP, 0, 9, "anchor", "candidate");
        Assert.assertEquals(baseline, intent(1, 7L, 3L, AutoToolSwapAction.SWAP, 0, 9, "anchor", "candidate"));
        Assert.assertNotEquals(baseline, intent(2, 7L, 3L, AutoToolSwapAction.SWAP, 0, 9, "anchor", "candidate"));
        Assert.assertNotEquals(baseline, intent(1, 8L, 3L, AutoToolSwapAction.SWAP, 0, 9, "anchor", "candidate"));
        Assert.assertNotEquals(baseline, intent(1, 7L, 4L, AutoToolSwapAction.SWAP, 0, 9, "anchor", "candidate"));
        Assert.assertNotEquals(baseline, intent(1, 7L, 3L, AutoToolSwapAction.RESTORE, 0, 9, "anchor", "candidate"));
        Assert.assertNotEquals(baseline, intent(1, 7L, 3L, AutoToolSwapAction.SWAP, 1, 9, "anchor", "candidate"));
        Assert.assertNotEquals(baseline, intent(1, 7L, 3L, AutoToolSwapAction.SWAP, 0, 10, "anchor", "candidate"));
        Assert.assertNotEquals(baseline, intent(1, 7L, 3L, AutoToolSwapAction.SWAP, 0, 9, "other", "candidate"));
        Assert.assertNotEquals(baseline, intent(1, 7L, 3L, AutoToolSwapAction.SWAP, 0, 9, "anchor", "other"));

        AutoToolSwapRoundResult result = new AutoToolSwapRoundResult(7L, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 4L, 120L);
        AutoToolSwapActionResult actionResult = new AutoToolSwapActionResult(baseline, result);
        Assert.assertEquals(actionResult, new AutoToolSwapActionResult(baseline,
                new AutoToolSwapRoundResult(7L, AutoToolSwapResultCode.APPLIED,
                        AutoToolSwapRoundState.SWAPPED, 4L, 120L)));
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, actionResult.roundResult().outcome());
        Assert.assertEquals(AutoToolSwapRoundState.SWAPPED, actionResult.roundResult().roundState());
        Assert.assertEquals(4L, actionResult.roundResult().nextActionSequence());
        Assert.assertEquals(120L, actionResult.roundResult().serverTick());
    }

    private static AutoToolSwapIntent intent(int version, long roundId, long sequence, AutoToolSwapAction action,
            int anchorSlot, int candidateSlot, String anchor, String candidate) {
        return new AutoToolSwapIntent(version, roundId, sequence, action, anchorSlot, candidateSlot,
                AutoToolSwapContentFingerprint.fromContent("mod:anchor", anchor),
                AutoToolSwapContentFingerprint.fromContent("mod:candidate", candidate));
    }

    private static void assertUnique(AutoToolSwapAction[] values) {
        Set<Integer> codes = new HashSet<Integer>();
        for (AutoToolSwapAction value : values) {
            Assert.assertTrue(codes.add(Integer.valueOf(value.wireCode())));
        }
    }

    private static void assertUnique(AutoToolSwapRoundState[] values) {
        Set<Integer> codes = new HashSet<Integer>();
        for (AutoToolSwapRoundState value : values) {
            Assert.assertTrue(codes.add(Integer.valueOf(value.wireCode())));
        }
    }

    private static void assertUnique(AutoToolSwapResultCode[] values) {
        Set<Integer> codes = new HashSet<Integer>();
        for (AutoToolSwapResultCode value : values) {
            Assert.assertTrue(codes.add(Integer.valueOf(value.wireCode())));
        }
    }

    private static void assertUnknownActionRejected() {
        try {
            AutoToolSwapAction.fromWireCode(99);
            Assert.fail("unknown action must fail closed");
        } catch (IllegalArgumentException expected) {
            // 合同断言
        }
    }

    private static void assertUnknownStateRejected() {
        try {
            AutoToolSwapRoundState.fromWireCode(99);
            Assert.fail("unknown state must fail closed");
        } catch (IllegalArgumentException expected) {
            // 合同断言
        }
    }

    private static void assertUnknownResultRejected() {
        try {
            AutoToolSwapResultCode.fromWireCode(99);
            Assert.fail("unknown result must fail closed");
        } catch (IllegalArgumentException expected) {
            // 合同断言
        }
    }
}
