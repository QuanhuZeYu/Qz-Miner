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
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;

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
    public void activeRepeatedKeyPressReturnsExistingRoundWithoutAdditionalReply() {
        Fixture fixture = new Fixture();
        fixture.service.beginRound(fixture.playerId, fixture.endpoint, 41L, 1L);
        long roundId = AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 2L, fixture);

        Assert.assertEquals(roundId, AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 3L, fixture));
        Assert.assertEquals(1, fixture.sendCount);
        Assert.assertEquals(AutoToolSwapRoundState.OPEN,
                fixture.service.snapshot(fixture.playerId, fixture.endpoint).roundState());
    }

    @Test
    public void directFreezeAndRepeatedKeyPressKeepCurrentRound() {
        Fixture swapped = new Fixture();
        long swappedRoundId = activate(swapped);
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, publish(swapped,
                freezeIntent(swappedRoundId), null, 3L).roundState());
        Assert.assertEquals(swappedRoundId, AutoToolSwapKeyStateBridge.onKeyState(swapped.playerId, swapped.endpoint,
                true, swapped.service, 4L, swapped));
        Assert.assertEquals(1, swapped.sendCount);

        Fixture frozen = new Fixture();
        long frozenRoundId = activate(frozen);
        Assert.assertEquals(1L, frozen.service.observeChainPhase(frozen.playerId, frozen.endpoint, frozenRoundId,
                true, false));
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN,
                frozen.service.snapshot(frozen.playerId, frozen.endpoint).roundState());
        Assert.assertEquals(frozenRoundId, AutoToolSwapKeyStateBridge.onKeyState(frozen.playerId, frozen.endpoint,
                true, frozen.service, 4L, frozen));
        Assert.assertEquals(1, frozen.sendCount);
    }

    @Test
    public void keyReleaseFinishesProjectionAndFreshRoundCanStart() {
        Fixture fixture = new Fixture();
        fixture.service.beginRound(fixture.playerId, fixture.endpoint, 41L, 1L);
        long roundId = AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 2L, fixture);

        long releasedRound = AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, false,
                fixture.service, 3L, fixture);

        Assert.assertEquals(roundId, releasedRound);
        Assert.assertEquals(1, fixture.sendCount);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED,
                fixture.service.snapshot(fixture.playerId, fixture.endpoint).roundState());
        Assert.assertEquals(0L, AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 4L, fixture));
        Assert.assertEquals(1, fixture.sendCount);

        fixture.service.beginRound(fixture.playerId, fixture.endpoint, 42L, 5L);
        Assert.assertTrue(AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 6L, fixture) > roundId);
        Assert.assertEquals(2, fixture.sendCount);
    }

    @Test
    public void closeRemainsAcceptedAfterKeyReleaseFinishedProjection() {
        Fixture fixture = new Fixture();
        long roundId = activate(fixture);
        AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, false,
                fixture.service, 3L, fixture);
        AutoToolSwapRoundResult close = publish(fixture, closeIntent(roundId), null, 4L);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, close.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, close.roundState());
    }

    @Test
    public void terminalAndEndpointMismatchKeyPressDoNotReply() {
        Fixture terminal = new Fixture();
        long terminalRoundId = activate(terminal);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED,
                publish(terminal, closeIntent(terminalRoundId), null, 3L).roundState());
        Assert.assertEquals(0L, AutoToolSwapKeyStateBridge.onKeyState(terminal.playerId, terminal.endpoint, true,
                terminal.service, 4L, terminal));
        Assert.assertEquals(1, terminal.sendCount);

        Fixture mismatch = new Fixture();
        activate(mismatch);
        Assert.assertEquals(0L, AutoToolSwapKeyStateBridge.onKeyState(mismatch.playerId, new Object(), true,
                mismatch.service, 3L, mismatch));
        Assert.assertEquals(1, mismatch.sendCount);
    }

    @Test
    public void finishedRoundCannotReviveButFreshNonceThenKeyPressOpensDifferentRound() {
        Fixture fixture = new Fixture();
        long firstRoundId = activate(fixture);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED,
                publish(fixture, closeIntent(firstRoundId), null, 3L).roundState());

        Assert.assertEquals(0L, AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 4L, fixture));
        Assert.assertEquals(1, fixture.sendCount);

        fixture.service.beginRound(fixture.playerId, fixture.endpoint, 42L, 5L);
        long secondRoundId = AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true,
                fixture.service, 6L, fixture);
        Assert.assertTrue(secondRoundId > firstRoundId);
        Assert.assertNotEquals(firstRoundId, secondRoundId);
        Assert.assertEquals(42L, fixture.clientNonce);
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, fixture.result.roundState());
        Assert.assertEquals(2, fixture.sendCount);
    }

    private static long activate(Fixture fixture) {
        fixture.service.beginRound(fixture.playerId, fixture.endpoint, 41L, 1L);
        return AutoToolSwapKeyStateBridge.onKeyState(fixture.playerId, fixture.endpoint, true, fixture.service, 2L,
                fixture);
    }

    /** 模拟 ActionResult sender 正常返回，并在 publication 线性化点确认结果。 */
    private static AutoToolSwapRoundResult publish(Fixture fixture, AutoToolSwapIntent intent,
            AutoToolSwapInventoryPort inventory, long serverTick) {
        AutoToolSwapRoundResult result = fixture.service.handleIntent(
                fixture.playerId, fixture.endpoint, intent, inventory, serverTick);
        Assert.assertTrue(fixture.service.snapshot(fixture.playerId, fixture.endpoint)
                .hasPendingResultPublication());
        Assert.assertTrue(fixture.service.confirmIntentResultPublication(
                fixture.playerId, fixture.endpoint, intent, result));
        return result;
    }

    private static AutoToolSwapIntent freezeIntent(long roundId) {
        AutoToolSwapStackState stack = stack("mod:pickaxe", "freeze");
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, 1L,
                AutoToolSwapAction.FREEZE, 0, 0, stack.contentFingerprint(), stack.contentFingerprint());
    }

    private static AutoToolSwapIntent closeIntent(long roundId) {
        AutoToolSwapStackState stack = stack("mod:pickaxe", "close");
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, 1L, AutoToolSwapAction.CLOSE,
                0, 9, stack.contentFingerprint(), stack.contentFingerprint());
    }

    private static AutoToolSwapStackState stack(String role, String content) {
        return AutoToolSwapStackState.occupied(role, AutoToolSwapContentFingerprint.fromContent(role, content), 100);
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
