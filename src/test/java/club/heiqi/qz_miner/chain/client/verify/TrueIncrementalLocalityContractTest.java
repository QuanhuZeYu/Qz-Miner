package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T31 波次 6 真增量局部性契约（B4.1 第二步）。
 *
 * <p>判据不是「结果相等」而是「增量只动了该动的地方」：新增目标后，逐目标网格的
 * 世界坐标顶点集合差集必须**恰好等于**独立参考模型给出的差集（新增/消失两侧），
 * 且落在新增目标的 26 邻域内。另含「多次 extend == 一次性构建」与「无残留」判据。</p>
 */
public class TrueIncrementalLocalityContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float THICKNESS = 0.045F;

    private static List<ChainTarget> newestFirst(List<ChainTarget> chronological) {
        List<ChainTarget> copy = new ArrayList<ChainTarget>(chronological);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private static ChainPreviewMesh extend(GenerationSession session, List<ChainTarget> chronological) {
        return session.extend(newestFirst(chronological), null, VISUALS, THICKNESS);
    }

    private static List<float[]> expectedWorldVertices(List<ChainTarget> chronological) {
        VerifyMeshReferenceModel.Expectation expectation =
            VerifyMeshReferenceModel.build(chronological, null, THICKNESS, false);
        List<float[]> vertices = new ArrayList<float[]>();
        for (String key : expectation.vertices.keySet()) {
            String[] parts = key.split(",");
            vertices.add(new float[] {
                expectation.originX + Float.intBitsToFloat((int) Long.parseLong(parts[0], 16)),
                expectation.originY + Float.intBitsToFloat((int) Long.parseLong(parts[1], 16)),
                expectation.originZ + Float.intBitsToFloat((int) Long.parseLong(parts[2], 16))
            });
        }
        return vertices;
    }

    @Test
    public void incrementalVertexDeltaEqualsExpectedNeighborhoodDelta() {
        List<ChainTarget> base = VerifyShapes.plane(3);
        int[][] additions = {
            {4, 0, 0}, {-3, 0, 0}, {0, 4, 0}, {0, -3, 0}, {0, 0, 4}, {0, 0, -3},
            {4, 4, 0}, {-3, -3, 0}, {4, 0, 4}, {0, 4, 4}, {4, 4, 4}, {-3, 4, -3},
            {16, 0, 0}, {15, 0, 0}, {16, 16, 0}
        };
        for (int[] addition : additions) {
            List<ChainTarget> extended = new ArrayList<ChainTarget>(base);
            extended.add(new ChainTarget(addition[0], addition[1], addition[2]));

            GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
            ChainPreviewMesh before = extend(session, base);
            ChainPreviewMesh after = extend(session, extended);
            Assert.assertEquals("追加不得触发重锚", 0, session.getReanchorCount());

            List<float[]> actualBefore = VerifyWorldGeometry.vertices(before);
            List<float[]> actualAfter = VerifyWorldGeometry.vertices(after);
            List<float[]> addedVertices =
                VerifyWorldGeometry.difference(actualAfter, actualBefore, VerifyWorldGeometry.DEFAULT_TOLERANCE);
            List<float[]> removedVertices =
                VerifyWorldGeometry.difference(actualBefore, actualAfter, VerifyWorldGeometry.DEFAULT_TOLERANCE);
            Assert.assertFalse("追加必须真的改变几何: " + Arrays.toString(addition), addedVertices.isEmpty());

            List<float[]> expectedBefore = expectedWorldVertices(base);
            List<float[]> expectedAfter = expectedWorldVertices(extended);
            List<float[]> expectedAdded =
                VerifyWorldGeometry.difference(expectedAfter, expectedBefore, VerifyWorldGeometry.DEFAULT_TOLERANCE);
            List<float[]> expectedRemoved =
                VerifyWorldGeometry.difference(expectedBefore, expectedAfter, VerifyWorldGeometry.DEFAULT_TOLERANCE);

            Assert.assertNull("新增顶点集合必须恰好等于参考模型差集: " + Arrays.toString(addition),
                VerifyWorldGeometry.diff(expectedAdded, addedVertices, VerifyWorldGeometry.DEFAULT_TOLERANCE));
            Assert.assertNull("消失顶点集合必须恰好等于参考模型差集: " + Arrays.toString(addition),
                VerifyWorldGeometry.diff(expectedRemoved, removedVertices, VerifyWorldGeometry.DEFAULT_TOLERANCE));
            VerifyWorldGeometry.assertWithinNeighborhood("新增侧", addedVertices, addition, 2.6F);
            VerifyWorldGeometry.assertWithinNeighborhood("消失侧", removedVertices, addition, 2.6F);
        }
    }

    @Test
    public void multiExtendEqualsSingleShotBuild() {
        List<ChainTarget> chronological = new ArrayList<ChainTarget>();
        for (int x = 0; x < 8; x++) {
            chronological.add(new ChainTarget(x, 0, 0));
        }
        for (int z = 1; z < 4; z++) {
            chronological.add(new ChainTarget(7, 0, z));
        }
        GenerationSession stepwise = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh stepwiseMesh = null;
        for (int count = 1; count <= chronological.size(); count++) {
            stepwiseMesh = extend(stepwise, chronological.subList(0, count));
        }
        ChainPreviewMesh oneShot = new ChainPreviewMeshBuilder().build(
            VerifyFeeds.snapshot(chronological), VISUALS, THICKNESS, null);
        Assert.assertNotNull(stepwiseMesh);
        Assert.assertEquals(oneShot.getVertexFloatCount(), stepwiseMesh.getVertexFloatCount());
        Assert.assertArrayEquals("逐批 extend 必须与一次性构建逐字节一致",
            oneShot.vertexArray(), stepwiseMesh.vertexArray(), 0.0F);
        Assert.assertArrayEquals(oneShot.colorArray(), stepwiseMesh.colorArray(), 0.0F);
        Assert.assertArrayEquals(oneShot.indexArray(), stepwiseMesh.indexArray());
        Assert.assertArrayEquals(oneShot.auxArray(), stepwiseMesh.auxArray());
        Assert.assertEquals(oneShot.getOriginX(), stepwiseMesh.getOriginX());
        Assert.assertEquals(oneShot.getBlockCount(), stepwiseMesh.getBlockCount());
    }

    @Test
    public void repeatedAndShrinkingSnapshotsLeaveNoResidue() {
        List<ChainTarget> full = VerifyShapes.plane(4);
        List<ChainTarget> shrunk = new ArrayList<ChainTarget>(full.subList(0, 5));

        GenerationSession session = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh fromFull = extend(session, full);
        ChainPreviewMesh repeated = extend(session, full);
        Assert.assertArrayEquals("同快照重复 extend 必须幂等",
            fromFull.vertexArray(), repeated.vertexArray(), 0.0F);

        ChainPreviewMesh afterShrink = extend(session, shrunk);
        GenerationSession fresh = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh freshMesh = extend(fresh, shrunk);
        // 同代收缩语义：ChainPreviewState 无移除 API（删除只能经换代表达），故当前为「代内累积」。
        // 若 T29 引入移除语义，这里应变为「收缩后 == 全新会话」——两者都不得产生残留或幽灵几何
        // （下面的回归等价断言用「与全新会话的几何差集为空」的后半段覆盖）。
        Assert.assertTrue("收缩不得让几何小于同快照全新会话: fresh=" + freshMesh.getBlockCount()
            + " actual=" + afterShrink.getBlockCount(),
            afterShrink.getBlockCount() >= freshMesh.getBlockCount());
        Assert.assertEquals("代内累积语义下可见块数必须保持不变（移除需换代）",
            fromFull.getBlockCount(), afterShrink.getBlockCount());

        // 再新增回原集合：同样必须与全新会话一致
        ChainPreviewMesh afterRegrow = extend(session, full);
        GenerationSession regrown = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh regrownMesh = extend(regrown, full);
        Assert.assertNull("增删往复后必须与全新会话一致（无残留、无幽灵几何）",
            VerifyWorldGeometry.diff(VerifyWorldGeometry.vertices(regrownMesh),
                VerifyWorldGeometry.vertices(afterRegrow), VerifyWorldGeometry.DEFAULT_TOLERANCE));
        Assert.assertEquals("锚点必须与全新会话一致", regrownMesh.getOriginX(), afterRegrow.getOriginX());
        Assert.assertEquals("块数必须与全新会话一致", regrownMesh.getBlockCount(), afterRegrow.getBlockCount());
    }

    @Test
    public void incrementalDeltaShrinksWithRepeatedAppends() {
        // 差分强度：单批追加与逐目标追加的「受影响顶点数」必须一致（局部性成立时两者相同）
        List<ChainTarget> base = VerifyShapes.line(4);
        List<ChainTarget> appended = new ArrayList<ChainTarget>(base);
        appended.add(new ChainTarget(4, 0, 0));
        appended.add(new ChainTarget(5, 0, 0));

        GenerationSession batched = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh beforeBatch = extend(batched, base);
        ChainPreviewMesh afterBatch = extend(batched, appended);
        List<float[]> batchedDelta = VerifyWorldGeometry.difference(
            VerifyWorldGeometry.vertices(afterBatch), VerifyWorldGeometry.vertices(beforeBatch),
            VerifyWorldGeometry.DEFAULT_TOLERANCE);

        GenerationSession stepped = new ChainPreviewMeshBuilder().beginGeneration();
        ChainPreviewMesh beforeStep = extend(stepped, base);
        extend(stepped, appended.subList(0, base.size() + 1));
        ChainPreviewMesh afterStep = extend(stepped, appended);
        List<float[]> steppedDelta = VerifyWorldGeometry.difference(
            VerifyWorldGeometry.vertices(afterStep), VerifyWorldGeometry.vertices(beforeStep),
            VerifyWorldGeometry.DEFAULT_TOLERANCE);

        Assert.assertNull("逐目标追加与批量追加的受影响顶点集必须一致",
            VerifyWorldGeometry.diff(batchedDelta, steppedDelta, VerifyWorldGeometry.DEFAULT_TOLERANCE));
    }
}
