package club.heiqi.qz_miner.chain.client.render;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.config.QzMinerConfigDefaults;

/**
 * 面朝向明暗（view-independent face shading）契约。
 *
 * <p>本项最大的工程价值是「两侧逐位一致」：legacy 颜色流与 shader 顶点色都是
 * 「同一个十进制字面常量 × 同一 palette 常量」的单次 IEEE 单精度乘法。因此测试分三层：</p>
 * <ol>
 *   <li><b>关闭档逐位等于现状</b>：默认 false 时颜色流 R/G/B 与基线常量逐位相等（不执行乘法）；</li>
 *   <li><b>开启档逐面精确</b>：每个顶点的颜色必须等于「基色 × 独立复算系数」，用
 *       {@code Float.floatToIntBits} 比较（不是误差范围内相等）；</li>
 *   <li><b>两侧同源</b>：Java 侧 {@link ChainPreviewMeshBuilder#faceShading} 的返回值必须逐位等于
 *       GLSL 侧使用的十进制字面量，且 {@code uFaceShading} 已登记在 uniform 清单里。
 *       GLSL 源码文本不再被断言（重命名即误报、改系数却照样绿）；GLSL 逻辑改动走真机验证 +
 *       shader 头部「实机验证记录」标记，注释改动本身不触发重验。</li>
 * </ol>
 *
 * <p>视角无关性单独断言：同一 mesh 在不同相机位置下 RGB 必须逐位不变（只有 alpha 随距离变化）。</p>
 */
public class ChainPreviewFaceShadingTest {

    private static final float BASE_RED = 0.25F;
    private static final float BASE_GREEN = 0.9F;
    private static final float BASE_BLUE = 1.0F;

    /** 单目标 cube：6 个面 × 4 顶点 = 24 顶点，足以覆盖整张亮度表。 */
    private static List<ChainTarget> targets() {
        return Arrays.asList(new ChainTarget(0, 0, 0));
    }

    private static ChainPreviewMesh build(List<ChainTarget> targets, boolean faceShading, double cameraX) {
        return new ChainPreviewMeshBuilder().build(
            targets,
            new VisualParameters(cameraX, 0.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, 0.045F,
                false, 0.0F, faceShading));
    }

    /** 关闭档（默认）：颜色流必须逐位等于基线常量，且不得有任何乘法残差。 */
    @Test
    public void disabledByDefaultKeepsBaselineColorStreamBitIdentical() {
        Assert.assertFalse("配置默认值必须关闭（默认值不得超前于实现）",
            QzMinerConfigDefaults.CLIENT_PREVIEW_FACE_SHADING);
        Assert.assertFalse("DrawPlan 基线档必须关闭",
            ChainPreviewDrawPlan.Visuals.BASELINE.isFaceShadingEnabled());
        Assert.assertFalse("VisualParameters 简化构造必须关闭",
            new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, 0.045F)
                .isFaceShadingEnabled());

