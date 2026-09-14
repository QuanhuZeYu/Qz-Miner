package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B4.2 有界容量 / 峰值监控 / 回收路径（task-33）的 headless 行为契约。
 *
 * <p>判据：</p>
 * <ol>
 *   <li>唯一上限复用 {@link ChainPreviewMeshBuilder#MAX_RENDER_TARGETS}：等于上限不截断、超过上限按
 *       既有 truncated 语义可见，且缓存/峰值不越过上限；</li>
 *   <li>峰值（顶点 / 索引 / aux / 代级缓存条目）逐项取历史最大值，可 reset，并能交接进既有
 *       {@link ChainPreviewScaleCounters} 通道；</li>
 *   <li>收缩 / 会话结束 / 换代（切维度）/ lifecycle 四条回收路径都释放代级缓存与 mesh 引用；</li>
 *   <li>顶点 / 索引 / aux / 可见边是 unique 目标的派生量，受 168×块数 / 288×块数 / 4×顶点 / 12×块数 的
 *       派生上界约束（即孤立方块实测包络 168 顶点 / 288 索引 每块；T51 方案 A 前为 64 顶点 / 288 索引，
 *       不引入第二套上限数值）；</li>
 *   <li>容量语义与既有一次性全量入口一致：默认档行为不变。</li>
 * </ol>
 */
public class ChainPreviewCapacityContractTest {

    private static final int LIMIT = ChainPreviewMeshBuilder.MAX_RENDER_TARGETS;
    private static final float THICKNESS = 0.045F;

    /**
     * 上限边界（等于 / 超过）+ 派生上界 + 与全量基线一致：超限只走 truncated，不静默截断。
     */
    @Test
    public void capacityBoundaryIsExactAtLimitAndTruncatesBeyond() {
        List<ChainTarget> exactly = scattered(LIMIT, 0);

        GenerationSession exactSession = new ChainPreviewMeshBuilder().beginGeneration();
        Assert.assertEquals("上限必须复用既有常量", LIMIT, exactSession.getCapacityLimit());
        ChainPreviewMesh exactMesh = extend(exactSession, exactly);
        Assert.assertFalse("正好上限不得置截断: " + exactMesh.isTruncated(), exactMesh.isTruncated());
        Assert.assertFalse(exactSession.isOverflowed());
        Assert.assertEquals(LIMIT, exactMesh.getBlockCount());
        Assert.assertEquals(LIMIT, exactSession.getCacheEntryCount());
        Assert.assertEquals(LIMIT, exactSession.getGenerationTargetCount());
        assertDerivedBounds("exact", exactSession, exactMesh);

        List<ChainTarget> beyond = new ArrayList<ChainTarget>(exactly);
        beyond.add(new ChainTarget(LIMIT * 3 + 3, 0, 0));

        GenerationSession overSession = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh overMesh = extend(overSession, beyond);
        Assert.assertTrue("超过上限必须置截断", overMesh.isTruncated());
        Assert.assertTrue("超限必须可独立观察", overSession.isOverflowed());
        Assert.assertEquals("超限只保留上限个唯一目标", LIMIT, overMesh.getBlockCount());
        Assert.assertEquals(LIMIT, overSession.getCacheEntryCount());
        Assert.assertTrue("累积必须有界: " + overSession.getGenerationTargetCount(),
            overSession.getGenerationTargetCount() <= LIMIT + 1);
        assertDerivedBounds("over", overSession, overMesh);

        // 差分基线（时间序 + 显式锚点）：同一容量语义下逐字节一致。
        ChainPreviewMesh baseline = new ChainPreviewMeshBuilder().buildWithOrigin(
            beyond, visuals(), THICKNESS, null,
            overSession.getAnchorX(), overSession.getAnchorY(), overSession.getAnchorZ());
        Assert.assertArrayEquals("基线顶点", baseline.getVertices(), overMesh.getVertices(), 0.0F);
        Assert.assertArrayEquals("基线索引", baseline.getIndices(), overMesh.getIndices());
        Assert.assertEquals(baseline.getIndexCount(), overMesh.getIndexCount());

        // 默认档行为不变：公共一次性入口的容量语义与代级会话一致（截断 / 可见块 / 索引规模）。
        ChainPreviewMesh publicBaseline = new ChainPreviewMeshBuilder().build(
            newestFirst(beyond), visuals(), THICKNESS);
        Assert.assertEquals(publicBaseline.isTruncated(), overMesh.isTruncated());
        Assert.assertEquals(publicBaseline.getBlockCount(), overMesh.getBlockCount());
        Assert.assertEquals(publicBaseline.getIndexCount(), overMesh.getIndexCount());
    }

    /**
     * 有界性：连续向 B4.1 代级缓存注入超量目标（含同快照再修订、换一批目标触发收缩），
     * 缓存条目 / 峰值 / 可见段恒不超过上限派生的上界，且行为可观察（truncated + overflowed）。
     */
    @Test
    public void overCapacityInjectionStaysBoundedAcrossRevisions() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();

        List<ChainTarget> first = scattered(LIMIT + 250, 0);
        ChainPreviewMesh firstMesh = extend(session, first);
        Assert.assertTrue(firstMesh.isTruncated());
        assertDerivedBounds("rev1", session, firstMesh);
        Assert.assertEquals(LIMIT, session.getCacheEntryCount());

        // 同代再修订：同一超量快照（覆盖完整，不触发收缩重跑）仍必须保持有界。
        ChainPreviewMesh second = extend(session, first);
        Assert.assertTrue(second.isTruncated());
        assertDerivedBounds("rev2", session, second);
        Assert.assertEquals(LIMIT, session.getCacheEntryCount());

        // 换一批完全不同的超量目标：收缩与超限同时发生，缓存仍需回落到上限内。
        ChainPreviewMesh third = extend(session, offset(scattered(LIMIT + 250, 0), 1_000_000));
        Assert.assertTrue(third.isTruncated());
        Assert.assertTrue(session.isOverflowed());
        Assert.assertEquals(LIMIT, session.getCacheEntryCount());
        Assert.assertEquals(LIMIT, session.getGenerationTargetCount());
        assertDerivedBounds("rev3", session, third);

        Assert.assertTrue(session.getCacheEntryCount() <= LIMIT);
        Assert.assertTrue("缓存条目总数超过 6×上限: " + session.getCacheEntryTotal(),
            session.getCacheEntryTotal() <= 6 * LIMIT);
        Assert.assertTrue(session.getPeakCacheEntryCount() <= 6 * LIMIT);
        Assert.assertTrue(session.getPeakRetainedTargetCount() <= LIMIT + 1);
        Assert.assertTrue(session.getPeakVisibleSegmentCount() <= 12 * LIMIT);
        Assert.assertTrue(session.getPeakVertexCount() <= 168 * LIMIT);
        Assert.assertTrue(session.getPeakIndexCount() <= 288 * LIMIT);
        Assert.assertTrue(session.getPeakAuxBytes()
            <= ChainPreviewMesh.AUX_BYTES_PER_VERTEX * session.getPeakVertexCount());
    }

    /**
     * 有界性（跨修订）：目标数持续增长的超量快照反复喂入，缓存容器不得随修订增长
     * （超限目标不登记去重集合），truncated 必须每次都可见。
     */
    @Test
    public void growingOverCapacitySnapshotKeepsCacheBoundedAcrossRevisions() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        int steadyTotal = -1;
        for (int extra = 10; extra <= 120; extra += 10) {
            ChainPreviewMesh mesh = extend(session, scattered(LIMIT + extra, 0));
            Assert.assertTrue("修订 " + extra + " 必须置截断", mesh.isTruncated());
            Assert.assertEquals(LIMIT, mesh.getBlockCount());
            Assert.assertEquals(LIMIT, session.getCacheEntryCount());
            Assert.assertEquals(LIMIT, session.getGenerationTargetCount());
            if (steadyTotal < 0) {
                steadyTotal = session.getCacheEntryTotal();
            }
            Assert.assertEquals("缓存不得随超量修订增长: extra=" + extra,
                steadyTotal, session.getCacheEntryTotal());
        }
        Assert.assertTrue(steadyTotal <= 6 * LIMIT);
        Assert.assertTrue(session.isOverflowed());
        Assert.assertEquals(steadyTotal, session.getPeakCacheEntryCount());
        Assert.assertTrue(session.getPeakRetainedTargetCount() <= LIMIT + 1);
        Assert.assertTrue(session.getPeakVertexCount() <= 168 * LIMIT);
    }

    /** 有界性（稠密/共享面形态）：可见块数小于保留目标数时，缓存与几何仍按唯一目标上限有界。 */
    @Test
    public void denseOverCapacityClusterStaysBoundedByUniqueTargets() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        ChainPreviewMesh mesh = extend(session, cluster(LIMIT + 64, 0));
        Assert.assertTrue(mesh.isTruncated());
        Assert.assertEquals(LIMIT, session.getCacheEntryCount());
        Assert.assertEquals(LIMIT, session.getGenerationTargetCount());
        Assert.assertEquals("团内距离上界 < 重锚距离，不得重锚", 0, session.getReanchorCount());
        // 口径提示：mesh.getBlockCount() 是「可见块数」（有可见段的位置），稠密团内部块为 0 段；
        // 容量上界由 getCacheEntryCount() / getGenerationTargetCount()（唯一目标）断言。
        Assert.assertTrue("可见块数不得超过保留目标数", mesh.getBlockCount() <= LIMIT);
        Assert.assertTrue(mesh.getVertexCount() <= 168 * LIMIT);
        Assert.assertTrue(mesh.getIndexCount() <= 288 * LIMIT);
        Assert.assertTrue(session.getCacheEntryTotal() <= 6 * LIMIT);
        Assert.assertTrue(session.getPeakVisibleSegmentCount() <= 12 * LIMIT);
    }

    /** B3.x × B4.2：lod=auto 增量不改容量上限/峰值语义，收缩回收在 LOD 下同样释放重建。 */
    @Test
    public void lodAutoIncrementalKeepsCapacityAndShrinkSemantics() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        VisualParameters lod = new VisualParameters(
            0.5D - 700.0D, 0.5D, 0.5D, 0.0D, 1000.0D, 1.0F, 0.05F, THICKNESS, true, 0.05F);

        List<ChainTarget> full = scattered(LIMIT, 0);
        ChainPreviewMesh mesh = session.extend(newestFirst(full), null, lod, THICKNESS);
        Assert.assertEquals(LIMIT, session.getCacheEntryCount());
        Assert.assertEquals(LIMIT, session.getGenerationTargetCount());
        Assert.assertTrue("lod=auto 不得破坏容量上限: " + session.getCacheEntryTotal(),
            session.getCacheEntryTotal() <= 6 * LIMIT);
        Assert.assertTrue("可见块数不得超过保留目标数", mesh.getBlockCount() <= LIMIT);
        Assert.assertFalse("恰好上限不得置截断", mesh.isTruncated());
        Assert.assertTrue("相机 700 距/fadeEnd 1000 必须剔除远端目标",
            mesh.getCulledTargetCount() > 0);
        Assert.assertTrue(session.getPeakRetainedTargetCount() >= LIMIT);

        // 收缩回收：LOD 下同样释放整代缓存与 mesh 引用并按新快照重建。
        List<ChainTarget> shrunk = scattered(3, 0);
        ChainPreviewMesh shrunkMesh = session.extend(newestFirst(shrunk), null, lod, THICKNESS);
        Assert.assertEquals(3, session.getCacheEntryCount());
        Assert.assertEquals(3, session.getGenerationTargetCount());
        Assert.assertEquals("收缩后近端目标必须可见", 3, shrunkMesh.getBlockCount());
        Assert.assertEquals("收缩后锚点 = 新快照首个目标", 0, session.getAnchorX());
        Assert.assertEquals(LIMIT, session.getPeakRetainedTargetCount());
    }

    /**
     * 超容量 + lod=auto：缓存未覆盖全部 unique 目标（{@code isOverflowed()}），此时不做增量，
     * 保持既有全量回退（登记子场景）；容量仍有界、剔除计数与几何与回退路径一致。
     */
    @Test
    public void overCapacityLodAutoFallsBackButStaysBounded() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        VisualParameters lod = new VisualParameters(
            0.5D - 700.0D, 0.5D, 0.5D, 0.0D, 1000.0D, 1.0F, 0.05F, THICKNESS, true, 0.05F);
        List<ChainTarget> tooMany = scattered(LIMIT + 8, 0);
        ChainPreviewMesh mesh = session.extend(newestFirst(tooMany), null, lod, THICKNESS);
        Assert.assertTrue(mesh.isTruncated() || session.isOverflowed());
        Assert.assertTrue(session.isOverflowed());
        Assert.assertEquals(LIMIT, session.getCacheEntryCount());
        Assert.assertEquals(LIMIT, session.getGenerationTargetCount());
        Assert.assertTrue(session.getCacheEntryTotal() <= 6 * LIMIT);
        Assert.assertTrue("剔除计数必须有界: " + mesh.getCulledTargetCount(),
            mesh.getCulledTargetCount() <= LIMIT);
    }

    /** 峰值逐项取最大值、可 reset，且 reset 不影响缓存；交接进既有计数器通道。 */
    @Test
    public void peaksTrackMaximumsAndResetIndependently() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();

        ChainPreviewMesh small = extend(session, scattered(3, 0));
        Assert.assertEquals(small.getVertexCount(), session.getPeakVertexCount());
        Assert.assertEquals(small.getIndexCount(), session.getPeakIndexCount());
        Assert.assertEquals(small.getAuxByteCount(), session.getPeakAuxBytes());
        Assert.assertEquals(session.getCacheEntryTotal(), session.getPeakCacheEntryCount());
        Assert.assertEquals(3, session.getPeakRetainedTargetCount());
        Assert.assertTrue(session.getPeakVisibleSegmentCount() > 0);

        ChainPreviewMesh grown = extend(session, scattered(30, 0));
        Assert.assertTrue("追加必须抬高顶点峰值", grown.getVertexCount() > small.getVertexCount());
        Assert.assertEquals(grown.getVertexCount(), session.getPeakVertexCount());
        Assert.assertEquals(grown.getIndexCount(), session.getPeakIndexCount());
        int grownEntries = session.getCacheEntryTotal();
        Assert.assertEquals(grownEntries, session.getPeakCacheEntryCount());
        Assert.assertTrue("缓存条目必须落在 6×上限内: " + grownEntries, grownEntries <= 6 * LIMIT);

        // 收缩到 2：峰值保持历史最大值（不是当前值）。
        ChainPreviewMesh shrunk = extend(session, scattered(2, 100_000));
        Assert.assertEquals(2, shrunk.getBlockCount());
        Assert.assertEquals(2, session.getCacheEntryCount());
        Assert.assertEquals(grownEntries, session.getPeakCacheEntryCount());
        Assert.assertEquals(grown.getVertexCount(), session.getPeakVertexCount());
        Assert.assertEquals(grown.getIndexCount(), session.getPeakIndexCount());

        // 交接进既有计数器通道：逐项取最大值。
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        session.publishCapacityInto(counters);
        Assert.assertEquals(session.getPeakVertexCount(), (int) counters.getPeakVertexCount());
        Assert.assertEquals(session.getPeakIndexCount(), (int) counters.getPeakIndexCount());
        Assert.assertEquals(session.getPeakAuxBytes(), (int) counters.getPeakAuxBytes());
        Assert.assertEquals(session.getPeakCacheEntryCount(), (int) counters.getPeakGenerationCacheEntries());

        // 通道只增不减。
        counters.recordCapacity(small, 1);
        Assert.assertEquals(grown.getVertexCount(), (int) counters.getPeakVertexCount());

        // null 网格与负值按 0 处理：在新实例上单独验证，避免被既有峰值掩盖。
        ChainPreviewScaleCounters fresh = new ChainPreviewScaleCounters();
        fresh.recordCapacity((ChainPreviewMesh) null, 77);
        Assert.assertEquals(77L, fresh.getPeakGenerationCacheEntries());
        Assert.assertEquals(0L, fresh.getPeakVertexCount());
        fresh.recordCapacity(-5, -5, -5, -5);
        Assert.assertEquals(77L, fresh.getPeakGenerationCacheEntries());
        Assert.assertEquals(0L, fresh.getPeakIndexCount());

        // 会话峰值 reset：读数归零、缓存与网格不动。
        session.resetCapacityPeaks();
        Assert.assertEquals(0, session.getPeakVertexCount());
        Assert.assertEquals(0, session.getPeakIndexCount());
        Assert.assertEquals(0, session.getPeakAuxBytes());
        Assert.assertEquals(0, session.getPeakCacheEntryCount());
        Assert.assertEquals(0, session.getPeakVisibleSegmentCount());
        Assert.assertEquals(2, session.getCacheEntryCount());
        Assert.assertNotNull(session.getMesh());

        ChainPreviewMesh again = extend(session, scattered(4, 200_000));
        Assert.assertEquals(again.getVertexCount(), session.getPeakVertexCount());
        Assert.assertEquals(session.getCacheEntryTotal(), session.getPeakCacheEntryCount());

        // 计数器 reset：容量峰值与既有计数一起归零。
        counters.recordTopologyUpload();
        counters.reset();
        Assert.assertEquals(0L, counters.getPeakVertexCount());
        Assert.assertEquals(0L, counters.getPeakIndexCount());
        Assert.assertEquals(0L, counters.getPeakAuxBytes());
        Assert.assertEquals(0L, counters.getPeakGenerationCacheEntries());
        Assert.assertEquals(0L, counters.getRebuilds());

        session.publishCapacityInto(null);
        Assert.assertEquals(0L, counters.getPeakVertexCount());
    }

    /**
     * 回收路径一（拓扑收缩）：代内目标减少必须释放陈旧缓存并从本快照重建，
     * 几何与全新会话逐字节一致（不残留收缩前的条柱）。
     */
    @Test
    public void topologyShrinkReleasesStaleCacheAndRebuildsFromSnapshot() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        extend(session, scattered(12, 0));
        Assert.assertEquals(12, session.getCacheEntryCount());

        List<ChainTarget> shrunkTargets = scattered(3, 500);
        ChainPreviewMesh shrunk = extend(session, shrunkTargets);
        Assert.assertEquals("收缩后不得残留旧目标", 3, shrunk.getBlockCount());
        Assert.assertEquals(3, session.getCacheEntryCount());
        Assert.assertEquals(3, session.getGenerationTargetCount());
        Assert.assertEquals("收缩后锚点 = 新快照首个（最早）目标", 500, session.getAnchorX());
        Assert.assertEquals("峰值保留历史最大值", 12, session.getPeakRetainedTargetCount());

        ChainPreviewMesh reference = extend(
            new ChainPreviewMeshBuilder().beginGeneration(), shrunkTargets);
        assertByteEqual("shrink", reference, shrunk);

        // 收缩到空：缓存与网格都必须回落到空，不得残留。
        ChainPreviewMesh empty = extend(session, Collections.<ChainTarget>emptyList());
        Assert.assertTrue(empty.isEmpty());
        Assert.assertEquals(0, session.getCacheEntryCount());
        Assert.assertEquals(0, session.getVisibleSegmentCount());
        Assert.assertEquals(0, session.getGenerationTargetCount());
        Assert.assertFalse("清空不是 dispose", session.isDisposed());
    }

    /**
     * 回收路径二 / 三 / 四（会话结束 / 切维度换代 / lifecycle）：dispose 只置位 volatile 请求，
     * 由构建线程下次入口释放；释放后缓存、mesh 引用、锚点与峰值全部归零，且不跨代残留。
     */
    @Test
    public void disposeAndGenerationSwitchReleaseCachesAndMeshReferences() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession ended = builder.beginGeneration();
        extend(ended, scattered(20, 0));
        Assert.assertTrue(ended.getCacheEntryCount() > 0);
        Assert.assertNotNull(ended.getMesh());
        Assert.assertTrue(ended.getPeakCacheEntryCount() > 0);

        // 会话结束：请求位先置位，释放由构建线程入口消费（既有 volatile 置位模式）。
        ended.dispose();
        Assert.assertTrue(ended.isDisposed());
        Assert.assertTrue("未消费前主线程不得直接改构建线程缓存", ended.getCacheEntryCount() > 0);
        try {
            extend(ended, scattered(20, 0));
            Assert.fail("dispose 后再次使用必须被拒绝");
        } catch (IllegalStateException expected) {
            Assert.assertNotNull(expected);
        }
        Assert.assertEquals(0, ended.getCacheEntryCount());
        Assert.assertEquals(0, ended.getGenerationTargetCount());
        Assert.assertEquals(0, ended.getVisibleSegmentCount());
        Assert.assertNull("mesh 引用必须释放", ended.getMesh());
        Assert.assertEquals(0, ended.getAnchorX());
        Assert.assertEquals(0, ended.getAnchorY());
        Assert.assertEquals(0, ended.getAnchorZ());
        Assert.assertEquals(0, ended.getPeakCacheEntryCount());
        Assert.assertEquals(0, ended.getPeakVertexCount());

        // 切维度 / 换代：dispose 旧会话 + 新建；lifecycle 同时复位构建器滞回记忆。
        builder.resetLodHysteresis();
        GenerationSession next = builder.beginGeneration();
        ChainPreviewMesh nextMesh = extend(next, scattered(3, 100));
        Assert.assertEquals(3, next.getGenerationTargetCount());
        Assert.assertEquals("新代锚点不得沿用旧代", 100, next.getAnchorX());
        Assert.assertEquals(3, nextMesh.getBlockCount());
        Assert.assertTrue("新代峰值只记新代内容", next.getPeakRetainedTargetCount() >= 3);
        Assert.assertNotSame(next, ended);
    }

    private static void assertDerivedBounds(
            String label, GenerationSession session, ChainPreviewMesh mesh) {
        int blocks = Math.max(1, mesh.getBlockCount());
        Assert.assertTrue(label + " 顶点数超过 168×块数: " + mesh.getVertexCount(),
            mesh.getVertexCount() <= 168 * blocks);
        Assert.assertTrue(label + " 索引数超过 288×块数: " + mesh.getIndexCount(),
            mesh.getIndexCount() <= 288 * blocks);
        Assert.assertTrue(label + " aux 字节超过 4×顶点: " + mesh.getAuxByteCount(),
            mesh.getAuxByteCount() <= ChainPreviewMesh.AUX_BYTES_PER_VERTEX * mesh.getVertexCount());
        Assert.assertTrue(label + " 可见段超过 12×上限: " + session.getVisibleSegmentCount(),
            session.getVisibleSegmentCount() <= 12 * LIMIT);
    }

    private static ChainPreviewMesh extend(GenerationSession session, List<ChainTarget> chronological) {
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(snapshot);
        return session.extend(snapshot, null, visuals(), THICKNESS);
    }

    /** 转为生产快照序（最新→最早）。 */
    private static List<ChainTarget> newestFirst(List<ChainTarget> chronological) {
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(snapshot);
        return snapshot;
    }

    /**
     * 256 格 Chebyshev 内的稠密团（17×17×∞，相邻块共享面）：用于超限场景下与一次性全量入口
     * 逐字节对照——距离上界 16 &lt; 重锚距离，代级锚点不移动。
     */
    private static List<ChainTarget> cluster(int count, int base) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(
                base + index % 17, (index / 17) % 17, index / (17 * 17)));
        }
        return targets;
    }

    /** 孤立条柱（间距 3 格，无共享面），第 i 个位于 base + 3i。 */
    private static List<ChainTarget> scattered(int count, int base) {
        List<ChainTarget> targets = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            targets.add(new ChainTarget(base + index * 3, 0, 0));
        }
        return targets;
    }

    private static List<ChainTarget> offset(List<ChainTarget> targets, int deltaX) {
        List<ChainTarget> shifted = new ArrayList<ChainTarget>(targets.size());
        for (ChainTarget target : targets) {
            shifted.add(new ChainTarget(
                target.getX() + deltaX, target.getY(), target.getZ()));
        }
        return shifted;
    }

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, THICKNESS);
    }

    private static void assertByteEqual(String label, ChainPreviewMesh expected, ChainPreviewMesh actual) {
        Assert.assertArrayEquals(label + " vertices", expected.getVertices(), actual.getVertices(), 0.0F);
        Assert.assertArrayEquals(label + " colors", expected.getColors(), actual.getColors(), 0.0F);
        Assert.assertArrayEquals(label + " indices", expected.getIndices(), actual.getIndices());
        Assert.assertArrayEquals(label + " aux", expected.getAux(), actual.getAux());
        Assert.assertEquals(label + " originX", expected.getOriginX(), actual.getOriginX());
        Assert.assertEquals(label + " blockCount", expected.getBlockCount(), actual.getBlockCount());
    }
}
