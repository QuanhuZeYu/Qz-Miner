package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T27 波次 5 增量代契约（B4.1 / task-25）。
 *
 * <p>期望值一律来自 {@link VerifyMeshReferenceModel} 的独立全量模型（顶点位置集合按 float 位、
 * quad 集合、逐顶点 aux 事实、锚点/计数），不复用 owner 的差分基线
 * {@code buildWithOrigin}，也不读 owner 断言。</p>
 */
public class IncrementalGenerationContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float THICKNESS = 0.045F;

    private static List<ChainTarget> newestFirst(List<ChainTarget> chronological) {
        List<ChainTarget> copy = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(copy);
        return copy;
    }

    private static ChainPreviewMesh extend(
            GenerationSession session, List<ChainTarget> chronological, int[] semanticClasses) {
        return session.extend(newestFirst(chronological), semanticClasses, VISUALS, THICKNESS);
    }

    private static void assertEquivalent(String label, GenerationSession session,
            List<ChainTarget> chronological, int[] semanticClasses) {
        VerifyMeshReferenceModel.Expectation expectation = VerifyMeshReferenceModel.build(
            chronological, semanticClasses, THICKNESS, false,
            session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
        List<VerifyMeshReferenceModel.Mismatch> mismatches =
            VerifyMeshReferenceModel.compare(expectation, session.getMesh());
        Assert.assertTrue(label + " 增量结果必须与独立参考模型等价：" + mismatches, mismatches.isEmpty());
    }

    @Test
    public void extendMatchesReferenceModelAtEveryStep() {
        List<ChainTarget> chronological = new ArrayList<ChainTarget>();
        for (int x = 0; x < 6; x++) {
            chronological.add(new ChainTarget(x, 0, 0));
        }
        for (int z = 1; z < 5; z++) {
            chronological.add(new ChainTarget(5, 0, z));
        }
        for (int y = 1; y < 4; y++) {
            chronological.add(new ChainTarget(5, y, 4));
        }
        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        for (int count = 1; count <= chronological.size(); count += 2) {
            int end = Math.min(count, chronological.size());
            List<ChainTarget> soFar = new ArrayList<ChainTarget>(chronological.subList(0, end));
            ChainPreviewMesh mesh = extend(session, soFar, null);
            Assert.assertNotNull(mesh);
            Assert.assertEquals("代内唯一目标数", end, session.getGenerationTargetCount());
            Assert.assertEquals("锚点必须保持代内首个目标", 0, session.getAnchorX());
            Assert.assertEquals(0, session.getReanchorCount());
            assertEquivalent("step_" + end, session, soFar, null);
        }
        Assert.assertEquals(chronological.size(), session.getGenerationTargetCount());
        Assert.assertEquals(chronological.size(), session.getMesh().getBlockCount());
    }

    @Test
    public void neighborhoodAppendShapesStayEquivalentAndLocal() {
        List<ChainTarget> base = VerifyShapes.plane(3);
        int[][] additions = {
            {4, 0, 0}, {-3, 0, 0}, {0, 4, 0}, {0, -3, 0}, {0, 0, 4}, {0, 0, -3},
            {4, 4, 0}, {4, -3, 0}, {-3, 4, 0}, {-3, -3, 0},
            {4, 0, 4}, {4, 0, -3}, {-3, 0, 4}, {-3, 0, -3},
            {0, 4, 4}, {0, 4, -3}, {0, -3, 4}, {0, -3, -3},
            {4, 4, 4}, {-3, -3, -3},
            {16, 0, 0}, {15, 0, 0}, {0, 0, 16}, {16, 16, 0}
        };
        for (int[] addition : additions) {
            GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
            extend(session, base, null);
            VerifyMeshReferenceModel.Expectation before = VerifyMeshReferenceModel.build(
                base, null, THICKNESS, false, session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            List<ChainTarget> extended = new ArrayList<ChainTarget>(base);
            extended.add(new ChainTarget(addition[0], addition[1], addition[2]));
            extend(session, extended, null);
            Assert.assertEquals("邻居追加不得触发重锚", 0, session.getReanchorCount());
            assertEquivalent("append_" + Arrays.toString(addition), session, extended, null);

            VerifyMeshReferenceModel.Expectation after = VerifyMeshReferenceModel.build(
                extended, null, THICKNESS, false, session.getAnchorX(), session.getAnchorY(), session.getAnchorZ());
            Set<String> changed = new HashSet<String>(after.quads);
            changed.removeAll(before.quads);
            Assert.assertFalse("追加必须改变几何（差分强度）: " + Arrays.toString(addition), changed.isEmpty());
            for (String quad : changed) {
                assertQuadWithin(quad, addition, 2.6F, session);
            }
            Set<String> removed = new HashSet<String>(before.quads);
            removed.removeAll(after.quads);
            for (String quad : removed) {
                assertQuadWithin(quad, addition, 2.6F, session);
            }
        }
    }

    @Test
    public void anchorBoundaryIsChebyshevAt256() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        Assert.assertEquals(256, session.getReanchorDistance());

        List<ChainTarget> feed = new ArrayList<ChainTarget>();
        feed.add(new ChainTarget(0, 0, 0));
        extend(session, feed, null);
        Assert.assertEquals(0, session.getAnchorX());

        feed.add(new ChainTarget(256, 0, 0));
        extend(session, feed, null);
        Assert.assertEquals("Chebyshev 距离正好 256 不得重锚", 0, session.getReanchorCount());
        Assert.assertEquals(0, session.getAnchorX());

        feed.add(new ChainTarget(256, 256, 0));
        extend(session, feed, null);
        Assert.assertEquals("Chebyshev 256（欧氏 362）不得重锚：阈值必须是 Chebyshev",
            0, session.getReanchorCount());
        Assert.assertEquals(0, session.getAnchorX());
        assertEquivalent("at_256", session, feed, null);

        feed.add(new ChainTarget(257, 0, 0));
        extend(session, feed, null);
        Assert.assertEquals("257 > 256 必须重锚", 1, session.getReanchorCount());
        Assert.assertEquals(257, session.getAnchorX());
        Assert.assertEquals(0, session.getAnchorY());
        assertEquivalent("after_reanchor_1", session, feed, null);

        feed.add(new ChainTarget(257 + 256, 0, 0));
        extend(session, feed, null);
        Assert.assertEquals("新锚点 +256 不得重锚", 1, session.getReanchorCount());
        Assert.assertEquals(257, session.getAnchorX());

        feed.add(new ChainTarget(257 + 257, 0, 0));
        extend(session, feed, null);
        Assert.assertEquals("新锚点 +257 必须重锚", 2, session.getReanchorCount());
        Assert.assertEquals(514, session.getAnchorX());
        assertEquivalent("after_reanchor_2", session, feed, null);

        GenerationSession small = builder.beginGeneration(4);
        Assert.assertEquals(4, small.getReanchorDistance());
        List<ChainTarget> smallFeed = new ArrayList<ChainTarget>();
        smallFeed.add(new ChainTarget(0, 0, 0));
        extract(small, smallFeed);
        smallFeed.add(new ChainTarget(4, 0, 0));
        extract(small, smallFeed);
        Assert.assertEquals(0, small.getReanchorCount());
        smallFeed.add(new ChainTarget(5, 0, 0));
        extract(small, smallFeed);
        Assert.assertEquals(1, small.getReanchorCount());
        Assert.assertEquals(5, small.getAnchorX());

        Assert.assertEquals("重锚距离必须钳制到 >= 1", 1, builder.beginGeneration(0).getReanchorDistance());
        Assert.assertEquals("负值同样钳制", 1, builder.beginGeneration(-7).getReanchorDistance());
    }

    private static void extract(GenerationSession session, List<ChainTarget> chronological) {
        session.extend(newestFirst(chronological), null, VISUALS, THICKNESS);
    }

    @Test
    public void reanchorIsEquivalentToFullAssemblyWithReportedAnchor() {
        List<ChainTarget> chronological = new ArrayList<ChainTarget>();
        for (int x = 0; x < 20; x++) {
            chronological.add(new ChainTarget(x, 0, 0));
        }
        chronological.add(new ChainTarget(400, 0, 0));
        for (int z = 1; z < 5; z++) {
            chronological.add(new ChainTarget(400, 0, z));
        }
        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh mesh = extend(session, chronological, null);
        Assert.assertEquals("必须发生一次重锚", 1, session.getReanchorCount());
        Assert.assertEquals("重锚锚点必须是触发目标", 400, session.getAnchorX());
        Assert.assertEquals("meshOrigin 必须等于代内锚点", session.getAnchorX(), mesh.getOriginX());
        Assert.assertEquals(session.getAnchorY(), mesh.getOriginY());
        Assert.assertEquals(session.getAnchorZ(), mesh.getOriginZ());
        Assert.assertEquals(chronological.size(), session.getGenerationTargetCount());
        Assert.assertEquals("重锚后不得丢目标", chronological.size(), mesh.getBlockCount());
        assertEquivalent("reanchor_full_equivalence", session, chronological, null);
    }

    @Test
    public void duplicatesQuotaAndShrinkSemantics() {
        // 重复目标去重
        GenerationSession duplicates = new ChainPreviewMeshBuilder().beginGeneration();
        List<ChainTarget> repeated = new ArrayList<ChainTarget>();
        for (int index = 0; index < 64; index++) {
            repeated.add(new ChainTarget(7, 64, -3));
        }
        ChainPreviewMesh duplicateMesh = extend(duplicates, repeated, null);
        Assert.assertEquals(1, duplicates.getGenerationTargetCount());
        Assert.assertEquals(1, duplicateMesh.getBlockCount());
        Assert.assertEquals(0, duplicates.getReanchorCount());
        assertEquivalent("duplicates", duplicates, Collections.singletonList(new ChainTarget(7, 64, -3)), null);

        // 配额：4100 目标 -> 保留 4096 且置截断，累积有界
        List<ChainTarget> beyond = VerifyShapes.line(4100);
        GenerationSession quota = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh quotaMesh = extend(quota, beyond, null);
        Assert.assertTrue("超配额必须置 truncated", quotaMesh.isTruncated());
        Assert.assertEquals(4096, quotaMesh.getBlockCount());
        Assert.assertTrue("累积必须有界: " + quota.getGenerationTargetCount(),
            quota.getGenerationTargetCount() <= 4097);
        Assert.assertTrue(quota.getGenerationTargetCount() >= 4096);
        assertEquivalent("quota", quota, beyond, null);

        // 快照收缩：本步语义为「代内累积」，已见目标不会被移除（换代表达删除）
        GenerationSession shrink = new ChainPreviewMeshBuilder().beginGeneration();
        extend(shrink, VerifyShapes.line(5), null);
        ChainPreviewMesh before = shrink.getMesh();
        int blocksBefore = before.getBlockCount();
        extend(shrink, VerifyShapes.line(2), null);
        Assert.assertEquals("同代内快照收缩不得移除已见目标（已知语义）", blocksBefore, shrink.getMesh().getBlockCount());
        assertEquivalent("shrink_accumulate_only", shrink, VerifyShapes.line(5), null);
    }

    @Test
    public void disposeIsConsumedOnNextExtendAndResetsState() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        List<ChainTarget> targets = VerifyShapes.line(3);
        extend(session, targets, null);
        ChainPreviewMesh before = session.getMesh();
        Assert.assertFalse(session.isDisposed());

        session.dispose();
        Assert.assertTrue("dispose 请求必须立即可见", session.isDisposed());
        Assert.assertSame("释放请求尚未被构建线程消费前不得丢弃网格", before, session.getMesh());

        try {
            extend(session, targets, null);
            Assert.fail("dispose 后再次 extend 必须被拒绝");
        } catch (IllegalStateException expected) {
            Assert.assertNotNull(expected);
        }
        Assert.assertNull("消费释放请求后网格必须被清空", session.getMesh());
        Assert.assertEquals(0, session.getGenerationTargetCount());
        Assert.assertEquals(0, session.getAnchorX());

        session.dispose();
        Assert.assertTrue("重复 dispose 必须幂等", session.isDisposed());

        GenerationSession fresh = builder.beginGeneration();
        extend(fresh, VerifyShapes.single(9, 9, 9), null);
        Assert.assertEquals("新代必须从自己的首个目标起锚", 9, fresh.getAnchorX());
        Assert.assertFalse(fresh.isDisposed());
    }

    /** 未重锚时代内锚点 = 首个目标，增量结果必须与一次性全量构建逐字节一致（第二条交叉校验）。 */
    @Test
    public void incrementalGenerationIsByteIdenticalToFullBuildWhenAnchorIsFirstTarget() {
        List<ChainTarget> chronological = VerifyShapes.lShape(12);
        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh incremental = extend(session, chronological, null);
        Assert.assertEquals(0, session.getReanchorCount());
        Assert.assertEquals("未重锚时锚点必须是首个目标", chronological.get(0).getX(), session.getAnchorX());
        ChainPreviewMesh full = new ChainPreviewMeshBuilder().build(
            VerifyFeeds.snapshot(chronological), VISUALS, THICKNESS, null);
        Assert.assertEquals(full.getVertexFloatCount(), incremental.getVertexFloatCount());
        Assert.assertArrayEquals(full.vertexArray(), incremental.vertexArray(), 0.0F);
        Assert.assertArrayEquals(full.colorArray(), incremental.colorArray(), 0.0F);
        Assert.assertArrayEquals(full.indexArray(), incremental.indexArray());
        Assert.assertArrayEquals(full.auxArray(), incremental.auxArray());
        Assert.assertEquals(full.getOriginX(), incremental.getOriginX());
        Assert.assertEquals(full.getBlockCount(), incremental.getBlockCount());
        Assert.assertEquals(full.isTruncated(), incremental.isTruncated());
    }

    /**
     * 两条路径（一次性 begin()/advance() 与代级 extend()）对同一输入必须给出同口径的降级计数；
     * 且 {@code begin(..., semanticClasses)} 的冻结口径写明「null = 未提供类别，不计降级」。
     */
    @Test
    public void carrierFallbackCounterAgreesAcrossPaths() {
        List<ChainTarget> targets = VerifyShapes.line(3);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();

        ChainPreviewMeshBuilder.BuildSession direct =
            builder.begin(targets, VISUALS, THICKNESS, null);
        direct.advance(null);
        GenerationSession session = builder.beginGeneration();
        extend(session, targets, null);
        Assert.assertEquals("null 载体按冻结口径不计降级", 0, direct.getSemanticClassFallbackCount());
        Assert.assertEquals("同一输入两条路径的降级计数必须同口径",
            direct.getSemanticClassFallbackCount(), session.getSemanticClassFallbackCount());

        int[] truncatedCarrier = new int[] {3};
        ChainPreviewMeshBuilder.BuildSession directShort =
            builder.begin(targets, VISUALS, THICKNESS, truncatedCarrier);
        directShort.advance(null);
        GenerationSession sessionShort = builder.beginGeneration();
        extend(sessionShort, targets, truncatedCarrier);
        Assert.assertEquals("短载体必须两边都计入缺失目标", 2, directShort.getSemanticClassFallbackCount());
        Assert.assertEquals("短载体降级计数必须同口径",
            directShort.getSemanticClassFallbackCount(), sessionShort.getSemanticClassFallbackCount());

        int[] illegalCarrier = new int[] {0, 999, 2};
        ChainPreviewMeshBuilder.BuildSession directIllegal =
            builder.begin(targets, VISUALS, THICKNESS, illegalCarrier);
        directIllegal.advance(null);
        GenerationSession sessionIllegal = builder.beginGeneration();
        extend(sessionIllegal, targets, illegalCarrier);
        Assert.assertEquals(1, directIllegal.getSemanticClassFallbackCount());
        Assert.assertEquals("非法值降级计数必须同口径",
            directIllegal.getSemanticClassFallbackCount(), sessionIllegal.getSemanticClassFallbackCount());
    }

    private static void assertQuadWithin(String quad, int[] addition, float limit, GenerationSession session) {
        for (String corner : quad.split(";")) {
            if (corner.isEmpty()) {
                continue;
            }
            String[] parts = corner.split(",");
            int[] anchor = {session.getAnchorX(), session.getAnchorY(), session.getAnchorZ()};
            for (int axis = 0; axis < 3; axis++) {
                float local = Float.intBitsToFloat((int) Long.parseLong(parts[axis], 16));
                float world = local + anchor[axis];
                Assert.assertTrue("受影响的 quad 越出 26 邻域: " + corner + " 轴 " + axis
                    + " 距 " + addition[axis] + " = " + Math.abs(world - addition[axis]),
                    Math.abs(world - addition[axis]) <= limit);
            }
        }
    }
}
