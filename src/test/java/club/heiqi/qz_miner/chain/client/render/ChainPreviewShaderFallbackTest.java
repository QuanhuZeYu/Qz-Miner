package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 着色器路径的失败语义与回退契约。
 *
 * <p>用户已经拍板「着色器优先、不可用再回退 legacy」，因此本项最不能被违反的性质是：
 * <b>任何失败都必须可观测（describe）且不重复尝试</b>——否则一次编译失败会在每帧重试，
 * 把「观感降级」变成「帧时间灾难」。测试在无 GL 上下文的纯 JVM 内运行，恰好覆盖
 * 「GL 能力不可用」这条真实回退路径。</p>
 */
public class ChainPreviewShaderFallbackTest {

    // ------------------------------------------------------------------ 程序层：失败不重试

    /**
     * 失败后不再重试：连续多次 ensureReady 只允许发生一次真实初始化尝试。
     *
     * <p>无 GL 上下文时这次尝试本身会失败（并可能抛 UnsatisfiedLinkError），必须是「返回 false」
     * 而不是「向上抛」。这正是真机上着色器编译失败的同一收敛点。</p>
     */
    @Test
    public void shaderProgramNeverThrowsAndAttemptsInitializationOnlyOnce() {
        ChainPreviewShaderProgram program = new ChainPreviewShaderProgram();

        // 本测试对「GL 上下文是否可用」保持环境无关：headless 下初始化必然失败
        // （LWJGL native 缺失 / 无 context），真机开发环境可能成功。两种情况都必须满足
        // 「不抛异常 + 只尝试一次 + 状态自洽」。
        boolean ready = program.ensureReady();
        Assert.assertEquals("初始化必须只尝试一次", 1, program.getInitializationAttempts());

        for (int i = 0; i < 120; i++) {
            Assert.assertEquals("重复调用必须返回同一结论", ready, program.ensureReady());
        }
        Assert.assertEquals("反复调用不得触发第二次初始化", 1, program.getInitializationAttempts());

        if (ready) {
            Assert.assertTrue("成功时必须报告就绪", program.isReady());
            Assert.assertFalse("成功时不得标记不可用", program.isUnavailable());
            Assert.assertTrue("成功时必须持有 program 句柄", program.getProgramId() > 0);
        } else {
            Assert.assertTrue("失败必须记录原因供 describe 使用", program.getLastFailureMessage().length() > 0);
            Assert.assertTrue("失败后必须标记不可用", program.isUnavailable());
            Assert.assertFalse("失败后不得报告就绪", program.isReady());
            Assert.assertEquals("失败时不得留下 program 句柄", 0, program.getProgramId());
        }

        program.dispose();
        Assert.assertFalse("dispose 后不得报告就绪", program.isReady());
        Assert.assertEquals("dispose 必须清空尝试计数，允许配置热切换后重建", 0, program.getInitializationAttempts());
    }

    /** use/release 在未就绪时必须是安全的空操作（渲染帧不得因它们抛异常）。 */
    @Test
    public void useAndReleaseAreSafeWhileUnavailable() {
        ChainPreviewShaderProgram program = new ChainPreviewShaderProgram();
        program.ensureReady();
        program.use();
        program.setOriginRel(1.0F, 2.0F, 3.0F);
        program.setFadeCurve(1.0F, 2.0F, 0.1F, 0.9F);
        program.setMinScreenWidthPx(1.0F);
        program.setBarThickness(0.045F);
        program.setAnimation(1.0F, 0.0F);
        program.setSemanticColor(0, 0.25F, 0.9F, 1.0F);
        program.dispose();
    }

    // ------------------------------------------------------------------ 后端层：接口契约

    @Test
    public void backendAdvertisesShaderIdentityAndCpuColorContract() {
        ChainPreviewRenderBackend backend = ChainPreviewShaderBackend.create();
        Assert.assertNotNull("renderer 的 create() seam 必须可用", backend);
        Assert.assertEquals("shader", backend.id());
        Assert.assertFalse("shader 路径不消费 CPU 颜色流", backend.usesCpuColors());
        Assert.assertTrue("describe 必须给出诊断文本", backend.describe().contains("shader"));
    }

    /** 未就绪时 uploadTopology / draw 必须安全退化，不得抛异常、也不得触碰 GL。 */
    @Test
    public void backendDegradesSafelyWhenGlUnavailable() {
        ChainPreviewShaderBackend backend = new ChainPreviewShaderBackend();
        boolean ready = backend.ensureReady();

        // 无论就绪与否，下面的调用都必须安全：空 mesh / null mesh / null plan 不得抛异常。
        backend.uploadTopology(ChainPreviewMesh.EMPTY);
        backend.uploadTopology(null);
        Assert.assertFalse("shader 路径不接受 CPU 颜色上传", backend.uploadColors(ChainPreviewMesh.EMPTY));
        backend.draw(null);

        String described = backend.describe();
        Assert.assertTrue("必须暴露 drawFailures 计数: " + described, described.contains("drawFailures="));
        if (!ready) {
            Assert.assertTrue("失败原因必须写进 describe: " + described, described.contains("failure="));
        }
        backend.dispose();
        backend.dispose();
    }

    // ------------------------------------------------------------------ 生长序号统计

