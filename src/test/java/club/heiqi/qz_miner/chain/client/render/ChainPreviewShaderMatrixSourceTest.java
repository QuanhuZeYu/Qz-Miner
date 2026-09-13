package club.heiqi.qz_miner.chain.client.render;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assert;
import org.junit.Test;

/**
 * 显式相机矩阵的接线契约（T48c-A）。
 *
 * <p>三件事必须在纯 JVM 内可证伪，否则「换成显式 uniform」只是把不可观测的失败换个位置：</p>
 * <ol>
 *   <li><b>矩阵来源</b>：applyUniforms 必须取 <b>plan 的 origin</b>（与 renderer 的
 *       {@code glTranslated}、legacy 后端同源），不得再用上传期缓存的 origin；</li>
 *   <li><b>显式化</b>：必须读固定管线矩阵 → CPU 相乘 → 上传 uModelView/uModelViewProjection，
 *       且 draw 必须被自检结果门控（矩阵不可信时一帧都不画）；</li>
 *   <li><b>失败出口</b>：自检失败 ⇒ 一次性 {@code unavailable} ⇒ {@link ChainPreviewShaderBackend#ensureReady()}
 *       恒返回 false（renderer 既有的一次性永久回退 legacy 路径随之生效，不新增回退机制）。</li>
 * </ol>
 */
public class ChainPreviewShaderMatrixSourceTest {

    private static final String BACKEND_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderBackend.java";
    private static final String PROGRAM_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderProgram.java";

    /** 自检失败（一次性 unavailable）之后 ensureReady 必须一直返回 false，renderer 据此永久回退。 */
    @Test
    public void unavailableSelfCheckResultKeepsEnsureReadyFalse() throws Exception {
        ChainPreviewShaderBackend backend = new ChainPreviewShaderBackend();
        setField(backend, "initialized", Boolean.TRUE);
        setField(backend, "unavailable", Boolean.TRUE);
        setField(backend, "failureReason", "FFP 矩阵栈平移列模长 0.0 与期望 4.079 不符");
        setField(backend, "matrixSourceFailure", "FFP 矩阵栈平移列模长 0.0 与期望 4.079 不符");

        Assert.assertFalse("自检失败后必须恒返回 false（不每帧重试）", backend.ensureReady());
        Assert.assertFalse("重复调用必须仍为 false", backend.ensureReady());

        String described = backend.describe();
        Assert.assertTrue("失败原因必须进 describe: " + described, described.contains("failure="));
        Assert.assertTrue("矩阵来源必须是可读诊断: " + described, described.contains("matrix=untrusted("));
    }

    /** 未自检前 describe 必须显式标出「未检查」，不得让读者误以为矩阵已验证。 */
    @Test
    public void describeMarksMatrixSourceUncheckedBeforeFirstDraw() {
        ChainPreviewShaderBackend backend = new ChainPreviewShaderBackend();
        Assert.assertTrue("未自检时必须标 unchecked: " + backend.describe(),
                backend.describe().contains("matrix=unchecked"));
    }

    /** applyUniforms 必须用 plan origin + 显式矩阵，并且自检失败时返回 false（不再用上传期缓存 origin）。 */
    @Test
    public void applyUniformsUsesPlanOriginAndExplicitCameraMatrices() throws Exception {
        String body = methodBody(BACKEND_PATH, "private boolean applyUniforms(", "applyUniforms");

        Assert.assertTrue("必须取 plan 的 origin X", body.contains("plan.getOriginX()"));
        Assert.assertTrue("必须取 plan 的 origin Y", body.contains("plan.getOriginY()"));
        Assert.assertTrue("必须取 plan 的 origin Z", body.contains("plan.getOriginZ()"));
        Assert.assertFalse("不得再用上传期缓存的 origin（会与 legacy 侧语义分叉）",
                body.contains("(double) originX"));

        Assert.assertTrue("必须读固定管线相机矩阵", body.contains("uploadCameraMatrices("));
        Assert.assertTrue("自检失败必须返回 false（draw 据此不画）", body.contains("return false"));
    }

