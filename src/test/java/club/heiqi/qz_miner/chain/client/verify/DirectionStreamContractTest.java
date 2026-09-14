package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.MeshBuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 外扩方向流 aDirection 独立契约探针（接口冻结 §A 修订 T51）。
 *
 * <p>这条流只服务两件事：屏幕最小宽度与真描边的<b>横向外扩</b>。它必须是「每顶点的显式面方向」——
 * 着色器绝不允许从 {@code aPos} 的绝对值猜横向轴（长条端点 / junction / 跨轴线段会被误判，
 * 整面被推离原始几何）。因此本探针只钉两件事：<b>值域</b>（零或六面单位法线）与
 * <b>朝向</b>（非零分量必须真的指向该轴的极值面，写反了立刻红灯）。</p>
 *
 * <p>共享顶点口径（T51 方案 A）：顶点身份 = (位置, 面)，每顶点只属一个面，方向即该面单位法线，
 * 同位置的相邻面各持一个顶点、互不干扰。分裂前「同位置方向冲突即归零」的合并策略会把
 * <b>全部</b>角点归零（SDF 条柱的每个角点都被 2~3 个面共享），外扩因此恒等失效——方案 A 是
 * 该语义生效的前置条件。</p>
 */
public class DirectionStreamContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float EPSILON = 0.001F;
    /** float 顶点坐标经叉积后的法线容差。 */
    private static final double NORMAL_EPSILON = 1.0E-4D;
    private static final int FLOATS_PER_VERTEX = 3;

    @Test
    public void directionStreamIsBoundToVertexCountAndStaysInValueDomain() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<List<ChainTarget>> fixtures = new ArrayList<List<ChainTarget>>();
        fixtures.add(VerifyShapes.line(64));
        fixtures.add(VerifyShapes.lShape(64));
        fixtures.add(VerifyShapes.plane(9));
        fixtures.add(VerifyShapes.single(0, 0, 0));
        for (List<ChainTarget> targets : fixtures) {
            ChainPreviewMesh mesh = builder.build(targets, VISUALS);
            int vertexCount = mesh.getVertexFloatCount() / 3;
            Assert.assertTrue("构建路径必须产出方向流", mesh.isDirectionAvailable());
            Assert.assertNull("方向可用时不得留降级原因", mesh.getDirectionDegradationReason());
            float[] directions = mesh.getDirections();
            Assert.assertNotNull(directions);
            Assert.assertEquals(vertexCount * FLOATS_PER_VERTEX, directions.length);
            Assert.assertEquals(vertexCount * FLOATS_PER_VERTEX, mesh.getDirectionFloatCount());
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                float x = directions[vertex * FLOATS_PER_VERTEX];
                float y = directions[vertex * FLOATS_PER_VERTEX + 1];
                float z = directions[vertex * FLOATS_PER_VERTEX + 2];
                Assert.assertTrue("方向分量必须是有限值", isFinite(x) && isFinite(y) && isFinite(z));
                float magnitude = Math.abs(x) + Math.abs(y) + Math.abs(z);
                Assert.assertTrue("方向必须恰好是零向量或某个单位面法线（顶点 " + vertex
                    + " -> (" + x + "," + y + "," + z + ")）", near(magnitude, 0.0F) || near(magnitude, 1.0F));
            }
        }
    }

    /**
     * 方案 A（顶点按面分裂）后方向语义已生效：顶点身份 = (位置, 面)，方向 = 该面单位法线，
     * 因此不存在「零方向」顶点。
     *
     * <p>判据取<b>几何自洽</b>而非构建器的面知识：逐 quad 用顶点绕向算外法线（{@code VerifyMeshAudit}
     * 已独立确认全部 quad 为外向 CCW 闭合体），它必须等于该 quad 四个顶点的方向流值。方向写反、
     * 同面顶点方向不一致、或顶点跨面共享（一个顶点被两个法线不同的面引用）都会立刻红灯。</p>
     */
    @Test
    public void everyQuadNormalMatchesItsVertexDirections() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<List<ChainTarget>> fixtures = new ArrayList<List<ChainTarget>>();
        fixtures.add(VerifyShapes.line(8));
        fixtures.add(VerifyShapes.line(64));
        fixtures.add(VerifyShapes.lShape(64));
        fixtures.add(VerifyShapes.single(0, 0, 0));
        fixtures.add(VerifyShapes.plane(9));
        for (List<ChainTarget> targets : fixtures) {
            String label = "shape[" + targets.size() + "]";
            ChainPreviewMesh mesh = builder.build(targets, VISUALS);
            Assert.assertTrue(label + " 构建路径必须产出方向流", mesh.isDirectionAvailable());
            float[] vertices = mesh.getVertices();
            float[] directions = mesh.getDirections();
            int[] indices = mesh.getIndices();
            int vertexCount = mesh.getVertexFloatCount() / 3;
            Assert.assertEquals(label + " 方向流长度必须与顶点数绑定",
                vertexCount * FLOATS_PER_VERTEX, directions.length);
            int zeroDirections = 0;
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                if (near(magnitude(directions, vertex), 0.0F)) {
                    zeroDirections++;
                }
            }
            Assert.assertEquals(label + " 方案 A 下每顶点恰属一个面，不得出现零方向顶点", 0, zeroDirections);
            for (int offset = 0; offset + 3 < indices.length; offset += 4) {
                int[] quad = {indices[offset], indices[offset + 1], indices[offset + 2], indices[offset + 3]};
                String quadLabel = label + " quad " + (offset / 4);
                for (int corner = 1; corner < 4; corner++) {
                    for (int axis = 0; axis < FLOATS_PER_VERTEX; axis++) {
                        Assert.assertEquals(quadLabel + " 四个顶点必须同面同向",
                            directions[quad[0] * FLOATS_PER_VERTEX + axis],
                            directions[quad[corner] * FLOATS_PER_VERTEX + axis], EPSILON);
                    }
                }
                double[] normal = outwardNormal(vertices, quad[0], quad[1], quad[2]);
                Assert.assertTrue(quadLabel + " 不得退化", normal != null);
                Assert.assertEquals(quadLabel + " 外法线必须等于方向流 X",
                    normal[0], directions[quad[0] * FLOATS_PER_VERTEX], NORMAL_EPSILON);
                Assert.assertEquals(quadLabel + " 外法线必须等于方向流 Y",
                    normal[1], directions[quad[0] * FLOATS_PER_VERTEX + 1], NORMAL_EPSILON);
                Assert.assertEquals(quadLabel + " 外法线必须等于方向流 Z",
                    normal[2], directions[quad[0] * FLOATS_PER_VERTEX + 2], NORMAL_EPSILON);
            }
        }
    }

    /** @return 由 CCW 绕向得到的外法线（单位向量）；退化时返回 {@code null}。 */
    private static double[] outwardNormal(float[] vertices, int first, int second, int third) {
        double abX = vertices[second * 3] - vertices[first * 3];
        double abY = vertices[second * 3 + 1] - vertices[first * 3 + 1];
        double abZ = vertices[second * 3 + 2] - vertices[first * 3 + 2];
        double acX = vertices[third * 3] - vertices[first * 3];
        double acY = vertices[third * 3 + 1] - vertices[first * 3 + 1];
        double acZ = vertices[third * 3 + 2] - vertices[first * 3 + 2];
        double x = abY * acZ - abZ * acY;
        double y = abZ * acX - abX * acZ;
        double z = abX * acY - abY * acX;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length <= 1.0E-9D) {
            return null;
        }
        return new double[] {x / length, y / length, z / length};
    }

    private static float magnitude(float[] directions, int vertex) {
        return Math.abs(directions[vertex * FLOATS_PER_VERTEX])
            + Math.abs(directions[vertex * FLOATS_PER_VERTEX + 1])
            + Math.abs(directions[vertex * FLOATS_PER_VERTEX + 2]);
    }

    /** 同输入重建必须逐字节一致（方向流参与确定性契约）。 */
    @Test
    public void directionStreamIsDeterministicAcrossRebuild() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh first = builder.build(VerifyShapes.line(64), VISUALS);
        ChainPreviewMesh second = builder.build(VerifyShapes.line(64), VISUALS);
        Assert.assertArrayEquals("方向流必须逐字节可复现", first.getDirections(), second.getDirections(), 0.0F);
        Assert.assertArrayEquals(first.getVertices(), second.getVertices(), 0.0F);
    }

    /** 增量会话与全量构建必须产出同一条方向流（方向不得只在全量路径上写）。 */
    @Test
    public void incrementalSessionKeepsDirectionStreamConsistent() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<ChainTarget> targets = VerifyShapes.line(64);
        ChainPreviewMesh full = builder.build(VerifyFeeds.snapshot(targets), VISUALS);
        // 真机走的是代级增量路径（beginGeneration().beginRevision()）：方向流必须在两条路径上一致，
        // 否则「方向只写在全量路径」这类漏接线会悄悄退化成零方向（外扩静默失效）。
        GenerationSession generation = builder.beginGeneration();
        MeshBuildSession session = generation.beginRevision(
            VerifyFeeds.snapshot(targets), null, VISUALS, 0.045F);
        while (!session.advance(null)) {
            // 分片推进：null 门即「无让出」，与真机安全点语义一致。
        }
        ChainPreviewMesh incremental = session.getMesh();
        Assert.assertTrue("增量路径必须同样产出方向流", incremental.isDirectionAvailable());
        Assert.assertArrayEquals("增量与全量方向流必须逐字节一致",
            full.getDirections(), incremental.getDirections(), 0.0F);
    }

    /** 长度不符必须降级且可观察（与 aAux 同口径），不得半信半疑地当有效流用。 */
    @Test
    public void malformedDirectionLengthIsNotExposedAsValid() {
        ChainPreviewMesh malformed = new ChainPreviewMesh(
            new float[] {0.0F, 0.0F, 0.0F},
            new float[] {0.25F, 0.9F, 1.0F, 0.8F},
            new int[] {0, 0, 0, 0},
            1,
            new byte[] {1, 2, 3, 4},
            0,
            new float[] {0.0F, 1.0F});
        Assert.assertFalse("长度不符不得被当作有效方向流", malformed.isDirectionAvailable());
        Assert.assertNull(malformed.getDirections());
        Assert.assertEquals(0, malformed.getDirectionFloatCount());
        Assert.assertNotNull("降级原因必须可读", malformed.getDirectionDegradationReason());
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean near(float value, float expected) {
        return Math.abs(value - expected) <= EPSILON;
    }
}