        float[] colors = build(targets(), false, 0.5D).getColors();
        Assert.assertTrue("颜色流不得为空", colors.length > 0);
        for (int offset = 0; offset < colors.length; offset += 4) {
            Assert.assertEquals("关闭档 R 必须逐位等于基线",
                Float.floatToIntBits(BASE_RED), Float.floatToIntBits(colors[offset]));
            Assert.assertEquals("关闭档 G 必须逐位等于基线",
                Float.floatToIntBits(BASE_GREEN), Float.floatToIntBits(colors[offset + 1]));
            Assert.assertEquals("关闭档 B 必须逐位等于基线",
                Float.floatToIntBits(BASE_BLUE), Float.floatToIntBits(colors[offset + 2]));
        }
    }

    /** 开启档：每个顶点必须逐位等于「基色 × 独立复算系数」，且六个面都必须出现。 */
    @Test
    public void enabledMultipliesEachFaceByTheFrozenTable() {
        ChainPreviewMesh mesh = build(targets(), true, 0.5D);
        float[] vertices = mesh.getVertices();
        float[] colors = mesh.getColors();
        byte[] directions = mesh.directionArray();
        Assert.assertNotNull("测试前提：mesh 必须带方向流", directions);
        Assert.assertTrue("测试前提：方向流长度必须与顶点数匹配",
            directions.length >= vertices.length / 3 * ChainPreviewMesh.DIRECTION_BYTES_PER_VERTEX);

        int vertexCount = vertices.length / 3;
        int[] perFaceHits = new int[6];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int directionOffset = vertex * ChainPreviewMesh.DIRECTION_BYTES_PER_VERTEX;
            float normalX = component(directions[directionOffset]);
            float normalY = component(directions[directionOffset + 1]);
            float normalZ = component(directions[directionOffset + 2]);
            float expected = independentFaceShading(normalX, normalY, normalZ);
            int colorOffset = vertex * 4;
            Assert.assertEquals("R 必须逐位等于 基色 × 独立复算系数（顶点 " + vertex + "）",
                Float.floatToIntBits(BASE_RED * expected),
                Float.floatToIntBits(colors[colorOffset]));
            Assert.assertEquals("G 必须逐位等于 基色 × 独立复算系数（顶点 " + vertex + "）",
                Float.floatToIntBits(BASE_GREEN * expected),
                Float.floatToIntBits(colors[colorOffset + 1]));
            Assert.assertEquals("B 必须逐位等于 基色 × 独立复算系数（顶点 " + vertex + "）",
                Float.floatToIntBits(BASE_BLUE * expected),
                Float.floatToIntBits(colors[colorOffset + 2]));
            perFaceHits[faceIndex(normalX, normalY, normalZ)]++;
        }
        for (int face = 0; face < perFaceHits.length; face++) {
            Assert.assertTrue("六个面都必须被覆盖（面 " + face + " 命中 " + perFaceHits[face] + " 次）",
                perFaceHits[face] > 0);
        }
    }

    /** 视角无关：换相机只改 alpha，RGB 必须逐位不变。 */
    @Test
    public void shadingIsViewIndependentAcrossCameraPositions() {
        float[] near = build(targets(), true, 0.5D).getColors();
        float[] far = build(targets(), true, -40.0D).getColors();
        Assert.assertEquals("测试前提：两种相机下顶点数必须一致", near.length, far.length);
        boolean alphaDiffers = false;
        for (int offset = 0; offset < near.length; offset += 4) {
            for (int channel = 0; channel < 3; channel++) {
                Assert.assertEquals("面朝向亮度必须与相机位置无关（通道 " + channel + "）",
                    Float.floatToIntBits(near[offset + channel]),
                    Float.floatToIntBits(far[offset + channel]));
            }
            if (Float.floatToIntBits(near[offset + 3]) != Float.floatToIntBits(far[offset + 3])) {
                alphaDiffers = true;
            }
        }
        Assert.assertTrue("测试前提：相机变化必须改变 alpha（否则本测试没区分开两者）", alphaDiffers);
    }

    /** 距离刷新（recolor）必须与全量装配用同一张表：明暗档下颜色流仍逐位一致。 */
    @Test
    public void recolorSessionReproducesShadedColorStream() {
        List<ChainTarget> targets = targets();
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh topology = build(targets, true, 0.5D);
        VisualParameters moved = new VisualParameters(-12.0D, 0.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, 0.045F,
            false, 0.0F, true);
        ChainPreviewMeshBuilder.ColorBuildSession session = builder.beginRecolor(topology, moved);
        Assert.assertTrue("recolor 必须一次推进完成", session.advance(null));
        ChainPreviewMesh rebuilt = builder.build(targets, moved);
        Assert.assertArrayEquals("明暗档 recolor 必须与全量装配逐位一致",
            rebuilt.getColors(), session.getMesh().getColors(), 0.0F);
    }

    /**
     * 门控 uniform 必须登记在清单里（否则链接期缺 location 会让功能静默失效），
     * 且 Java 侧亮度表的每个系数必须逐位等于 GLSL 侧使用的十进制字面量。
     *
     * <p>「GLSL 源码里含字面量 1.00 / 0.72 / …」这类<strong>源码文本匹配</strong>已按裁定移除：
     * 它重命名即误报、改语义却照样绿，拦不住真问题（GLSL 改系数、改判定顺序都不会让它变红）。
     * 两侧同源改为：Java 侧数值在此逐位钉死 + GLSL<strong>逻辑</strong>改动走真机验证并在
     * shader 头部「实机验证记录」追加标记（注释改动本身不触发重验）。</p>
     */
    @Test
    public void faceShadingUniformIsRegisteredAndTableIsBitExact() {
        assertLiteral("1.00", ChainPreviewMeshBuilder.faceShading(0.0F, 1.0F, 0.0F));
        assertLiteral("0.72", ChainPreviewMeshBuilder.faceShading(0.0F, -1.0F, 0.0F));
        assertLiteral("0.90", ChainPreviewMeshBuilder.faceShading(0.0F, 0.0F, 1.0F));
        assertLiteral("0.84", ChainPreviewMeshBuilder.faceShading(0.0F, 0.0F, -1.0F));
        assertLiteral("0.78", ChainPreviewMeshBuilder.faceShading(1.0F, 0.0F, 0.0F));
        assertLiteral("0.78", ChainPreviewMeshBuilder.faceShading(-1.0F, 0.0F, 0.0F));
        Assert.assertEquals("零方向必须恒等（不得压暗无方向顶点）",
            Float.floatToIntBits(1.0F), Float.floatToIntBits(ChainPreviewMeshBuilder.faceShading(0.0F, 0.0F, 0.0F)));

        Set<String> registered = new HashSet<String>();
        for (String name : ChainPreviewShaderProgram.requiredUniforms()) {
            registered.add(name);
        }
        for (String name : ChainPreviewShaderProgram.capabilityUniforms()) {
            registered.add(name);
        }
        Assert.assertTrue("uFaceShading 必须登记在 uniform 清单（必备或能力型）",
            registered.contains("uFaceShading"));
    }

    /** 亮度系数必须逐位等于该十进制字面量在 GLSL 侧的解析值（两侧同源的最小判据）。 */
    private static void assertLiteral(String literal, float actual) {
        Assert.assertEquals("Java 系数必须逐位等于 GLSL 字面量 " + literal,
            Float.floatToIntBits(Float.parseFloat(literal)), Float.floatToIntBits(actual));
    }

    /** 独立复算（不复用生产函数）：面法线 → 亮度系数。 */
    private static float independentFaceShading(float normalX, float normalY, float normalZ) {
        if (normalY > 0.5F) {
            return 1.00F;
        }
        if (normalY < -0.5F) {
            return 0.72F;
        }
        if (normalZ > 0.5F) {
            return 0.90F;
        }
        if (normalZ < -0.5F) {
            return 0.84F;
        }
        if (normalX > 0.5F || normalX < -0.5F) {
            return 0.78F;
        }
        return 1.00F;
    }

    /** 面下标：+Y=0、−Y=1、+Z=2、−Z=3、+X=4、−X=5；零方向归到 +Y（复用同一格计数）。 */
    private static int faceIndex(float normalX, float normalY, float normalZ) {
        if (normalY > 0.5F) {
            return 0;
        }
        if (normalY < -0.5F) {
            return 1;
        }
        if (normalZ > 0.5F) {
            return 2;
        }
        if (normalZ < -0.5F) {
            return 3;
        }
        if (normalX > 0.5F) {
            return 4;
        }
        if (normalX < -0.5F) {
            return 5;
        }
        return 0;
    }

    /** 方向字节 → 浮点分量（归一化 byte 的 ±{@link ChainPreviewMesh#DIRECTION_UNIT} 还原为 ±1）。 */
    private static float component(byte directionByte) {
        if (directionByte > 0) {
            return 1.0F;
        }
        return directionByte < 0 ? -1.0F : 0.0F;
    }
}
