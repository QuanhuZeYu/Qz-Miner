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
 *
 * <p>接管收敛：连接 B / world B 在主线程 gate 内统一 cleanup；
 * 旧 A cleanup 在 B 建立后 no-op；重复 connect/load 不重复 init/清理。</p>
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
        ClientConnectionLifecycle.TransitionResult r1 = ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.TransitionResult r2 = ClientConnectionLifecycle.connect(handlerA);
        Assert.assertTrue(r1.transitioned());
        Assert.assertFalse(r1.replacedPreviousLifecycle());
        Assert.assertFalse(r2.transitioned());
        Assert.assertFalse(r2.replacedPreviousLifecycle());
        Assert.assertSame(r1.token(), r2.token());
        Assert.assertTrue(r1.token().isConnectionActive());
        Assert.assertEquals(r1.token().connectionGeneration(), r2.token().connectionGeneration());
    }

    @Test
    public void initThenReadyClaimsReplayOnce() {
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.connect(handlerA).token();
        Assert.assertTrue(ClientConnectionLifecycle.markConnectionInitComplete(token));
        Assert.assertFalse(ClientConnectionLifecycle.claimReadyReplay(token));
        Assert.assertTrue(ClientConnectionLifecycle.markServerReady(token));
        Assert.assertTrue(ClientConnectionLifecycle.claimReadyReplay(token));
        Assert.assertFalse(ClientConnectionLifecycle.claimReadyReplay(token));
    }

    @Test
    public void readyThenInitClaimsReplayOnce() {
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.connect(handlerA).token();
        Assert.assertTrue(ClientConnectionLifecycle.markServerReady(token));
        Assert.assertFalse(ClientConnectionLifecycle.claimReadyReplay(token));
        Assert.assertTrue(ClientConnectionLifecycle.markConnectionInitComplete(token));
        Assert.assertTrue(ClientConnectionLifecycle.claimReadyReplay(token));
        Assert.assertFalse(ClientConnectionLifecycle.claimReadyReplay(token));
    }

    @Test
    public void oldConnectionReadinessCannotClaimAfterReconnect() {
        ClientConnectionLifecycle.Token tokenA = ClientConnectionLifecycle.connect(handlerA).token();
        Assert.assertTrue(ClientConnectionLifecycle.markConnectionInitComplete(tokenA));
        ClientConnectionLifecycle.Token tokenB = ClientConnectionLifecycle.connect(handlerB).token();
        Assert.assertFalse(ClientConnectionLifecycle.markServerReady(tokenA));
        Assert.assertFalse(ClientConnectionLifecycle.claimReadyReplay(tokenA));
        Assert.assertTrue(ClientConnectionLifecycle.markServerReady(tokenB));
        Assert.assertTrue(ClientConnectionLifecycle.markConnectionInitComplete(tokenB));
        Assert.assertTrue(ClientConnectionLifecycle.claimReadyReplay(tokenB));
    }

    @Test
    public void differentHandlerConnectCreatesNewActiveToken() {
        ClientConnectionLifecycle.TransitionResult ra = ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.TransitionResult rb = ClientConnectionLifecycle.connect(handlerB);
        Assert.assertTrue(ra.transitioned());
        Assert.assertFalse(ra.replacedPreviousLifecycle());
        Assert.assertTrue(rb.transitioned());
        Assert.assertTrue(rb.replacedPreviousLifecycle());
        ClientConnectionLifecycle.Token a = ra.token();
        ClientConnectionLifecycle.Token b = rb.token();
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
        ClientConnectionLifecycle.Token tokenA = ClientConnectionLifecycle.connect(handlerA).token();
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

        ClientConnectionLifecycle.Token tokenB = ClientConnectionLifecycle.connect(handlerB).token();
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
        ClientConnectionLifecycle.Token conn = ClientConnectionLifecycle.connect(handlerA).token();
        ClientConnectionLifecycle.TransitionResult bind = ClientConnectionLifecycle.bindWorld(worldA);
        Assert.assertTrue(bind.transitioned());
        Assert.assertFalse(bind.replacedPreviousLifecycle());
        ClientConnectionLifecycle.Token afterBind = bind.token();
        Assert.assertEquals(conn.connectionGeneration(), afterBind.connectionGeneration());
        Assert.assertTrue(afterBind.isWorldActive());
        Assert.assertTrue(ClientConnectionLifecycle.isConnectionCurrentAndActive(conn));
        Assert.assertTrue(ClientConnectionLifecycle.isConnectionCurrentAndActive(afterBind));
    }

    @Test
    public void worldReplaceAdvancesWorldGenerationKeepsConnection() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.Token wA = ClientConnectionLifecycle.bindWorld(worldA).token();
        ClientConnectionLifecycle.TransitionResult replace = ClientConnectionLifecycle.bindWorld(worldB);
        Assert.assertTrue(replace.transitioned());
        Assert.assertTrue(replace.replacedPreviousLifecycle());
        ClientConnectionLifecycle.Token wB = replace.token();
        Assert.assertEquals(wA.connectionGeneration(), wB.connectionGeneration());
        Assert.assertTrue(wA.worldGeneration() != wB.worldGeneration());
        Assert.assertFalse(ClientConnectionLifecycle.isWorldCurrentAndActive(wA));
        Assert.assertTrue(ClientConnectionLifecycle.isWorldCurrentAndActive(wB));
    }

    @Test
    public void sameWorldBindIsNoOp() {
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.TransitionResult first = ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.TransitionResult repeat = ClientConnectionLifecycle.bindWorld(worldA);
        Assert.assertTrue(first.transitioned());
        Assert.assertFalse(repeat.transitioned());
        Assert.assertFalse(repeat.replacedPreviousLifecycle());
        Assert.assertSame(first.token(), repeat.token());
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
        ClientConnectionLifecycle.Token oldWorld = ClientConnectionLifecycle.bindWorld(worldA).token();
        ClientConnectionLifecycle.WorldUnbindResult unbind =
                ClientConnectionLifecycle.unbindWorld(worldA);
        Assert.assertTrue(unbind.transitioned());
        Assert.assertTrue(ClientConnectionLifecycle.capture().isConnectionActive());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isWorldActive());
        Assert.assertFalse(ClientConnectionLifecycle.isWorldCurrentAndActive(oldWorld));

        ClientConnectionLifecycle.Token newWorld = ClientConnectionLifecycle.bindWorld(worldB).token();
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

        ClientConnectionLifecycle.Token withWorld = ClientConnectionLifecycle.bindWorld(worldA).token();
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
        ClientConnectionLifecycle.Token oldWorld = ClientConnectionLifecycle.bindWorld(worldA).token();
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
        ClientConnectionLifecycle.Token tokenA = ClientConnectionLifecycle.bindWorld(worldA).token();
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
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.connect(handlerA).token();
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
        ClientConnectionLifecycle.DisconnectResult disc =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertTrue(disc.transitioned());
        Assert.assertFalse(ClientConnectionLifecycle.capture().isConnectionActive());
    }

    @Test
    public void publicationHoldingGateBlocksDisconnectUntilComplete() throws Exception {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.connect(handlerA).token();
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
        final ClientConnectionLifecycle.Token oldToken = ClientConnectionLifecycle.connect(handlerA).token();
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
        ClientConnectionLifecycle.Token newToken = ClientConnectionLifecycle.connect(handlerB).token();
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

    // ---------- Listener 调用链：接管清理 / 重复事件 ----------

    /**
     * A 资源已建立 → connect B 先于 A disconnect → 运行 B init：
     * 统一 takeover cleanup 收敛 A 残留，并完成 B reset；随后 A cleanup no-op 且不清 B 新状态。
     */
    @Test
    public void connectionBTakeoverCleansAResourcesBeforeADisconnectCleanup() {
        ClientConnectionListener listener = new ClientConnectionListener();
        final List<String> cleanups = new ArrayList<String>();
        final AtomicInteger inits = new AtomicInteger(0);
        final AtomicInteger resourceMarker = new AtomicInteger(1);
        final AtomicInteger configProjection = new AtomicInteger(99);

        listener.cleanupHookForTests = new java.util.function.Consumer<String>() {
            @Override
            public void accept(String reason) {
                cleanups.add(reason);
                resourceMarker.set(0);
            }
        };
        listener.initHookForTests = new Runnable() {
            @Override
            public void run() {
                inits.incrementAndGet();
                configProjection.set(0);
            }
        };

        // 序一：A 建立资源 → B connect 先于 A disconnect → B init 接管清理
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.bindWorld(worldA);
        Assert.assertEquals(1, resourceMarker.get());

        ClientConnectionLifecycle.TransitionResult connectB =
                ClientConnectionLifecycle.connect(handlerB);
        Assert.assertTrue(connectB.transitioned());
        Assert.assertTrue(connectB.replacedPreviousLifecycle());
        Assert.assertTrue(ClientConnectionListener.shouldScheduleConnectionInit(connectB));

        Assert.assertTrue(listener.runConnectionTakeoverAndInit(connectB.token()));
        Assert.assertEquals(1, cleanups.size());
        Assert.assertEquals("connection-takeover", cleanups.get(0));
        Assert.assertEquals(0, resourceMarker.get());
        Assert.assertEquals(1, inits.get());
        Assert.assertEquals(0, configProjection.get());

        ClientConnectionLifecycle.DisconnectResult lateA =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertFalse(lateA.transitioned());

        // 序二：A disconnect 排队 cleanup → B connect → B init 接管；A cleanup no-op 不清 B
        ClientConnectionLifecycle.resetForTests();
        cleanups.clear();
        resourceMarker.set(1);
        configProjection.set(42);
        inits.set(0);

        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.DisconnectResult discA =
                ClientConnectionLifecycle.disconnect(handlerA);
        Assert.assertTrue(discA.transitioned());
        final ClientConnectionLifecycle.Token cleanupA = discA.token();

        ClientConnectionLifecycle.TransitionResult bAgain =
                ClientConnectionLifecycle.connect(handlerB);
        Assert.assertTrue(bAgain.transitioned());
        Assert.assertTrue(listener.runConnectionTakeoverAndInit(bAgain.token()));
        Assert.assertEquals(1, cleanups.size());
        Assert.assertEquals("connection-takeover", cleanups.get(0));
        Assert.assertEquals(0, resourceMarker.get());
        Assert.assertEquals(1, inits.get());

        resourceMarker.set(7);
        configProjection.set(3);
        boolean aCleaned = ClientConnectionLifecycle.runIfInactiveDisconnectCurrent(cleanupA, new Runnable() {
            @Override
            public void run() {
                cleanups.add("a-late-cleanup");
                resourceMarker.set(0);
                configProjection.set(-1);
            }
        });
        Assert.assertFalse(aCleaned);
        Assert.assertEquals(1, cleanups.size());
        Assert.assertEquals(7, resourceMarker.get());
        Assert.assertEquals(3, configProjection.get());
    }

    /**
     * world A 资源 → Load B 先于 Unload A → B takeover 清理；
     * 随后 A unload cleanup no-op 且不清 B 新状态。
     */
    @Test
    public void worldBTakeoverCleansAResourcesBeforeAUnloadCleanup() {
        ClientConnectionListener listener = new ClientConnectionListener();
        final List<String> cleanups = new ArrayList<String>();
        final AtomicInteger resourceMarker = new AtomicInteger(1);

        listener.cleanupHookForTests = new java.util.function.Consumer<String>() {
            @Override
            public void accept(String reason) {
                cleanups.add(reason);
                resourceMarker.set(0);
            }
        };

        // 序一：A active → Load B 直接替换 → takeover cleanup；迟到 Unload A no-op
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.TransitionResult bindA =
                ClientConnectionLifecycle.bindWorld(worldA);
        Assert.assertTrue(bindA.transitioned());
        Assert.assertFalse(bindA.replacedPreviousLifecycle());
        Assert.assertFalse(ClientConnectionListener.shouldScheduleWorldTakeoverCleanup(bindA));

        ClientConnectionLifecycle.TransitionResult bindB =
                ClientConnectionLifecycle.bindWorld(worldB);
        Assert.assertTrue(bindB.transitioned());
        Assert.assertTrue(bindB.replacedPreviousLifecycle());
        Assert.assertTrue(ClientConnectionListener.shouldScheduleWorldTakeoverCleanup(bindB));

        Assert.assertTrue(listener.runWorldTakeoverCleanup(bindB.token()));
        Assert.assertEquals(1, cleanups.size());
        Assert.assertEquals("world-takeover", cleanups.get(0));
        Assert.assertEquals(0, resourceMarker.get());

        ClientConnectionLifecycle.WorldUnbindResult lateUnloadA =
                ClientConnectionLifecycle.unbindWorld(worldA);
        Assert.assertFalse(lateUnloadA.transitioned());

        // 序二：A unbind 排队 cleanup → bind B → A cleanup no-op 不清 B
        ClientConnectionLifecycle.resetForTests();
        cleanups.clear();
        resourceMarker.set(1);
        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.bindWorld(worldA);
        ClientConnectionLifecycle.WorldUnbindResult unbindA =
                ClientConnectionLifecycle.unbindWorld(worldA);
        Assert.assertTrue(unbindA.transitioned());
        final ClientConnectionLifecycle.Token cleanupA = unbindA.token();

        ClientConnectionLifecycle.TransitionResult bAfterUnbind =
                ClientConnectionLifecycle.bindWorld(worldB);
        Assert.assertTrue(bAfterUnbind.transitioned());
        // A 已 unbind，B 是空槽首次 bind：replaced=false，不调度 world-takeover
        Assert.assertFalse(bAfterUnbind.replacedPreviousLifecycle());
        Assert.assertFalse(ClientConnectionListener.shouldScheduleWorldTakeoverCleanup(bAfterUnbind));

        resourceMarker.set(9);
        boolean aCleaned = ClientConnectionLifecycle.runIfWorldUnbindCurrent(cleanupA, new Runnable() {
            @Override
            public void run() {
                cleanups.add("a-late-world-cleanup");
                resourceMarker.set(0);
            }
        });
        Assert.assertFalse(aCleaned);
        Assert.assertEquals(0, cleanups.size());
        Assert.assertEquals(9, resourceMarker.get());
    }

    /**
     * 同 handler 重复 connect：transitioned=false，不调度 init；
     * 有效 S2C 后投影不被回退、不重复 C2S/init。
     */
    @Test
    public void repeatConnectDoesNotRescheduleInitOrRollbackProjection() {
        ClientConnectionListener listener = new ClientConnectionListener();
        final AtomicInteger inits = new AtomicInteger(0);
        final AtomicInteger cleanups = new AtomicInteger(0);
        final AtomicInteger projection = new AtomicInteger(-1);

        listener.cleanupHookForTests = new java.util.function.Consumer<String>() {
            @Override
            public void accept(String reason) {
                cleanups.incrementAndGet();
            }
        };
        listener.initHookForTests = new Runnable() {
            @Override
            public void run() {
                inits.incrementAndGet();
                projection.set(0);
            }
        };

        ClientConnectionLifecycle.TransitionResult first =
                ClientConnectionLifecycle.connect(handlerA);
        Assert.assertTrue(ClientConnectionListener.shouldScheduleConnectionInit(first));
        Assert.assertTrue(listener.runConnectionTakeoverAndInit(first.token()));
        Assert.assertEquals(1, inits.get());
        Assert.assertEquals(1, cleanups.get());
        Assert.assertEquals(0, projection.get());

        projection.set(42);

        ClientConnectionLifecycle.TransitionResult repeat =
                ClientConnectionLifecycle.connect(handlerA);
        Assert.assertFalse(repeat.transitioned());
        Assert.assertFalse(ClientConnectionListener.shouldScheduleConnectionInit(repeat));
        Assert.assertEquals(1, inits.get());
        Assert.assertEquals(42, projection.get());
        Assert.assertEquals(1, cleanups.get());
    }

    /**
     * 重复 world load：transitioned=false，不重复 cleanup。
     */
    @Test
    public void repeatWorldLoadDoesNotRescheduleCleanup() {
        ClientConnectionListener listener = new ClientConnectionListener();
        final AtomicInteger cleanups = new AtomicInteger(0);
        listener.cleanupHookForTests = new java.util.function.Consumer<String>() {
            @Override
            public void accept(String reason) {
                cleanups.incrementAndGet();
            }
        };

        ClientConnectionLifecycle.connect(handlerA);
        ClientConnectionLifecycle.TransitionResult first =
                ClientConnectionLifecycle.bindWorld(worldA);
        Assert.assertFalse(ClientConnectionListener.shouldScheduleWorldTakeoverCleanup(first));

        ClientConnectionLifecycle.TransitionResult replace =
                ClientConnectionLifecycle.bindWorld(worldB);
        Assert.assertTrue(ClientConnectionListener.shouldScheduleWorldTakeoverCleanup(replace));
        Assert.assertTrue(listener.runWorldTakeoverCleanup(replace.token()));
        Assert.assertEquals(1, cleanups.get());

        ClientConnectionLifecycle.TransitionResult repeat =
                ClientConnectionLifecycle.bindWorld(worldB);
        Assert.assertFalse(repeat.transitioned());
        Assert.assertFalse(ClientConnectionListener.shouldScheduleWorldTakeoverCleanup(repeat));
        Assert.assertEquals(1, cleanups.get());
    }

    @Test
    public void nullHandlerConnectDoesNotTransition() {
        ClientConnectionLifecycle.TransitionResult r = ClientConnectionLifecycle.connect(null);
        Assert.assertFalse(r.transitioned());
        Assert.assertFalse(r.replacedPreviousLifecycle());
        Assert.assertFalse(ClientConnectionListener.shouldScheduleConnectionInit(r));
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
