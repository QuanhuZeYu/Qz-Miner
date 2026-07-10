package club.heiqi.qz_miner.client;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 客户端连接生命周期 token 与 S2C 配置同步守卫的纯 JVM 确定性测试。
 *
 * <p>避免实例化 GuiScreen / Minecraft。</p>
 */
public class ClientConnectionLifecycleTest {

    @Before
    public void setUp() {
        ClientConnectionLifecycle.resetForTests();
    }

    @After
    public void tearDown() {
        ClientConnectionLifecycle.resetForTests();
    }

    @Test
    public void oldTokenPacketQueuedThenDisconnectConnectDoesNotPublish() {
        ClientConnectionLifecycle.Token oldToken = ClientConnectionLifecycle.advanceActive();
        Harness harness = dispatchWithToken(12, 345, 67, oldToken);
        Assert.assertTrue(harness.accepted);
        Assert.assertNotNull(harness.queued);

        ClientConnectionLifecycle.advanceInactive();
        ClientConnectionLifecycle.advanceActive();

        harness.queued.run();
        Assert.assertEquals(0, harness.publications);
        Assert.assertArrayEquals(new int[] {91, 92, 93}, harness.state);
    }

    @Test
    public void oldTokenPacketQueuedThenWorldUnloadDoesNotPublish() {
        ClientConnectionLifecycle.Token oldToken = ClientConnectionLifecycle.advanceActive();
        Harness harness = dispatchWithToken(12, 345, 67, oldToken);
        Assert.assertTrue(harness.accepted);

        ClientConnectionLifecycle.Token afterUnload = ClientConnectionLifecycle.advanceKeepActive();
        Assert.assertTrue(afterUnload.isActive());
        Assert.assertTrue(afterUnload != oldToken);

        harness.queued.run();
        Assert.assertEquals(0, harness.publications);
    }

    @Test
    public void newTokenPacketPublishesOnce() {
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.advanceActive();
        Harness harness = dispatchWithToken(12, 345, 67, token);
        harness.queued.run();
        Assert.assertArrayEquals(new int[] {12, 345, 67}, harness.state);
        Assert.assertEquals(1, harness.publications);
    }

    @Test
    public void inactiveTokenDoesNotEnqueue() {
        ClientConnectionLifecycle.advanceInactive();
        ClientConnectionLifecycle.Token inactive = ClientConnectionLifecycle.capture();
        Assert.assertFalse(inactive.isActive());

        final Harness harness = new Harness();
        boolean accepted = ClientChainConfigSyncDispatch.dispatch(
                12,
                345,
                67,
                inactive,
                lifecycleGate(),
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        harness.queued = task;
                        harness.enqueued = true;
                        return true;
                    }
                },
                new ClientChainConfigSyncDispatch.Publication() {
                    @Override
                    public void publish(int radius, int maxBlocks, int matchedCount) {
                        harness.publications++;
                    }
                });
        Assert.assertTrue("inactive drop is not a dispatcher rejection", accepted);
        Assert.assertFalse(harness.enqueued);
        Assert.assertNull(harness.queued);
        Assert.assertEquals(0, harness.publications);
    }

    @Test
    public void afterWorldUnloadSameConnectionNewActiveTokenCanPublish() {
        ClientConnectionLifecycle.advanceActive();
        ClientConnectionLifecycle.Token afterUnload = ClientConnectionLifecycle.advanceKeepActive();
        Assert.assertTrue(afterUnload.isActive());

        Harness harness = dispatchWithToken(8, 100, 3, afterUnload);
        harness.queued.run();
        Assert.assertEquals(1, harness.publications);
        Assert.assertArrayEquals(new int[] {8, 100, 3}, harness.state);
    }

    @Test
    public void repeatedConnectDisconnectUnloadAdvancesIdempotently() {
        ClientConnectionLifecycle.Token a1 = ClientConnectionLifecycle.advanceActive();
        ClientConnectionLifecycle.Token a2 = ClientConnectionLifecycle.advanceActive();
        Assert.assertTrue(a1 != a2);
        Assert.assertTrue(a2.isActive());

        ClientConnectionLifecycle.Token i1 = ClientConnectionLifecycle.advanceInactive();
        ClientConnectionLifecycle.Token i2 = ClientConnectionLifecycle.advanceInactive();
        Assert.assertTrue(i1 != i2);
        Assert.assertFalse(i2.isActive());

        ClientConnectionLifecycle.Token u1 = ClientConnectionLifecycle.advanceKeepActive();
        Assert.assertFalse(u1.isActive());
        ClientConnectionLifecycle.Token u2 = ClientConnectionLifecycle.advanceKeepActive();
        Assert.assertTrue(u1 != u2);
        Assert.assertFalse(u2.isActive());

        ClientConnectionLifecycle.Token reconnect = ClientConnectionLifecycle.advanceActive();
        Assert.assertTrue(reconnect.isActive());
        Assert.assertTrue(ClientConnectionLifecycle.isCurrentAndActive(reconnect));
        Assert.assertFalse(ClientConnectionLifecycle.isCurrentAndActive(a2));
    }

    private static Harness dispatchWithToken(
            int radius, int maxBlocks, int matchedCount, ClientConnectionLifecycle.Token token) {
        final Harness harness = new Harness();
        harness.accepted = ClientChainConfigSyncDispatch.dispatch(
                radius,
                maxBlocks,
                matchedCount,
                token,
                lifecycleGate(),
                new ClientChainConfigSyncDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        harness.queued = task;
                        harness.enqueued = true;
                        return true;
                    }
                },
                new ClientChainConfigSyncDispatch.Publication() {
                    @Override
                    public void publish(int publishedRadius, int publishedMaxBlocks, int publishedMatchedCount) {
                        harness.state[0] = publishedRadius;
                        harness.state[1] = publishedMaxBlocks;
                        harness.state[2] = publishedMatchedCount;
                        harness.publications++;
                    }
                });
        return harness;
    }

    private static ClientChainConfigSyncDispatch.LifecycleGate lifecycleGate() {
        return new ClientChainConfigSyncDispatch.LifecycleGate() {
            @Override
            public boolean isActive(Object token) {
                return token instanceof ClientConnectionLifecycle.Token
                        && ((ClientConnectionLifecycle.Token) token).isActive();
            }

            @Override
            public boolean isCurrentAndActive(Object token) {
                if (!(token instanceof ClientConnectionLifecycle.Token)) {
                    return false;
                }
                return ClientConnectionLifecycle.isCurrentAndActive(
                        (ClientConnectionLifecycle.Token) token);
            }
        };
    }

    private static final class Harness {
        private final int[] state = new int[] {91, 92, 93};
        private Runnable queued;
        private int publications;
        private boolean accepted;
        private boolean enqueued;
    }
}
