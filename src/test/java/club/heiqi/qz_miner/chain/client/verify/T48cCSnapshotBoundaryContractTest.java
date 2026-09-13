package club.heiqi.qz_miner.chain.client.verify;

import java.lang.reflect.Field;
import java.util.Locale;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderMatrixSnapshot;

/**
 * T48c-C 诊断快照的边界独立复核（task-49 增量复核，preview-verifier）。
 *
 * <p>三件事必须可证伪：属性语义（只有 "true" 开）、一次性（只在首次绘制打一行）、
 * 以及<b>诊断不得改变渲染行为</b>（非法入参不抛、字段始终可解析）。</p>
 *
 * <p>另锁一条静默失效面：诊断属性名在 {@code ChainPreviewRenderCache}（包私有类 + 包私有常量，无法
 * import）与 {@link ChainPreviewShaderMatrixSnapshot}（公共常量）中<b>各自独立声明</b>，改名一处会让诊断
 * 永不输出且无任何报错；此处按类名与字段名反射逐字比对，把两处钉在一起。
 * <b>内部类名/字段名变更需同步此处</b>（改错即抛 {@code ClassNotFoundException} /
 * {@code NoSuchFieldException}，不会静默跳过）。</p>
 */
public class T48cCSnapshotBoundaryContractTest {

    private static final String KEY = ChainPreviewShaderMatrixSnapshot.DIAGNOSTICS_PROPERTY;

    /** 包私有类：只能用类名反射（无公共类型可用）。 */
    private static final String RENDER_CACHE_CLASS = "club.heiqi.qz_miner.chain.client.ChainPreviewRenderCache";

    @Test
    public void diagnosticKeyIsSingleSourced() throws Exception {
        Class<?> renderCache = Class.forName(RENDER_CACHE_CLASS);
        Field field = renderCache.getDeclaredField("DIAGNOSTICS_PROPERTY");
        field.setAccessible(true);
        String cacheKey = (String) field.get(null);
        Assert.assertEquals("两处诊断属性名必须逐字相同（改名一处 ⇒ 诊断静默失效）", cacheKey, KEY);
        Assert.assertEquals("属性名必须与真机验收清单一致", "qz_miner.preview.diagnostics", KEY);
    }

    @Test
    public void propertySemanticsMatchBooleanGetBoolean() {
        Assert.assertFalse("null 必须关闭", ChainPreviewShaderMatrixSnapshot.enabled(null));
        Assert.assertFalse("空串必须关闭", ChainPreviewShaderMatrixSnapshot.enabled(""));
        Assert.assertFalse("false 必须关闭", ChainPreviewShaderMatrixSnapshot.enabled("false"));
        Assert.assertFalse("1 必须关闭（不是数字语义）", ChainPreviewShaderMatrixSnapshot.enabled("1"));
        Assert.assertFalse("yes 必须关闭", ChainPreviewShaderMatrixSnapshot.enabled("yes"));
        Assert.assertTrue("true 必须开启", ChainPreviewShaderMatrixSnapshot.enabled("true"));
        Assert.assertTrue("TRUE 必须开启（忽略大小写）", ChainPreviewShaderMatrixSnapshot.enabled("TRUE"));
        Assert.assertTrue("首尾空白必须忽略", ChainPreviewShaderMatrixSnapshot.enabled("  true  "));
    }

    @Test
    public void snapshotIsOneShotAndSilentWhenDisabled() {
        String previous = System.getProperty(KEY);
        try {
            System.clearProperty(KEY);
            Assert.assertFalse("属性关闭时不得输出", ChainPreviewShaderMatrixSnapshot.shouldReport(false));
            Assert.assertFalse("属性关闭时不得输出（重复查询同结论，无副作用）",
                    ChainPreviewShaderMatrixSnapshot.shouldReport(false));

            System.setProperty(KEY, "true");
            Assert.assertTrue("属性开启且未输出过 ⇒ 输出", ChainPreviewShaderMatrixSnapshot.shouldReport(false));
            Assert.assertFalse("已输出过 ⇒ 永不再输出（一次性）",
                    ChainPreviewShaderMatrixSnapshot.shouldReport(true));

            System.setProperty(KEY, "false");
            Assert.assertFalse("显式 false ⇒ 关闭", ChainPreviewShaderMatrixSnapshot.shouldReport(false));
        } finally {
            if (previous == null) {
                System.clearProperty(KEY);
            } else {
                System.setProperty(KEY, previous);
            }
        }
    }

