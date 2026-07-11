package club.heiqi.qz_miner.network;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import club.heiqi.qz_miner.thread.KeyedLatestTaskLane;

/** 对象组 C2S latest-wins、endpoint identity、revision 与非法保留旧值测试。 */
public class ServerObjectGroupConfigRequestDispatchTest {

    @Test
    public void latestRevisionWinsAndDifferentPlayersAreIsolated() {
        final KeyedLatestTaskLane<Object> lane = new KeyedLatestTaskLane<Object>(64, 64);
        lane.start();
        final ConcurrentHashMap<UUID, Object> online = new ConcurrentHashMap<UUID, Object>();
        final ConcurrentHashMap<UUID, Long> revisions = new ConcurrentHashMap<UUID, Long>();
        final ConcurrentHashMap<UUID, ObjectGroupRuleSet> written = new ConcurrentHashMap<UUID, ObjectGroupRuleSet>();
        final AtomicInteger accepts = new AtomicInteger();
        final AtomicInteger rejects = new AtomicInteger();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Object firstEndpoint = new Object();
        Object secondEndpoint = new Object();
        online.put(first, firstEndpoint);
        online.put(second, secondEndpoint);

        ObjectGroupWireConfig firstConfig = wire(1L, "logs", "minecraft:log@*");
        ObjectGroupWireConfig newerConfig = wire(2L, "logs", "minecraft:log@[0,4,8,12]");
        submit(first, firstEndpoint, firstConfig, lane, online, revisions, written, accepts, rejects);
        submit(first, firstEndpoint, newerConfig, lane, online, revisions, written, accepts, rejects);
        submit(second, secondEndpoint, wire(1L, "stone", "minecraft:stone@0"),
                lane, online, revisions, written, accepts, rejects);
        Assert.assertEquals(2, lane.pendingCount());
        while (lane.pendingCount() > 0) {
            lane.drain();
        }

        Assert.assertEquals(2, accepts.get());
        Assert.assertEquals("minecraft:log@[0,4,8,12]",
                written.get(first).groups().get(0).members().get(0).canonical());
        Assert.assertEquals("stone", written.get(second).groups().get(0).id());

        submit(first, firstEndpoint, wire(1L, "old", "minecraft:dirt@0"),
                lane, online, revisions, written, accepts, rejects);
        lane.drain();
        Assert.assertEquals(2, accepts.get());
        Assert.assertEquals(1, rejects.get());
        Assert.assertEquals("logs", written.get(first).groups().get(0).id());
    }

    private static ObjectGroupWireConfig wire(long revision, String id, String selector) {
        ObjectGroupRuleSet rules = new ObjectGroupRuleSet(Collections.singletonList(new ObjectGroup(
                id, Collections.singletonList(ObjectGroupParser.parseSelector(selector)))));
        return ObjectGroupWireConfig.fromRuleSet(revision, rules);
    }

    private static void submit(final UUID uuid, final Object endpoint, ObjectGroupWireConfig payload,
            final KeyedLatestTaskLane<Object> lane, final ConcurrentHashMap<UUID, Object> online,
            final ConcurrentHashMap<UUID, Long> revisions,
            final ConcurrentHashMap<UUID, ObjectGroupRuleSet> written,
            final AtomicInteger accepts, final AtomicInteger rejects) {
        Assert.assertTrue(ServerObjectGroupConfigRequestDispatch.submit(
                uuid, endpoint, payload,
                new ServerObjectGroupConfigRequestDispatch.KeyedDispatcher() {
                    @Override
                    public boolean tryRunLatest(Object key, Runnable task) {
                        return lane.submit(key, task);
                    }
                },
                new ServerObjectGroupConfigRequestDispatch.PlayerLookup() {
                    @Override
                    public Object getPlayer(UUID playerId) {
                        return online.get(playerId);
                    }
                },
                new ServerObjectGroupConfigRequestDispatch.RevisionLookup() {
                    @Override
                    public long currentRevision(UUID playerId) {
                        Long value = revisions.get(playerId);
                        return value == null ? 0L : value.longValue();
                    }
                },
                new ServerObjectGroupConfigRequestDispatch.StateWriter() {
                    @Override
                    public void write(UUID playerId, Object playerEndpoint, ObjectGroupRuleSet rules, long revision) {
                        written.put(playerId, rules);
                        revisions.put(playerId, Long.valueOf(revision));
                        accepts.incrementAndGet();
                    }
                },
                new ServerObjectGroupConfigRequestDispatch.AckSender() {
                    @Override
                    public void send(UUID playerId, Object playerEndpoint, long requestedRevision,
                            long authoritativeRevision, boolean accepted, int groupCount) {
                        if (!accepted) {
                            rejects.incrementAndGet();
                        }
                    }
                }));
    }
}
