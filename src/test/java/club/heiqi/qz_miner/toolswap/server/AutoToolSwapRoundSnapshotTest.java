package club.heiqi.qz_miner.toolswap.server;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** Round snapshot 只暴露 projection/control 状态。 */
public class AutoToolSwapRoundSnapshotTest {
    @Test
    public void carriesNonceRoundSequencePhaseAndPublicationOnly() {
        AutoToolSwapRoundSnapshot snapshot = new AutoToolSwapRoundSnapshot(
                1L, 2L, AutoToolSwapRoundState.FROZEN, 3L, 4L, true, true);
        Assert.assertEquals(1L, snapshot.clientNonce());
        Assert.assertEquals(2L, snapshot.serverRoundId());
        Assert.assertEquals(3L, snapshot.nextActionSequence());
        Assert.assertEquals(4L, snapshot.phaseSequence());
        Assert.assertTrue(snapshot.keyDown());
        Assert.assertTrue(snapshot.hasPendingResultPublication());
    }
}
