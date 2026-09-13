package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T35 波次 7 容量峰值契约（B4.2 / task-33）：峰值一律从公共访问器读、期望值由独立参考模型复算。
 */
public class CapacityPeakContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float THICKNESS = 0.045F;

    private static List<ChainTarget> newestFirst(List<ChainTarget> chronological) {
        List<ChainTarget> copy = new ArrayList<ChainTarget>(chronological);
        java.util.Collections.reverse(copy);
        return copy;
    }

    @Test
    public void peaksAreIndependentlyRecomputedFromReferenceModel() {
        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        Assert.assertEquals("容量上限必须复用唯一上限语义",
            ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, session.getCapacityLimit());

        int[] sizes = {3, 5, 8, 8};
        int maxVertices = 0;
        int maxIndices = 0;
        int previousEntries = 0;
        for (int size : sizes) {
            List<ChainTarget> chronological = VerifyShapes.scatteredX(size, 3);
            ChainPreviewMesh mesh = session.extend(
                newestFirst(chronological), null, VISUALS, THICKNESS);
            VerifyMeshReferenceModel.Expectation expectation =
                VerifyMeshReferenceModel.build(chronological, null, THICKNESS, false);
            int expectedVertices = expectation.vertices.size();
            int expectedIndices = expectation.quadCount * 4;
            maxVertices = Math.max(maxVertices, expectedVertices);
            maxIndices = Math.max(maxIndices, expectedIndices);

            Assert.assertEquals("保留目标数必须与独立模型一致", size, session.getCacheEntryCount());
            Assert.assertEquals("顶点数必须与独立模型一致", expectedVertices, mesh.getVertexFloatCount() / 3);
            Assert.assertEquals("索引数必须与独立模型一致", expectedIndices, mesh.getIndexCount());
            Assert.assertTrue("缓存条目必须随保留目标单调不减",
                session.getCacheEntryTotal() >= previousEntries);
            previousEntries = session.getCacheEntryTotal();
            Assert.assertTrue("缓存条目上界必须成立（<= 6 × 上限）",
                session.getCacheEntryTotal() <= 6 * session.getCapacityLimit());
            Assert.assertTrue("可见段上界必须成立（<= 12 × 唯一目标）",
                session.getVisibleSegmentCount() <= 12 * size);
            Assert.assertFalse("未超限不得置溢出", session.isOverflowed());
        }

        Assert.assertEquals("峰值保留目标数 = 最终唯一目标数", 8, session.getPeakRetainedTargetCount());
        Assert.assertEquals("峰值顶点数必须等于独立复算的最大值", maxVertices, session.getPeakVertexCount());
        Assert.assertEquals("峰值索引数必须等于独立复算的最大值", maxIndices, session.getPeakIndexCount());
        Assert.assertEquals("峰值 aux 字节 = 4 × 峰值顶点数", 4 * maxVertices, session.getPeakAuxBytes());
        Assert.assertTrue("峰值缓存条目必须不小于峰值保留目标",
            session.getPeakCacheEntryCount() >= session.getPeakRetainedTargetCount());
        Assert.assertTrue("峰值可见段必须为正", session.getPeakVisibleSegmentCount() > 0);
    }

    @Test
    public void overflowIsObservableAtBoundaryAndDedupDoesNotConsumeQuota() {
        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh exact = session.extend(
            newestFirst(VerifyShapes.scatteredX(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, 3)),
            null, VISUALS, THICKNESS);
        Assert.assertFalse("正好上限不得溢出", session.isOverflowed());
        Assert.assertFalse(exact.isTruncated());
        Assert.assertEquals(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, session.getCacheEntryCount());

        List<ChainTarget> over = new ArrayList<ChainTarget>(
            VerifyShapes.scatteredX(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, 3));
        over.add(new ChainTarget(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS * 3, 0, 0));
        ChainPreviewMesh overMesh = session.extend(newestFirst(over), null, VISUALS, THICKNESS);
        Assert.assertTrue("超过上限必须可独立观察溢出", session.isOverflowed());
        Assert.assertTrue(overMesh.isTruncated());

        GenerationSession duplicates = new ChainPreviewMeshBuilder().beginGeneration();
        List<ChainTarget> many = new ArrayList<ChainTarget>();
        for (int index = 0; index < ChainPreviewMeshBuilder.MAX_RENDER_TARGETS + 100; index++) {
            many.add(new ChainTarget(9, 9, 9));
        }
        duplicates.extend(newestFirst(many), null, VISUALS, THICKNESS);
        Assert.assertFalse("重复输入不得触发溢出（去重先于配额）", duplicates.isOverflowed());
        Assert.assertEquals(1, duplicates.getCacheEntryCount());
    }

    @Test
    public void capacityPublishesIntoCountersMonotonicallyAndResets() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        GenerationSession small = new ChainPreviewMeshBuilder().beginGeneration();
        small.extend(newestFirst(VerifyShapes.scatteredX(3, 3)), null, VISUALS, THICKNESS);
        small.publishCapacityInto(counters);
        long vertexPeak = counters.getPeakVertexCount();
        Assert.assertEquals("计数器必须接手会话峰值",
            small.getPeakVertexCount(), (int) vertexPeak);
        Assert.assertEquals(small.getPeakIndexCount(), (int) counters.getPeakIndexCount());
        Assert.assertEquals(small.getPeakAuxBytes(), (int) counters.getPeakAuxBytes());
        Assert.assertEquals(small.getPeakCacheEntryCount(), (int) counters.getPeakGenerationCacheEntries());

        // 更小会话不得让峰值回退
        GenerationSession tiny = new ChainPreviewMeshBuilder().beginGeneration();
        tiny.extend(newestFirst(VerifyShapes.single(0, 0, 0)), null, VISUALS, THICKNESS);
        tiny.publishCapacityInto(counters);
        Assert.assertEquals("峰值必须单调不减", vertexPeak, counters.getPeakVertexCount());

        GenerationSession larger = new ChainPreviewMeshBuilder().beginGeneration();
        larger.extend(newestFirst(VerifyShapes.scatteredX(24, 3)), null, VISUALS, THICKNESS);
        larger.publishCapacityInto(counters);
        Assert.assertTrue("更大会话必须抬高峰值", counters.getPeakVertexCount() > vertexPeak);
        Assert.assertEquals(larger.getPeakVertexCount(), (int) counters.getPeakVertexCount());

        counters.reset();
        Assert.assertEquals(0L, counters.getPeakVertexCount());
        Assert.assertEquals(0L, counters.getPeakIndexCount());
        Assert.assertEquals(0L, counters.getPeakAuxBytes());
        Assert.assertEquals(0L, counters.getPeakGenerationCacheEntries());

        larger.resetCapacityPeaks();
        Assert.assertEquals("峰值归零只影响读数", 0, larger.getPeakVertexCount());
        Assert.assertTrue("归零不得影响缓存", larger.getCacheEntryCount() > 0);
    }

    @Test
    public void reclamationZeroesPublicCacheCounters() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession session = builder.beginGeneration();
        session.extend(newestFirst(VerifyShapes.plane(4)), null, VISUALS, THICKNESS);
        Assert.assertTrue(session.getCacheEntryTotal() > 0);
        Assert.assertTrue(session.getVisibleSegmentCount() > 0);

        session.dispose();
        try {
            session.extend(newestFirst(VerifyShapes.plane(4)), null, VISUALS, THICKNESS);
            Assert.fail("dispose 后再次使用必须被拒绝");
        } catch (IllegalStateException expected) {
            Assert.assertNotNull(expected);
        }
        Assert.assertEquals("回收后缓存条目必须为 0", 0, session.getCacheEntryTotal());
        Assert.assertEquals(0, session.getCacheEntryCount());
        Assert.assertEquals("回收后可见段必须为 0", 0, session.getVisibleSegmentCount());
        Assert.assertEquals(0, session.getGenerationTargetCount());
        Assert.assertEquals("回收必须同时归零峰值", 0, session.getPeakRetainedTargetCount());
    }
}
