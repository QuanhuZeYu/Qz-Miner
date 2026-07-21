package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;

/** BreakEvent 前的隧道方向冻结合同。 */
public class ChainPlannerTunnelDirectionTest {

    @Test
    public void hitFaceIsLatestWinsAndConsumedExactlyOnce() {
        ChainPlayerState state = state(TunnelDirectionSource.HIT_FACE);
        state.recordPendingTunnelHit(0, 1, 2, 3, 2);
        state.recordPendingTunnelHit(0, 1, 2, 3, 5);

        Assert.assertEquals(4, ChainPlanner.freezeBreakFace(
                state, ChainSubMode.AREA_TUNNEL, 0, 1, 2, 3, 1));
        Assert.assertFalse(state.hasPendingTunnelHit());
        Assert.assertEquals(1, ChainPlanner.freezeBreakFace(
                state, ChainSubMode.AREA_TUNNEL, 0, 1, 2, 3, 1));
    }

    @Test
    public void coordinateOrDimensionMismatchConsumesAndFallsBackSameLook() {
        ChainPlayerState state = state(TunnelDirectionSource.HIT_FACE);
        state.recordPendingTunnelHit(0, 1, 2, 3, 2);
        Assert.assertEquals(5, ChainPlanner.freezeBreakFace(
                state, ChainSubMode.AREA_TUNNEL, 0, 9, 2, 3, 5));
        Assert.assertFalse(state.hasPendingTunnelHit());

        state.recordPendingTunnelHit(1, 1, 2, 3, 2);
        Assert.assertEquals(0, ChainPlanner.freezeBreakFace(
                state, ChainSubMode.AREA_TUNNEL, 0, 1, 2, 3, 0));
        Assert.assertFalse(state.hasPendingTunnelHit());
    }

    @Test
    public void lookClearsPendingWhileNonTunnelPathIsIsolated() {
        ChainPlayerState state = state(TunnelDirectionSource.HIT_FACE);
        state.recordPendingTunnelHit(0, 1, 2, 3, 2);
        Assert.assertEquals(0, ChainPlanner.freezeBreakFace(
                state, ChainSubMode.AREA_SAME_BLOCK, 0, 1, 2, 3, 5));
        Assert.assertTrue(state.hasPendingTunnelHit());

        state.setAcceptedChainConfig(8, 64, TunnelDirectionSource.LOOK_DIRECTION);
        Assert.assertEquals(5, ChainPlanner.freezeBreakFace(
                state, ChainSubMode.AREA_TUNNEL, 0, 1, 2, 3, 5));
        Assert.assertFalse(state.hasPendingTunnelHit());
    }

    private static ChainPlayerState state(TunnelDirectionSource source) {
        ChainPlayerState state = new ChainPlayerState(UUID.randomUUID());
        state.setAcceptedChainConfig(8, 64, source);
        return state;
    }
}
