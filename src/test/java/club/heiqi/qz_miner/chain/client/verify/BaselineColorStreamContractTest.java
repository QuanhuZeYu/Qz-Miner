package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.config.QzMinerConfigDefaults;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T7 基线颜色流契约（独立金值）。
 *
 * <p>冻结口径：
 * <ul>
 * <li>所有顶点 R/G/B 必须逐位等于 (0.25F, 0.9F, 1.0F)；</li>
 * <li>alpha 是"世界坐标 → 相机距离"的二次淡出函数，d &lt;= 2 → 0.78，d &gt;= 6 → 0.15；</li>
 * <li>d = 4 的解析值 0.622499942779541 来自独立 Python 模型（float32 收窄）。</li>
 * </ul>
 */
public class BaselineColorStreamContractTest {

    private static final float BASE_RED = 0.25F;
    private static final float BASE_GREEN = 0.9F;
    private static final float BASE_BLUE = 1.0F;
    private static final float ALPHA_MAX = 0.78F;
    private static final float ALPHA_MIN = 0.15F;
    private static final double FADE_START = 2.0D;
    private static final double FADE_END = 6.0D;
    private static final float ALPHA_AT_DISTANCE_4 = 0.622499942779541F;
    private static final float EPSILON = 0.00001F;

    @Test
    public void baselineConfigDefaultsAreUnchanged() {
        Assert.assertEquals(2.0D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS, 0.0D);
        Assert.assertEquals(6.0D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS, 0.0D);
        Assert.assertEquals(0.78D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE, 0.0D);
        Assert.assertEquals(0.15D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE, 0.0D);
        Assert.assertEquals(16, QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS);
        Assert.assertEquals(1024, QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS);
        Assert.assertTrue(QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER);
    }

    @Test
    public void nearAndFarCamerasSaturateToFrozenAlphaBounds() {
        applyBaselineConfig();
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<ChainTarget> targets = VerifyShapes.line(8);

        // 单方块整体位于 fadeStart(2.0) 半径内 → 全部顶点必须饱和到 0.78。
        ChainPreviewMesh near = builder.build(
            VerifyShapes.single(0, 0, 0),
            VisualParameters.fromCurrentConfig(0.5D, 0.5D, 0.5D));
        assertBoundColorStream(near, ALPHA_MAX);

        ChainPreviewMesh far = builder.build(
            targets,
            VisualParameters.fromCurrentConfig(400.0D, 0.5D, 0.5D));
        assertBoundColorStream(far, ALPHA_MIN);
    }

    @Test
    public void alphaFollowsIndependentDistanceCurvePerVertex() {
        applyBaselineConfig();
        double cameraX = -3.9775D;
        double cameraY = -0.0225D;
        double cameraZ = -0.0225D;
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            VerifyShapes.single(0, 0, 0),
            VisualParameters.fromCurrentConfig(cameraX, cameraY, cameraZ));

        float[] vertices = mesh.getVertices();
        float[] colors = mesh.getColors();
        Assert.assertEquals(mesh.getVertexFloatCount() / 3 * 4, colors.length);

