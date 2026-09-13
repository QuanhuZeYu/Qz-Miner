package club.heiqi.qz_miner.chain.client.render;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * 诊断快照格式与开关的 headless 契约（T48c-C）。
 *
 * <p>真实价值在真机：异常时这一行要能单独支撑桌面端离线复算。因此这里断言三件事——
 * 开关语义（默认关闭、只输出一次）、字段与顺序固定、以及输出的数值能原样解析回来
 * （即「写到日志里的矩阵 = 当时用的矩阵」）。</p>
 */
public class ChainPreviewShaderMatrixSnapshotTest {

    private static final String PROPERTY = ChainPreviewShaderMatrixSnapshot.DIAGNOSTICS_PROPERTY;

    /**
     * 属性名必须与既有诊断开关同源同名 —— 用反射读 {@code ChainPreviewRenderCache.DIAGNOSTICS_PROPERTY}
     * （包私有，跨包不可直接引用）逐字比对。
     *
     * <p>为什么要锁死：两处是**独立声明**，只靠注释约束；改一处会让快照/诊断<b>永不输出且无任何报错</b>
     * （静默失效），真机排查时最容易被误当成「没问题」。</p>
     */
    @Test
    public void propertyNameMatchesExistingDiagnosticsSwitch() throws Exception {
        Assert.assertEquals("必须与 ChainPreviewRenderCache.DIAGNOSTICS_PROPERTY 同名",
                "qz_miner.preview.diagnostics", PROPERTY);
        java.lang.reflect.Field field = Class.forName("club.heiqi.qz_miner.chain.client.ChainPreviewRenderCache")
                .getDeclaredField("DIAGNOSTICS_PROPERTY");
        field.setAccessible(true);
        Assert.assertEquals("两处独立声明必须字面相等（改名漏一处 ⇒ 诊断静默失效）",
                PROPERTY, field.get(null));
    }

    /** 属性值语义与 Boolean.getBoolean 一致：仅 "true"（忽略大小写、允许空白）为开。 */
    @Test
    public void enabledSemanticsMatchBooleanGetBoolean() {
        Assert.assertTrue(ChainPreviewShaderMatrixSnapshot.enabled("true"));
        Assert.assertTrue(ChainPreviewShaderMatrixSnapshot.enabled("TRUE"));
        Assert.assertTrue(ChainPreviewShaderMatrixSnapshot.enabled(" True "));
        Assert.assertFalse(ChainPreviewShaderMatrixSnapshot.enabled("false"));
        Assert.assertFalse(ChainPreviewShaderMatrixSnapshot.enabled(""));
        Assert.assertFalse(ChainPreviewShaderMatrixSnapshot.enabled("1"));
        Assert.assertFalse(ChainPreviewShaderMatrixSnapshot.enabled(null));
    }

    /**
     * 默认关闭：属性未设时不请求；属性打开时请求。
     *
     * <p>「只输出一次」由后端的一次性标记负责（后端在首次绘制<b>无条件</b>置位 ⇒ 属性只读一次），
     * 该接线由 {@code ChainPreviewShaderMatrixSourceTest} 的源码形状断言钉住。</p>
     */
    @Test
    public void requestedFollowsPropertyOnly() {
        String previous = System.getProperty(PROPERTY);
        try {
            System.clearProperty(PROPERTY);
            Assert.assertFalse("默认（属性未设）不得请求输出", ChainPreviewShaderMatrixSnapshot.requested());

            System.setProperty(PROPERTY, "true");
            Assert.assertTrue("属性打开 ⇒ 请求输出", ChainPreviewShaderMatrixSnapshot.requested());

            System.setProperty(PROPERTY, "false");
            Assert.assertFalse("属性为 false 不得请求输出", ChainPreviewShaderMatrixSnapshot.requested());
        } finally {
            if (previous == null) {
                System.clearProperty(PROPERTY);
            } else {
                System.setProperty(PROPERTY, previous);
            }
        }
    }

    /** 字段与顺序固定：离线脚本按位置解析，顺序变化必须让本用例失败。 */
    @Test
    public void lineCarriesAllFieldsInFixedOrder() {
        String line = sample();
        Assert.assertTrue("必须以固定前缀开头: " + line, line.startsWith(ChainPreviewShaderMatrixSnapshot.PREFIX));

        String[] order = {"projection=[", "modelView=[", "modelViewProjection=[", "expected=", "actual=",
                "renderPos=", "viewRotation=(", "origin=", "indexCount=", "vertexCount=", "anchorLocal=",
                "anchorClip=", "anchorNdc="};
        int previous = -1;
        for (String field : order) {
            int at = line.indexOf(field);
            Assert.assertTrue("缺少字段 " + field + ": " + line, at >= 0);
            Assert.assertTrue("字段顺序错: " + field + " 出现在前一个字段之前", at > previous);
            previous = at;
        }
        Assert.assertTrue("必须以 } 收尾", line.endsWith("}"));
        Assert.assertTrue("期望/实际模长必须可读: " + line, line.contains("expected=4.079118") && line.contains("actual=4.079000"));
        Assert.assertTrue("renderPos 必须可读: " + line, line.contains("renderPos=(-68.620000,66.620000,267.480000)"));
        Assert.assertTrue("viewRotation（离线复算朝向的唯一输入）必须可读: " + line,
                line.contains("viewRotation=(45.000000,30.000000)"));
        Assert.assertTrue("origin 必须是整数口径: " + line, line.contains("origin=(-70,65,264)"));
        Assert.assertTrue("索引/顶点数必须可读: " + line, line.contains("indexCount=24") && line.contains("vertexCount=32"));
    }

