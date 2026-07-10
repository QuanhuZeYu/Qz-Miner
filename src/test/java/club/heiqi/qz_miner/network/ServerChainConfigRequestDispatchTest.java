package club.heiqi.qz_miner.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
            // 保持在 caps 内，避免 clamp 掩盖 final 值断言
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
        // 旧端点 identity 不匹配被丢弃；新端点写入
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
    public void concurrentDrainUpdateEndsWithFinalAcceptedValue() throws Exception {
        UUID uuid = UUID.randomUUID();
        Object endpoint = new Object();
        online.put(uuid, endpoint);
        final AtomicInteger next = new AtomicInteger();
        List<Thread> producers = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 250; i++) {
                        int value = next.incrementAndGet();
                        int radius = 1 + (value % 100);
                        int maxBlocks = 1 + (value % 200);
                        submit(uuid, endpoint, radius, maxBlocks);
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
        while (lane.pendingCount() > 0) {
            lane.drain();
        }
        Assert.assertNotNull(written.get());
        Assert.assertTrue(written.get()[0] >= 1);
        Assert.assertTrue(written.get()[1] >= 1);
        Assert.assertTrue(writeCount.get() >= 1);
    }

    @Test
    public void endpointKeyEqualsUsesUuidAndIdentity() {
        UUID uuid = UUID.randomUUID();
        EndpointKey a = new EndpointKey(uuid, 1);
        EndpointKey b = new EndpointKey(uuid, 1);
        EndpointKey c = new EndpointKey(uuid, 2);
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
}