        double bestDistanceGap = Double.MAX_VALUE;
        int anchorVertex = -1;
        float minimumAlpha = Float.POSITIVE_INFINITY;
        float maximumAlpha = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float vertexX = vertices[vertex * 3];
            float vertexY = vertices[vertex * 3 + 1];
            float vertexZ = vertices[vertex * 3 + 2];
            double worldX = mesh.getOriginX() + (double) vertexX;
            double worldY = mesh.getOriginY() + (double) vertexY;
            double worldZ = mesh.getOriginZ() + (double) vertexZ;
            double distance = Math.sqrt(
                square(worldX - cameraX) + square(worldY - cameraY) + square(worldZ - cameraZ));
            float expectedAlpha = independentAlpha(distance);
            float actualAlpha = colors[vertex * 4 + 3];
            Assert.assertEquals(
                "vertex " + vertex + " alpha at distance " + distance,
                expectedAlpha,
                actualAlpha,
                EPSILON);
            Assert.assertEquals("R 必须冻结", BASE_RED, colors[vertex * 4], 0.0F);
            Assert.assertEquals("G 必须冻结", BASE_GREEN, colors[vertex * 4 + 1], 0.0F);
            Assert.assertEquals("B 必须冻结", BASE_BLUE, colors[vertex * 4 + 2], 0.0F);
            Assert.assertEquals(
                "float32 逐位一致(R)",
                Float.floatToIntBits(BASE_RED),
                Float.floatToIntBits(colors[vertex * 4]));
            minimumAlpha = Math.min(minimumAlpha, actualAlpha);
            maximumAlpha = Math.max(maximumAlpha, actualAlpha);
            double gap = Math.abs(distance - 4.0D);
            if (gap < bestDistanceGap) {
                bestDistanceGap = gap;
                anchorVertex = vertex;
            }
        }
        Assert.assertTrue("alpha 必须随距离变化（防止平凡通过）", maximumAlpha - minimumAlpha > 0.01F);
        Assert.assertTrue("必须存在距离≈4 的锚点顶点", bestDistanceGap < 0.001D);
        Assert.assertEquals(
            "d=4 锚点 alpha 必须等于解析值",
            ALPHA_AT_DISTANCE_4,
            colors[anchorVertex * 4 + 3],
            0.002F);
        Assert.assertTrue("alpha 越界", minimumAlpha >= ALPHA_MIN - EPSILON);
        Assert.assertTrue("alpha 越界", maximumAlpha <= ALPHA_MAX + EPSILON);
    }

    @Test
    public void alphaIsMonotonicNonIncreasingWithDistance() {
        applyBaselineConfig();
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            VerifyShapes.lShape(32),
            VisualParameters.fromCurrentConfig(-8.0D, 4.0D, 0.5D));
        float[] vertices = mesh.getVertices();
        float[] colors = mesh.getColors();
        List<double[]> samples = new ArrayList<double[]>();
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            double worldX = mesh.getOriginX() + (double) vertices[vertex * 3];
            double worldY = mesh.getOriginY() + (double) vertices[vertex * 3 + 1];
            double worldZ = mesh.getOriginZ() + (double) vertices[vertex * 3 + 2];
            double dx = worldX + 8.0D;
            double dy = worldY - 4.0D;
            double dz = worldZ - 0.5D;
            samples.add(new double[] {
                Math.sqrt(dx * dx + dy * dy + dz * dz),
                colors[vertex * 4 + 3]});
        }
        for (int outer = 1; outer < samples.size(); outer++) {
            for (int inner = outer; inner > 0; inner--) {
                double[] previous = samples.get(inner - 1);
                double[] current = samples.get(inner);
                if (previous[0] <= current[0]) {
                    break;
                }
                samples.set(inner - 1, current);
                samples.set(inner, previous);
            }
        }
        for (int index = 1; index < samples.size(); index++) {
            double previousDistance = samples.get(index - 1)[0];
            double currentDistance = samples.get(index)[0];
            if (currentDistance - previousDistance < 0.05D) {
                continue;
            }
            Assert.assertTrue(
                "远顶点 alpha 不得更高 d=" + previousDistance + "->" + currentDistance,
                samples.get(index - 1)[1] + 0.0001F >= samples.get(index)[1]);
        }
    }

    @Test
    public void recolorSessionReproducesFullBuildColorStream() {
        applyBaselineConfig();
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        List<ChainTarget> targets = VerifyShapes.plane(9);
        ChainPreviewMesh topology = builder.build(
            targets,
            VisualParameters.fromCurrentConfig(0.5D, 0.5D, 0.5D));
        VisualParameters moved = VisualParameters.fromCurrentConfig(-12.0D, 0.5D, 0.5D);
        ChainPreviewMeshBuilder.ColorBuildSession session = builder.beginRecolor(topology, moved);
        Assert.assertTrue("recolor 必须一次推进完成", session.advance(null));
        ChainPreviewMesh recolored = session.getMesh();
        ChainPreviewMesh rebuilt = builder.build(targets, moved);
        Assert.assertArrayEquals(rebuilt.getVertices(), recolored.getVertices(), 0.0F);
        Assert.assertArrayEquals(rebuilt.getColors(), recolored.getColors(), 0.0F);
        Assert.assertArrayEquals(rebuilt.getIndices(), recolored.getIndices());
    }

    private static void assertBoundColorStream(ChainPreviewMesh mesh, float expectedAlpha) {
        float[] colors = mesh.getColors();
        Assert.assertTrue("颜色流不得为空", colors.length > 0);
        for (int offset = 0; offset < colors.length; offset += 4) {
            Assert.assertEquals(BASE_RED, colors[offset], 0.0F);
            Assert.assertEquals(BASE_GREEN, colors[offset + 1], 0.0F);
            Assert.assertEquals(BASE_BLUE, colors[offset + 2], 0.0F);
            Assert.assertEquals(expectedAlpha, colors[offset + 3], EPSILON);
        }
    }

    /** 独立复算：与实现同口径的距离二次淡出（不读取被测实现）。 */
    private static float independentAlpha(double distance) {
        if (distance <= FADE_START) {
            return ALPHA_MAX;
        }
        if (distance >= FADE_END) {
            return ALPHA_MIN;
        }
        float normalized = (float) ((distance - FADE_START) / (FADE_END - FADE_START));
        float squared = normalized * normalized;
        return ALPHA_MAX - (ALPHA_MAX - ALPHA_MIN) * squared;
    }

    private static double square(double value) {
        return value * value;
    }

    /** 固定基线默认值，避免 JUnit 同 JVM 内其它配置测试的写入影响本类断言。 */
    private static void applyBaselineConfig() {
        Config.clientEnablePreviewRender = QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER;
        Config.clientPreviewMaxRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS;
        Config.clientPreviewMaxTargets = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS;
        Config.clientPreviewAlphaFadeStartRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS;
        Config.clientPreviewAlphaFadeEndRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS;
        Config.clientPreviewAlphaStartValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE;
        Config.clientPreviewAlphaEndValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE;
    }
}
