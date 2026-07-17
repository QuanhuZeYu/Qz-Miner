package club.heiqi.qz_miner.client.toolswap;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;

/** 槽位快照的角色与完整内容指纹合同。 */
public class SlotSnapshotTest {

    @Test
    public void stringAndExplicitFingerprintConstructorsAreEquivalent() {
        AutoToolSwapContentFingerprint fingerprint =
                AutoToolSwapContentFingerprint.fromContent("mod:pick@0", "damage=4");
        SlotSnapshot fromStrings = new SlotSnapshot(3, "mod:pick@0", "damage=4");
        SlotSnapshot fromFingerprint = new SlotSnapshot(3, "mod:pick@0", fingerprint);

        Assert.assertTrue(fromStrings.contentFingerprint().sameContent(fingerprint));
        Assert.assertTrue(fromStrings.sameContent(fromFingerprint));
        Assert.assertTrue(fromStrings.sameRole(fromFingerprint));
    }

    @Test
    public void dynamicChangeKeepsRoleButChangesStrictContent() {
        SlotSnapshot original = new SlotSnapshot(3, "mod:pick@0", "damage=4");
        SlotSnapshot changed = new SlotSnapshot(3, "mod:pick@0", "damage=5");

        Assert.assertTrue(original.sameRole(changed));
        Assert.assertFalse(original.sameContent(changed));
    }

    @Test
    public void roleChangeChangesRoleAndStrictContent() {
        SlotSnapshot original = new SlotSnapshot(3, "mod:pick@0", "damage=4");
        SlotSnapshot changed = new SlotSnapshot(3, "mod:axe@0", "damage=4");

        Assert.assertFalse(original.sameRole(changed));
        Assert.assertFalse(original.sameContent(changed));
    }

    @Test
    public void emptySnapshotUsesCanonicalEmptyFingerprint() {
        SlotSnapshot empty = new SlotSnapshot(3, SlotSnapshot.EMPTY_ROLE_KEY, "");

        Assert.assertTrue(empty.isEmpty());
        Assert.assertTrue(empty.contentFingerprint().sameContent(
                AutoToolSwapContentFingerprint.canonicalEmpty()));
    }
}
