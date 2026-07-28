package club.heiqi.qz_miner.toolswap.server;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 严格 5.2 round service 只处理 FREEZE/CLOSE projection。 */
public class AutoToolSwapRoundServiceTest {
    @Test
    public void nonceActivationAndRoundIdsRemainEndpointBoundAndMonotonic() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        Assert.assertEquals(AutoToolSwapRoundState.PENDING_KEY,
                service.beginRound(player, endpoint, 11L, 1L).roundState());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                service.beginRound(player, new Object(), 11L, 2L).outcome());
        long first = service.activatePendingRound(player, endpoint, 3L).serverRoundId();
        publish(service, player, endpoint, control(first, 1L, AutoToolSwapAction.CLOSE), 4L);
        service.beginRound(player, endpoint, 12L, 5L);
        Assert.assertEquals(first + 1L, service.activatePendingRound(player, endpoint, 6L).serverRoundId());
    }

    @Test
    public void freezeAndCloseAreInventoryFreeAndExactRetryDoesNotAdvanceTwice() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        AutoToolSwapIntent freeze = control(roundId, 1L, AutoToolSwapAction.FREEZE);
        AutoToolSwapRoundResult pending = service.handleIntent(player, endpoint, freeze, null, 2L);
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, pending.roundState());
        Assert.assertEquals(pending, service.handleIntent(player, endpoint, freeze, null, 3L));
        Assert.assertTrue(service.confirmIntentResultPublication(player, endpoint, freeze, pending));
        Assert.assertEquals(pending, service.handleIntent(player, endpoint, freeze, null, 4L));
        Assert.assertEquals(2L, service.snapshot(player).nextActionSequence());

        AutoToolSwapIntent close = control(roundId, 2L, AutoToolSwapAction.CLOSE);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED,
                publish(service, player, endpoint, close, 5L).roundState());
    }

    @Test
    public void phaseProjectionAndLifecycleCleanupRemainIdentityGated() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        Assert.assertEquals(0L, service.observeChainPhase(player, new Object(), roundId, true, false));
        Assert.assertEquals(1L, service.observeChainPhase(player, endpoint, roundId, true, false));
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, service.snapshot(player).roundState());
        service.cleanup(player);
        Assert.assertNull(service.snapshot(player));
    }

    private static AutoToolSwapRoundResult publish(AutoToolSwapRoundService service, UUID player,
            Object endpoint, AutoToolSwapIntent intent, long tick) {
        AutoToolSwapRoundResult result = service.handleIntent(player, endpoint, intent, null, tick);
        Assert.assertTrue(service.confirmIntentResultPublication(player, endpoint, intent, result));
        return result;
    }

    private static AutoToolSwapIntent control(long roundId, long sequence, AutoToolSwapAction action) {
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, sequence,
                action, 0, 0, empty, empty);
    }
}
