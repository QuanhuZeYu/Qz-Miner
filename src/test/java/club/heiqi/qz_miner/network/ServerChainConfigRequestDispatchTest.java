package club.heiqi.qz_miner.network;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.ConfigCaps;
import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.EndpointKey;
import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.KeyedDispatcher;
import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.PlayerLookup;
import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.StateWriter;
import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.AcceptedStateWriter;
import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.Acknowledgement;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.thread.KeyedLatestTaskLane;

/** C2S 配置请求 keyed 调度适配的纯 JVM 测试。 */
public class ServerChainConfigRequestDispatchTest {

    private KeyedLatestTaskLane<Object> lane;
    private final ConcurrentHashMap<UUID, Object> online = new ConcurrentHashMap<UUID, Object>();
    private final AtomicReference<int[]> written = new AtomicReference<int[]>();
    private final AtomicInteger writeCount = new AtomicInteger();

    @Before
    public void setUp() {
        ServerChainConfigRequestDispatch.resetDiagnosticsForTests();
        lane = new KeyedLatestTaskLane<Object>(256, 64);
        lane.start();
        online.clear();
        written.set(null);
        writeCount.set(0);
    }

    @Test
    public void sameEndpointTenThousandSubmitsKeepSingleSlotWithFinalValue() {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        online.put(uuid, endpoint);
        for (int i = 1; i <= 10000; i++) {
            int radius = 1 + (i % 100);
            int maxBlocks = 1 + (i % 200);
            Assert.assertTrue(submit(uuid, endpoint, radius, maxBlocks));
        }
        Assert.assertEquals(1, lane.pendingCount());
        lane.drain();
        Assert.assertArrayEquals(new int[] {1 + (10000 % 100), 1 + (10000 % 200)}, written.get());
        Assert.assertEquals(1, writeCount.get());
    }

    @Test
    public void sixtyFiveEndpointsFirstDrainSixtyFourThenOne() {
        for (int i = 0; i < 65; i++) {
            UUID uuid = UUID.randomUUID();
            Object endpoint = new Object();
            online.put(uuid, endpoint);
            Assert.assertTrue(submit(uuid, endpoint, 10 + i, 20 + i));
        }
        Assert.assertEquals(65, lane.pendingCount());
        Assert.assertEquals(64, lane.drain());
        Assert.assertEquals(1, lane.pendingCount());
        Assert.assertEquals(1, lane.drain());
        Assert.assertEquals(65, writeCount.get());
        Assert.assertEquals(0, lane.pendingCount());
    }

    @Test
    public void capacityFullAllowsExistingKeyUpdateAndRejectsNewKey() {
        final KeyedLatestTaskLane<Object> small = new KeyedLatestTaskLane<Object>(1, 64);
        small.start();
        KeyedDispatcher dispatcher = new KeyedDispatcher() {
            @Override
            public boolean tryRunLatest(Object key, Runnable task) {
                return small.submit(key, task);
            }
        };
        UUID first = UUID.randomUUID();
        Object firstEndpoint = new Object();
        online.put(first, firstEndpoint);
        Assert.assertTrue(submit(first, firstEndpoint, 3, 4, dispatcher));
        UUID second = UUID.randomUUID();
        Object secondEndpoint = new Object();
        online.put(second, secondEndpoint);
        Assert.assertFalse(submit(second, secondEndpoint, 5, 6, dispatcher));
        Assert.assertTrue(submit(first, firstEndpoint, 30, 40, dispatcher));
        small.drain();
        Assert.assertArrayEquals(new int[] {30, 40}, written.get());
        Assert.assertEquals(1, writeCount.get());
    }

    @Test
    public void oldAndNewEndpointIdentityAreIsolated() {
        UUID uuid = UUID.randomUUID();
        Object oldEndpoint = new Object();
        Object newEndpoint = new Object();
        online.put(uuid, newEndpoint);
        Assert.assertTrue(submit(uuid, oldEndpoint, 11, 12));
        Assert.assertTrue(submit(uuid, newEndpoint, 21, 22));
        Assert.assertEquals(2, lane.pendingCount());
        lane.drain();
        Assert.assertArrayEquals(new int[] {21, 22}, written.get());
        Assert.assertEquals(1, writeCount.get());
    }

    @Test
    public void stopAndRestartInvalidatesOldLaneSubmissions() {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        online.put(uuid, endpoint);
        Assert.assertTrue(submit(uuid, endpoint, 7, 8));
        lane.stop();
        Assert.assertFalse(submit(uuid, endpoint, 9, 10));
        Assert.assertEquals(0, lane.drain());
        Assert.assertNull(written.get());

        lane.start();
        Assert.assertTrue(submit(uuid, endpoint, 13, 14));
        lane.drain();
        Assert.assertArrayEquals(new int[] {13, 14}, written.get());
    }

