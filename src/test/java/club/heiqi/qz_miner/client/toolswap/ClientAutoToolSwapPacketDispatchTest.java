package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** S2C packet dispatch 的 lifecycle 与主线程边界合同。 */
public class ClientAutoToolSwapPacketDispatchTest {

    @Test
    public void inactiveTokenIsDroppedBeforeDispatcherSubmission() {
        Token inactive = new Token(1, 1, false);
        FakeGate gate = new FakeGate(inactive);
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);

        boolean accepted = ClientAutoToolSwapPacketDispatch.dispatch(
                inactive, gate, dispatcher, new CountingPublication());

        Assert.assertTrue(accepted);
        Assert.assertEquals(0, dispatcher.tasks.size());
    }

    @Test
    public void dispatcherRejectionIsObservableAndDoesNotRetry() {
        Token current = new Token(1, 1, true);
        FakeGate gate = new FakeGate(current);
        RecordingDispatcher dispatcher = new RecordingDispatcher(false);

        boolean accepted = ClientAutoToolSwapPacketDispatch.dispatch(
                current, gate, dispatcher, new CountingPublication());

        Assert.assertFalse(accepted);
        Assert.assertEquals(1, dispatcher.submissions);
        Assert.assertEquals(0, dispatcher.tasks.size());
    }

    @Test
    public void oldConnectionIsDroppedAtMainThreadPublication() {
        Token oldConnection = new Token(1, 1, true);
        FakeGate gate = new FakeGate(oldConnection);
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);
        CountingPublication publication = new CountingPublication();

        ClientAutoToolSwapPacketDispatch.dispatch(oldConnection, gate, dispatcher, publication);
        gate.current = new Token(2, 1, true);
        dispatcher.runAll();

        Assert.assertEquals(0, publication.calls);
    }

    @Test
    public void oldWorldIsDroppedAtMainThreadPublication() {
        Token oldWorld = new Token(1, 1, true);
        FakeGate gate = new FakeGate(oldWorld);
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);
        CountingPublication publication = new CountingPublication();

        ClientAutoToolSwapPacketDispatch.dispatch(oldWorld, gate, dispatcher, publication);
        gate.current = new Token(1, 2, true);
        dispatcher.runAll();

        Assert.assertEquals(0, publication.calls);
    }

    @Test
    public void currentTokenPublishesExactlyOnce() {
        Token current = new Token(1, 1, true);
        FakeGate gate = new FakeGate(current);
        RecordingDispatcher dispatcher = new RecordingDispatcher(true);
        CountingPublication publication = new CountingPublication();

        Assert.assertTrue(ClientAutoToolSwapPacketDispatch.dispatch(current, gate, dispatcher, publication));
        dispatcher.runAll();

        Assert.assertEquals(1, publication.calls);
        Assert.assertEquals(1, gate.publicationAttempts);
    }

    private static final class Token {
        private final int connection;
        private final int world;
        private final boolean active;

        private Token(int connection, int world, boolean active) {
            this.connection = connection;
            this.world = world;
            this.active = active;
        }
    }

    private static final class FakeGate implements ClientAutoToolSwapPacketDispatch.LifecycleGate {
        private Token current;
        private int publicationAttempts;

        private FakeGate(Token current) {
            this.current = current;
        }

        @Override
        public boolean isActive(Object token) {
            return token instanceof Token && ((Token) token).active;
        }

        @Override
        public boolean publishIfCurrentAndActive(Object token, Runnable publication) {
            publicationAttempts++;
            Token captured = token instanceof Token ? (Token) token : null;
            if (captured == null || !captured.active || current == null || !current.active
                    || captured.connection != current.connection || captured.world != current.world) {
                return false;
            }
            publication.run();
            return true;
        }
    }

    private static final class RecordingDispatcher implements ClientAutoToolSwapPacketDispatch.Dispatcher {
        private final boolean accepts;
        private final List<Runnable> tasks = new ArrayList<Runnable>();
        private int submissions;

        private RecordingDispatcher(boolean accepts) {
            this.accepts = accepts;
        }

        @Override
        public boolean dispatch(Runnable task) {
            submissions++;
            if (!accepts) {
                return false;
            }
            tasks.add(task);
            return true;
        }

        private void runAll() {
            for (Runnable task : tasks) {
                task.run();
            }
        }
    }

    private static final class CountingPublication implements Runnable {
        private int calls;

        @Override
        public void run() {
            calls++;
        }
    }
}
