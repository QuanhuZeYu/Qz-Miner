package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

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
 * <p>共享顶点口径：同一条边被多个面引用时，其方向在建网格阶段被合并为<b>零向量</b>
 * （不外扩），保证共享角点不会被推向任一轴；零方向顶点在着色器里恒等退化。</p>
 */
public class DirectionStreamContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float EPSILON = 0.001F;
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
     * 记录当前缺口：条柱几何的全部角点都是多面共享，方向流因此恒为零，外扩实际未生效。
     *
     * <p>实测：SDF 条柱几何里每个角点都被 2~3 个面共享，因此 {@code mergeDirection} 的
     * 「不一致即归零」策略把<b>全部</b>顶点都归零（line(8) 直方图 {(0,0,0)=176}），
     * 外扩恒等失效。方向语义需重新设计（顶点分裂 / 显式横向轴 / 面内独立方向），
     * 在此之前不得断言朝向——否则等于把未实现的口径写成绿的。</p>
     */
    @Test
    public void allCornersAreSharedSoDirectionStreamIsCurrentlyAllZero() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(VerifyShapes.line(8), VISUALS);
        float[] directions = mesh.getDirections();
        int vertexCount = mesh.getVertexFloatCount() / 3;
        int oriented = 0;
        TreeMap<String, Integer> histogram = new TreeMap<String, Integer>();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            float x = directions[vertex * FLOATS_PER_VERTEX];
            float y = directions[vertex * FLOATS_PER_VERTEX + 1];
            float z = directions[vertex * FLOATS_PER_VERTEX + 2];
            String key = "(" + x + "," + y + "," + z + ")";
            Integer previous = histogram.get(key);
            histogram.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
            if (!near(Math.abs(x) + Math.abs(y) + Math.abs(z), 0.0F)) {
                oriented++;
            }
        }
        Assert.assertEquals("方向语义已改变（出现非零方向顶点）——请删除本用例并补朝向断言；直方图=" + histogram,
                0, oriented);
        Assert.assertTrue("方向流必须与顶点数绑定；直方图=" + histogram, histogram.containsKey("(0.0,0.0,0.0)"));
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
