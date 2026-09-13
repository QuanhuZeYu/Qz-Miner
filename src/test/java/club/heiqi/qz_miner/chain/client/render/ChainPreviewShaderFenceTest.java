package club.heiqi.qz_miner.chain.client.render;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 状态围栏与边界契约（task-8 交叉审查 D1/D2a/D3/D4/D5 的回归护栏）。
 *
 * <p>这些缺陷的共同危害是「污染外部渲染状态」或「在无围栏路径触碰 GL」，都不是观感问题，
 * 而是会让原版渲染出错、或在生命周期路径上炸掉的契约问题。它们要么可以用纯函数直接断言
 * （D5、D3 的静态初始化），要么可以用<strong>调用顺序断言</strong>固定下来（D1、D2a、D4）——
 * 顺序本身就是契约，且不是源码快照：断言的是「同一方法体内关键调用的相对次序」。</p>
 */
public class ChainPreviewShaderFenceTest {

    private static final String BACKEND_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderBackend.java";
    private static final String PROGRAM_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderProgram.java";

    /** 纯 JVM 分配器：headless 测试不加载 LWJGL native。 */
    private static final ChainPreviewShaderBackend.BufferAllocator HEAP_ALLOCATOR =
            new ChainPreviewShaderBackend.BufferAllocator() {

                @Override
                public java.nio.FloatBuffer floatBuffer(int capacity) {
                    return java.nio.ByteBuffer.allocate(capacity * 4).asFloatBuffer();
                }

                @Override
                public java.nio.IntBuffer intBuffer(int capacity) {
                    return java.nio.ByteBuffer.allocate(capacity * 4).asIntBuffer();
                }

                @Override
                public ByteBuffer byteBuffer(int capacity) {
                    return ByteBuffer.allocate(capacity);
                }
            };

    // ---------------------------------------------------------------- D1：attrib 必须成对关闭

    /**
     * D1：attrib 的 enable 状态属于 VAO，必须在自绑 VAO 还绑定时成对 disable，
     * 之后再 glBindVertexArray(0)。
     */
    @Test
    public void drawDisablesAttributesBeforeUnbindingVao() throws IOException {
        String body = methodBody(BACKEND_PATH, "public void draw(", "void draw(ChainPreviewDrawPlan plan)");

        int lastEnable = body.lastIndexOf("glEnableVertexAttribArray(");
        int firstDisable = body.indexOf("glDisableVertexAttribArray(");
        int unbind = body.indexOf("glBindVertexArray(0)");

        Assert.assertTrue("draw 必须启用属性数组", lastEnable > 0);
        Assert.assertTrue("draw 必须关闭属性数组", firstDisable > lastEnable);
        Assert.assertTrue("必须先解绑 VAO 再关闭属性数组：关错对象会污染外部默认 VAO",
                firstDisable < unbind);
    }

    /**
     * 关闭的属性必须覆盖启用过的全部槽位，否则残留 enable 泄漏到外部 VAO。
     *
     * <p><b>T50 修订</b>：本测试原先断言字面量 {@code (0)/(1)/(2)}——那正是被真机证伪的假设。
     * 驱动把 {@code aPos} 分到槽位 1、{@code aAux} 分到槽位 2（{@code glBindAttribLocation}
     * 无错返回却不生效），于是硬编码 0/1/2 让 GPU 拿 aux 字节当坐标读。槽位改为运行时解析后，
     * 本测试改断言「两侧调用集合相等」——它守的纪律（成对）不变，且不再把错误的槽位固化下来。</p>
     */
    @Test
    public void drawDisablesEveryAttributeItEnabled() throws IOException {
        String body = methodBody(BACKEND_PATH, "public void draw(", "void draw(ChainPreviewDrawPlan plan)");
        java.util.Set<String> enabled = callArguments(body, "glEnableVertexAttribArray(");
        java.util.Set<String> disabled = callArguments(body, "glDisableVertexAttribArray(");
        Assert.assertFalse("draw 必须启用属性数组", enabled.isEmpty());
        Assert.assertEquals("每个 enable 的槽位都必须有对应的 disable（且不得多关）", enabled, disabled);
    }

    /**
     * T50 防回归：属性指针的槽位必须来自运行时查询，不得写死 0/1/2。
     *
     * <p>真机根因就是「假设 aPos=0 / aAux=1」：驱动实际分配 1/2，于是顶点坐标从 aux 字节读，
     * 几何整体塌进 [0,1]³——而数据、绑定、矩阵回读全部自洽，离线一片绿。
     * 这条断言把「槽位是请求还是事实」的区别固定在代码里。</p>
     */
    @Test
    public void attributePointersUseRuntimeResolvedSlots() throws IOException {
        for (String signature : new String[] {"private void initializeGl()", "private void bindVertexLayout()"}) {
            String body = methodBody(BACKEND_PATH, signature, signature);
            java.util.Set<String> slots = callArguments(body, "glVertexAttribPointer(");
            Assert.assertFalse("必须存在 glVertexAttribPointer: " + signature, slots.isEmpty());
            for (String slot : slots) {
                Assert.assertTrue("属性槽位必须是运行时解析值而不是字面量：" + signature + " -> " + slot,
                        slot.startsWith("attributePosition") || slot.startsWith("attributeAux")
                                || slot.startsWith("attributeDirection"));
            }
            Assert.assertTrue("绑定必须先取运行时槽位：" + signature,
                    body.contains("attributePosition") && body.contains("attributeAux")
                            && body.contains("attributeDirection"));
        }
    }