    @Test
    public void invalidRequestDoesNotWriteState() {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        online.put(uuid, endpoint);
        Assert.assertTrue(submit(uuid, endpoint, 0, 10));
        lane.drain();
        Assert.assertNull(written.get());
        Assert.assertEquals(0, writeCount.get());
    }

    @Test
    public void extendedAcceptedConfigWritesAllFieldsBeforeSingleAck() {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        online.put(uuid, endpoint);
        final AtomicReference<Object[]> accepted = new AtomicReference<Object[]>();
        final AtomicInteger acknowledgements = new AtomicInteger();

        Assert.assertTrue(ServerChainConfigRequestDispatch.submit(
                uuid, endpoint, 200, 5000,
                PacketChainConfigRequest.PROTOCOL_VERSION, TunnelDirectionSource.HIT_FACE.wireCode(), true,
                new KeyedDispatcher() {
                    @Override public boolean tryRunLatest(Object key, Runnable task) { return lane.submit(key, task); }
                },
                new PlayerLookup() {
                    @Override public Object getPlayer(UUID playerId) { return online.get(playerId); }
                },
                new ConfigCaps() {
                    @Override public int chainRadius() { return 64; }
                    @Override public int chainMaxBlocks() { return 4096; }
                },
                new AcceptedStateWriter() {
                    @Override public void write(UUID playerId, int radius, int maxBlocks,
                            TunnelDirectionSource source) {
                        accepted.set(new Object[] {radius, maxBlocks, source});
                        Assert.assertEquals(0, acknowledgements.get());
                    }
                },
                new Acknowledgement() {
                    @Override public void acknowledge(UUID playerId) { acknowledgements.incrementAndGet(); }
                }));
        lane.drain();

        Assert.assertArrayEquals(new Object[] {64, 4096, TunnelDirectionSource.HIT_FACE}, accepted.get());
        Assert.assertEquals(1, acknowledgements.get());
    }

    @Test
    public void unknownExtendedDirectionDoesNotPartiallyWriteOrAck() {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        online.put(uuid, endpoint);
        final AtomicInteger sideEffects = new AtomicInteger();
        Assert.assertTrue(ServerChainConfigRequestDispatch.submit(
                uuid, endpoint, 12, 300, PacketChainConfigRequest.PROTOCOL_VERSION, 99, true,
                new KeyedDispatcher() {
                    @Override public boolean tryRunLatest(Object key, Runnable task) { return lane.submit(key, task); }
                },
                new PlayerLookup() {
                    @Override public Object getPlayer(UUID playerId) { return online.get(playerId); }
                },
                new ConfigCaps() {
                    @Override public int chainRadius() { return 64; }
                    @Override public int chainMaxBlocks() { return 4096; }
                },
                new AcceptedStateWriter() {
                    @Override public void write(UUID playerId, int radius, int maxBlocks,
                            TunnelDirectionSource source) { sideEffects.incrementAndGet(); }
                },
                new Acknowledgement() {
                    @Override public void acknowledge(UUID playerId) { sideEffects.incrementAndGet(); }
                }));
        lane.drain();
        Assert.assertEquals(0, sideEffects.get());
    }

