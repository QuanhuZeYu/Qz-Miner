package club.heiqi.qz_miner.chain.client.render;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;

/**
 * 淡入淡出包络（B3.2 / L5）与最终 alpha 的契约。
 *
 * <p>规范要求：{@code fadeAlpha = 0} ⇒ 全透明、{@code 0.5} ⇒ 半透、{@code 1} ⇒ 与今天逐值一致，
 * 且与 legacy 乘子路径的最终 alpha 逐值相等。这里用参考模型逐值断言（纯 JVM），
 * 并把「GLSL 真的乘了该 uniform」用结构断言固定下来。</p>
 */
public class ChainPreviewShaderFadeAlphaTest {

    private static final String VERTEX_PATH = "src/main/resources/assets/qz_miner/shaders/preview.vert";
    private static final String PROGRAM_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderProgram.java";
    private static final String BACKEND_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderBackend.java";

    private static final float FADE_START = 2.0F;
    private static final float FADE_END = 6.0F;
    private static final float MAX_ALPHA = 0.8F;
    private static final float MIN_ALPHA = 0.2F;

    /** fadeAlpha=1 必须与现状逐值一致（乘 1 不改变任何结果）。 */
    @Test
    public void fadeAlphaOneMatchesTodayExactly() {
        for (int step = 0; step <= 200; step++) {
            float distance = 0.0F + 10.0F * step / 200.0F;
            float today = ChainPreviewShaderMath.fadeAlpha(distance, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA);
            float withEnvelope = ChainPreviewShaderMath.finalAlpha(today, 1.0F);
            Assert.assertEquals("fadeAlpha=1 在距离 " + distance + " 处必须逐值一致", today, withEnvelope, 0.0F);
        }
    }

    /** fadeAlpha=0 ⇒ 全透明；0.5 ⇒ 半透（与距离淡出线性相乘）。 */
    @Test
    public void fadeAlphaZeroIsFullyTransparentAndHalfIsHalf() {
        for (int step = 0; step <= 200; step++) {
            float distance = 0.0F + 10.0F * step / 200.0F;
            float today = ChainPreviewShaderMath.fadeAlpha(distance, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA);

            Assert.assertEquals("fadeAlpha=0 必须全透明", 0.0F,
                    ChainPreviewShaderMath.finalAlpha(today, 0.0F), 0.0F);
            Assert.assertEquals("fadeAlpha=0.5 必须是距离淡出的一半",
                    today * 0.5F, ChainPreviewShaderMath.finalAlpha(today, 0.5F), 0.0F);
        }
    }

    /**
     * 与 legacy 乘子路径逐值一致。
     *
     * <p>legacy 把包络乘进 CPU 颜色流的 alpha；shader 把它乘进顶点 alpha。
     * 两者必须是同一乘法，否则同一场景在两档不同亮度。</p>
     */
    @Test
    public void shaderEnvelopeMatchesLegacyMultiplier() throws Exception {
        VisualParameters cpuVisuals = new VisualParameters(
                0.0D, 0.0D, 0.0D, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA, 0.045F);
        java.lang.reflect.Method alphaFor = VisualParameters.class.getDeclaredMethod(
                "alphaFor", double.class, double.class, double.class);
        alphaFor.setAccessible(true);

        for (int envelopeStep = 0; envelopeStep <= 20; envelopeStep++) {
            float fadeAlpha = envelopeStep / 20.0F;
            for (int distanceStep = 0; distanceStep <= 100; distanceStep++) {
                double distance = 10.0D * distanceStep / 100.0D;

                // legacy 侧：CPU 用真实的 alphaFor（double）得距离 alpha，再乘包络
                float cpuDistanceAlpha = ((Float) alphaFor.invoke(cpuVisuals, distance, 0.0D, 0.0D)).floatValue();
                float legacyAlpha = cpuDistanceAlpha * fadeAlpha;

                // shader 侧：参考模型 = fadeAlpha(quadratic) × 包络
                float shaderDistanceAlpha = ChainPreviewShaderMath.fadeAlpha(
                        (float) distance, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA);
                float shaderAlpha = ChainPreviewShaderMath.finalAlpha(shaderDistanceAlpha, fadeAlpha);

                Assert.assertEquals("包络=" + fadeAlpha + " 距离=" + distance + " 两档最终 alpha 必须一致",
                        legacyAlpha, shaderAlpha, 1.0e-5F);
            }
        }
    }

    /** 包络必须被 clamp 到 [0,1]；NaN 收敛为 1（不透明），避免异常配置导致整链透明。 */
    @Test
    public void envelopeIsClampedAndNaNIsSafe() {
        Assert.assertEquals("负包络 clamp 到 0", 0.0F,
                ChainPreviewShaderMath.finalAlpha(0.7F, -1.0F), 0.0F);
        Assert.assertEquals("超界包络 clamp 到 1", 0.7F,
                ChainPreviewShaderMath.finalAlpha(0.7F, 2.0F), 0.0F);
        float nanResult = ChainPreviewShaderMath.finalAlpha(0.7F, Float.NaN);
        Assert.assertFalse("NaN 包络不得产生 NaN alpha", Float.isNaN(nanResult));
    }

    /** GLSL 必须真的消费 uFadeAlpha（而不是声明后不用）。 */
    @Test
    public void vertexShaderConsumesFadeAlpha() throws Exception {
        String body = stripComments(read(VERTEX_PATH));
        Assert.assertTrue("必须声明 uFadeAlpha", body.contains("uniform float uFadeAlpha;"));
        Assert.assertTrue("最终 alpha 必须乘上该包络",
                body.contains("float alpha = fade * growth * uFadeAlpha;"));
    }

    /** 后端必须真的消费 plan 的包络（否则文档声称的「shader 路径有淡入淡出」不成立）。 */
    @Test
    public void backendConsumesPlanFadeAlpha() throws Exception {
        String body = stripComments(read(BACKEND_PATH));
        Assert.assertTrue("后端必须从 plan 读 getFadeAlpha()",
                body.contains("plan.getFadeAlpha()"));
        Assert.assertTrue("读到的包络必须写进 uniform",
                body.contains("program.setFadeAlpha("));
    }

    /** 宿主必须提供安全初值：uniform 未赋值时为 0，漏设会让整链透明。 */
    @Test
    public void programWritesSafeInitialEnvelope() throws Exception {
        String body = stripComments(read(PROGRAM_PATH));
        Assert.assertTrue("必须提供 setFadeAlpha（含 clamp/NaN 兜底）",
                body.contains("public void setFadeAlpha(float fadeAlpha)"));
        Assert.assertTrue("ensureReady 成功后必须写入安全初值 1",
                body.contains("setFadeAlpha(1.0F);"));
    }

    private static String read(String relativePath) throws Exception {
        Path direct = Paths.get(relativePath);
        if (!Files.isRegularFile(direct)) {
            Path dir = Paths.get("").toAbsolutePath();
            while (dir != null) {
                Path candidate = dir.resolve(relativePath);
                if (Files.isRegularFile(candidate)) {
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                }
                dir = dir.getParent();
            }
        }
        Assert.assertTrue("找不到文件: " + relativePath, Files.isRegularFile(direct));
        return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
    }

    private static String stripComments(String source) {
        return Glsl120StaticChecker.stripComments(source, "src", new ArrayList<Glsl120StaticChecker.Finding>());
    }
}