    /** 矩阵必须按 16 值输出且能原样解析回来（日志里的矩阵 = 当时用的矩阵）。 */
    @Test
    public void printedMatricesRoundTrip() {
        float[] projection = ramp(0.25F);
        float[] modelView = ramp(-0.5F);
        float[] mvp = ramp(1.125F);
        String line = ChainPreviewShaderMatrixSnapshot.format(
                projection, modelView, mvp, 4.079118D, 4.079F,
                new double[] {1.0D, 2.0D, 3.0D}, 90.0F, -12.5F, new int[] {4, 5, 6}, 7, 8,
                new float[] {0.0225F, 0.0F, 0.0225F}, new float[] {0.1F, 0.2F, 0.3F, 4.0F});

        assertRoundTrip(line, "projection", projection);
        assertRoundTrip(line, "modelView", modelView);
        assertRoundTrip(line, "modelViewProjection", mvp);
    }

    /** 锚点 NDC 必须能由裁剪坐标复算出来（离线复算的关键一步）。 */
    @Test
    public void anchorNdcIsRecomputableFromClip() {
        String line = ChainPreviewShaderMatrixSnapshot.format(
                identity(), identity(), identity(), 1.0D, 1.0F,
                new double[] {0.0D, 0.0D, 0.0D}, 0.0F, 0.0F, new int[] {0, 0, 0}, 0, 0,
                new float[] {1.0F, 2.0F, 3.0F}, new float[] {2.0F, 4.0F, 8.0F, 2.0F});
        List<Float> printedClip = parseTuple(line, "anchorClip=");
        Assert.assertEquals("anchorClip 必须打印 4 个值（含 w）——否则离线端无法复算 NDC", 4, printedClip.size());
        List<Float> ndc = parseTuple(line, "anchorNdc=");
        Assert.assertEquals("NDC.x = clip.x / clip.w", 1.0F, ndc.get(0).floatValue(), 1.0e-6F);
        Assert.assertEquals("NDC.y = clip.y / clip.w", 2.0F, ndc.get(1).floatValue(), 1.0e-6F);
        Assert.assertEquals("NDC.z = clip.z / clip.w", 4.0F, ndc.get(2).floatValue(), 1.0e-6F);
        // 关键：NDC 必须能**从打印出来的 anchorClip** 复算（含 w）——这正是快照的用途。
        Assert.assertEquals("离线复算 NDC.x = 打印的 clip.x / 打印的 clip.w",
                1.0F, printedClip.get(0).floatValue() / printedClip.get(3).floatValue(), 1.0e-6F);
        Assert.assertEquals("离线复算 NDC.y = 打印的 clip.y / 打印的 clip.w",
                2.0F, printedClip.get(1).floatValue() / printedClip.get(3).floatValue(), 1.0e-6F);
    }

    /** 非法/缺失入参不得抛异常（诊断路径不得影响渲染帧）。 */
    @Test
    public void malformedInputsNeverThrow() {
        Assert.assertNotNull(ChainPreviewShaderMatrixSnapshot.format(
                null, null, null, Double.NaN, Float.NaN, null, 0.0F, 0.0F, null, 0, 0, null, null));
        Assert.assertNotNull(ChainPreviewShaderMatrixSnapshot.format(
                new float[3], new float[2], new float[1], 1.0D, 1.0F,
                new double[] {1.0D}, Float.NaN, Float.NaN, new int[] {1}, -1, -1,
                new float[] {1.0F}, new float[] {1.0F}));
    }

    // ------------------------------------------------------------------ 辅助

    private static String sample() {
        return ChainPreviewShaderMatrixSnapshot.format(
                ramp(0.25F), ramp(-0.5F), ramp(1.125F), 4.079118D, 4.079F,
                new double[] {-68.62D, 66.62D, 267.48D}, 45.0F, 30.0F, new int[] {-70, 65, 264}, 24, 32,
                new float[] {0.0225F, 0.0F, 0.0225F}, new float[] {0.1F, 0.2F, 0.3F, 4.0F});
    }

    private static float[] ramp(float step) {
        float[] matrix = new float[16];
        for (int index = 0; index < matrix.length; index++) {
            matrix[index] = (index - 8) * step;
        }
        return matrix;
    }

    private static float[] identity() {
        float[] matrix = new float[16];
        matrix[0] = 1.0F;
        matrix[5] = 1.0F;
        matrix[10] = 1.0F;
        matrix[15] = 1.0F;
        return matrix;
    }

    private static void assertRoundTrip(String line, String name, float[] expected) {
        List<Float> values = parseList(line, name + "=[");
        Assert.assertEquals(name + " 必须恰好输出 16 个值", 16, values.size());
        for (int index = 0; index < 16; index++) {
            Assert.assertEquals(name + "[" + index + "] 必须可解析回来",
                    expected[index], values.get(index).floatValue(), 1.0e-6F);
        }
    }

    private static List<Float> parseList(String line, String prefix) {
        int at = line.indexOf(prefix);
        Assert.assertTrue("缺少 " + prefix + ": " + line, at >= 0);
        int end = line.indexOf(']', at);
        Assert.assertTrue("矩阵未闭合: " + line, end > at);
        List<Float> values = new ArrayList<Float>();
        for (String part : line.substring(at + prefix.length(), end).split(",")) {
            values.add(Float.valueOf(part.trim()));
        }
        return values;
    }

    private static List<Float> parseTuple(String line, String prefix) {
        int at = line.indexOf(prefix);
        Assert.assertTrue("缺少 " + prefix + ": " + line, at >= 0);
        int end = line.indexOf(')', at);
        Assert.assertTrue("元组未闭合: " + line, end > at);
        List<Float> values = new ArrayList<Float>();
        for (String part : line.substring(at + prefix.length() + 1, end).split(",")) {
            values.add(Float.valueOf(part.trim()));
        }
        return values;
    }
}