    /**
     * 随机并发后串行最终 accepted 提交必须可达；执行值须属于 accepted 集合。
     */
    @Test
    public void concurrentDrainUpdateEndsWithLastAcceptedValue() throws Exception {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        online.put(uuid, endpoint);
        final AtomicInteger next = new AtomicInteger();
        final ConcurrentLinkedQueue<String> acceptedPairs = new ConcurrentLinkedQueue<String>();
        List<Thread> producers = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 250; i++) {
                        int value = next.incrementAndGet();
                        int radius = 1 + (value % 100);
                        int maxBlocks = 1 + (value % 200);
                        if (submit(uuid, endpoint, radius, maxBlocks)) {
                            acceptedPairs.add(radius + ":" + maxBlocks);
                        }
                    }
                }
            }, "c2s-producer-" + t);
            producers.add(thread);
            thread.start();
        }
        for (Thread thread : producers) {
            thread.join(5000L);
            Assert.assertFalse(thread.isAlive());
        }
        Assert.assertFalse(acceptedPairs.isEmpty());

        int lastValue = next.incrementAndGet();
        int lastRadius = 1 + (lastValue % 100);
        int lastMax = 1 + (lastValue % 200);
        Assert.assertTrue(submit(uuid, endpoint, lastRadius, lastMax));
        acceptedPairs.add(lastRadius + ":" + lastMax);

        while (lane.pendingCount() > 0) {
            lane.drain();
        }
        Assert.assertNotNull(written.get());
        Assert.assertArrayEquals(
                "last linearized submit after production stop must be the final written value",
                new int[] {lastRadius, lastMax},
                written.get());
        Assert.assertTrue(acceptedPairs.contains(lastRadius + ":" + lastMax));
        Assert.assertTrue(writeCount.get() >= 1);
    }

    /**
     * 同 UUID 不同实例永不 equal / 不合槽（不依赖真制造 hash 碰撞）。
     */
    @Test
    public void sameUuidDifferentInstancesNeverEqualOrShareSlot() {
        UUID uuid = UUID.randomUUID();
        Object first = new Object();
        Object second = new Object();
        EndpointKey a = new EndpointKey(uuid, first);
        EndpointKey b = new EndpointKey(uuid, second);
        Assert.assertFalse(a.equals(b));
        Assert.assertFalse(b.equals(a));

        online.put(uuid, second);
        Assert.assertTrue(submit(uuid, first, 3, 4));
        Assert.assertTrue(submit(uuid, second, 5, 6));
        Assert.assertEquals(2, lane.pendingCount());
        lane.drain();
        Assert.assertArrayEquals(new int[] {5, 6}, written.get());
        Assert.assertEquals(1, writeCount.get());
    }

    /**
     * 弱 referent GC 后不得错误等于其他实例；过期键可被 purge 释放容量。
     */
    @Test
    public void weakReferentStaleKeyDoesNotEqualAndCanBePurged() {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        EndpointKey key = new EndpointKey(uuid, endpoint);
        Assert.assertFalse(key.isStale());
        Assert.assertSame(endpoint, key.endpointOrNull());

        EndpointKey sameLive = new EndpointKey(uuid, endpoint);
        Assert.assertEquals(key, sameLive);
        Assert.assertEquals(key.hashCode(), sameLive.hashCode());

        final KeyedLatestTaskLane<Object> small = new KeyedLatestTaskLane<Object>(1, 64);
        small.start();
        Assert.assertTrue(small.submit(key, new Runnable() {
            @Override
            public void run() {
                // no-op stale-pending
            }
        }));
        Assert.assertEquals(1, small.pendingCount());

        WeakReference<Object> probe = new WeakReference<Object>(endpoint);
        endpoint = null;
        forceGc(probe);
        Assert.assertNull("endpoint should be GC'd for stale test", probe.get());
        Assert.assertTrue(key.isStale());

        Object replacement = new Object();
        EndpointKey afterGc = new EndpointKey(uuid, replacement);
        Assert.assertFalse(key.equals(afterGc));
        Assert.assertFalse(afterGc.equals(key));

        Assert.assertEquals(1, small.purgeStaleKeys());
        Assert.assertEquals(0, small.pendingCount());
        Assert.assertTrue(small.submit(afterGc, new Runnable() {
            @Override
            public void run() {
            }
        }));
        Assert.assertEquals(1, small.pendingCount());
    }

    @Test
    public void endpointKeyEqualsUsesUuidAndObjectIdentity() {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        EndpointKey a = new EndpointKey(uuid, endpoint);
        EndpointKey b = new EndpointKey(uuid, endpoint);
        EndpointKey c = new EndpointKey(uuid, new Object());
        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
        Assert.assertFalse(a.equals(c));
    }

    private boolean submit(UUID uuid, Object endpoint, int radius, int maxBlocks) {
        return submit(uuid, endpoint, radius, maxBlocks, new KeyedDispatcher() {
            @Override
            public boolean tryRunLatest(Object key, Runnable task) {
                return lane.submit(key, task);
            }
        });
    }

    private boolean submit(
            UUID uuid,
            Object endpoint,
            int radius,
            int maxBlocks,
            KeyedDispatcher dispatcher) {
        return ServerChainConfigRequestDispatch.submit(
                uuid,
                endpoint,
                radius,
                maxBlocks,
                dispatcher,
                new PlayerLookup() {
                    @Override
                    public Object getPlayer(UUID playerId) {
                        return online.get(playerId);
                    }
                },
                new ConfigCaps() {
                    @Override
                    public int chainRadius() {
                        return 128;
                    }

                    @Override
                    public int chainMaxBlocks() {
                        return 1024;
                    }
                },
                new StateWriter() {
                    @Override
                    public void write(UUID playerId, int writtenRadius, int writtenMaxBlocks) {
                        writeCount.incrementAndGet();
                        written.set(new int[] {writtenRadius, writtenMaxBlocks});
                    }
                });
    }

    private static void forceGc(WeakReference<?> probe) {
        for (int i = 0; i < 50 && probe.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
