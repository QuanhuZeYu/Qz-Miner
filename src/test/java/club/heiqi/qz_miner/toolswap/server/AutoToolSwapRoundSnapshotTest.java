package club.heiqi.qz_miner.toolswap.server;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** round 快照只暴露不可变协议事实。 */
public class AutoToolSwapRoundSnapshotTest {

    @Test
    public void snapshotFieldsAreFinalAndContainNoEndpointOrStackState() {
        for (Field field : AutoToolSwapRoundSnapshot.class.getDeclaredFields()) {
            Assert.assertTrue("snapshot field must be final: " + field.getName(),
                    Modifier.isFinal(field.getModifiers()));
            String typeName = field.getType().getName();
            Assert.assertFalse(typeName.contains("WeakReference"));
            Assert.assertFalse(typeName.contains("AutoToolSwapStackState"));
            Assert.assertFalse(typeName.contains("ItemStack"));
        }
    }

    @Test
    public void snapshotReportsOnlyRoundAndLedgerFacts() {
        AutoToolSwapRoundSnapshot snapshot = new AutoToolSwapRoundSnapshot(9L, 12L,
                AutoToolSwapRoundState.SWAPPED, 3L, 4L, true, true, 2, 18);

        Assert.assertEquals(9L, snapshot.clientNonce());
        Assert.assertEquals(12L, snapshot.serverRoundId());
        Assert.assertEquals(AutoToolSwapRoundState.SWAPPED, snapshot.roundState());
        Assert.assertEquals(3L, snapshot.nextActionSequence());
        Assert.assertEquals(4L, snapshot.phaseSequence());
        Assert.assertTrue(snapshot.keyDown());
        Assert.assertTrue(snapshot.hasLedger());
        Assert.assertEquals(2, snapshot.ledgerAnchorSlot());
        Assert.assertEquals(18, snapshot.ledgerCandidateSlot());
    }

    @Test(expected = IllegalArgumentException.class)
    public void absentLedgerCannotExposeSlots() {
        new AutoToolSwapRoundSnapshot(1L, 0L, AutoToolSwapRoundState.PENDING_KEY, 1L, 0L, false, false,
                0, AutoToolSwapRoundSnapshot.NO_LEDGER_SLOT);
    }
}
