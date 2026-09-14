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
 *   <li><b>矩阵来源</b>：相机相对 origin 必须取 <b>plan 的 origin</b>（与 renderer 的
 *       {@code glTranslated}、legacy 后端同源），且在 double 域相减——取值路径已下沉为纯函数
 *       {@link ChainPreviewShaderBackend#originRelativeTo(ChainPreviewDrawPlan, double, double, double, double[])}，
 *       断言其数值而不是源码文本；</li>
 *   <li><b>失败出口</b>：自检 / 读取失败 ⇒ 一次性 {@code unavailable} ⇒
 *       {@link ChainPreviewShaderBackend#ensureReady()} 恒返回 false（renderer 既有的一次性永久回退
 *       legacy 路径随之生效，不新增回退机制），且失败原因进 {@code describe()}；</li>
 *   <li><b>必备 uniform</b>：链接后必须校验 location，缺失即整体不可用（清单内容用反射对账）。</li>
 * </ol>
 *
 * <p><b>为什么不再读 Java 源码文本</b>：原先这一层断言「applyUniforms 里有没有写
 * {@code plan.getOriginX()} / {@code uploadCameraMatrices(} / {@code if (!program.setModelView(…))}」——
 * 重命名即误报、把上传整段删掉却可能照样绿。现在：能下沉成纯函数的（origin 换算、相机扭曲判定）
 * 按数值断言；失败路径按<strong>实际调用</strong>断言（headless 下矩阵读取必然失败，走的就是真机上
 * 「内建矩阵失同步」的同一失败出口）。<b>矩阵读回 / 相乘 / 上传本身需要真实 GL 上下文</b>
 * （headless 下固定管线矩阵栈不存在），这部分只有真机验证能覆盖，见类注释与 shader 头部记录。</p>
 *
 * <p><b>可达性边界（实测）</b>：{@code applyUniforms} 的第一步是读 {@code RenderManager.renderPosX}，
 * 而测试运行时初始化原版渲染类会抛
 * {@code NoSuchMethodError: org.lwjgl.opengl.DisplayMode.<init>(int, int)}（lwjgl3ify 兼容层）；
 * {@code draw} 的第一步 {@code glGetInteger} 在无上下文时同样先失败。故「读回 → CPU 相乘 → 上传
 * mvp/mv → 自检」整条链在 headless 下不可达（真机验证覆盖），本类改按可到达的等价面断言：
 * 纯函数数值（origin / 相机扭曲）+ 失败出口的实际后果链（锁存 ⇒ describe ⇒ ensureReady）。</p>
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

    /**
     * 相机相对 origin 必须取 plan 的 origin，且在 double 域相减。
     *
     * <p>断言打在 {@link ChainPreviewShaderBackend#originRelativeTo} 的<b>数值</b>上：函数的入参只有
     * plan 与相机世界坐标，源码里不再存在「上传期缓存的 origin」这条岔路（T48c-A 的语义分叉根因）；
     * 「上传期缓存」的回归表现为「换 plan 而 uniform 值不变」——本用例用两个 origin 不同的 plan
     * 证明取值确实跟着 plan 走。</p>
     *
     * <p>double 域不是形式主义：float 在 3e7 处的间距是 4，30M 级坐标下相减会把 0.75 格算成 0
     * （表型是整链相对方块抖动）。</p>
     */
    @Test
    public void originRelativeComesFromPlanOriginInDoubleDomain() {
        double[] out = new double[3];

        ChainPreviewShaderBackend.originRelativeTo(planWithOrigin(10, 64, -3), 1.5D, 2.5D, 3.5D, out);
        Assert.assertEquals(8.5D, out[0], 0.0D);
        Assert.assertEquals(61.5D, out[1], 0.0D);
        Assert.assertEquals(-6.5D, out[2], 0.0D);

        ChainPreviewDrawPlan moved = planWithOrigin(11, 64, -3);
        ChainPreviewShaderBackend.originRelativeTo(moved, 1.5D, 2.5D, 3.5D, out);
        Assert.assertEquals("取值必须跟着 plan 的 origin 走（不得来自任何缓存）", 9.5D, out[0], 0.0D);
        Assert.assertEquals(61.5D, out[1], 0.0D);
        Assert.assertEquals(-6.5D, out[2], 0.0D);

        // 大世界坐标：差值必须精确（float 域相减会把 -0.75 / 1.5 算成 0.0 / 2.0）
        ChainPreviewShaderBackend.originRelativeTo(planWithOrigin(30000000, -30000000, 30000000),
                30000000.75D, -29999999.25D, 29999998.5D, out);
        Assert.assertEquals("不得丢掉亚格精度", -0.75D, out[0], 0.0D);
        Assert.assertEquals(-0.75D, out[1], 0.0D);
        Assert.assertEquals(1.5D, out[2], 0.0D);
    }

    /**
     * 矩阵来源不可信 ⇒ 一次性锁存为不可用 ⇒ 下一帧 {@code ensureReady()} 返回 false
     * （renderer 既有的一次性永久回退 legacy 随之生效，不新增回退机制），且原因可读。
     *
     * <p>断言打在<b>失败出口本身</b>（{@code markMatrixSourceUntrusted}）的后果链上，而不是
     * 「这个方法里有没有写 {@code unavailable = true} / {@code matrixSourceFailure = reason}」：
     * 锁存后必须同时满足「describe 标 untrusted 且带原因」「failure 同步非空」「ensureReady 恒 false」。</p>
     *
     * <p><b>为什么不到</b> {@code applyUniforms}：它第一步就读 {@code RenderManager.renderPosX}，
     * 而本测试运行时（lwjgl3ify 兼容层）初始化原版渲染类的 {@code <clinit>} 会直接抛
     * {@code NoSuchMethodError: org.lwjgl.opengl.DisplayMode.<init>(int, int)} ⇒ 该路径在纯 JVM 内
     * 不可达，只能真机验证。同理 {@code draw} 的第一句 {@code glGetInteger} 在无上下文时也先失败。</p>
     */
    @Test
    public void matrixFailureLatchMakesBackendUnavailableAndObservable() throws Exception {
        ChainPreviewShaderBackend backend = new ChainPreviewShaderBackend();
        java.lang.reflect.Method latch = ChainPreviewShaderBackend.class
                .getDeclaredMethod("markMatrixSourceUntrusted", String.class);
        latch.setAccessible(true);
        String reason = "FFP 矩阵栈平移列模长 0.0 与期望 4.079 不符";
        latch.invoke(backend, reason);

        String described = backend.describe();
        Assert.assertTrue("矩阵来源必须标成不可信并带原因: " + described,
                described.contains("matrix=untrusted(" + reason + ")"));
        Assert.assertTrue("失败原因必须同时进 failure（describe 可观测）: " + described,
                described.contains("failure="));
        Assert.assertFalse("一次性不可用后 ensureReady() 必须返回 false（renderer 据此回退 legacy）",
                backend.ensureReady());
        Assert.assertFalse("重复调用必须仍为 false（不每帧重试）", backend.ensureReady());
    }

    /** 指定 origin 的 plan（已 sanitize：可见索引范围 = 索引数）。 */
    private static ChainPreviewDrawPlan planWithOrigin(int originX, int originY, int originZ) {
        return new ChainPreviewDrawPlan(
                0, 12, null, ChainPreviewDrawPlan.Visuals.BASELINE,
                ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
                originX, originY, originZ, 8, false, 0, 0L, 0L).sanitized();
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
                "uModelViewProjection", "uOriginRel", "uFadeAlpha", "uColorPrimary" }) {
            Assert.assertTrue("硬必备 uniform 必须包含 " + name + "（实际 " + names + "）", names.contains(name));
        }
        // T51：aDirection 位移把 uModelView / uPixelScale / uBarThickness / uMinScreenWidthPx 从能力型
        // 升为硬必备——它们已是活代码（不再被 if (false && …) 包住），编译器不会优化掉；
        // 缺任一都会让外扩量算错却照样出画面，属于「宁可回退 legacy 也不画错帧」的一类。
        // uOutlineWidthPx 一直是活引用（描边壳段与主色分支），一并归必备。
        for (String name : new String[] {
                "uModelView", "uPixelScale", "uBarThickness", "uMinScreenWidthPx", "uOutlineWidthPx" }) {
            Assert.assertTrue("位移 / 描边专用 uniform 必须升为硬必备：" + name + "（实际 " + names + "）",
                    names.contains(name));
        }
        java.lang.reflect.Field capabilityField =
                ChainPreviewShaderProgram.class.getDeclaredField("CAPABILITY_UNIFORMS");
        capabilityField.setAccessible(true);
        String[] capability = (String[]) capabilityField.get(null);
        Assert.assertEquals("T51 后不存在「按契约保留但当前关闭」的 uniform，能力型清单必须为空",
                0, capability.length);
    }

    /**
     * 矩阵上传必须返回成功与否，且 location&lt;0 时不得伪装成功（T48c-C 第 1 条）。
     *
     * <p>断言打在<b>实际调用结果</b>上：非法入参（null / 长度不足）任何环境都必须 false；
     * 无 GL 上下文时 location 查不到 ⇒ 必须 false 且把「矩阵 uniform 缺失」记进诊断，
     * 而不是静默跳过让 shader 拿零矩阵绘制。</p>
     */
    @Test
    public void matrixUploadReportsFailureInsteadOfSilentSkip() {
        ChainPreviewShaderProgram program = new ChainPreviewShaderProgram();
        boolean ready = program.ensureReady();

        Assert.assertFalse("null 入参不得伪装成功", program.setModelViewProjection(null));
        Assert.assertFalse("null modelview 不得伪装成功", program.setModelView(null));
        Assert.assertFalse("长度不足不得伪装成功", program.setModelView(new float[15]));
        Assert.assertFalse("长度不足不得伪装成功（MVP）", program.setModelViewProjection(new float[3]));

        if (ready) {
            // 真机开发环境（GL 上下文可用）：必备 uniform 齐全 ⇒ 上传必须真的成功
            Assert.assertTrue("就绪后 MVP 必须上传成功", program.setModelViewProjection(new float[16]));
            Assert.assertTrue("就绪后 modelview 必须上传成功", program.setModelView(new float[16]));
        } else {
            // 无 GL 上下文：location < 0 ⇒ 必须返回 false，且缺失必须可观测
            Assert.assertFalse("location<0 必须返回 false", program.setModelViewProjection(new float[16]));
            Assert.assertFalse("location<0 必须返回 false（modelview）", program.setModelView(new float[16]));
            Assert.assertTrue("「矩阵 uniform 缺失」必须记录给 describe（不得静默）",
                    program.hasMissingMatrixUniforms());
        }
    }

    /**
     * T48c-C 第 2 条的原版相机扭曲例外：传送门/反胃时 modelview 被施加非均匀缩放，
     * 无条件刚性判据会误判并永久回退（比原缺陷更重）。
     *
     * <p>判定本体是纯函数 {@link ChainPreviewShaderBackend#cameraWarpActive(float, float)}，按数值断言：
     * <b>prev 也必须看</b>——渲染用的是两者的插值，只看当前值会漏掉首末过渡帧。读取客户端状态的那一层
     * 由反射直接调用，断言「取不到客户端状态时安全退化为 false」而不是「源码里有没有写 catch」。</p>
     */
    @Test
    public void vanillaCameraWarpWidensJudgementOnlyWhileWarping() throws Exception {
        Assert.assertTrue("timeInPortal > 0 必须视为扭曲生效",
                ChainPreviewShaderBackend.cameraWarpActive(0.5F, 0.0F));
        Assert.assertTrue("prevTimeInPortal > 0 同样必须生效（覆盖首末过渡帧）",
                ChainPreviewShaderBackend.cameraWarpActive(0.0F, 0.5F));
        Assert.assertFalse("常态不得放宽判据（否则会漏掉真实的矩阵失同步）",
                ChainPreviewShaderBackend.cameraWarpActive(0.0F, 0.0F));

        java.lang.reflect.Method warp = ChainPreviewShaderBackend.class
                .getDeclaredMethod("vanillaCameraWarpActive");
        warp.setAccessible(true);
        try {
            // 两种「取不到客户端状态」都走同一出口：thePlayer 为 null，或客户端类根本不可加载
            // （测试运行时初始化原版渲染类会抛 NoSuchMethodError）。任一情形都必须返回 false 而不是抛出。
            Assert.assertEquals("取不到客户端状态必须安全退化为 false", Boolean.FALSE, warp.invoke(null));
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Assert.fail("取不到客户端状态不得抛出: " + failure.getCause());
        }
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