    /** 上传路径必须「读 → CPU 相乘 → 上传 mvp/mv → 自检」，且失败原因可读。 */
    @Test
    public void matrixUploadReadsMultipliesUploadsAndSelfChecks() throws Exception {
        String body = methodBody(BACKEND_PATH, "private boolean uploadCameraMatrices(", "uploadCameraMatrices");

        Assert.assertTrue("必须从程序层读回投影/modelview", body.contains("readCameraMatrices(projectionMatrix, modelViewMatrix)"));
        Assert.assertTrue("必须在 CPU 侧相乘出 MVP（列主序，投影 × modelview）",
                body.contains("multiply4x4(") && body.contains("modelViewProjectionMatrix, projectionMatrix, modelViewMatrix"));
        Assert.assertTrue("自检必须用加固后的总判据（T48c-C）",
                body.contains("verifyCameraMatrices(") && body.contains("translationMagnitude(modelViewMatrix)"));
        Assert.assertTrue("不得再只查平移列模长（投影/刚性/方向三类漏过已登记）",
                body.contains("MatrixVerdict.TRUSTWORTHY"));
        Assert.assertTrue("期望值必须是 |origin − renderPos| 的模长",
                body.contains("magnitude(") && body.contains("originRelativeX"));
        Assert.assertTrue("必须上传 uModelViewProjection", body.contains("setModelViewProjection(modelViewProjectionMatrix)"));
        Assert.assertTrue("必须上传 uModelView", body.contains("setModelView(modelViewMatrix)"));
        Assert.assertTrue("矩阵未上传必须走同一失败出口（T48c-C 第 1 条）",
                body.contains("矩阵 uniform 未上传") && body.contains("uploaded"));

        String untrusted = methodBody(BACKEND_PATH, "private void markMatrixSourceUntrusted(", "markMatrixSourceUntrusted");
        Assert.assertTrue("失败必须锁成一次性 unavailable", untrusted.contains("unavailable = true"));
        Assert.assertTrue("失败原因必须留存给 describe", untrusted.contains("matrixSourceFailure = reason"));
    }

    /** draw 必须被自检结果门控：不可信时一帧都不画（绝不留错误空间的一帧）。 */
    @Test
    public void drawIsGatedBySelfCheckResult() throws Exception {
        String draw = methodBody(BACKEND_PATH, "public void draw(ChainPreviewDrawPlan plan)", "draw");
        Assert.assertTrue("一次性不可用后不得再绘制", draw.contains("unavailable"));
        Assert.assertTrue("必须由 applyUniforms 的返回值门控绘制",
                draw.contains("if (!applyUniforms(plan))"));
        Assert.assertTrue("门控分支必须提前结束本帧", draw.contains("return"));
    }

    /**
     * ensureReady 的两个分支顺序不可交换：{@code unavailable} 必须先判。
     *
     * <p>矩阵自检发生在 draw 内（那里才拿得到「已 glTranslated 的 modelview」），它只能把后端置为
     * 一次性不可用；renderer 下一帧读 ensureReady 时必须读到 false，回退路径才会生效。</p>
     */
    @Test
    public void ensureReadyChecksUnavailableBeforeInitialized() throws Exception {
        String body = methodBody(BACKEND_PATH, "public boolean ensureReady()", "ensureReady");
        int unavailable = body.indexOf("if (unavailable)");
        int initialized = body.indexOf("if (initialized)");
        Assert.assertTrue("必须存在 unavailable 分支", unavailable >= 0);
        Assert.assertTrue("必须存在 initialized 分支", initialized >= 0);
        Assert.assertTrue("unavailable 必须先于 initialized 判定", unavailable < initialized);
    }

    /**
     * T48c-C 第 1 条：必备 uniform 缺失必须让程序不可用，而不是静默拿零矩阵绘制。
     *
     * <p>真机后果：{@code uModelViewProjection} 若缺失，shader 用默认零矩阵把顶点全塌到原点，
     * 而后端的自检读的是<b>驱动矩阵</b>（不是 uniform 值）⇒ 自检照样通过 ⇒「自检通过、画面全错」。</p>
     */
    @Test
    public void requiredUniformsAreValidatedAfterLink() throws Exception {
        String ensureReady = methodBody(PROGRAM_PATH, "public boolean ensureReady()", "ensureReady");
        Assert.assertTrue("链接完成后必须校验必备 uniform", ensureReady.contains("verifyRequiredUniforms()"));
        Assert.assertTrue("校验必须在 compileAndLink() 之后", ensureReady.indexOf("compileAndLink()") < ensureReady.indexOf("verifyRequiredUniforms()"));

        String verify = methodBody(PROGRAM_PATH, "private void verifyRequiredUniforms()", "verifyRequiredUniforms");
        Assert.assertTrue("缺失必须走失败（抛异常 ⇒ 收敛为程序不可用）", verify.contains("throw new IllegalStateException"));
        Assert.assertTrue("原因必须带缺失名单", verify.contains("missing"));

        java.lang.reflect.Field field = ChainPreviewShaderProgram.class.getDeclaredField("REQUIRED_UNIFORMS");
        field.setAccessible(true);
        String[] required = (String[]) field.get(null);
        java.util.List<String> names = java.util.Arrays.asList(required);
        for (String name : new String[] {
                "uModelViewProjection", "uModelView", "uOriginRel", "uBarThickness", "uFadeAlpha", "uColorPrimary" }) {
            Assert.assertTrue("必备 uniform 必须包含 " + name + "（实际 " + names + "）", names.contains(name));
        }
    }

