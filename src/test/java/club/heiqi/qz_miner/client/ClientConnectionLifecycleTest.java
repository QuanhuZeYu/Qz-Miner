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

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * 客户端连接 identity 生命周期确定性测试。
 *
 * <p>用普通 Object 模拟 INetHandler / World 对象 identity（{@code ==}），
 * 不实例化 GuiScreen / Minecraft / NetHandlerPlayClient。</p>
 */
public class ClientConnectionLifecycleTest {

    private static final long LATCH_TIMEOUT_MS = 5_000L;

    private Object handlerA;
    private Object handlerB;
    private Object worldA;
    private Object worldB;

    @Before
    public void setUp() {
        ClientConnectionLifecycle.resetForTests();
        handlerA = new Object();
        handlerB = new Object();
        worldA = new Object();
        worldB = new Object();
    }

    @After
    public void tearDown() {
        ClientConnectionLifecycle.resetForTests();
    }

    @Test
    public void sameActiveHandlerConnectIsNoOp() {
        ClientConnectionLifecycle.Token t1 = ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token t2 = ClientConnectionLifecycle.connect(handlerA);
        Assert.assertSame(t1, t2);
        Assert.assertTrue(t1.isConnectionActive());
        Assert.assertEquals(t1.connectionGeneration(), t2.connectionGeneration());
    }

    @Test
    public void differentHandlerConnectCreatesNewActiveToken() {
        ClientConnectionLifecycle.Token a = ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token b = ClientConnectionLifecycle.connect(handlerB);
        Assert.assertNotSame(a, b);
        Assert.assertTrue(b.isConnectionActive());
        Assert.assertTrue(a.connectionGeneration() != b.connectionGeneration());
        Assert.assertSame(handlerB, b.connectionIdentity());
    }

