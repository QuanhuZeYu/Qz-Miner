package club.heiqi.qz_miner.toolswap.server;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 服务端按键与工具换位 round 的纯 JVM 行为合同。 */
public class AutoToolSwapKeyStateBridgeTest {

    @Test
    public void keyPressWithoutRoundDoesNotAckOrCreateRecord() {
        Fixture fixture = new Fixture();

        Assert.assertEquals(0L, AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 1L, fixture));
        Assert.assertEquals(0, fixture.sendCount);
        Assert.assertNull(fixture.service.snapshot(fixture.playerId));
    }

    @Test
    public void pendingKeyPressActivatesAndRepliesWithOriginalNonceAndRound() {
        Fixture fixture = new Fixture();
        fixture.service.beginRound(fixture.playerId, fixture.endpoint, 41L, 1L);

        long roundId = AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 2L, fixture);

        Assert.assertTrue(roundId > 0L);
        Assert.assertEquals(1, fixture.sendCount);
        Assert.assertEquals(41L, fixture.clientNonce);
        Assert.assertEquals(roundId, fixture.result.serverRoundId());
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, fixture.result.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, fixture.result.roundState());
    }

    @Test
    public void keyReleaseReturnsRoundAndMovesToClosingWithoutReplyOrInventoryAccess() {
        Fixture fixture = new Fixture();
        fixture.service.beginRound(fixture.playerId, fixture.endpoint, 41L, 1L);
        long roundId = AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 2L, fixture);

        long releasedRound = AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, false,
                fixture.service, 3L, fixture);

        Assert.assertEquals(roundId, releasedRound);
        Assert.assertEquals(1, fixture.sendCount);
        Assert.assertEquals(AutoToolSwapRoundState.CLOSING,
                fixture.service.snapshot(fixture.playerId, fixture.endpoint).roundState());
    }

    private static final class Fixture implements AutoToolSwapKeyStateBridge.RoundResultSender {
        private final UUID playerId = UUID.randomUUID();
        private final Object endpoint = new Object();
        private final AutoToolSwapRoundService service = new AutoToolSwapRoundService();
        private int sendCount;
        private long clientNonce;
        private AutoToolSwapRoundResult result;

        @Override
        public void send(UUID requestedPlayerId, Object requestedEndpoint, long nonce,
                AutoToolSwapRoundResult roundResult) {
            Assert.assertEquals(playerId, requestedPlayerId);
            Assert.assertSame(endpoint, requestedEndpoint);
            sendCount++;
            clientNonce = nonce;
            result = roundResult;
        }
    }
}
