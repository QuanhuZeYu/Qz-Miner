package club.heiqi.qz_miner.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapInventoryPort;

/** 自动工具换位 C2S FIFO、身份重取和 fail-closed 边界测试。 */
public class ServerAutoToolSwapRequestDispatchTest {

    @Test
    public void productionDispatchUsesOnlyMyModOwnedRoundServiceAndFailsClosedWhenAbsent() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/network/ServerAutoToolSwapRequestDispatch.java").toPath()),
                StandardCharsets.UTF_8);

        Assert.assertFalse(source.contains("static final AutoToolSwapRoundService"));
        Assert.assertTrue(source.contains("final AutoToolSwapRoundService roundService = MyMod.autoToolSwapRoundService"));
        Assert.assertTrue(source.contains("if (roundService == null)"));
        Assert.assertTrue(source.indexOf("if (roundService == null)")
                < source.indexOf("return new RoundService()"));
    }

    @Test
    public void firstAcceptedPendingStartDoesNotReplyBeforeKeyActivation() {
        Fixture fixture = new Fixture();
        Assert.assertTrue(ServerAutoToolSwapRequestDispatch.submitRoundStart(fixture.playerId, fixture.endpoint,
                1, 41L, true, fixture.fifo, fixture, fixture.service, fixture, fixture));

        fixture.fifo.runNext();

        Assert.assertEquals(1, fixture.service.beginCalls);
        Assert.assertEquals(0, fixture.roundReplies);
    }

    @Test
    public void rejectedPendingStartForDifferentNonceRepliesWithRequestNonce() {
        Fixture fixture = new Fixture();
        submitRoundStart(fixture, 41L);
        fixture.fifo.runNext();
        fixture.service.beginResult = rejectedPending();
        submitRoundStart(fixture, 42L);
        fixture.fifo.runNext();

        Assert.assertEquals(2, fixture.service.beginCalls);
        Assert.assertEquals(1, fixture.roundReplies);
        Assert.assertEquals(42L, fixture.lastNonce);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.lastRoundResult.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.PENDING_KEY, fixture.lastRoundResult.roundState());
    }

    @Test
    public void pendingReplayForSameNonceDoesNotReplyBeforeKeyActivation() {
        Fixture fixture = new Fixture();
        submitRoundStart(fixture, 41L);
        submitRoundStart(fixture, 41L);

        fixture.fifo.runNext();
        fixture.fifo.runNext();

        Assert.assertEquals(2, fixture.service.beginCalls);
        Assert.assertEquals(0, fixture.roundReplies);
    }

    @Test
    public void activatedReplayForSameNonceRepliesWithAcceptedResult() {
        Fixture fixture = new Fixture();
        submitRoundStart(fixture, 41L);
        fixture.fifo.runNext();
        fixture.service.beginResult = acceptedOpen();
        submitRoundStart(fixture, 41L);
        fixture.fifo.runNext();

        Assert.assertEquals(2, fixture.service.beginCalls);
        Assert.assertEquals(1, fixture.roundReplies);
        Assert.assertEquals(41L, fixture.lastNonce);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, fixture.lastRoundResult.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, fixture.lastRoundResult.roundState());
    }

    @Test
    public void identityReplacementDropsQueuedRequestBeforeServiceOrReply() {
        Fixture fixture = new Fixture();
        Assert.assertTrue(ServerAutoToolSwapRequestDispatch.submitRoundStart(fixture.playerId, fixture.endpoint,
                1, 8L, true, fixture.fifo, fixture, fixture.service, fixture, fixture));
        fixture.online.put(fixture.playerId, new Object());
        fixture.fifo.runNext();

        Assert.assertEquals(0, fixture.service.beginCalls);
        Assert.assertEquals(0, fixture.roundReplies);
    }

    @Test
    public void invalidIntentNeverCreatesInventoryPortAndStillReplies() {
        Fixture fixture = new Fixture();
        submitIntent(fixture, 999, 0, 1);
        fixture.fifo.runNext();

        Assert.assertEquals(0, fixture.inventoryCreates);
        Assert.assertEquals(0, fixture.service.intentCalls);
        Assert.assertEquals(1, fixture.actionReplies);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.lastActionResult.outcome());
    }

    @Test
    public void legalAndDuplicateIntentsRemainFifoAndEachReceiveResult() {
        Fixture fixture = new Fixture();
        submitIntent(fixture, 1, 0, 1);
        submitIntent(fixture, 1, 0, 1);
        Assert.assertEquals(2, fixture.fifo.size());

        fixture.fifo.runNext();
        fixture.fifo.runNext();

        Assert.assertEquals(2, fixture.service.intentCalls);
        Assert.assertEquals(2, fixture.inventoryCreates);
        Assert.assertEquals(2, fixture.actionReplies);
        Assert.assertEquals(1L, fixture.lastRawIntent.actionSequence);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, fixture.lastActionResult.outcome());
    }

    private static void submitIntent(Fixture fixture, int actionCode, int anchorSlot, int candidateSlot) {
        Assert.assertTrue(ServerAutoToolSwapRequestDispatch.submitIntent(fixture.playerId, fixture.endpoint, 1, 7L,
                1L, actionCode, anchorSlot, candidateSlot, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, true,
                fixture.fifo, fixture, fixture.service, fixture, fixture, fixture));
    }

    private static void submitRoundStart(Fixture fixture, long clientNonce) {
        Assert.assertTrue(ServerAutoToolSwapRequestDispatch.submitRoundStart(fixture.playerId, fixture.endpoint,
                AutoToolSwapProtocol.PROTOCOL_VERSION, clientNonce, true, fixture.fifo, fixture, fixture.service,
                fixture, fixture));
    }

    private static AutoToolSwapRoundResult acceptedOpen() {
        return new AutoToolSwapRoundResult(7L, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.OPEN,
                AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, 12L);
    }

    private static AutoToolSwapRoundResult rejectedPending() {
        return new AutoToolSwapRoundResult(AutoToolSwapProtocol.NO_SERVER_ROUND_ID,
                AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.PENDING_KEY,
                AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, 12L);
    }

    private static final class Fixture implements ServerAutoToolSwapRequestDispatch.FifoDispatcher,
            ServerAutoToolSwapRequestDispatch.PlayerLookup, ServerAutoToolSwapRequestDispatch.InventoryFactory,
            ServerAutoToolSwapRequestDispatch.TickSource, ServerAutoToolSwapRequestDispatch.RoundResultSender,
            ServerAutoToolSwapRequestDispatch.ActionResultSender {

        private final UUID playerId = UUID.randomUUID();
        private final Object endpoint = new Object();
        private final Map<UUID, Object> online = new HashMap<UUID, Object>();
        private final Fifo fifo = new Fifo();
        private final RecordingService service = new RecordingService();
        private int inventoryCreates;
        private int roundReplies;
        private int actionReplies;
        private long lastNonce;
        private AutoToolSwapRoundResult lastRoundResult;
        private ServerAutoToolSwapRequestDispatch.RawIntent lastRawIntent;
        private AutoToolSwapRoundResult lastActionResult;

        private Fixture() {
            online.put(playerId, endpoint);
        }

        @Override
        public boolean tryRun(Runnable task) {
            return fifo.tryRun(task);
        }

        @Override
        public Object getPlayer(UUID requestedPlayerId) {
            return online.get(requestedPlayerId);
        }

        @Override
        public AutoToolSwapInventoryPort create(Object requestedEndpoint) {
            Assert.assertSame(endpoint, requestedEndpoint);
            inventoryCreates++;
            return null;
        }

        @Override
        public long currentServerTick() {
            return 12L;
        }

        @Override
        public void send(UUID requestedPlayerId, Object requestedEndpoint, long clientNonce,
                AutoToolSwapRoundResult result) {
            Assert.assertEquals(playerId, requestedPlayerId);
            Assert.assertSame(endpoint, requestedEndpoint);
            roundReplies++;
            lastNonce = clientNonce;
            lastRoundResult = result;
        }

        @Override
        public void send(UUID requestedPlayerId, Object requestedEndpoint,
                ServerAutoToolSwapRequestDispatch.RawIntent rawIntent, AutoToolSwapRoundResult result) {
            Assert.assertEquals(playerId, requestedPlayerId);
            Assert.assertSame(endpoint, requestedEndpoint);
            actionReplies++;
            lastRawIntent = rawIntent;
            lastActionResult = result;
        }
    }

    private static final class Fifo implements ServerAutoToolSwapRequestDispatch.FifoDispatcher {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

        @Override
        public boolean tryRun(Runnable task) {
            tasks.add(task);
            return true;
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            tasks.remove().run();
        }
    }

    private static final class RecordingService implements ServerAutoToolSwapRequestDispatch.RoundService {
        private int beginCalls;
        private int intentCalls;
        private AutoToolSwapRoundResult beginResult = new AutoToolSwapRoundResult(0L,
                AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.PENDING_KEY, 1L, 12L);

        @Override
        public AutoToolSwapRoundResult beginRound(UUID playerId, Object endpoint, long clientNonce, long serverTick) {
            beginCalls++;
            return beginResult;
        }

        @Override
        public AutoToolSwapRoundResult handleIntent(UUID playerId, Object endpoint, AutoToolSwapIntent intent,
                AutoToolSwapInventoryPort inventory, long serverTick) {
            intentCalls++;
            return new AutoToolSwapRoundResult(7L, AutoToolSwapResultCode.ACCEPTED,
                    AutoToolSwapRoundState.OPEN, 2L, serverTick);
        }
    }
}
