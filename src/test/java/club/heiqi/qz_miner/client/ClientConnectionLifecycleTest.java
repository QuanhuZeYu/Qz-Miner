package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final long LATCH_TIMEOUT_MS = 5_000L;

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

    /**
     * a) validate 完成后、进入 publication gate 前推进 disconnect/unload → publication=0。
     */
    @Test
    public void disconnectAfterValidateBeforePublicationGateDoesNotPublish() throws Exception {
        final ClientConnectionLifecycle.Token oldToken = ClientConnectionLifecycle.advanceActive();
        final AtomicInteger publications = new AtomicInteger(0);
        final CountDownLatch enteredTask = new CountDownLatch(1);
        final CountDownLatch allowGate = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        Thread taskThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 模拟主线程任务：先校验（此处恒合法），再在 gate 前被打断
                    if (!(12 > 0 && 345 > 0 && 67 >= 0)) {
                        return;
                    }
                    enteredTask.countDown();
                    Assert.assertTrue(allowGate.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                    boolean published = ClientConnectionLifecycle.publishIfCurrentAndActive(
                            oldToken,
                            new Runnable() {
                                @Override
                                public void run() {
                                    publications.incrementAndGet();
                                }
                            });
                    Assert.assertFalse(published);
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "validate-then-disconnect");
        taskThread.start();

        Assert.assertTrue(enteredTask.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        ClientConnectionLifecycle.advanceInactive();
        ClientConnectionLifecycle.advanceKeepActive();
        allowGate.countDown();
        taskThread.join(LATCH_TIMEOUT_MS);
        Assert.assertFalse(taskThread.isAlive());
        rethrow(error.get());
        Assert.assertEquals(0, publications.get());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isActive());
    }

    /**
     * b) publication 已取得 gate 时并发 disconnect 必须等待；publication 完成后 disconnect 推进；无死锁。
     */
    @Test
    public void publicationHoldingGateBlocksDisconnectUntilComplete() throws Exception {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.advanceActive();
        final List<String> order = new ArrayList<String>();
        final Object orderLock = new Object();
        final CountDownLatch insidePublication = new CountDownLatch(1);
        final CountDownLatch releasePublication = new CountDownLatch(1);
        final CountDownLatch disconnectStarted = new CountDownLatch(1);
        final CountDownLatch disconnectDone = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        Thread pubThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = ClientConnectionLifecycle.publishIfCurrentAndActive(
                            token,
                            new Runnable() {
                                @Override
                                public void run() {
                                    record(order, orderLock, "publish-enter");
                                    insidePublication.countDown();
                                    try {
                                        Assert.assertTrue(
                                                releasePublication.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                    record(order, orderLock, "publish-exit");
                                }
                            });
                    Assert.assertTrue(ok);
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "publication-holder");
        pubThread.start();

        Assert.assertTrue(insidePublication.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        Thread disconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    disconnectStarted.countDown();
                    ClientConnectionLifecycle.advanceInactive();
                    record(order, orderLock, "disconnect");
                    disconnectDone.countDown();
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "disconnect-waiter");
        disconnectThread.start();

        Assert.assertTrue(disconnectStarted.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        // disconnect 必须在 publication 持锁时无法完成
        Assert.assertFalse(
                "disconnect must wait while publication holds lifecycle monitor",
                disconnectDone.await(200L, TimeUnit.MILLISECONDS));

        releasePublication.countDown();
        Assert.assertTrue(disconnectDone.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        pubThread.join(LATCH_TIMEOUT_MS);
        disconnectThread.join(LATCH_TIMEOUT_MS);
        Assert.assertFalse(pubThread.isAlive());
        Assert.assertFalse(disconnectThread.isAlive());
        rethrow(error.get());

        synchronized (orderLock) {
            Assert.assertEquals(3, order.size());
            Assert.assertEquals("publish-enter", order.get(0));
            Assert.assertEquals("publish-exit", order.get(1));
            Assert.assertEquals("disconnect", order.get(2));
        }
        Assert.assertFalse(ClientConnectionLifecycle.capture().isActive());
        Assert.assertFalse(ClientConnectionLifecycle.isCurrentAndActive(token));
    }

    /**
     * c) unload 观察旧 active 与 disconnect 交错，最终 inactive。
     */
    @Test
    public void unloadInterleavedWithDisconnectEndsInactive() throws Exception {
        ClientConnectionLifecycle.advanceActive();
        final int rounds = 200;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AtomicInteger unloadSeenActiveThenFinalInactive = new AtomicInteger(0);

        Thread unloadThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    for (int i = 0; i < rounds; i++) {
                        ClientConnectionLifecycle.Token t = ClientConnectionLifecycle.advanceKeepActive();
                        // 线性化后若仍 active，说明 disconnect 尚未覆盖该次；最终循环外再断言
                        if (t.isActive()) {
                            unloadSeenActiveThenFinalInactive.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "unload-racer");
        Thread disconnectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    for (int i = 0; i < rounds; i++) {
                        ClientConnectionLifecycle.advanceInactive();
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "disconnect-racer");

        unloadThread.start();
        disconnectThread.start();
        start.countDown();
        unloadThread.join(LATCH_TIMEOUT_MS);
        disconnectThread.join(LATCH_TIMEOUT_MS);
        Assert.assertFalse(unloadThread.isAlive());
        Assert.assertFalse(disconnectThread.isAlive());
        rethrow(error.get());

        // 再推进一次 unload：若 disconnect 已先提交，必须保持 inactive
        ClientConnectionLifecycle.Token finalUnload = ClientConnectionLifecycle.advanceKeepActive();
        Assert.assertFalse(
                "after disconnect wins, keep-active must not resurrect active",
                finalUnload.isActive());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isActive());
    }

    /**
     * d) disconnect+reconnect 与旧 publication 交错：旧 token 不能在新 active 后写。
     */
    @Test
    public void oldPublicationCannotWriteAfterDisconnectReconnect() throws Exception {
        final ClientConnectionLifecycle.Token oldToken = ClientConnectionLifecycle.advanceActive();
        final AtomicInteger publications = new AtomicInteger(0);
        final CountDownLatch insideGateCheckPath = new CountDownLatch(1);
        final CountDownLatch reconnectDone = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AtomicBoolean published = new AtomicBoolean(false);

        // 持有 monitor 的旧 publication 路径：进入后等 reconnect，再尝试写
        Thread oldPub = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 先进入 monitor 路径前让 reconnect 有机会交错：用两阶段
                    // 阶段1：disconnect+reconnect 完成
                    insideGateCheckPath.countDown();
                    Assert.assertTrue(reconnectDone.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                    boolean ok = ClientConnectionLifecycle.publishIfCurrentAndActive(
                            oldToken,
                            new Runnable() {
                                @Override
                                public void run() {
                                    publications.incrementAndGet();
                                }
                            });
                    published.set(ok);
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "old-publication");
        oldPub.start();

        Assert.assertTrue(insideGateCheckPath.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        ClientConnectionLifecycle.advanceInactive();
        ClientConnectionLifecycle.Token newToken = ClientConnectionLifecycle.advanceActive();
        Assert.assertTrue(newToken.isActive());
        Assert.assertTrue(newToken != oldToken);
        reconnectDone.countDown();
        oldPub.join(LATCH_TIMEOUT_MS);
        Assert.assertFalse(oldPub.isAlive());
        rethrow(error.get());

        Assert.assertFalse(published.get());
        Assert.assertEquals(0, publications.get());
        Assert.assertTrue(ClientConnectionLifecycle.isCurrentAndActive(newToken));

        // 新 token 正常发布
        boolean newOk = ClientConnectionLifecycle.publishIfCurrentAndActive(
                newToken,
                new Runnable() {
                    @Override
                    public void run() {
                        publications.incrementAndGet();
                    }
                });
        Assert.assertTrue(newOk);
        Assert.assertEquals(1, publications.get());
    }

    /**
     * e) 新 token 正常发布（经完整 dispatch 路径）。
     */
    @Test
    public void newTokenNormalPublicationViaDispatchPath() {
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.advanceActive();
        Harness harness = dispatchWithToken(21, 400, 5, token);
        harness.queued.run();
        Assert.assertEquals(1, harness.publications);
        Assert.assertArrayEquals(new int[] {21, 400, 5}, harness.state);
        Assert.assertTrue(ClientConnectionLifecycle.isCurrentAndActive(token));
    }

    /**
     * c2) 确定性：模拟「unload 已读到旧 active」窗口被 disconnect 抢先提交后，
     * unload 在同一线性化协议下重试，最终仍 inactive（禁复活 active）。
     */
    @Test
    public void keepActiveAfterDisconnectCannotResurrectActive() {
        ClientConnectionLifecycle.Token active = ClientConnectionLifecycle.advanceActive();
        Assert.assertTrue(active.isActive());

        ClientConnectionLifecycle.Token disconnected = ClientConnectionLifecycle.advanceInactive();
        Assert.assertFalse(disconnected.isActive());

        // unload 在 disconnect 之后：必须保持 inactive，即使「逻辑上」曾观察过旧 active
        ClientConnectionLifecycle.Token afterUnload = ClientConnectionLifecycle.advanceKeepActive();
        Assert.assertFalse(afterUnload.isActive());
        Assert.assertTrue(afterUnload != disconnected);
        Assert.assertFalse(ClientConnectionLifecycle.capture().isActive());
    }

    /**
     * 持锁 publication 与随后 disconnect+unload：顺序可断言且最终 inactive。
     */
    @Test
    public void publicationThenDisconnectThenUnloadEndsInactive() throws Exception {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.advanceActive();
        final List<String> order = new ArrayList<String>();
        final Object orderLock = new Object();
        final CountDownLatch insidePublication = new CountDownLatch(1);
        final CountDownLatch releasePublication = new CountDownLatch(1);
        final CountDownLatch lifecycleDone = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        Thread pubThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = ClientConnectionLifecycle.publishIfCurrentAndActive(
                            token,
                            new Runnable() {
                                @Override
                                public void run() {
                                    record(order, orderLock, "publish");
                                    insidePublication.countDown();
                                    try {
                                        Assert.assertTrue(
                                                releasePublication.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            });
                    Assert.assertTrue(ok);
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "publication");
        pubThread.start();
        Assert.assertTrue(insidePublication.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));

        Thread lifecycleThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ClientConnectionLifecycle.advanceInactive();
                    record(order, orderLock, "disconnect");
                    ClientConnectionLifecycle.Token keep = ClientConnectionLifecycle.advanceKeepActive();
                    Assert.assertFalse(keep.isActive());
                    record(order, orderLock, "unload");
                    lifecycleDone.countDown();
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "disconnect-unload");
        lifecycleThread.start();

        Assert.assertFalse(
                "disconnect+unload must wait while publication holds lifecycle monitor",
                lifecycleDone.await(200L, TimeUnit.MILLISECONDS));

        releasePublication.countDown();
        Assert.assertTrue(lifecycleDone.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        pubThread.join(LATCH_TIMEOUT_MS);
        lifecycleThread.join(LATCH_TIMEOUT_MS);
        Assert.assertFalse(pubThread.isAlive());
        Assert.assertFalse(lifecycleThread.isAlive());
        rethrow(error.get());

        synchronized (orderLock) {
            Assert.assertEquals(3, order.size());
            Assert.assertEquals("publish", order.get(0));
            Assert.assertEquals("disconnect", order.get(1));
            Assert.assertEquals("unload", order.get(2));
        }
        Assert.assertFalse(ClientConnectionLifecycle.capture().isActive());
    }

    private static void record(List<String> order, Object lock, String event) {
        synchronized (lock) {
            order.add(event);
        }
    }

    private static void rethrow(Throwable t) throws Exception {
        if (t == null) {
            return;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Exception) {
            throw (Exception) t;
        }
        throw new RuntimeException(t);
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

            @Override
            public boolean publishIfCurrentAndActive(Object token, Runnable publication) {
                if (!(token instanceof ClientConnectionLifecycle.Token)) {
                    return false;
                }
                return ClientConnectionLifecycle.publishIfCurrentAndActive(
                        (ClientConnectionLifecycle.Token) token, publication);
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