    /** 收集某次调用在方法体内出现过的第一实参（原样文本，去空白）。 */
    private static java.util.Set<String> callArguments(String body, String callPrefix) {
        java.util.Set<String> args = new java.util.TreeSet<String>();
        int from = 0;
        while (true) {
            int at = body.indexOf(callPrefix, from);
            if (at < 0) {
                return args;
            }
            int open = at + callPrefix.length();
            int close = body.indexOf(')', open);
            if (close < 0) {
                return args;
            }
            args.add(body.substring(open, close).trim());
            from = close;
        }
    }

    // ---------------------------------------------------------------- D2a：空网格不得触发 GL

    /**
     * D2a：空网格判定必须早于 ensureReady()。
     *
     * <p>renderer 的 clearMesh 路径在帧围栏之外调用 uploadTopology；若先做初始化，
     * 就会把着色器编译与 VAO/buffer 绑定带到无围栏路径。</p>
     */
    @Test
    public void uploadTopologyChecksEmptyMeshBeforeInitialization() throws IOException {
        String body = methodBody(BACKEND_PATH, "public void uploadTopology(", "void uploadTopology(ChainPreviewMesh mesh)");

        int emptyCheck = body.indexOf("mesh == null || mesh.isEmpty()");
        int ensure = body.indexOf("ensureReady()");
        Assert.assertTrue("必须有空网格判定", emptyCheck > 0);
        Assert.assertTrue("必须调用 ensureReady()", ensure > 0);
        Assert.assertTrue("空网格判定必须前移到 ensureReady() 之前", emptyCheck < ensure);
    }

    // ---------------------------------------------------------------- D4：program 绑定必须还回

    /**
     * D4：进入前的 GL_CURRENT_PROGRAM 必须被保存，并在 finally 里恢复。
     *
     * <p>glPushAttrib 不覆盖 GL_CURRENT_PROGRAM，把外部 program 置 0 会影响后续原版渲染。</p>
     */
    @Test
    public void drawSavesAndRestoresCurrentProgram() throws IOException {
        String body = methodBody(BACKEND_PATH, "public void draw(", "void draw(ChainPreviewDrawPlan plan)");

        Assert.assertTrue("必须读取进入前的 program",
                body.contains("glGetInteger(GL20.GL_CURRENT_PROGRAM)"));
        int finallyAt = body.indexOf("finally");
        int restoreAt = body.indexOf("glUseProgram(previousProgram)");
        Assert.assertTrue("必须存在 finally 块", finallyAt > 0);
        Assert.assertTrue("必须在 finally 内恢复 program", restoreAt > finallyAt);
    }

    // ---------------------------------------------------------------- D5：aAux 整段填未定义

    /**
     * D5：无语义流时 aAux 必须整段填 0xFF。
     *
     * <p>只填前 64 字节会让第 16 个顶点之后的 aAux 读到未定义数据；B2.3 接线颜色语义后必现。</p>
     */
    @Test
    public void undefinedAuxBufferFillsEveryRequestedByte() {
        for (int vertexCount : new int[] {1, 16, 4096, 65536}) {
            int required = vertexCount * 4;
            ByteBuffer buffer = ChainPreviewShaderBackend.prepareUndefinedBuffer(null, required);
            Assert.assertEquals("position 必须归零", 0, buffer.position());
            Assert.assertTrue("buffer 长度必须覆盖请求", buffer.limit() >= required);
            for (int index = 0; index < required; index++) {
                Assert.assertEquals("第 " + index + " 字节必须是 0xFF（未定义）",
                        (byte) 0xFF, buffer.get(index));
            }
        }
    }

    /**
     * 注入纯 JVM 分配器时，后端全生命周期也必须安全（含无 LWJGL native 的 headless 环境）。
     *
     * <p>这条同时守住「buffer 分配不写死在 BufferUtils」：一旦有人改回静态分配，
     * headless 下就会退化成 NoClassDefFoundError，而本断言会先失败。</p>
     */
    @Test
    public void backendWithHeapAllocatorSurvivesFullLifecycle() {
        ChainPreviewShaderBackend backend = new ChainPreviewShaderBackend(
                new ChainPreviewShaderProgram(), HEAP_ALLOCATOR);
        backend.ensureReady();
        backend.uploadTopology(null);
        backend.uploadTopology(ChainPreviewMesh.EMPTY);
        Assert.assertFalse(backend.uploadColors(ChainPreviewMesh.EMPTY));
        backend.draw(null);
        Assert.assertTrue(backend.describe().contains("drawFailures="));
        backend.dispose();
    }

