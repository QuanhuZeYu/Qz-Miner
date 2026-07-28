package club.heiqi.qz_miner.network;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.network.ServerChainConfigRequestDispatch.EndpointKey;
import club.heiqi.qz_miner.network.ServerCuboidSelectionRequestDispatch.SelectionKey;
import club.heiqi.qz_miner.thread.KeyedLatestTaskLane;

/** 选点入包按 endpoint + point 槽位有界合并。 */
public class ServerCuboidSelectionRequestDispatchTest {

    @Test
    public void eachPointKeepsOnlyLatestTaskForEndpoint() {
        Object endpoint = new Object();
        UUID uuid = UUID.randomUUID();
        KeyedLatestTaskLane<Object> lane = new KeyedLatestTaskLane<Object>(8, 8);
        lane.start();
        final AtomicInteger point1 = new AtomicInteger();
        final AtomicInteger point2 = new AtomicInteger();

        Assert.assertTrue(lane.submit(key(uuid, endpoint, 1), set(point1, 1)));
        Assert.assertTrue(lane.submit(key(uuid, endpoint, 1), set(point1, 10)));
        Assert.assertTrue(lane.submit(key(uuid, endpoint, 2), set(point2, 20)));
        Assert.assertEquals(2, lane.drain());
        Assert.assertEquals(10, point1.get());
        Assert.assertEquals(20, point2.get());
    }

    private static SelectionKey key(UUID uuid, Object endpoint, int pointIndex) {
        return new SelectionKey(new EndpointKey(uuid, endpoint), pointIndex);
    }

    private static Runnable set(final AtomicInteger value, final int next) {
        return new Runnable() {
            @Override public void run() { value.set(next); }
        };
    }

}
