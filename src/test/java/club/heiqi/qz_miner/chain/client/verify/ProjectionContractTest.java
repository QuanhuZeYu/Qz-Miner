package club.heiqi.qz_miner.chain.client.verify;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * T22 表现投影独立契约探针（B1.1 / task-19a）。
 *
 * <p>独立口径来自 temp/chain-preview/verify/wave4_model.json：
 * header 字段映射、身份维度任一变化即重发布（全同则零通知）、订阅/退订与生命周期清理、
 * header 不得携带集合或数组字段（O(1) 结构约束）、订阅者异常隔离。</p>
 */
public class ProjectionContractTest {

    private static final long WORLD = 7L;
    private static final long LIFECYCLE = 3L;
    private static final long ROUND = 11L;
    private static final long CONFIG_REV = 5L;
    private static final long OBJECT_GROUP_REV = 2L;

    @Test
    public void headerMapsStateFieldsIncludingTruncation() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.PRIMARY_LOCAL);
        state.addPreviewTarget(generation, new ChainTarget(1, 0, 0));
        state.addPreviewTarget(generation, new ChainTarget(2, 0, 0));
        state.incrementScannedCount(generation);
        state.incrementScannedCount(generation);
        state.incrementScannedCount(generation);
        Assert.assertTrue(state.reportTruncation(
            generation, ChainPreviewState.TruncationReason.MAX_TARGETS, 0, 4096));

        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        ChainPreviewPresentationHeader header = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, true);

        Assert.assertNotNull(header);
        Assert.assertEquals(ChainPhase.IDLE, header.getPhase());
        Assert.assertEquals(generation, header.getPreviewGeneration());
        Assert.assertTrue(header.isPreviewActive());
        Assert.assertFalse(header.isPreviewCompleted());
        Assert.assertEquals(3, header.getScannedCount());
        Assert.assertEquals(2, header.getMatchedCount());
        Assert.assertEquals("visible 本轮与 matched 同源", 2, header.getVisibleCount());
        Assert.assertEquals(
            ChainPreviewState.TruncationReason.MAX_TARGETS, header.getTruncationReason());
        Assert.assertEquals(0, header.getTruncatedCount());
        Assert.assertEquals(4096, header.getTotalCount());
        Assert.assertEquals(ChainPreviewState.CancelReason.NONE, header.getCancelReason());
        Assert.assertFalse("无控制器时不得凭空出现在途远端请求", header.isRemoteRequestPending());
        Assert.assertEquals(0, header.getRemoteRequestId());
        Assert.assertEquals(WORLD, header.getWorldIdentity());
        Assert.assertEquals(LIFECYCLE, header.getLifecycleEpoch());
        Assert.assertEquals(ROUND, header.getServerRoundId());
        Assert.assertEquals(CONFIG_REV, header.getConfigRevision());
        Assert.assertEquals(OBJECT_GROUP_REV, header.getObjectGroupRevision());
        Assert.assertTrue(header.isTruncationSignalEnabled());
        Assert.assertTrue("首次发布 revision 必须为 1", header.getRevision() >= 1L);
        Assert.assertSame(header, projection.currentHeader());
    }

    @Test
    public void cancelReasonAndInactiveStateAreProjected() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(4, 0, 0));
        Assert.assertTrue(state.cancelPreview(
            generation, ChainPreviewState.CancelReason.REMOTE_TIMEOUT));
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        ChainPreviewPresentationHeader header = projection.sampleAndPublish(
            state, null, null, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, false);
        Assert.assertEquals(
            ChainPreviewState.CancelReason.REMOTE_TIMEOUT, header.getCancelReason());
        Assert.assertFalse("取消后必须投影为非活动", header.isPreviewActive());
        Assert.assertFalse(header.isTruncationSignalEnabled());
    }

    @Test
    public void eachIdentityDimensionForcesExactlyOneRepublish() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0));
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        final AtomicInteger notifications = new AtomicInteger();
        projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
            @Override
            public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                notifications.incrementAndGet();
            }
        });

        long[] dims = {WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV};
        ChainPreviewPresentationHeader baseline = projection.sampleAndPublish(
            state, null, null, dims[0], dims[1], dims[2], dims[3], dims[4], false);
        Assert.assertEquals(1, notifications.get());
        ChainPreviewPresentationHeader identical = projection.sampleAndPublish(
            state, null, null, dims[0], dims[1], dims[2], dims[3], dims[4], false);
        Assert.assertSame("内容全同必须返回原实例且零通知", baseline, identical);
        Assert.assertEquals("内容全同不得重复通知", 1, notifications.get());

        String[] labels = {"worldIdentity", "lifecycleEpoch", "serverRoundId", "configRevision", "objectGroupRevision"};
        for (int dimension = 0; dimension < dims.length; dimension++) {
            long[] changed = dims.clone();
            changed[dimension] = dims[dimension] + 1L;
            ChainPreviewPresentationHeader next = projection.sampleAndPublish(
                state, null, null, changed[0], changed[1], changed[2], changed[3], changed[4], false);
            Assert.assertEquals(
                labels[dimension] + " 变化必须通知一次", dimension + 2, notifications.get());
            Assert.assertTrue(
                labels[dimension] + " 变化必须提升 revision",
                next.getRevision() > baseline.getRevision());
            dims = changed;
            baseline = next;
        }

        // 预览代变化同样必须重发布（B1.1 身份维度含 previewGeneration）。
        int nextGeneration = state.begin(new ChainTarget(1, 0, 0));
        Assert.assertNotEquals(generation, nextGeneration);
        projection.sampleAndPublish(
            state, null, null, dims[0], dims[1], dims[2], dims[3], dims[4], false);
        Assert.assertEquals("generation 变化必须通知", 7, notifications.get());
    }

    @Test
    public void subscribeUnsubscribeAndLifecycleCleanupAreClean() {
        ChainPreviewState state = new ChainPreviewState();
        state.begin(new ChainTarget(0, 0, 0));
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        final AtomicInteger notifications = new AtomicInteger();
        ChainPreviewPresentationProjection.Listener listener =
            new ChainPreviewPresentationProjection.Listener() {
                @Override
                public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                    notifications.incrementAndGet();
                }
            };
        ChainPreviewPresentationProjection.Subscription first = projection.subscribe(listener);
        Assert.assertEquals(1, projection.getListenerCount());
        Assert.assertEquals("重复订阅同一 listener 必须去重", 1, projection.getListenerCount());
        projection.subscribe(listener);
        Assert.assertEquals(1, projection.getListenerCount());
        projection.sampleAndPublish(state, null, null, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, false);
        Assert.assertEquals(1, notifications.get());

        first.unsubscribe();
        first.unsubscribe();
        Assert.assertEquals("退订必须幂等且摘除", 0, projection.getListenerCount());
        projection.sampleAndPublish(state, null, null, WORLD + 1, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, false);
        Assert.assertEquals("退订后不得再收通知", 1, notifications.get());

        ChainPreviewPresentationProjection.Subscription second = projection.subscribe(listener);
        Assert.assertEquals(1, projection.getListenerCount());
        projection.clear();
        Assert.assertNull("clear 必须丢弃当前 header", projection.currentHeader());
        projection.sampleAndPublish(state, null, null, WORLD + 2, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, false);
        Assert.assertEquals("clear 后重新发布必须从 revision 1 起算", 1L, projection.currentHeader().getRevision());
        Assert.assertEquals(2, notifications.get());
        projection.clearSubscriptions();
        Assert.assertEquals(0, projection.getListenerCount());
        projection.sampleAndPublish(state, null, null, WORLD + 3, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, false);
        Assert.assertEquals("clearSubscriptions 后不得再通知", 2, notifications.get());
        second.unsubscribe();
    }

    @Test
    public void listenerFailureIsIsolatedAndDoesNotDetachOthers() {
        ChainPreviewState state = new ChainPreviewState();
        state.begin(new ChainTarget(0, 0, 0));
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        final AtomicInteger healthyCalls = new AtomicInteger();
        projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
            @Override
            public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                throw new RuntimeException("boom");
            }
        });
        projection.subscribe(new ChainPreviewPresentationProjection.Listener() {
            @Override
            public void onHeaderChanged(ChainPreviewPresentationHeader header) {
                healthyCalls.incrementAndGet();
            }
        });
        projection.sampleAndPublish(state, null, null, WORLD, LIFECYCLE, ROUND, CONFIG_REV, OBJECT_GROUP_REV, false);
        Assert.assertEquals("异常订阅者不得阻断其他订阅者", 1, healthyCalls.get());
        Assert.assertEquals("异常订阅者不得被静默摘除", 2, projection.getListenerCount());
    }

    @Test
    public void installUninstallAndSoftThreadCheckAreObservable() {
        ChainPreviewPresentationProjection previous = ChainPreviewPresentationProjection.installed();
        try {
            ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
            Assert.assertNull("install 必须返回之前的实例", ChainPreviewPresentationProjection.install(projection));
            Assert.assertSame(projection, ChainPreviewPresentationProjection.installed());
            Assert.assertSame(projection, ChainPreviewPresentationProjection.uninstall(projection));
            Assert.assertNull(ChainPreviewPresentationProjection.installed());

            ChainPreviewPresentationProjection newer = new ChainPreviewPresentationProjection();
            ChainPreviewPresentationProjection.install(newer);
            ChainPreviewPresentationProjection.uninstall(projection);
            Assert.assertSame("卸载不匹配实例不得清空新装配", newer, ChainPreviewPresentationProjection.installed());
            ChainPreviewPresentationProjection.uninstall(newer);
            Assert.assertNull(ChainPreviewPresentationProjection.installed());
        } finally {
            ChainPreviewPresentationProjection.install(previous);
        }

        // 线程软校验：绑定他人线程后仍可发布（文档口径为告警，不抛异常），探针据此锁定现状。
        ChainPreviewPresentationProjection bound = new ChainPreviewPresentationProjection();
        bound.bindMainThread(new Thread("verify-not-current-thread"));
        ChainPreviewState state = new ChainPreviewState();
        state.begin(new ChainTarget(0, 0, 0));
        ChainPreviewPresentationHeader header = bound.sampleAndPublish(
            state, null, null, 1L, 1L, 1L, 1L, 1L, false);
        Assert.assertNotNull(header);
        Assert.assertSame("同内容必须零重发布", header, bound.project(header));
        Assert.assertEquals(1L, header.getRevision());
    }

    @Test
    public void headerCarriesNoCollectionOrArrayFields() {
        Field[] fields = ChainPreviewPresentationHeader.class.getDeclaredFields();
        Assert.assertTrue("header 字段数必须有界（O(1) 结构）", fields.length <= 24);
        for (Field field : fields) {
            Class<?> type = field.getType();
            Assert.assertFalse(
                "header 不得持有集合字段：" + field.getName(),
                Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type));
            Assert.assertFalse("header 不得持有数组字段：" + field.getName(), type.isArray());
            Assert.assertFalse(
                "header 不得持有 Iterable 字段：" + field.getName(),
                Iterable.class.isAssignableFrom(type));
        }
    }

    @Test
    public void nullInputsFallBackSafely() {
        ChainPreviewPresentationProjection projection = new ChainPreviewPresentationProjection();
        ChainPreviewPresentationHeader header = projection.sampleAndPublish(
            null, null, null, 0L, 0L, 0L, 0L, 0L, false);
        Assert.assertNotNull(header);
        Assert.assertEquals(0, header.getPreviewGeneration());
        Assert.assertFalse(header.isPreviewActive());
        Assert.assertEquals(ChainPhase.IDLE, header.getPhase());
        Assert.assertSame("null 候选必须为无操作", header, projection.project(null));
        boolean threw = false;
        try {
            projection.subscribe(null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        Assert.assertTrue("null listener 必须被拒绝", threw);
    }
}