    @Test
    public void formatCarriesEveryFieldNeededForOfflineReplay() {
        float[] matrix = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0.5F, -64.0F, 0.25F, 1 };
        double[] renderPos = { 100.0D, 64.0D, -200.0D };
        float[] anchorLocal = { 0.5F, 0.0F, 0.5F };
        float[] anchorClip = { 1.25F, -0.5F, 0.75F, 2.5F };

        String line = ChainPreviewShaderMatrixSnapshot.format(
                matrix, matrix, matrix, 4.079118D, 3.9F, renderPos, new int[] { 100, 64, -200 },
                240, 240, anchorLocal, anchorClip);

        Assert.assertTrue("必须带可 grep 的前缀", line.startsWith(ChainPreviewShaderMatrixSnapshot.PREFIX));
        for (String field : new String[] {
            "projection=[", "modelView=[", "modelViewProjection=[", "expected=", "actual=",
            "renderPos=", "origin=", "indexCount=240", "vertexCount=240", "anchorLocal=", "anchorClip=", "anchorNdc=",
        }) {
            Assert.assertTrue("缺字段 " + field + "： " + line, line.contains(field));
        }
        Assert.assertEquals("矩阵必须按 16 个元素输出（列主序全量）", 16, countElements(line, "projection=["));
        Assert.assertEquals("MVP 必须按 16 个元素输出", 16, countElements(line, "modelViewProjection=["));

        int ordered = -1;
        for (String field : new String[] {
            "projection=[", "modelView=[", "modelViewProjection=[", "expected=", "actual=", "renderPos=",
            "origin=", "indexCount=", "vertexCount=", "anchorLocal=", "anchorClip=", "anchorNdc=",
        }) {
            int at = line.indexOf(field);
            Assert.assertTrue("字段顺序必须固定（离线脚本按位置解析）： " + field, at > ordered);
            ordered = at;
        }
        Assert.assertTrue("锚点 NDC 必须能离线复算： " + line, line.contains("anchorNdc=(0.500000,-0.200000,0.300000)"));
    }

    @Test
    public void formatNeverThrowsOnIllegalInput() {
        String line = ChainPreviewShaderMatrixSnapshot.format(
                null, new float[3], null, Double.NaN, Float.NaN, null, null, -1, -1, null, null);
        Assert.assertTrue("非法入参必须降级为占位值而不是抛异常（诊断不得影响渲染帧）",
                line.startsWith(ChainPreviewShaderMatrixSnapshot.PREFIX));
        Assert.assertTrue("缺失矩阵必须补零而不是截断： " + line, line.contains("projection=["));

        float[] clipWithZeroW = { 1.0F, 2.0F, 3.0F, 0.0F };
        String zeroW = ChainPreviewShaderMatrixSnapshot.format(
                new float[16], new float[16], new float[16], 0.0D, 0.0F, new double[3], new int[3],
                0, 0, new float[3], clipWithZeroW);
        Assert.assertTrue("w=0 必须输出 NaN（不得伪造成 0）： " + zeroW, zeroW.contains("anchorNdc=(NaN,NaN,NaN)"));
    }

    @Test
    public void formatIsIndependentOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String line = ChainPreviewShaderMatrixSnapshot.format(
                    new float[16], new float[16], new float[16], 1.5D, 1.5F, new double[3], new int[3],
                    0, 0, new float[3], new float[] { 0, 0, 0, 1 });
            Assert.assertTrue("小数分隔符不得随宿主区域设置变化（德语区域会输出逗号）： " + line,
                    line.contains("expected=1.500000"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static int countElements(String line, String prefix) {
        int start = line.indexOf(prefix);
        if (start < 0) {
            return -1;
        }
        int end = line.indexOf(']', start);
        if (end < 0) {
            return -1;
        }
        String body = line.substring(start + prefix.length(), end);
        return body.isEmpty() ? 0 : body.split(",").length;
    }
}