    /**
     * appearOrder 扫描：u16 小端还原、0xFFFF 视为未定义、越界顶点不读。
     *
     * <p>这个值是 GLSL 生长的归一化分母，若把 0xFFFF 当最大序号，整代条柱都会「永不生长」。</p>
     */
    @Test
    public void maxAppearOrderRestoresLittleEndianAndSkipsUndefined() {
        byte[] aux = new byte[] {
            0, 0, 0, 0,               // order = 0
            0, 0, 5, 0,               // order = 5
            0, 0, (byte) 0xFF, (byte) 0xFF, // 未定义
            0, 0, (byte) 250, 0,      // order = 250
        };
        Assert.assertEquals(250.0F, ChainPreviewShaderBackend.maxAppearOrder(aux, 4), 0.0F);
        Assert.assertEquals("越界顶点不得读取", 5.0F, ChainPreviewShaderBackend.maxAppearOrder(aux, 2), 0.0F);
        Assert.assertEquals("无 aAux 必须返回 -1（区分「无序号信息」与「单目标」）",
                -1.0F, ChainPreviewShaderBackend.maxAppearOrder(null, 10), 0.0F);
        Assert.assertEquals("空数组安全且视为无序号信息",
                -1.0F, ChainPreviewShaderBackend.maxAppearOrder(new byte[0], 0), 0.0F);
    }

    // ------------------------------------------------------------------ 机制开关的恒等性
    // 生长与最小宽度的完整行为契约见 ChainPreviewShaderGrowthWidthTest（task-11）；
    // 这里只保留「开关关闭时恒等」的最小回归面，避免同一语义两处断言漂移。

    /**
     * minScreenWidthPx=0（本轮默认）必须严格恒等，不得留下任何加宽残差。
     *
     * <p>断言打在 {@link ChainPreviewShaderMath#displaceVertex}（与 {@code preview.vert} 同形的
     * 顶点位移）上，而不是任何「从 aPos 猜横向轴」的旧模型——后者已随 T51 删除。</p>
     */
    @Test
    public void minScreenWidthDisabledIsExactIdentity() {
        float[] position = {0.0225F, 0.0225F, 0.0225F};
        for (float px : new float[] {0.0F, -1.0F}) {
            Assert.assertArrayEquals("px<=0 必须逐值恒等", position,
                    ChainPreviewShaderMath.displaceVertex(position[0], position[1], position[2],
                            0.0F, 1.0F, 0.0F, 900.0F, px, 0.045F, 0.0F),
                    0.0F);
        }
        Assert.assertArrayEquals("px=NaN 同样视为关闭", position,
                ChainPreviewShaderMath.displaceVertex(position[0], position[1], position[2],
                        0.0F, 1.0F, 0.0F, 900.0F, Float.NaN, 0.045F, 0.0F),
                0.0F);
    }

    /** 生长关闭 / u>=1 / 无序号信息时都必须整段可见。 */
    @Test
    public void growthWeightIsClampedAndMonotonic() {
        Assert.assertEquals("生长关闭时必须恒等",
                1.0F, ChainPreviewShaderMath.growthWeight(false, 0.0F, 0.5F, 256.0F), 0.0F);
        Assert.assertEquals("u>=1 必须整段可见",
                1.0F, ChainPreviewShaderMath.growthWeight(true, 1.0F, 255.0F, 256.0F), 0.0F);
        Assert.assertEquals("无 aAux 序号信息（total<=0）必须恒等",
                1.0F, ChainPreviewShaderMath.growthWeight(true, 0.0F, 10.0F, 0.0F), 0.0F);
        Assert.assertEquals("0xFFFF 未定义序号必须恒可见",
                1.0F, ChainPreviewShaderMath.growthWeight(
                        true, 0.0F, (float) ChainPreviewMesh.APPEAR_ORDER_UNDEFINED, 4096.0F), 0.0F);

        float previous = -1.0F;
        for (int step = 0; step <= 100; step++) {
            float progress = step / 100.0F;
            float weight = ChainPreviewShaderMath.growthWeight(true, progress, 128.0F, 256.0F);
            Assert.assertTrue("权重必须在 [0,1]", weight >= 0.0F && weight <= 1.0F);
            Assert.assertTrue("权重必须随进度单调不减", weight >= previous - 1.0e-6F);
            previous = weight;
        }
    }

    /** 归一化字节还原：0/255/中值都必须精确往返（决定语义类别与 tubeEdge 的判定）。 */
    @Test
    public void unquantizeChannelRoundTripsByteRange() {
        Assert.assertEquals(0, ChainPreviewShaderMath.unquantizeChannel(0.0F));
        Assert.assertEquals(255, ChainPreviewShaderMath.unquantizeChannel(1.0F));
        for (int value = 0; value <= 255; value++) {
            float normalized = value / 255.0F;
            Assert.assertEquals("原始字节 " + value + " 必须精确还原",
                    value, ChainPreviewShaderMath.unquantizeChannel(normalized));
        }
    }

    /** 像素缩放换算：非法输入必须收敛为 0（等价于关闭钳制），不得产生 NaN。 */
    @Test
    public void pixelScaleRejectsIllegalInput() {
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.pixelScale(1.5F, 0), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.pixelScale(Float.NaN, 1080), 0.0F);
        Assert.assertEquals("投影矩阵 [1][1] 与视口高度共同决定像素尺度",
                1.5F * 1080.0F * 0.5F, ChainPreviewShaderMath.pixelScale(1.5F, 1080), 1.0e-3F);
    }
}
