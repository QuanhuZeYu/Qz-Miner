package club.heiqi.qz_miner.network;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapInventoryPort;

/** C2S dispatcher 在主线程解码严格 5.3 FREEZE/CLOSE，且没有 inventory factory。 */
public class ServerAutoToolSwapRequestDispatchTest {
    @Test
    public void validControlIsDecodedAfterEndpointGate() {
        Fixture fixture = new Fixture();
        Assert.assertTrue(submit(fixture, AutoToolSwapAction.FREEZE.wireCode(), true));
        Assert.assertEquals(AutoToolSwapAction.FREEZE, fixture.intent.action());
        Assert.assertEquals(1, fixture.confirmations);
        Assert.assertEquals(1, fixture.sends);
    }

    @Test
    public void legacyAndMalformedActionsAreRejectedBeforeService() {
        for (int code : new int[] { 1, 2, 5, 6, 7 }) {
            Fixture fixture = new Fixture();
            Assert.assertTrue(submit(fixture, code, true));
            Assert.assertNull(fixture.intent);
            Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.result.outcome());
        }
        Fixture malformed = new Fixture();
        Assert.assertTrue(submit(malformed, AutoToolSwapAction.CLOSE.wireCode(), false));
        Assert.assertNull(malformed.intent);
    }

    @Test
    public void staleEndpointDropsWithoutPublication() {
        Fixture fixture = new Fixture();
        fixture.currentEndpoint = new Object();
        Assert.assertTrue(submit(fixture, AutoToolSwapAction.CLOSE.wireCode(), true));
        Assert.assertEquals(0, fixture.sends);
        Assert.assertNull(fixture.intent);
    }

    private static boolean submit(Fixture fixture, int actionCode, boolean rawValid) {
        return ServerAutoToolSwapRequestDispatch.submitIntent(fixture.playerId, fixture.endpoint,
                AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L, actionCode, 0, 0,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, rawValid,
                fixture, fixture, fixture, fixture, fixture);
    }

    private static final class Fixture implements ServerAutoToolSwapRequestDispatch.FifoDispatcher,
            ServerAutoToolSwapRequestDispatch.PlayerLookup, ServerAutoToolSwapRequestDispatch.RoundService,
            ServerAutoToolSwapRequestDispatch.TickSource, ServerAutoToolSwapRequestDispatch.ActionResultSender {
        private final UUID playerId = UUID.randomUUID();
        private final Object endpoint = new Object();
        private Object currentEndpoint = endpoint;
        private AutoToolSwapIntent intent;
        private AutoToolSwapRoundResult result;
        private int sends;
        private int confirmations;

        @Override public boolean tryRun(Runnable task) { task.run(); return true; }
        @Override public Object getPlayer(UUID id) { return id.equals(playerId) ? currentEndpoint : null; }
        @Override public long currentServerTick() { return 4L; }
        @Override public AutoToolSwapRoundResult beginRound(UUID id, Object ep, long nonce, long tick) {
            throw new AssertionError("not used");
        }
        @Override public AutoToolSwapRoundResult handleIntent(UUID id, Object ep, AutoToolSwapIntent value,
                AutoToolSwapInventoryPort inventory, long tick) {
            Assert.assertNull(inventory);
            intent = value;
            result = new AutoToolSwapRoundResult(value.serverRoundId(), AutoToolSwapResultCode.ACCEPTED,
                    value.action() == AutoToolSwapAction.FREEZE
                            ? AutoToolSwapRoundState.FROZEN : AutoToolSwapRoundState.FINISHED,
                    value.actionSequence() + 1L, tick);
            return result;
        }
        @Override public boolean confirmIntentResultPublication(UUID id, Object ep, AutoToolSwapIntent value,
                AutoToolSwapRoundResult published) {
            confirmations++;
            return true;
        }
        @Override public void send(UUID id, Object ep, ServerAutoToolSwapRequestDispatch.RawIntent raw,
                AutoToolSwapRoundResult published) {
            sends++;
            result = published;
        }
    }
}