    @Test
    public void disconnectOnlyWhenCurrentActiveHandler() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.DisconnectResult late =
                ClientConnectionLifecycle.disconnect(handlerB);
        Assert.assertFalse(late.transitioned());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());

        ClientConnectionLifecycle.DisconnectResult ok =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertTrue(ok.transitioned());
        Assert.assertFalse(ok.token().isConnectionActive());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());

        ClientConnectionLifecycle.DisconnectResult repeat =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertFalse(repeat.transitioned());
    }

    @Test
    public void lateDisconnectAfterReconnectIsNoOp() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.disconnect(handlerA);
        ClientConnectionLifecycle.connect(handlerB);
        ClientConnectionLifecycle.DisconnectResult late =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertFalse(late.transitioned());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());
        Assert.assertSame(handlerB, ClientConnectionLifecycle.capture().connectionIdentity());
    }

    @Test
    public void secondCloseAfterDisconnectIsNoOp() {
        ClientConnectionLifecycle.connect(handlerA);
        Assert.assertTrue(ClientConnectionLifecycle.disconnect(handlerA).transitioned());
        Assert.assertFalse(ClientConnectionLifecycle.disconnect(handlerA).transitioned());
    }

    /**
     * A connect → init 排队 → A disconnect → B connect → 运行 A init no-op。
     */
    @Test
    public void queuedInitForAAfterBConnectIsNoOp() {
        ClientConnectionLifecycle.Token tokenA = ClientConnectionLifecycle.connect(handlerA);
        final AtomicInteger inits = new AtomicInteger(0);

        ClientConnectionLifecycle.disconnect(handlerA);
        ClientConnectionLifecycle.connect(handlerB);

        boolean ran = ClientConnectionLifecycle.runIfConnectionCurrentAndActive(tokenA, new Runnable() {
            @Override
            public void run() {
                inits.incrementAndGet();
            }
        });
        Assert.assertFalse(ran);
        Assert.assertEquals(0, inits.get());

        ClientConnectionLifecycle.Token tokenB = ClientConnectionLifecycle.capture();
        Assert.assertTrue(ClientConnectionLifecycle.runIfConnectionCurrentAndActive(tokenB, new Runnable() {
            @Override
            public void run() {
                inits.incrementAndGet();
            }
        }));
        Assert.assertEquals(1, inits.get());
    }

    /**
     * A cleanup 排队 → B connect + preview/phase 建立 → A cleanup no-op。
     */
    @Test
    public void queuedDisconnectCleanupForAAfterBConnectIsNoOp() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.DisconnectResult discA =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertTrue(discA.transitioned());
        final ClientConnectionLifecycle.Token cleanupA = discA.token();

        ClientConnectionLifecycle.Token tokenB = ClientConnectionLifecycle.connect(handlerB);
        ClientConnectionLifecycle.bindWorld(worldB);
        final AtomicInteger cleanups = new AtomicInteger(0);
        final AtomicInteger phases = new AtomicInteger(0);

        boolean cleaned = ClientConnectionLifecycle.runIfInactiveDisconnectCurrent(cleanupA, new Runnable() {
            @Override
            public void run() {
                cleanups.incrementAndGet();
            }
        });
        Assert.assertFalse(cleaned);
        Assert.assertEquals(0, cleanups.get());

        ClientConnectionLifecycle.Token worldBToken = ClientConnectionLifecycle.capture();
        Assert.assertTrue(ClientConnectionLifecycle.runIfWorldCurrentAndActive(worldBToken, new Runnable() {
            @Override
            public void run() {
                phases.incrementAndGet();
            }
        }));
        Assert.assertEquals(1, phases.get());
        Assert.assertTrue(ClientConnectionLifecycle.isConnectionCurrentAndActive(tokenB));
    }

    @Test
    public void disconnectCleanupRunsOnInactiveCurrentToken() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.DisconnectResult disc =
                ClientConnectionLifecycle.disconnect(handlerA);
        final AtomicInteger cleanups = new AtomicInteger(0);
        Assert.assertTrue(ClientConnectionLifecycle.runIfInactiveDisconnectCurrent(disc.token(), new Runnable() {
            @Override
            public void run() {
                cleanups.incrementAndGet();
            }
        }));
        Assert.assertEquals(1, cleanups.get());
    }

    @Test
    public void firstWorldBindKeepsConnectionGeneration() {
        ClientConnectionLifecycle.Token conn = ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token afterBind = ClientConnectionLifecycle.bindWorld(worldA);
        Assert.assertEquals(conn.connectionGeneration(), afterBind.connectionGeneration());
        Assert.assertTrue(afterBind.isWorldActive());
        Assert.assertTrue(ClientConnectionLifecycle.isConnectionCurrentAndActive(conn));
        Assert.assertTrue(ClientConnectionLifecycle.isConnectionCurrentAndActive(afterBind));
    }

    @Test
    public void worldReplaceAdvancesWorldGenerationKeepsConnection() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token wA = ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.Token wB = ClientConnectionLifecycle.bindWorld(worldB);
        Assert.assertEquals(wA.connectionGeneration(), wB.connectionGeneration());
        Assert.assertTrue(wA.worldGeneration() != wB.worldGeneration());
        Assert.assertFalse(ClientConnectionLifecycle.isWorldCurrentAndActive(wA));
        Assert.assertTrue(ClientConnectionLifecycle.isWorldCurrentAndActive(wB));
    }

    @Test
    public void oldWorldUnloadAfterWorldBIsNoOp() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.bindWorld(worldB);
        ClientConnectionLifecycle.WorldUnbindResult old =
                ClientConnectionLifecycle.unbindWorld(worldA);
        Assert.assertFalse(old.transitioned());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isWorldActive());
        Assert.assertSame(worldB, ClientConnectionLifecycle.capture().worldIdentity());
    }

    @Test
    public void unloadAfterDisconnectIsNoOp() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.disconnect(handlerA);
        ClientConnectionLifecycle.WorldUnbindResult r =
                ClientConnectionLifecycle.unbindWorld(worldA);
        Assert.assertFalse(r.transitioned());
    }

    @Test
    public void sameConnectionDimensionChangeInvalidatesOldWorldToken() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token oldWorld = ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.WorldUnbindResult unbind =
                ClientConnectionLifecycle.unbindWorld(worldA);
        Assert.assertTrue(unbind.transitioned());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isWorldActive());
        Assert.assertFalse(ClientConnectionLifecycle.isWorldCurrentAndActive(oldWorld));

        ClientConnectionLifecycle.Token newWorld = ClientConnectionLifecycle.bindWorld(worldB);
        Assert.assertTrue(newWorld.isWorldActive());
        Assert.assertEquals(oldWorld.connectionGeneration(), newWorld.connectionGeneration());
    }

    /**
     * packet callback(A) 在 B connect 后，即使全局 current=B 也丢弃。
     */
    @Test
    public void packetCapturedForAAfterBConnectIsDropped() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token tokenA =
                ClientConnectionLifecycle.captureForConnection(handlerA);
        Assert.assertNotNull(tokenA);

        ClientConnectionLifecycle.connect(handlerB);
        Assert.assertNull(ClientConnectionLifecycle.captureForConnection(handlerA));
        Assert.assertNotNull(ClientConnectionLifecycle.captureForConnection(handlerB));

        final AtomicInteger pubs = new AtomicInteger(0);
        Assert.assertFalse(ClientConnectionLifecycle.publishIfConnectionCurrentAndActive(tokenA, new Runnable() {
            @Override
            public void run() {
                pubs.incrementAndGet();
            }
        }));
        Assert.assertEquals(0, pubs.get());
    }

    @Test
    public void configSyncOldConnectionPacketDoesNotPublish() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token tokenA =
                ClientConnectionLifecycle.captureForConnection(handlerA);
        Harness harness = dispatchConfig(12, 345, 67, tokenA);
        Assert.assertTrue(harness.accepted);

        ClientConnectionLifecycle.connect(handlerB);
        harness.queued.run();
        Assert.assertEquals(0, harness.publications);
        Assert.assertArrayEquals(new int[] {91, 92, 93}, harness.state);
    }

    @Test
    public void configSyncNewConnectionPublishes() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token token =
                ClientConnectionLifecycle.captureForConnection(handlerA);
        Harness harness = dispatchConfig(12, 345, 67, token);
        harness.queued.run();
        Assert.assertEquals(1, harness.publications);
        Assert.assertArrayEquals(new int[] {12, 345, 67}, harness.state);
    }

    @Test
    public void phaseAndPreviewRequireWorldActive() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token noWorld =
                ClientConnectionLifecycle.captureForConnection(handlerA);
        final AtomicInteger actions = new AtomicInteger(0);
        Assert.assertFalse(ClientConnectionLifecycle.runIfWorldCurrentAndActive(noWorld, new Runnable() {
            @Override
            public void run() {
                actions.incrementAndGet();
            }
        }));

        ClientConnectionLifecycle.Token withWorld = ClientConnectionLifecycle.bindWorld(worldA);
        Assert.assertTrue(ClientConnectionLifecycle.runIfWorldCurrentAndActive(withWorld, new Runnable() {
            @Override
            public void run() {
                actions.incrementAndGet();
            }
        }));
        Assert.assertEquals(1, actions.get());
    }

    @Test
    public void phasePacketOldWorldDroppedAfterWorldReplace() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token oldWorld = ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.bindWorld(worldB);
        final AtomicInteger pubs = new AtomicInteger(0);
        Assert.assertFalse(ClientConnectionLifecycle.runIfWorldCurrentAndActive(oldWorld, new Runnable() {
            @Override
            public void run() {
                pubs.incrementAndGet();
            }
        }));
        Assert.assertEquals(0, pubs.get());
    }

    @Test
    public void previewPacketOldConnectionDropped() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token tokenA = ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.connect(handlerB);
        ClientConnectionLifecycle.bindWorld(worldB);
        final AtomicInteger apps = new AtomicInteger(0);
        Assert.assertFalse(ClientConnectionLifecycle.runIfWorldCurrentAndActive(tokenA, new Runnable() {
            @Override
            public void run() {
                apps.incrementAndGet();
            }
        }));
        Assert.assertEquals(0, apps.get());
    }

    @Test
    public void inactiveTokenDoesNotEnqueueConfig() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.disconnect(handlerA);
        ClientConnectionLifecycle.Token inactive = ClientConnectionLifecycle.capture();
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
        Assert.assertTrue(accepted);
        Assert.assertFalse(harness.enqueued);
        Assert.assertEquals(0, harness.publications);
    }

    @Test
    public void clearPendingPreventsOldPhaseRewrite() {
        ChainEventBus bus = new ChainEventBus();
        final AtomicInteger deliveries = new AtomicInteger(0);
        bus.subscribe(ChainPhaseChanged.class, new club.heiqi.qz_miner.chain.eventbus.EventSubscriber<ChainPhaseChanged>() {
            @Override
            public void onEvent(ChainPhaseChanged event) {
                deliveries.incrementAndGet();
            }
        });
        bus.publish(new ChainPhaseChanged(
                null, 1, ChainPhase.IDLE, ChainPhase.RUNNING, 10L, System.nanoTime()));
        Assert.assertEquals(1, bus.pendingCount());
        bus.clearPending();
        Assert.assertEquals(0, bus.pendingCount());
        Assert.assertEquals(0, bus.drain());
        Assert.assertEquals(0, deliveries.get());
    }

    @Test
    public void actionExceptionDoesNotHoldLifecycleLock() throws Exception {
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.connect(handlerA);
        try {
            ClientConnectionLifecycle.publishIfConnectionCurrentAndActive(token, new Runnable() {
                @Override
                public void run() {
                    throw new RuntimeException("boom");
                }
            });
            Assert.fail("expected exception");
        } catch (RuntimeException expected) {
            Assert.assertEquals("boom", expected.getMessage());
        }
        // 异常后仍可推进
        ClientConnectionLifecycle.DisconnectResult disc =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertTrue(disc.transitioned());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());
    }

    @Test
    public void publicationHoldingGateBlocksDisconnectUntilComplete() throws Exception {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.connect(handlerA);
        final List<String> order = new ArrayList<String>();
        final Object orderLock = new Object();
        final CountDownLatch insidePublication = new CountDownLatch(1);
        final CountDownLatch releasePublication = new CountDownLatch(1);
        final CountDownLatch disconnectDone = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        Thread pubThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean ok = ClientConnectionLifecycle.publishIfConnectionCurrentAndActive(
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
                    ClientConnectionLifecycle.disconnect(handlerA);
                    record(order, orderLock, "disconnect");
                    disconnectDone.countDown();
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "disconnect-waiter");
        disconnectThread.start();

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
    }

    @Test
    public void oldPublicationCannotWriteAfterDisconnectReconnect() throws Exception {
        final ClientConnectionLifecycle.Token oldToken = ClientConnectionLifecycle.connect(handlerA);
        final AtomicInteger publications = new AtomicInteger(0);
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch reconnectDone = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AtomicBoolean published = new AtomicBoolean(false);

        Thread oldPub = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ready.countDown();
                    Assert.assertTrue(reconnectDone.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                    boolean ok = ClientConnectionLifecycle.publishIfConnectionCurrentAndActive(
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
        Assert.assertTrue(ready.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        ClientConnectionLifecycle.disconnect(handlerA);
        ClientConnectionLifecycle.Token newToken = ClientConnectionLifecycle.connect(handlerB);
        reconnectDone.countDown();
        oldPub.join(LATCH_TIMEOUT_MS);
        Assert.assertFalse(oldPub.isAlive());
        rethrow(error.get());
        Assert.assertFalse(published.get());
        Assert.assertEquals(0, publications.get());
        Assert.assertTrue(ClientConnectionLifecycle.isConnectionCurrentAndActive(newToken));
    }

    @Test
    public void captureForConnectionRequiresMatchingIdentity() {
        ClientConnectionLifecycle.connect(handlerA);
        Assert.assertNotNull(ClientConnectionLifecycle.captureForConnection(handlerA));
        Assert.assertNull(ClientConnectionLifecycle.captureForConnection(handlerB));
        Assert.assertNull(ClientConnectionLifecycle.captureForConnection(null));
    }

    @Test
    public void worldUnbindCleanupNoOpAfterNewWorld() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.WorldUnbindResult unbind =
                ClientConnectionLifecycle.unbindWorld(worldA);
        Assert.assertTrue(unbind.transitioned());
        ClientConnectionLifecycle.bindWorld(worldB);
        final AtomicInteger cleanups = new AtomicInteger(0);
        Assert.assertFalse(ClientConnectionLifecycle.runIfWorldUnbindCurrent(unbind.token(), new Runnable() {
            @Override
            public void run() {
                cleanups.incrementAndGet();
            }
        }));
        Assert.assertEquals(0, cleanups.get());
    }

    @Test
    public void worldUnbindCleanupRunsWhenStillCurrent() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.WorldUnbindResult unbind =
                ClientConnectionLifecycle.unbindWorld(worldA);
        final AtomicInteger cleanups = new AtomicInteger(0);
        Assert.assertTrue(ClientConnectionLifecycle.runIfWorldUnbindCurrent(unbind.token(), new Runnable() {
            @Override
            public void run() {
                cleanups.incrementAndGet();
            }
        }));
        Assert.assertEquals(1, cleanups.get());
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

    private static Harness dispatchConfig(
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
                        && ((ClientConnectionLifecycle.Token) token).isConnectionActive();
            }

            @Override
            public boolean isCurrentAndActive(Object token) {
                if (!(token instanceof ClientConnectionLifecycle.Token)) {
                    return false;
                }
                return ClientConnectionLifecycle.isConnectionCurrentAndActive(
                        (ClientConnectionLifecycle.Token) token);
            }

            @Override
            public boolean publishIfCurrentAndActive(Object token, Runnable publication) {
                if (!(token instanceof ClientConnectionLifecycle.Token)) {
                    return false;
                }
                return ClientConnectionLifecycle.publishIfConnectionCurrentAndActive(
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