    /** 同一缓冲复用：第二次请求较短长度时不得残留上一次的有效数据。 */
    @Test
    public void undefinedAuxBufferReuseKeepsPrefixUndefined() {
        ByteBuffer reused = ChainPreviewShaderBackend.prepareUndefinedBuffer(null, 4096);
        reused = ChainPreviewShaderBackend.prepareUndefinedBuffer(reused, 256);
        Assert.assertEquals(0, reused.position());
        for (int index = 0; index < 256; index++) {
            Assert.assertEquals((byte) 0xFF, reused.get(index));
        }
    }

    // ---------------------------------------------------------------- D3：视口缓存与静态初始化

    /** D3：每帧只查 GL_VIEWPORT，投影矩阵仅在视口尺寸变化时读一次。 */
    @Test
    public void pixelScaleReadsViewportEveryFrameButCachesProjection() throws IOException {
        String body = methodBody(PROGRAM_PATH, "public float readPixelScale()", "readPixelScale()");
        Assert.assertTrue("必须每帧查询 GL_VIEWPORT", body.contains("GL11.GL_VIEWPORT"));
        Assert.assertTrue("必须比较视口尺寸", body.contains("viewportWidth != cachedViewportWidth")
                || body.contains("cachedViewportWidth"));
        int guard = body.indexOf("pixelScaleValid");
        int projectionRead = body.indexOf("GL11.GL_PROJECTION_MATRIX");
        Assert.assertTrue("必须存在缓存有效位", guard > 0);
        Assert.assertTrue("必须读投影矩阵", projectionRead > 0);
        Assert.assertTrue("投影矩阵读回必须在缓存未命中分支内", projectionRead > guard);
    }

    /**
     * 静态初始化契约（Lead 裁定）：类首次加载不得触碰 LWJGL。
     *
     * <p>一旦 static 初始化里调 BufferUtils/GL，无 GL 环境下会抛 ExceptionInInitializerError，
     * JVM 会把该类永久标记为不可用（NoClassDefFoundError），真机上等于一次失败永久失去该后端。</p>
     */
    @Test
    public void staticInitializationDoesNotTouchLwjgl() throws Exception {
        // 若静态初始化触碰 LWJGL native，这里的首次主动使用就会抛 UnsatisfiedLinkError /
        // ExceptionInInitializerError；测试通过即证明静态初始化是干净的。
        String programName = ChainPreviewShaderProgram.class.getName();
        Assert.assertNotNull(Class.forName(programName));
        Assert.assertNotNull(ChainPreviewShaderBackend.class.getName());
        Assert.assertNotNull(new ChainPreviewShaderProgram().toString());
    }

    /** GL 探测与编译只能在 ensureReady() 内；静态字段只允许常量与不可变配置。 */
    @Test
    public void staticFieldsAreConstantsOnly() throws IOException {
        for (String path : new String[] {PROGRAM_PATH, BACKEND_PATH}) {
            String stripped = stripComments(read(path));
            for (String line : stripped.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("private static final") && !trimmed.startsWith("public static final")) {
                    continue;
                }
                Assert.assertFalse("静态常量不得初始化 Buffer / GL 对象: " + trimmed,
                        trimmed.contains("BufferUtils") || trimmed.contains("glGen") || trimmed.contains("glCreate"));
            }
        }
    }

    // ---------------------------------------------------------------- 辅助

    /** 抽取方法体（按花括号配平），用于「相对调用顺序」断言。 */
    private static String methodBody(String relativePath, String signaturePrefix, String label) throws IOException {
        String code = stripComments(read(relativePath));
        int at = code.indexOf(signaturePrefix);
        Assert.assertTrue("找不到方法 " + label + "（签名前缀=" + signaturePrefix + "）", at >= 0);
        int open = code.indexOf('{', at);
        Assert.assertTrue("方法 " + label + " 缺少函数体", open > at);
        int depth = 0;
        int index = open;
        while (index < code.length()) {
            char ch = code.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(at, index + 1);
                }
            }
            index++;
        }
        Assert.fail("方法 " + label + " 的花括号不平衡");
        return "";
    }

    private static String read(String relativePath) throws IOException {
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
        Assert.assertTrue("找不到源码: " + relativePath, Files.isRegularFile(direct));
        return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
    }

    /** 剥离行注释与块注释（复用校验器的同一实现路径）。 */
    private static String stripComments(String source) {
        return Glsl120StaticChecker.stripComments(
                source, "java-source", new java.util.ArrayList<Glsl120StaticChecker.Finding>());
    }

    /** 保留：断言辅助（供未来扩展）。 */
    static List<String> of(String... values) {
        return Arrays.asList(values);
    }
}
