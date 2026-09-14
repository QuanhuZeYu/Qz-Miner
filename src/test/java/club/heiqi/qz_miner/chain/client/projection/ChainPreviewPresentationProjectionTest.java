package club.heiqi.qz_miner.chain.client.projection;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;
import club.heiqi.qz_miner.chain.client.ChainPreviewBackendDiagnostics;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/** B1.1 表现投影：字段映射、六个失效维度、订阅生命周期与 O(1) payload 契约。 */
public class ChainPreviewPresentationProjectionTest {

    private static final long WORLD = 11L;
    private static final long LIFECYCLE = 2L;
    private static final long ROUND = 7L;
    private static final long CONFIG_REV = 5L;
    private static final long OBJECT_GROUP_REV = 4L;

    @Test
    public void sampleAndPublishMapsStateControllerAndPhaseFields() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.CHAIN_LOCAL);
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(1, 0, 0));
        Assert.assertTrue(state.reportTruncation(
            generation, ChainPreviewState.TruncationReason.MAX_TARGETS, 0, 4096));
        Assert.assertTrue(state.setCompleted(generation, true));

        ClientPhaseProjection phaseProjection = new ClientPhaseProjection();
        phaseProjection.update(ChainPhase.PLANNING, 3, 100L);

        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        final List<ChainPreviewPresentationHeader> received = new ArrayList<ChainPreviewPresentationHeader>();
        projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
            @Override
            public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                received.add(header);
            }
        });

        ChainPreviewPresentationHeader header = projection.sampleAndPublish(
            state, new ChainPreviewController(), phaseProjection,
            WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true);

        Assert.assertEquals(1, received.size());
        Assert.assertSame(header, received.get(0));
        Assert.assertSame(header, projection.currentHeader());
        Assert.assertEquals(1L, header.getRevision());
        Assert.assertEquals(ChainPhase.PLANNING, header.getPhase());
        Assert.assertEquals(3, header.getServerGeneration());
        Assert.assertEquals(generation, header.getPreviewGeneration());
        Assert.assertTrue(header.isPreviewActive());
        Assert.assertTrue(header.isPreviewCompleted());
        Assert.assertEquals(0, header.getScannedCount());
        Assert.assertEquals(2, header.getMatchedCount());
        Assert.assertEquals("visible 本轮与 matched 同源", 2, header.getVisibleCount());
        Assert.assertEquals(
            ChainPreviewState.TruncationReason.MAX_TARGETS, header.getTruncationReason());
        Assert.assertEquals(0, header.getTruncatedCount());
        Assert.assertEquals(4096, header.getTotalCount());
        Assert.assertEquals(ChainPreviewState.CancelReason.NONE, header.getCancelReason());
        Assert.assertFalse(header.isRemoteRequestPending());
        Assert.assertEquals(0, header.getRemoteRequestId());
        Assert.assertEquals(WORLD, header.getWorldIdentity());
        Assert.assertEquals(LIFECYCLE, header.getLifecycleEpoch());
        Assert.assertEquals(ROUND, header.getServerRoundId());
        Assert.assertEquals(CONFIG_REV, header.getConfigRevision());
        Assert.assertEquals(OBJECT_GROUP_REV, header.getObjectGroupRevision());
        Assert.assertTrue("截断可见开关必须在 header 上可零分配读取", header.isTruncationSignalEnabled());
    }

    @Test
    public void sixIdentityDimensionsRepublishWhileUnchangedContentDoesNot() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.CHAIN_LOCAL);
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        ClientPhaseProjection phaseProjection = new ClientPhaseProjection();
        phaseProjection.update(ChainPhase.ARMED, 1, 10L);
        ChainPreviewController controller = new ChainPreviewController();

        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        final int[] notifications = {0};
        projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
            @Override
            public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                notifications[0]++;
            }
        });

        ChainPreviewPresentationHeader first = projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true);
        Assert.assertEquals(1, notifications[0]);
        Assert.assertEquals(1L, first.getRevision());

        ChainPreviewPresentationHeader same = projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true);
        Assert.assertSame("内容不变不得重新发布", first, same);
        Assert.assertEquals(1, notifications[0]);

        Assert.assertNotSame(first, projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD + 1L, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true));
        Assert.assertNotSame(first, projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE + 1L, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true));
        Assert.assertNotSame(first, projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE, ROUND + 1L, CONFIG_REV, OBJECT_GROUP_REV, true));
        Assert.assertNotSame(first, projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE, ROUND, CONFIG_REV + 1L, OBJECT_GROUP_REV, true));
        Assert.assertNotSame(first, projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV + 1L, true));
        Assert.assertEquals("五个身份维度各触发一次发布", 6, notifications[0]);
        Assert.assertEquals(6L, projection.currentHeader().getRevision());

        ClientPhaseProjection nextServerGeneration = new ClientPhaseProjection();
        nextServerGeneration.update(ChainPhase.RUNNING, 2, 20L);
        Assert.assertNotSame(first, projection.sampleAndPublish(
            state, controller, nextServerGeneration, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true));

        int nextPreviewGeneration = state.begin(
            new ChainTarget(1, 1, 1), ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        state.addPreviewTarget(nextPreviewGeneration, new ChainTarget(1, 1, 1));
        ChainPreviewPresentationHeader afterGenerationChange = projection.sampleAndPublish(
            state, controller, nextServerGeneration, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true);
        Assert.assertEquals(nextPreviewGeneration, afterGenerationChange.getPreviewGeneration());
        Assert.assertEquals(8, notifications[0]);
    }

    @Test
    public void subscribeUnsubscribeLifecycleAndListenerFailureIsolation() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.CHAIN_LOCAL);
        state.addPreviewTarget(generation, new ChainTarget(0, 0, 0));
        ClientPhaseProjection phaseProjection = new ClientPhaseProjection();
        ChainPreviewController controller = new ChainPreviewController();
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();

        final int[] healthy = {0};
        ChainPreviewPresentationProjection.Subscription throwingSubscription =
            projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
                @Override
                public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                    throw new IllegalStateException("listener boom");
                }
            });
        ChainPreviewPresentationProjection.Subscription healthySubscription =
            projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
                @Override
                public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                    healthy[0]++;
                }
            });
        Assert.assertEquals(2, projection.getListenerCount());

        projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true);
        Assert.assertEquals("抛异常的订阅者不得影响其他订阅者", 1, healthy[0]);

        throwingSubscription.unsubscribe();
        throwingSubscription.unsubscribe();
        Assert.assertEquals("退订幂等", 1, projection.getListenerCount());
        projection.clear();
        Assert.assertNull(projection.currentHeader());
        projection.sampleAndPublish(
            state, controller, phaseProjection, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true);
        Assert.assertEquals("clear 后 revision 水位重置，订阅保留", 2, healthy[0]);
        Assert.assertEquals(1L, projection.currentHeader().getRevision());

        healthySubscription.unsubscribe();
        Assert.assertEquals(0, projection.getListenerCount());
        projection.clearSubscriptions();
        Assert.assertEquals(0, projection.getListenerCount());
    }

    @Test
    public void concurrentProjectNeverRepeatsRevision() throws Exception {
        final ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        final int threads = 4;
        final int perThread = 25;
        final List<Long> revisions = java.util.Collections.synchronizedList(new ArrayList<Long>());
        Thread[] workers = new Thread[threads];
        for (int index = 0; index < threads; index++) {
            final int threadIndex = index;
            workers[index] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int step = 0; step < perThread; step++) {
                        ChainPreviewPresentationHeader published = projection.project(
                            concurrentHeader(threadIndex * perThread + step + 1));
                        revisions.add(Long.valueOf(published.getRevision()));
                    }
                }
            });
            workers[index].start();
        }
        for (Thread worker : workers) {
            worker.join(10000L);
        }
        Assert.assertEquals(threads * perThread, revisions.size());
        Assert.assertEquals("并发发布不得出现重复 revision",
            revisions.size(), new java.util.HashSet<Long>(revisions).size());
    }

    private static ChainPreviewPresentationHeader concurrentHeader(int seed) {
        return new ChainPreviewPresentationHeader(
            ChainPhase.IDLE, 0, 0, false, false, 0, 0, 0, 0,
            ChainPreviewState.TruncationReason.NONE, 0, 0, ChainPreviewState.CancelReason.NONE,
            false, seed, seed, 0L, 0L, 0L, 0L, false, false,
            ChainPreviewBackendDiagnostics.DISABLED, 0L);
    }

    @Test
    public void installedSingletonInstallAndUninstallAreExplicitAndMismatchSafe() {
        ChainPreviewPresentationProjection first = new ChainPreviewPresentationProjection();
        ChainPreviewPresentationProjection second = new ChainPreviewPresentationProjection();
        try {
            Assert.assertNull("初始未装配必须为 null", ChainPreviewPresentationProjection.installed());
            Assert.assertNull(ChainPreviewPresentationProjection.install(first));
            Assert.assertSame(first, ChainPreviewPresentationProjection.installed());
            Assert.assertSame(first, ChainPreviewPresentationProjection.install(second));
            Assert.assertSame(second, ChainPreviewPresentationProjection.installed());
            Assert.assertSame(second,
                ChainPreviewPresentationProjection.uninstall(first));
            Assert.assertSame("不匹配的实例不得卸载当前装配",
                second, ChainPreviewPresentationProjection.installed());
            Assert.assertSame(second, ChainPreviewPresentationProjection.uninstall(second));
            Assert.assertNull(ChainPreviewPresentationProjection.installed());
        } finally {
            ChainPreviewPresentationProjection.uninstall(ChainPreviewPresentationProjection.installed());
        }
    }

    @Test
    public void headerCarriesNoCollectionPayload() {
        for (Field field : ChainPreviewPresentationHeader.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            Assert.assertFalse("header 不得携带集合字段：" + field.getName(),
                type.isArray()
                    || Collection.class.isAssignableFrom(type)
                    || Map.class.isAssignableFrom(type)
                    || Iterable.class.isAssignableFrom(type));
        }
    }
}
