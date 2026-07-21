package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/** pending face 与 accepted source 生命周期回归。 */
public class ChainPlayerTunnelDirectionLifecycleTest {

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
    }

    @Test
    public void releaseModeSubModeAndRuntimeCleanupClearPending() {
        ChainPlayerState state = new ChainPlayerState(UUID.randomUUID());
        state.setChainKeyPressed(true);
        state.recordPendingTunnelHit(0, 1, 2, 3, 1);
        state.setChainKeyPressed(false);
        Assert.assertFalse(state.hasPendingTunnelHit());

        state.recordPendingTunnelHit(0, 1, 2, 3, 1);
        state.setSelectedMode(ChainMode.AREA);
        Assert.assertFalse(state.hasPendingTunnelHit());

        state.recordPendingTunnelHit(0, 1, 2, 3, 1);
        state.setSelectedSubMode(ChainSubMode.AREA_TUNNEL);
        Assert.assertFalse(state.hasPendingTunnelHit());

        state.recordPendingTunnelHit(0, 1, 2, 3, 1);
        state.clearRuntimeState("test");
        Assert.assertFalse(state.hasPendingTunnelHit());
    }

    @Test
    public void acceptedSourceDefaultsAndResetsToLook() {
        ChainPlayerState state = new ChainPlayerState(UUID.randomUUID());
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION,
                state.getAcceptedTunnelDirectionSource());
        state.setAcceptedChainConfig(8, 64, TunnelDirectionSource.HIT_FACE);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, state.getAcceptedTunnelDirectionSource());
        state.resetAcceptedTunnelDirectionSource();
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION,
                state.getAcceptedTunnelDirectionSource());
    }

    @Test
    public void clientRequestedConfigCannotOptimisticallyChangeAcceptedSource() {
        ChainClientState state = new ChainClientState();
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION,
                state.getAcceptedTunnelDirectionSource());
        state.setRequestedChainRadius(20);
        state.setRequestedChainMaxBlocks(200);
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION,
                state.getAcceptedTunnelDirectionSource());
        state.setAcceptedTunnelDirectionSource(TunnelDirectionSource.HIT_FACE);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE,
                state.getAcceptedTunnelDirectionSource());
    }
}