    /** 矩阵上传必须返回成功与否，且 location<0 时不得伪装成功。 */
    @Test
    public void matrixUploadReportsFailureInsteadOfSilentSkip() throws Exception {
        String upload = methodBody(PROGRAM_PATH, "private boolean setUniformMatrix4(", "setUniformMatrix4");
        Assert.assertTrue("location<0 必须返回 false", upload.contains("return false"));
        Assert.assertTrue("必须记录「矩阵 uniform 缺失」（诊断可观测）", upload.contains("missingMatrices = true"));
        Assert.assertTrue("成功后必须返回 true", upload.contains("return true"));

        String projection = methodBody(PROGRAM_PATH, "public boolean setModelViewProjection(", "setModelViewProjection");
        Assert.assertTrue("setModelViewProjection 必须把上传结果透出给后端", projection.contains("return setUniformMatrix4("));
    }

    /**
     * T48c-C 第 2 条的原版相机扭曲例外：传送门/反胃时 modelview 被施加非均匀缩放，
     * 无条件刚性判据会误判并永久回退（比原缺陷更重）。
     */
    @Test
    public void vanillaCameraWarpExceptionIsWired() throws Exception {
        String warp = methodBody(BACKEND_PATH, "private static boolean vanillaCameraWarpActive()", "vanillaCameraWarpActive");
        Assert.assertTrue("必须读 timeInPortal", warp.contains("player.timeInPortal"));
        Assert.assertTrue("必须读 prevTimeInPortal（覆盖首末过渡帧）", warp.contains("player.prevTimeInPortal"));
        Assert.assertTrue("取不到客户端状态必须安全退化", warp.contains("catch (Throwable failure)"));

        String upload = methodBody(BACKEND_PATH, "private boolean uploadCameraMatrices(", "uploadCameraMatrices");
        Assert.assertTrue("扭曲状态必须传进总判据", upload.contains("vanillaCameraWarpActive()"));
    }

    /** 程序层的矩阵 API 在任何环境下都必须安全退化：非法入参返回 false、绝不抛。 */
    @Test
    public void matrixApiDegradesSafelyWithoutGlContext() {
        ChainPreviewShaderProgram program = new ChainPreviewShaderProgram();
        program.ensureReady();

        Assert.assertFalse("输出数组长度不足必须返回 false",
                program.readCameraMatrices(new float[15], new float[16]));
        Assert.assertFalse("null 输出必须返回 false", program.readCameraMatrices(null, new float[16]));
        Assert.assertFalse("null modelview 输出必须返回 false", program.readCameraMatrices(new float[16], null));

        try {
            // 无 GL 上下文（headless CI）时读矩阵必须收敛为 false 而不是抛出。
            program.readCameraMatrices(new float[16], new float[16]);
            program.setModelViewProjection(new float[16]);
            program.setModelView(new float[16]);
            program.setModelView(null);
            program.setModelView(new float[3]);
            program.setModelViewProjection(null);
        } catch (Throwable failure) {
            Assert.fail("矩阵 API 不得向渲染帧抛出: " + failure);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 抽取方法体（按花括号配平），与既有测试同一套口径。 */
    private static String methodBody(String relativePath, String signature, String label) throws Exception {
        String code = stripComments(read(relativePath));
        int at = code.indexOf(signature);
        Assert.assertTrue("找不到 " + label + "（签名=" + signature + "）", at >= 0);
        int open = code.indexOf('{', at);
        Assert.assertTrue(label + " 缺函数体", open > at);
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char ch = code.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(at, index + 1);
                }
            }
        }
        Assert.fail(label + " 花括号不平衡");
        return "";
    }

    private static String read(String relativePath) throws Exception {
        Path direct = Paths.get(relativePath);
        if (Files.isRegularFile(direct)) {
            return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
        }
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 " + relativePath);
    }

    /** 去掉行注释与块注释（保留换行），避免注释里的字样污染源码断言。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean block = false;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (block) {
                if (ch == '*' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    block = false;
                    index++;
                    continue;
                }
                if (ch == '\n') {
                    out.append(ch);
                }
                continue;
            }
            if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                if (index < source.length()) {
                    out.append('\n');
                }
                continue;
            }
            if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                block = true;
                index++;
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }
}
