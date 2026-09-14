package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T7 基线几何契约（独立金值）。
 *
 * <p>金值来自 temp/chain-preview/verify/cp_verify_baseline.py 的独立 Python 重建模型，
 * 与目标仓既有测试夹具、owner 探针均无复用关系。任何一项在本次施工后变化都意味着
 * 默认观感被改变，必须回到 Lead 裁决。</p>
 *
 * <p><b>T51 方案 A（顶点按面分裂）后的金值更新：</b>顶点身份由「位置」升级为「(位置, 面)」，
 * 金值改由 temp/chain-preview/verify/cp_verify_split_model.py 独立复算（同一几何重建，
 * 按 CUBOID_QUAD_INDICES 展开每个 quad 的角点后按 (位置, 面) 去重）。该模型自检
 * 「split 顶点的位置投影 == 原位置数」与「quad 数不变」全部通过，quads 金值因此完全未变。
 * lead 裁决依据：方向流本身在分裂前恒为零（同位置冲突法线被归零），方案 A 是方向语义
 * 生效的前置条件。</p>
 */
public class BaselineGeometryContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);

    @Test
    public void isolatedAndConnectedShapesMatchIndependentModel() {
        assertShape("single", VerifyShapes.single(0, 0, 0), 168, 72, 1, false);
        assertShape("adjacent_x2", VerifyShapes.line(2), 200, 88, 2, false);
        assertShape("line_100", VerifyShapes.line(100), 3336, 1656, 100, false);
        assertShape("plane_16x16", VerifyShapes.plane(16), 1128, 552, 60, false);
        assertShape("solid_10_cube", VerifyShapes.solidCube(10), 1032, 504, 104, false);
        assertShape("lshape_64", VerifyShapes.lShape(64), 4252, 2108, 127, false);
        assertShape("edge_contact", VerifyShapes.edgeContact(), 300, 130, 2, false);
        assertShape("corner_contact", VerifyShapes.cornerContact(), 318, 138, 2, false);
        assertShape("duplicates_1024", VerifyShapes.duplicated(1024), 168, 72, 1, false);
    }

    @Test
    public void isolated4096TargetsMatchIndependentScaleModel() {
        // 独立模型：每目标 12 边 × 4 面 = 48 quad，8 接头 × 3 面 = 24 quad，合计 72 quad；
        // 顶点 = (位置, 面) 配对数：8 接头位置 × 8 offset × 3 面 = 192/目标，其中 24 个被管段复用，净 168/目标；
        // 4096 × 168 = 688128（quads 与位置数口径不变）。
        assertShape("scattered_4096", VerifyShapes.scatteredX(4096, 3), 688128, 294912, 4096, false);
        assertShape("scatter_lattice_4096", VerifyShapes.deterministicScatter(4096), 688128, 294912, 4096, false);
    }

    @Test
    public void hardCapKeepsFirstRetainedSnapshotAndFlagsTruncation() {
        List<ChainTarget> beyond = VerifyShapes.scatteredX(4097, 3);
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(VerifyFeeds.snapshot(beyond), VISUALS);
        Assert.assertTrue("超出 4096 必须置 truncated", mesh.isTruncated());
        Assert.assertEquals(4096, mesh.getBlockCount());
        Assert.assertEquals(688128, mesh.getVertexFloatCount() / 3);
        Assert.assertEquals(294912 * 4, mesh.getIndexCount());
        Assert.assertEquals(0, mesh.getOriginX());
        float[] vertices = mesh.getVertices();
        float maxX = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < vertices.length; offset += 3) {
            maxX = Math.max(maxX, vertices[offset]);
        }
        // 第 4097 个目标位于 x = 4096*3 = 12288；未被保留时最大 x 只能到 12285+1+0.0225。
        Assert.assertEquals(12286.0225F, maxX, 0.001F);

        ChainPreviewMesh exact = new ChainPreviewMeshBuilder().build(
            VerifyFeeds.snapshot(VerifyShapes.scatteredX(4096, 3)), VISUALS);
        Assert.assertFalse("正好 4096 不得置 truncated", exact.isTruncated());
    }

    @Test
    public void meshOriginIsFirstUniquePositionInIterationOrder() {
        List<ChainTarget> ordered = Arrays.asList(
            new ChainTarget(5, 0, 0),
            new ChainTarget(0, 0, 0),
            new ChainTarget(5, 0, 0));
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(ordered, VISUALS);
        Assert.assertEquals(5, mesh.getOriginX());
        Assert.assertEquals(0, mesh.getOriginY());
        Assert.assertEquals(0, mesh.getOriginZ());
        Assert.assertEquals(2, mesh.getBlockCount());
    }

    @Test
    public void defaultThicknessStaysBaselineAfterFromCurrentConfig() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            VerifyShapes.single(0, 0, 0),
            VisualParameters.fromCurrentConfig(0.5D, 0.5D, 0.5D));
        float[] vertices = mesh.getVertices();
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        for (int offset = 0; offset < vertices.length; offset += 3) {
            minX = Math.min(minX, vertices[offset]);
            maxX = Math.max(maxX, vertices[offset]);
        }
        // 基线 barThickness = 0.045 -> 半厚 0.0225。
        Assert.assertEquals(-0.0225F, minX, 0.0005F);
        Assert.assertEquals(1.0225F, maxX, 0.0005F);
    }

    @Test
    public void everyShapePassesIndependentMeshAudit() {
        List<List<ChainTarget>> shapes = new ArrayList<List<ChainTarget>>();
        shapes.add(VerifyShapes.single(3, -2, 7));
        shapes.add(VerifyShapes.line(64));
        shapes.add(VerifyShapes.plane(9));
        shapes.add(VerifyShapes.solidCube(5));
        shapes.add(VerifyShapes.lShape(32));
        shapes.add(VerifyShapes.edgeContact());
        shapes.add(VerifyShapes.cornerContact());
        shapes.add(VerifyShapes.scatteredX(128, 3));
        shapes.add(VerifyShapes.duplicated(64));
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        for (List<ChainTarget> targets : shapes) {
            ChainPreviewMesh mesh = builder.build(targets, VISUALS);
            VerifyMeshAudit.Report report = VerifyMeshAudit.audit(mesh);
            String label = "shape[" + targets.size() + "]";
            Assert.assertEquals(label + " 索引越界", 0, report.indexOutOfRange);
            Assert.assertEquals(label + " 索引非 quad 对齐", 0, report.indexCountNotQuadAligned);
            Assert.assertEquals(label + " 存在未引用顶点", 0, report.unusedVertex);
            Assert.assertEquals(label + " 退化 quad", 0, report.degenerateQuad);
            Assert.assertEquals(label + " 重复 quad", 0, report.duplicateQuad);
            // T51 方案 A（顶点按面分裂）后，同一位置出现多个顶点是**预期结构**（外扩方向 = 该面法线），
            // 因此不再断言「位置唯一」——那与本方案直接对立。改判「同位置顶点的语义属性必须一致」：
            // 否则逐波生长时同位置的不同面会在不同时刻出现，条柱表面露缝。构建侧由
            // normalizeAppearOrderByPosition() 保证，本行是它的独立复核（金值来源见类注释）。
            Assert.assertEquals(label + " 同位置顶点语义不一致", 0, report.inconsistentSharedVertexSemantics);
            Assert.assertEquals(label + " 开放边", 0, report.openEdge);
            Assert.assertEquals(label + " 非流形边", 0, report.nonManifoldEdge);
            Assert.assertEquals(label + " 绕向不一致", 0, report.inconsistentWinding);
            Assert.assertTrue(label + " 必须为外向 CCW 闭合体", report.signedVolume > 0.0D);
        }
    }

    @Test
    public void rebuildOfSameInputIsByteIdentical() {
        List<ChainTarget> targets = VerifyShapes.solidCube(6);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh first = builder.build(targets, VISUALS);
        ChainPreviewMesh second = builder.build(targets, VISUALS);
        Assert.assertArrayEquals(first.getVertices(), second.getVertices(), 0.0F);
        Assert.assertArrayEquals(first.getColors(), second.getColors(), 0.0F);
        Assert.assertArrayEquals(first.getIndices(), second.getIndices());
        Assert.assertEquals(first.getOriginX(), second.getOriginX());
        Assert.assertEquals(first.getBlockCount(), second.getBlockCount());
    }

    private static void assertShape(
            String label,
            List<ChainTarget> targets,
            int expectedVertices,
            int expectedQuads,
            int expectedBlocks,
            boolean expectedTruncated) {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(targets, VISUALS);
        Assert.assertEquals(label + " 顶点数", expectedVertices, mesh.getVertexFloatCount() / 3);
        Assert.assertEquals(label + " 颜色浮点数", expectedVertices * 4, mesh.getColorFloatCount());
        Assert.assertEquals(label + " 索引数", expectedQuads * 4, mesh.getIndexCount());
        Assert.assertEquals(label + " 可见方块数", expectedBlocks, mesh.getBlockCount());
        Assert.assertEquals(label + " truncated", expectedTruncated, mesh.isTruncated());
    }
}
