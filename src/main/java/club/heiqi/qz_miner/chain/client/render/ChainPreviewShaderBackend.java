package club.heiqi.qz_miner.chain.client.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.entity.RenderManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 预览着色器后端：GPU 距离淡出 + 屏幕最小宽度钳制 + 逐波生长 + 语义颜色。
 *
 * <p><strong>失败必回退</strong>：任何编译 / 链接 / 验证 / 能力缺失都在 {@link #ensureReady()}
 * 内被捕获，返回 false 并把原因写进 {@link #describe()}；失败后不再重试（{@code unavailable}），
 * 渲染帧不会因本类抛异常。runtime 异常在 {@link #draw(ChainPreviewDrawPlan)} 内被吞并计数，
 * 保证不中断渲染主循环。</p>
 *
 * <p><strong>零 CPU 颜色上传</strong>：{@link #usesCpuColors()} 返回 false，颜色由 aAux 语义类别
 * + uniform 调色板在 GPU 侧决定；aColor 仍按接口冻结 §A 上传并作为顶点色参与距离淡出 alpha。</p>
 *
 * <p><strong>显式相机矩阵 + 自检（T48c-A）</strong>：着色器不再读固定管线内建矩阵。每次 draw 在
 * renderer 完成 {@code glTranslated(origin − renderPos)} 之后，用 {@code glGetFloatv} 取
 * GL_PROJECTION_MATRIX / GL_MODELVIEW_MATRIX，CPU 侧相乘成 MVP 并上传 {@code uModelViewProjection}
 * / {@code uModelView}；随后对 modelview 平移列做 {@code |平移列| ≈ |origin − renderPos|} 自检
 * （真机下内建矩阵失同步时栈保持单位阵 ⇒ 模长 0 ⇒ 立即检出）。自检不通过 ⇒ 一次性
 * {@code unavailable} ⇒ {@link #ensureReady()} 返回 false ⇒ renderer 既有的
 * {@code ensureReadyBackend()} 永久回退 legacy：不新增回退机制，也绝不画错帧。</p>
 *
 * <p><strong>T48c-C 加固</strong>：① 程序链接后校验必备 uniform 的 location（缺失 ⇒ 程序不可用，
 * 见 {@link ChainPreviewShaderProgram}），并让矩阵上传返回成功与否 —— 堵住「uniform 缺失静默跳过 ⇒
 * shader 拿零矩阵、而自检读驱动矩阵照样通过」；② 自检扩展为「投影可信 + 线性部分刚性 + 平移列模长 +
 * 平移-线性一致性」（原版相机扭曲期间跳过后两项，见 {@link #vanillaCameraWarpActive()}）。</p>
 *
 * <p><strong>GL 状态契约</strong>：帧级 pushAttrib / pushClientAttrib 与绑定围栏由 renderer 统一
 * 负责；本类内部除了 {@link #dispose()}（可能被帧外生命周期调用）以外不捕获绑定快照，也不改
 * 矩阵 / 混合 / 深度状态，只在 draw 内切换自己需要的程序与顶点属性槽位并恢复 attrib 1/2。
 * 自己拥有的 VAO 在 draw 结束时回到 0，避免覆盖 renderer 之后的状态。</p>
 *
 * <p>线程契约：所有方法只在渲染线程调用。</p>
 */
public final class ChainPreviewShaderBackend implements ChainPreviewRenderBackend {

    public static final String ID = "shader";

    /**
     * builtin 档语义色 = legacy 精确基线常量 (0.25, 0.9, 1.0)。
     *
     * <p>刻意不用 0x40E6FF 的 8bit 量化值（量化后 R=0.25098…/G=0.90196…，与 legacy
     * 有 ≤0.002 色差）。片元直接输出该绝对色，不再乘顶点基色——否则会二次乘色
     * （0.25×0.25 = 0.0625、0.9×0.9 = 0.81），与「builtin 逐字节等于现状」冲突。</p>
     */
    private static final float BUILTIN_COLOR_RED = ChainPreviewShaderMath.BUILTIN_COLOR_RED;
    private static final float BUILTIN_COLOR_GREEN = ChainPreviewShaderMath.BUILTIN_COLOR_GREEN;
    private static final float BUILTIN_COLOR_BLUE = ChainPreviewShaderMath.BUILTIN_COLOR_BLUE;

    private static final int INITIAL_CAPACITY = 16 * 1024;

    /** 顶点属性显式 stride（字节）：core profile 下不依赖 stride=0 的"紧凑"语义。 */
    private static final int POSITION_STRIDE_BYTES = 3 * 4;
    private static final int COLOR_STRIDE_BYTES = 4 * 4;
    private static final int AUX_STRIDE_BYTES = 4;

    private final ChainPreviewShaderProgram program;

    /** 程序首次就绪的能力对账日志是否已输出（一次性）。 */
    private boolean programReadyReported;
    /** uModelView 缺失（被编译器优化掉）的一次性说明是否已输出。 */
    private boolean capabilityMatrixMissingLogged;

    private int vao;
    private int vbo;
    private int cbo;
    private int abo;
    private int ebo;
    private int vboCapacity;
    private int cboCapacity;
    private int aboCapacity;
    private int eboCapacity;
    private int indexCount;
    private int vertexCount;

    private boolean initialized;
    private boolean unavailable;
    private String failureReason = "";
    private long drawFailures;

    /** 相机矩阵回读 / 相乘缓冲（渲染线程复用，零每帧分配）。 */
    private final float[] projectionMatrix = new float[ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS];
    private final float[] modelViewMatrix = new float[ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS];
    private final float[] modelViewProjectionMatrix = new float[ChainPreviewShaderMatrixMath.MATRIX_ELEMENTS];
    /** 最近一次自检的实际 / 期望模长（诊断用；未自检时为 NaN）。 */
    private float matrixTranslationMagnitude = Float.NaN;
    private double matrixExpectedMagnitude = Double.NaN;
    /** 自检失败原因（一次性）；空串表示从未失败。 */
    private String matrixSourceFailure = "";
    /** 同代最大出现序号（扫描 aAux 得到）；< 0 表示无 aAux（关闭生长比较），0 表示单目标。 */
    private float appearSpan = -1.0F;

    /*
     * shader 后端统一使用三角形。Mesh 和 ChainPreviewDrawPlan 继续以 quad 索引为
     * 业务语义，在上传 EBO 时一次性展开为 [a,b,c, a,c,d]。不能在当前 Angelica GLSM +
     * core profile 环境依赖 GL_QUADS；legacy 后端仍保留原始 quad 路径。
     */
    /** 三角形索引展开缓冲（渲染线程复用）。 */
    private int[] triangleIndexScratch;
    private FloatBuffer vertexStaging;
    private FloatBuffer colorStaging;
    private IntBuffer indexStaging;
    private ByteBuffer auxStaging;

    /** buffer 分配 seam：默认走 LWJGL BufferUtils（native 支撑），测试可注入纯 JVM 实现。 */
    interface BufferAllocator {

        FloatBuffer floatBuffer(int capacity);

        IntBuffer intBuffer(int capacity);

        ByteBuffer byteBuffer(int capacity);
    }

    /** 默认分配器：惰性实例，避免类初始化期触碰 LWJGL native。 */
    private static final BufferAllocator LWJGL_ALLOCATOR = new BufferAllocator() {

        @Override
        public FloatBuffer floatBuffer(int capacity) {
            return BufferUtils.createFloatBuffer(capacity);
        }

        @Override
        public IntBuffer intBuffer(int capacity) {
            return BufferUtils.createIntBuffer(capacity);
        }

        @Override
        public ByteBuffer byteBuffer(int capacity) {
            return BufferUtils.createByteBuffer(capacity);
        }
    };

    private final BufferAllocator allocator;

    /**
     * T50 运行时属性槽位（链接后查询得到；0/1/2 只是「请求」而非事实）。
     *
     * <p>真机实测：{@code glBindAttribLocation} 无错返回却不生效，驱动把 {@code aPos} 分到槽位 1、
     * {@code aAux} 分到槽位 2、{@code aColor} 因未被着色器读取而整体优化掉（-1）。此前按 0/1/2
     * 硬编码写指针，等于让 GPU 把 <b>aux 的 uint8 字节值当顶点坐标</b>读——几何整体落进 [0,1]³，
     * 而数据/绑定/矩阵回读全部自洽，只剩画面上「瞄准方块上的一小块色斑」。</p>
     */
    private int attributePosition = -1;
    private int attributeAux = -1;
    private int attributeColor = -1;

    public ChainPreviewShaderBackend() {
        this(new ChainPreviewShaderProgram(), LWJGL_ALLOCATOR);
    }

    ChainPreviewShaderBackend(ChainPreviewShaderProgram program) {
        this(program, LWJGL_ALLOCATOR);
    }

    ChainPreviewShaderBackend(ChainPreviewShaderProgram program, BufferAllocator allocator) {
        this.program = program;
        this.allocator = allocator;
    }

    /**
     * renderer 的反射 seam（render-core 已裁定）。
     *
     * @return 全新后端实例；未初始化，失败也要等 ensureReady 才知道
     */
    public static ChainPreviewRenderBackend create() {
        return new ChainPreviewShaderBackend();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean usesCpuColors() {
        return false;
    }

    /**
     * 惰性初始化；<b>任一步失败（含 draw 期矩阵自检失败）之后恒返回 false</b>，不每帧重试。
     *
     * <p>两个分支的顺序不可交换：{@code unavailable} 必须先判。矩阵自检发生在 draw 内（只有那里
     * 拿得到「已 glTranslated 的 modelview」），它只能把后端置为一次性不可用；renderer 下一帧经
     * {@code ensureReadyBackend()} 读到 false 后走既有的一次性永久回退 legacy 路径。</p>
     */
    @Override
    public boolean ensureReady() {
        if (unavailable) {
            return false;
        }
        if (initialized) {
            return true;
        }
        try {
            if (!program.ensureReady()) {
                unavailable = true;
                failureReason = program.getLastFailureMessage().isEmpty()
                        ? "着色器程序不可用" : program.getLastFailureMessage();
                return false;
            }
            // T50：槽位必须在写 VAO 之前解析——绑错槽位就是「顶点数据永远读不到」。
            resolveAttributeBindings();
            initializeGl();
            initialized = true;
            failureReason = "";
            reportProgramReady();
            return true;
        } catch (Throwable failure) {
            unavailable = true;
            failureReason = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            try {
                releaseGl();
            } catch (Throwable cleanupFailure) {
                // 上下文不可用时清理也会失败；此时句柄已随上下文失效，收敛掉即可。
            }
            return false;
        }
    }

    @Override
    public void uploadTopology(ChainPreviewMesh mesh) {
        // 契约（ChainPreviewRenderBackend）：空网格不得触发 GL 初始化。
        // renderer 的 clearMesh 路径在帧围栏之外调用本方法，若此处先 ensureReady()
        // 就会把着色器编译 + VAO/buffer 绑定带到无围栏路径（对齐 legacy 后端顺序）。
        if (mesh == null || mesh.isEmpty()) {
            indexCount = 0;
            vertexCount = 0;
            appearSpan = -1.0F;
            return;
        }
        if (!ensureReady()) {
            return;
        }

        float[] vertices = mesh.vertexArray();
        float[] colors = mesh.colorArray();
        int[] indices = mesh.indexArray();
        byte[] aux = mesh.isAuxAvailable() ? mesh.auxArray() : null;
        int vertexFloatCount = mesh.getVertexFloatCount();
        int colorFloatCount = mesh.getColorFloatCount();

        // plan 的索引语义恒为 mesh 的 quad 索引；shader EBO 在此处按 4→6 展开。
        int quadIndexCount = mesh.getIndexCount();
        int uploadIndexCount = expandQuadsToTriangles(indices, quadIndexCount);
        int[] uploadIndices = triangleIndexScratch;
        indexCount = uploadIndexCount;
        vertexCount = vertexFloatCount / 3;
        // 刻意不缓存 meshOrigin：uOriginRel 与自检期望值一律取 plan 的 origin（renderer 的
        // glTranslated 与 legacy 后端都用 plan origin）。上传期缓存会在「同 mesh 换 plan」时
        // 让两后端语义分叉（T48c-A）。
        appearSpan = maxAppearOrder(aux, vertexCount);

        // ARRAY_BUFFER 绑定是**全局**状态（不属于 VAO）：本方法为写 VBO / CBO / ABO 连续切换绑定，
        // 必须在出口成对恢复，否则会把「当前绑定 = 我们的内部缓冲」泄漏给后续渲染路径。
        //
        // 为什么这条泄漏在本环境是致命的：Angelica 的 GLSM 带有 GLStateManager 状态缓存层
        // （com.gtnewhorizons.angelica.glsm.GLStateManager + glsm/recording/*），它按**自己的缓存**
        // 判断「绑定是否已是目标」；我们直接调用原生 GL15 绕过该缓存，一旦缓存与实际不一致，
        // 它后续的缓冲上传/重分配就会落到我们当前绑定的缓冲上。真机表型正是：VBO 全 0 而 EBO 正常
        // （EBO 绑定属于 VAO 状态，随 glBindVertexArray(0) 一起收敛，所以从未被串扰）。
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int requiredVboSize = vertexFloatCount * 4;
        // T49：真机实测「不前置 glBufferData 的 glBufferSubData」会静默丢失——只有 vboCapacity
        // 增长的那一次写入有效，后续写入后立即回读仍全 0（draw 期同样为 0）。本环境 GL 调用
        // 会被 Angelica 的 GLSMRedirector 重写进 GLStateManager（它跟踪 boundVBO），继续逆向
        // 其内部时序性价比低，改用已被证明有效的组合：每次上传先重新分配（buffer orphaning）
        // 再写入。语义不变（本来就是全量重传），代价是每次上传一次缓冲重分配。
        vboCapacity = calculateNewCapacity(requiredVboSize);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        vertexStaging = prepareFloatBuffer(vertexStaging, vertices, vertexFloatCount);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertexStaging);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
        int requiredCboSize = colorFloatCount * 4;
        // T49：同 VBO，每次重新分配后再写入（见上方说明）。
        cboCapacity = calculateNewCapacity(requiredCboSize);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cboCapacity, GL15.GL_DYNAMIC_DRAW);
        colorStaging = prepareFloatBuffer(colorStaging, colors, colorFloatCount);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, colorStaging);

        uploadAux(aux, vertexCount);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        int requiredEboSize = indexCount * 4;
        // T49：同 VBO/CBO/ABO，每次重新分配后再写入；EBO 此前看似正确其实是假阳性
        // （前几个索引在不同几何间恰好相同），一并纳入同一策略。
        eboCapacity = calculateNewCapacity(requiredEboSize);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);
        indexStaging = prepareIntBuffer(indexStaging, uploadIndices, uploadIndexCount);
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0, indexStaging);

        GL30.glBindVertexArray(0);
        // 成对恢复：与 draw 路径同一纪律（draw 早已恢复 ARRAY_BUFFER，upload 此前遗漏）。
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
    }

    /** shader 路径不消费 CPU 颜色流：颜色在 GPU 侧由 aAux + uniform 调色板产生。 */
    @Override
    public boolean uploadColors(ChainPreviewMesh mesh) {
        return false;
    }

    @Override
    public void draw(ChainPreviewDrawPlan plan) {
        if (!initialized || unavailable || indexCount <= 0 || plan == null) {
            return;
        }
        int visibleIndexCount = plan.getVisibleIndexCount();
        if (visibleIndexCount <= 0) {
            return;
        }
        int indexOffset = Math.max(0, plan.getIndexOffset());
        // plan 仍以 quad 索引描述可见范围；shader EBO 已逐 quad 展开为 6 个三角形索引。
        indexOffset = indexOffset / 4 * 6;
        visibleIndexCount = visibleIndexCount / 4 * 6;
        if (indexOffset + visibleIndexCount > indexCount) {
            visibleIndexCount = indexCount - indexOffset;
            if (visibleIndexCount <= 0) {
                return;
            }
        }

        int previousProgram = 0;
        try {
            // GL_CURRENT_PROGRAM 不受 glPushAttrib 覆盖，必须显式保存 / 恢复（Lead 批准 +1 次查询）。
            previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            program.use();
            if (!applyUniforms(plan)) {
                // 相机矩阵来源不可信：本帧不画（绝不留错误空间的一帧）。后端已被置为一次性不可用，
                // 下一帧由 renderer 既有的 ensureReadyBackend() 永久回退 legacy。
                return;
            }

            GL30.glBindVertexArray(vao);
            // 每帧显式重设属性布局：本环境是 core profile + GLSM，VAO 的 attrib 记录不保证
            // 在外部渲染路径之后仍然有效（详见 bindVertexLayout 的 javadoc）。
            int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            bindVertexLayout();
            GL20.glEnableVertexAttribArray(attributePosition);
            GL20.glEnableVertexAttribArray(attributeAux);
            if (attributeColor >= 0) {
                GL20.glEnableVertexAttribArray(attributeColor);
            }
            int primitive = GL11.GL_TRIANGLES;
            GL11.glDrawElements(
                primitive,
                visibleIndexCount,
                GL11.GL_UNSIGNED_INT,
                (long) indexOffset * 4L);
            // attrib 的 enable 状态属于 VAO：必须在自绑 VAO 还绑定时成对关闭，
            // 否则关掉的是外部默认 VAO 的 attrib 数组（D1）。
            if (attributeColor >= 0) {
                GL20.glDisableVertexAttribArray(attributeColor);
            }
            GL20.glDisableVertexAttribArray(attributeAux);
            GL20.glDisableVertexAttribArray(attributePosition);
            GL30.glBindVertexArray(0);
            // GL_ARRAY_BUFFER 绑定不属于 VAO，必须显式还原（本环境没有可用的固定管线围栏）。
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        } catch (Throwable failure) {
            // 渲染帧不得因着色器路径抛异常：计数并让本帧静默结束，下一帧仍可绘制。
            drawFailures++;
            if (failureReason.isEmpty()) {
                failureReason = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            }
        } finally {
            // 异常路径也必须还回进入前的 program 绑定，否则残留本程序影响后续原版渲染。
            try {
                GL20.glUseProgram(previousProgram);
            } catch (Throwable ignored) {
                // GL 不可用时无需恢复。
            }
        }
    }


    @Override
    public void dispose() {
        indexCount = 0;
        vertexCount = 0;
        appearSpan = -1.0F;
        program.dispose();
        try {
            releaseGl();
        } catch (Throwable failure) {
            // dispose 可能在帧外生命周期调用，GL 上下文可能已失效：不得向上抛。
        }
        initialized = false;
        unavailable = false;
        failureReason = "";
        drawFailures = 0L;
        matrixSourceFailure = "";
        matrixTranslationMagnitude = Float.NaN;
        matrixExpectedMagnitude = Double.NaN;
    }

    @Override
    public String describe() {
        StringBuilder text = new StringBuilder(ID).append('{')
            .append("program=").append(program.getProgramId())
            .append(", vao=").append(vao)
            .append(", vbo=").append(vbo)
            .append(", cbo=").append(cbo)
            .append(", abo=").append(abo)
            .append(", ebo=").append(ebo)
            .append(", indexCount=").append(indexCount)
            .append(", vertexCount=").append(vertexCount)
            .append(", appearSpan=").append(appearSpan)
            .append(", initialized=").append(initialized)
            .append(", unavailable=").append(unavailable)
            .append(", drawFailures=").append(drawFailures)
            .append(", matrix=").append(describeMatrixSource())
            .append(", ").append(program.describePixelScaleCache())
            .append(", ").append(program.getCapabilityReport())
            .append('}');
        if (!failureReason.isEmpty()) {
            text.append(" failure=").append(failureReason);
        }
        if (program.isUnavailable() && !program.getLastFailureMessage().isEmpty()) {
            text.append(" shaderFailure=").append(program.getLastFailureMessage());
        }
        if (program.hasMissingMatrixUniforms()) {
            text.append(" matrixUniforms=missing");
        }
        return text.toString();
    }

    /**
     * T49：程序首次就绪时打一条 INFO，把「必备全齐 + 能力型缺失清单」落到真机日志。
     *
     * <p>为什么必须打：能力型 uniform 缺失是「该能力关闭」的正常表现，但若没有对账日志，
     * 它与「shader 根本没生效」在观感上完全一样。一次性输出，常态零开销。</p>
     */
    private void reportProgramReady() {
        if (programReadyReported) {
            return;
        }
        programReadyReported = true;
        try {
            MyMod.LOG.info("[ChainPreview] shader program ready: requiredUniforms="
                + ChainPreviewShaderProgram.requiredUniforms().length
                + ", " + program.getCapabilityReport());
        } catch (Throwable ignored) {
            // 诊断日志不得影响渲染帧。
        }
    }

    /**
     * 相机矩阵来源诊断：正常时给出「读到的平移列模长 / 期望模长」，自检失败时给出原因。
     *
     * <p>真机若再出现「画进错误空间」，这一行可直接读出矩阵栈是否被更新（T48c-A 的可观测性目标）。</p>
     *
     * @return describe 片段
     */
    private String describeMatrixSource() {
        if (!matrixSourceFailure.isEmpty()) {
            return "untrusted(" + matrixSourceFailure + ")";
        }
        if (Float.isNaN(matrixTranslationMagnitude)) {
            return "unchecked";
        }
        return "translation=" + matrixTranslationMagnitude + "/expected=" + matrixExpectedMagnitude;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 逐帧 uniform：相机矩阵、距离淡出曲线（与 CPU 端 alphaFor 同形）、最小宽度、生长与调色板。
     *
     * <p>相机相对原点取 <b>plan 的 origin</b>（renderer 的 {@code glTranslated} 与 legacy 后端都用它），
     * 在 CPU 侧用 double 相减，避免大坐标在 GPU 端相减丢精度。</p>
     *
     * @return 相机矩阵来源是否可信；false 表示本帧不得绘制（后端已置一次性不可用）
     */
    private boolean applyUniforms(ChainPreviewDrawPlan plan) {
        double originRelativeX = (double) plan.getOriginX() - RenderManager.renderPosX;
        double originRelativeY = (double) plan.getOriginY() - RenderManager.renderPosY;
        double originRelativeZ = (double) plan.getOriginZ() - RenderManager.renderPosZ;
        program.setOriginRel((float) originRelativeX, (float) originRelativeY, (float) originRelativeZ);

        if (!uploadCameraMatrices(plan, originRelativeX, originRelativeY, originRelativeZ)) {
            return false;
        }

        program.setFadeCurve(
            plan.getFadeStartRadius(),
            plan.getFadeEndRadius(),
            plan.getAlphaEnd(),
            plan.getAlphaStart());
        program.setMinScreenWidthPx(plan.getMinScreenWidthPx());
        program.setBarThickness(plan.getBarThickness());
        program.setPixelScale(program.readPixelScale());

        float animationU = plan.getAnimationU();
        // 序号总数语义（T13-D1）：appearSpan 是「最大出现序号」，单目标时为 0，
        // 若拿它当关闭条件会把 1 个目标的链路误判成「关闭生长」而立即全显。
        // 真正表示「无序号信息」的是 aux 缺失（appearSpan < 0），而不是序号为 0。
        float orderCount = appearSpan >= 0.0F ? appearSpan + 1.0F : 0.0F;
        if (animationU >= ChainPreviewDrawPlan.ANIMATION_COMPLETE || orderCount <= 0.0F) {
            // u >= 1（或无语义序号）：整段可见，shader 完全不读 appearOrder，无逐顶点分支开销。
            program.setAnimation(1.0F, 0.0F);
        } else {
            program.setAnimation(clamp01(animationU), orderCount);
        }

        // 淡入淡出包络（B3.2 / L5）：与距离淡出、逐波生长相乘得到最终 alpha。
        // 默认档 getFadeAlpha() == 1 ⇒ 逐值等于启用动画前（乘 1 不改变结果）。
        program.setFadeAlpha(plan.getFadeAlpha());

        // B3.x 真描边：只有 OUTLINE 的**描边壳段**传非 0 宽度。
        // xray（默认）/ occlude / OUTLINE 主体段一律 0 ⇒ 顶点位移矩阵恒等，逐值等于现状。
        float outlineWidthPx = plan.isOutlineShell() ? plan.getOutlineWidthPx() : 0.0F;
        program.setOutlineWidthPx(ChainPreviewShaderMath.outlineWidthPx(outlineWidthPx));

        // 调色板：builtin 档四槽都传精确基线常量 (0.25, 0.9, 1.0)，逐位等于 legacy 颜色流
        // （不经 int 往返，避免 0.9 → 230/255 的 8bit 量化色差）。
        applyColorPalette(plan);
        return true;
    }

    /**
     * 读取固定管线矩阵（只在 renderer 的 {@code glTranslated} 之后调用）→ CPU 4×4 相乘 → 上传
     * {@code uModelView} / {@code uModelViewProjection}，并对 modelview 平移列做自检。
     *
     * <p>被弃用的内建 {@code gl_ModelViewProjectionMatrix} 在真机（Angelica GLSM 用生成着色器
     * 模拟固定管线 + {@code use_no_error_g_l_context=true}）下与真实相机矩阵失同步，这是 T48c-A
     * 的根因位点：显式矩阵让「实际用到的矩阵」可读、可断言、可自检。</p>
     *
     * @param plan            当前 draw plan（用于读取 origin）
     * @param originRelativeX 相机相对 origin X（已在 double 域算出，期望模长复用同一组值）
     * @param originRelativeY 相机相对 origin Y
     * @param originRelativeZ 相机相对 origin Z
     * @return 矩阵来源是否可信
     */
    private boolean uploadCameraMatrices(
            ChainPreviewDrawPlan plan,
            double originRelativeX, double originRelativeY, double originRelativeZ) {
        if (!program.readCameraMatrices(projectionMatrix, modelViewMatrix)) {
            markMatrixSourceUntrusted("矩阵读取失败");
            return false;
        }
        ChainPreviewShaderMatrixMath.multiply4x4(
            modelViewProjectionMatrix, projectionMatrix, modelViewMatrix);

        // 自检（纯数值，不额外查询 GL）：T48c-C 加固后为四项 —— 投影可信（有限 / [0][0]、[1][1] > 0 /
        // 行列式非退化）→ 线性部分刚性（三列单位长度且两两正交，挡塌缩与 2× 缩放）→ 平移列模长
        // （挡单位阵/陈旧平移）→ 平移-线性一致性（挡「平移被搬到别的轴」）。原版相机扭曲期间
        // （传送门 / 反胃）modelview 合法地非刚性，见 vanillaCameraWarpActive()。
        boolean cameraWarpActive = vanillaCameraWarpActive();
        double expected = ChainPreviewShaderMatrixMath.magnitude(
            originRelativeX, originRelativeY, originRelativeZ);
        float actual = ChainPreviewShaderMatrixMath.translationMagnitude(modelViewMatrix);
        matrixTranslationMagnitude = actual;
        matrixExpectedMagnitude = expected;
        ChainPreviewShaderMatrixMath.MatrixVerdict verdict = ChainPreviewShaderMatrixMath.verifyCameraMatrices(
            projectionMatrix, modelViewMatrix,
            originRelativeX, originRelativeY, originRelativeZ,
            cameraWarpActive, translationDirectionSlack());
        if (verdict != ChainPreviewShaderMatrixMath.MatrixVerdict.TRUSTWORTHY) {
            markMatrixSourceUntrusted(describeVerdict(verdict, actual, expected));
            return false;
        }

        // 硬矩阵：uModelViewProjection 决定 gl_Position，缺它必然全错（顶点塌到原点，而后端自检读的是
        // 驱动矩阵，查不出来）⇒ 上传失败即放弃本帧并回退（T48c-C）。
        if (!program.setModelViewProjection(modelViewProjectionMatrix)) {
            markMatrixSourceUntrusted("uModelViewProjection 未上传（location < 0 或 GL 失败）");
            return false;
        }
        // 能力矩阵：uModelView 当前只服务「屏幕最小宽度 / 深度换算」这些尚未启用的分支，GLSL 编译器
        // 可以按规范把它优化掉（location = -1）。缺失 ⇒ 该能力关闭（已登记进 capabilityReport），
        // 但投影本身仍正确，不得据此作废整帧 —— 这是 2026-09-13 真机「shader 档什么都不画」的第二段原因。
        // 返回值仍被显式消费：一次性说明「哪个能力因此关闭」，避免再次静默。
        boolean modelViewUploaded = program.setModelView(modelViewMatrix);
        if (!modelViewUploaded && !capabilityMatrixMissingLogged) {
            capabilityMatrixMissingLogged = true;
            try {
                MyMod.LOG.info("[ChainPreview] uModelView 不可用（GLSL 编译器已优化掉该 uniform）："
                    + "屏幕最小宽度 / 深度换算能力关闭，其余功能不受影响");
            } catch (Throwable ignored) {
                // 诊断日志不得影响渲染帧。
            }
        }

        return true;
    }

    /**
     * 自检结论 → 诊断文本。
     *
     * @param verdict  结论（非 TRUSTWORTHY）
     * @param actual   实测平移列模长
     * @param expected 期望平移列模长
     * @return 人类可读原因
     */
    private static String describeVerdict(
            ChainPreviewShaderMatrixMath.MatrixVerdict verdict, float actual, double expected) {
        switch (verdict) {
            case PROJECTION_UNTRUSTED:
                return "投影矩阵不可信（非有限 / [0][0] 或 [1][1] <= 0 / 行列式退化）";
            case LINEAR_PART_NOT_RIGID:
                return "modelview 线性部分非刚性（长度或正交性超容差）";
            case TRANSLATION_MISMATCH:
                return "FFP 矩阵栈平移列模长 " + actual + " 与期望 " + expected + " 不符";
            case TRANSLATION_DIRECTION_MISMATCH:
                return "平移列与线性部分 × 相机相对原点不一致（平移方向异常）";
            default:
                return "矩阵来源不可信";
        }
    }

    /**
     * 原版相机扭曲（下界传送门 / 反胃药水）是否生效。
     *
     * <p>{@code EntityPlayerSP.timeInPortal > 0} 时 {@code EntityRenderer.setupCameraTransform:710-712}
     * 会对 modelview 施加 {@code glRotatef → glScalef(1/f3, 1, 1) → glRotatef}</p>，即<b>非均匀缩放</b>
     * ⇒ 线性部分合法地非刚性、平移-线性一致性也不再成立（被缩放）。此时跳过刚性/方向两项判据，
     * 否则会把正常相机状态判成不可用并<b>永久回退</b> legacy（误判代价比原缺陷更重）。
     *
     * <p>取 {@code timeInPortal} 与 {@code prevTimeInPortal} 的较大者：渲染用的是两者的插值，
     * 只看当前值会漏掉首末过渡帧（{@code EntityPlayerSP:136} 每 tick 同步 prev）。</p>
     *
     * @return 是否处于原版相机扭曲状态；取不到客户端状态时按 false（保持既有严格度）
     */
    private static boolean vanillaCameraWarpActive() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            EntityPlayerSP player = minecraft == null ? null : minecraft.thePlayer;
            if (player == null) {
                return false;
            }
            return player.timeInPortal > 0.0F || player.prevTimeInPortal > 0.0F;
        } catch (Throwable failure) {
            // 客户端状态不可读（生命周期早期 / 非客户端线程）不得影响渲染帧。
            return false;
        }
    }

    /** vanilla 第三人称相机拉回上限（{@code EntityRenderer.thirdPersonDistance} 默认 4.0F，且为 private）。 */
    private static final float VANILLA_THIRD_PERSON_DISTANCE_MAX = 4.0F;

    /**
     * 平移方向一致性容差：第一人称 {@link ChainPreviewShaderMatrixMath#TRANSLATION_DIRECTION_SLACK}
     * （6.0 格），第三人称再加 vanilla 拉回上限 4.0 格。
     *
     * <p>为什么第三人称要放宽：{@code orientCamera} 会把相机沿视线拉回
     * {@code thirdPersonDistance}（默认 4.0，{@code EntityRenderer:575/629}），这段相机空间平移
     * 会原样进入 modelview 的平移列 ⇒ 残差 ≈ 拉回距离 + bob + 眼位偏移。若统一用 6.0，
     * 拉回距离被宿主/模组推大时会把正常相机判成不一致并<b>永久回退</b> legacy（比原缺陷更轻但仍
     * 属误判）；第一人称则保持 6.0 的判别力。</p>
     *
     * <p>{@code thirdPersonDistance} 是 private 字段无法读取，且渲染用的是它的插值
     * {@code thirdPersonDistanceTemp}；这里用默认上限 4.0 作为预算。读不到客户端状态时按第三人称
     * （更宽松）处理——误判代价不对称。</p>
     *
     * @return 方向一致性容差（格）
     */
    private static double translationDirectionSlack() {
        double pullback;
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            boolean thirdPerson = minecraft != null && minecraft.gameSettings != null
                    && minecraft.gameSettings.thirdPersonView > 0;
            pullback = thirdPerson ? VANILLA_THIRD_PERSON_DISTANCE_MAX : 0.0D;
        } catch (Throwable failure) {
            pullback = VANILLA_THIRD_PERSON_DISTANCE_MAX;
        }
        return ChainPreviewShaderMatrixMath.TRANSLATION_DIRECTION_SLACK + pullback;
    }

    /**
     * 矩阵来源不可信 ⇒ 一次性不可用，<b>不新建回退机制</b>。
     *
     * <p>renderer 下一帧调用 {@link #ensureReady()} 拿到 false，随即走既有的一次性永久回退 legacy
     * 路径（一次 WARN + describe 诊断）。失败只发生一次、不每帧重试，也绝不画错误空间。</p>
     *
     * @param reason 人类可读原因（同时写进 {@link #describe()}）
     */
    private void markMatrixSourceUntrusted(String reason) {
        unavailable = true;
        matrixSourceFailure = reason;
        if (failureReason.isEmpty()) {
            failureReason = reason;
        }
    }

    /**
     * 设置四色调色板（uniform 声明在**顶点**着色器：选色在顶点阶段完成，F1）。
     *
     * <p>两档分别走不同精度通道：</p>
     * <ul>
     *   <li><b>builtin（生产默认）</b>：四槽传**精确基线常量** (0.25, 0.9, 1.0)。
     *       刻意不消费 plan 的 {@code Colors.BUILTIN_RGB}（= 0x40E6FF 的 8bit 量化值）——
     *       量化后 R=0.25098…、G=0.90196…，与 legacy 颜色流有 ≤0.002 色差，
     *       会破坏「builtin 逐字节等于现状」。plan 侧该量化值只用于值相等与诊断。</li>
     *   <li><b>config</b>：四槽取 plan 的四色（配置本以 int RGB 存储），按 8bit 量化
     *       （{@code /255}）；该量化差异只出现在本档，已登记。</li>
     * </ul>
     *
     * @param plan 当前 draw plan（配置只经 plan 传入，保持「只读 plan + uniform」单通道，遵守 §H）
     */
    private void applyColorPalette(ChainPreviewDrawPlan plan) {
        if (ChainPreviewShaderMath.COLOR_SOURCE_CONFIG.equals(plan.getColorSourceId())) {
            program.setSemanticColorRgb(ChainPreviewShaderMath.PALETTE_PRIMARY, plan.getColorPrimary());
            program.setSemanticColorRgb(ChainPreviewShaderMath.PALETTE_SECONDARY, plan.getColorSecondary());
            program.setSemanticColorRgb(ChainPreviewShaderMath.PALETTE_REMOTE, plan.getColorRemote());
            program.setSemanticColorRgb(ChainPreviewShaderMath.PALETTE_TRUNCATED, plan.getColorTruncated());
            return;
        }
        for (int slot = ChainPreviewShaderMath.PALETTE_PRIMARY;
                slot <= ChainPreviewShaderMath.PALETTE_TRUNCATED; slot++) {
            program.setSemanticColor(slot, BUILTIN_COLOR_RED, BUILTIN_COLOR_GREEN, BUILTIN_COLOR_BLUE);
        }
    }

    private void uploadAux(byte[] aux, int vertexCount) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, abo);
        int requiredAboSize = Math.max(vertexCount * ChainPreviewMesh.AUX_BYTES_PER_VERTEX, 1);
        if (aux == null || aux.length < requiredAboSize) {
            // 无语义流（或流长不足）时整段填「未定义」：semanticClass/tubeEdge = 255、
            // appearOrder = 0xFFFF。只填前 64 字节会让后续顶点的 aAux 读到未定义数据，
            // B2.3 接线颜色语义后会显形（cross-review D5）。
            aboCapacity = calculateNewCapacity(requiredAboSize);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, aboCapacity, GL15.GL_DYNAMIC_DRAW);
            undefinedAuxStaging = prepareUndefinedBuffer(undefinedAuxStaging, requiredAboSize);
            auxStaging = undefinedAuxStaging;
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, auxStaging);
            return;
        }
        // T49：同 VBO，每次重新分配后再写入（见 uploadTopology 的说明）。
        aboCapacity = calculateNewCapacity(requiredAboSize);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, aboCapacity, GL15.GL_DYNAMIC_DRAW);
        auxStaging = prepareByteBuffer(auxStaging, aux);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, auxStaging);
    }

    /**
     * 构造长度为 required 的「未定义」aAux 缓冲（全 0xFF）。
     *
     * <p>只有在无 aAux 流或流长不足时才走这里；容量不足时一次填充，之后复用，
     * 不退化成逐顶点循环。</p>
     */
    static ByteBuffer prepareUndefinedBuffer(ByteBuffer buffer, int required) {
        int target = Math.max(required, 1);
        if (buffer == null || buffer.capacity() < target) {
            buffer = ByteBuffer.allocate(calculateElementCapacity(target));
        }
        // put(index, value) 是绝对写入，不移动 position，也无需 limit 技巧。
        for (int i = 0; i < target; i++) {
            buffer.put(i, (byte) 0xFF);
        }
        buffer.position(0);
        buffer.limit(buffer.capacity());
        return buffer;
    }

    /**
     * 扫描 aAux 求同代最大出现序号（生长归一化分母）。
     *
     * <p>只在 uploadTopology 时执行一次（代级），不在每帧路径上。0xFFFF 视为未定义不参与。</p>
     */
    static float maxAppearOrder(byte[] aux, int vertexCount) {
        if (aux == null || aux.length < ChainPreviewMesh.AUX_BYTES_PER_VERTEX) {
            // 无 aAux 或长度不足一个顶点：视为「无序号信息」（-1）。
            // 不能返回 0——那会被当成「单目标」，让退化 mesh 误入生长路径。
            return -1.0F;
        }
        int limit = Math.min(vertexCount, aux.length / ChainPreviewMesh.AUX_BYTES_PER_VERTEX);
        int max = 0;
        for (int vertex = 0; vertex < limit; vertex++) {
            int offset = vertex * ChainPreviewMesh.AUX_BYTES_PER_VERTEX;
            int order = (aux[offset + 2] & 0xFF) | ((aux[offset + 3] & 0xFF) << 8);
            if (order != ChainPreviewMesh.APPEAR_ORDER_UNDEFINED && order > max) {
                max = order;
            }
        }
        return (float) max;
    }

    /**
     * 把 quad 索引序列 {@code [a,b,c,d]} 展开为三角形序列 {@code [a,b,c, a,c,d]}（每 4 个产出 6 个）。
     *
     * <p>上传 shader EBO 时使用；写进 {@link #triangleIndexScratch} 并返回有效长度。</p>
     *
     * @param indices        源索引数组（至少 {@code quadIndexCount} 个元素）
     * @param quadIndexCount 源索引数（恒为 4 的倍数）
     * @return 展开后的三角形索引数（= {@code quadIndexCount / 4 * 6}）
     */
    private int expandQuadsToTriangles(int[] indices, int quadIndexCount) {
        int triangles = quadIndexCount / 4 * 6;
        if (triangleIndexScratch == null || triangleIndexScratch.length < triangles) {
            triangleIndexScratch = new int[Math.max(triangles, 1)];
        }
        int source = 0;
        int target = 0;
        while (source + 3 < quadIndexCount) {
            int a = indices[source];
            int b = indices[source + 1];
            int c = indices[source + 2];
            int d = indices[source + 3];
            triangleIndexScratch[target++] = a;
            triangleIndexScratch[target++] = b;
            triangleIndexScratch[target++] = c;
            triangleIndexScratch[target++] = a;
            triangleIndexScratch[target++] = c;
            triangleIndexScratch[target++] = d;
            source += 4;
        }
        return target;
    }

    /**
     * 在当前绑定的 VAO 上重新声明三个顶点属性的 buffer / 格式 / stride / 偏移。
     *
     * <p><b>为什么必须每帧重设</b>：真机环境实测为 GL 4.6 <b>core profile</b>
     * （{@code profileMask=1}）＋ Angelica GLSM ＋ lwjgl3ify。同一上下文里
     * {@code glPushClientAttrib/glPopClientAttrib} 已被移除（返回 GL_INVALID_OPERATION，日志可见
     * "GL fence popClientAttrib reported error 1282"），也就是说「客户端顶点数组状态由固定管线栈
     * 保存/恢复」这个前提在该环境<b>不成立</b>。只依赖初始化时写入一次 VAO 的 attrib 记录，
     * 会在外部渲染路径改动后读到非几何数据——真机表型正是「整链塌缩到瞄准方块的一个面上」。
     * 本方法幂等，代价是每帧 3 次 {@code glVertexAttribPointer}；GL_ARRAY_BUFFER 绑定由调用方还原。</p>
     */
    private void bindVertexLayout() {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glVertexAttribPointer(attributePosition, 3, GL11.GL_FLOAT, false, POSITION_STRIDE_BYTES, 0L);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, abo);
        GL20.glVertexAttribPointer(attributeAux, 4, GL11.GL_UNSIGNED_BYTE, true, AUX_STRIDE_BYTES, 0L);
        if (attributeColor >= 0) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
            GL20.glVertexAttribPointer(attributeColor, 4, GL11.GL_FLOAT, false, COLOR_STRIDE_BYTES, 0L);
        }
    }

    /** T50：链接后取驱动分配的属性槽位；aPos / aAux 缺失即置后端不可用（绝不画错误空间的一帧）。 */
    private void resolveAttributeBindings() {
        attributePosition = program.getPositionAttributeLocation();
        attributeAux = program.getAuxAttributeLocation();
        attributeColor = program.getColorAttributeLocation();
        if (attributePosition < 0 || attributeAux < 0 || attributePosition == attributeAux) {
            throw new IllegalStateException("属性槽位非法：aPos=" + attributePosition
                + ", aAux=" + attributeAux + ", aColor=" + attributeColor);
        }
    }

    private void initializeGl() {
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        cbo = GL15.glGenBuffers();
        abo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
        vboCapacity = INITIAL_CAPACITY;
        cboCapacity = INITIAL_CAPACITY;
        aboCapacity = INITIAL_CAPACITY;
        eboCapacity = INITIAL_CAPACITY;

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        // 接口冻结 §A：aPos = 3 x float32（相对 meshOrigin 的局部坐标）。槽位取运行时解析值。
        GL20.glVertexAttribPointer(attributePosition, 3, GL11.GL_FLOAT, false, POSITION_STRIDE_BYTES, 0L);
        GL20.glEnableVertexAttribArray(attributePosition);

        // 接口冻结 §A：aAux = 4 x uint8 normalized（semanticClass / tubeEdge / appearOrder u16 LE）
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, abo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, aboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(attributeAux, 4, GL11.GL_UNSIGNED_BYTE, true, AUX_STRIDE_BYTES, 0L);
        GL20.glEnableVertexAttribArray(attributeAux);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cboCapacity, GL15.GL_DYNAMIC_DRAW);
        // 着色器从不读取 aColor（颜色由 aAux + uniform 调色板在顶点阶段决定），编译器会把它整体
        // 优化掉：此时 location = -1，绝不能拿它当槽位写指针（旧代码写死 2 恰好覆盖了 aAux）。
        if (attributeColor >= 0) {
            GL20.glVertexAttribPointer(attributeColor, 4, GL11.GL_FLOAT, false, COLOR_STRIDE_BYTES, 0L);
            GL20.glEnableVertexAttribArray(attributeColor);
        }

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);

        GL30.glBindVertexArray(0);
    }

    private void releaseGl() {
        attributePosition = -1;
        attributeAux = -1;
        attributeColor = -1;
        int deletedVao = vao;
        int deletedVbo = vbo;
        int deletedCbo = cbo;
        int deletedAbo = abo;
        int deletedEbo = ebo;
        boolean release = initialized
            || deletedVao != 0
            || deletedVbo != 0
            || deletedCbo != 0
            || deletedAbo != 0
            || deletedEbo != 0;
        vao = 0;
        vbo = 0;
        cbo = 0;
        abo = 0;
        ebo = 0;
        vboCapacity = 0;
        cboCapacity = 0;
        aboCapacity = 0;
        eboCapacity = 0;
        vertexStaging = null;
        colorStaging = null;
        indexStaging = null;
        auxStaging = null;
        if (!release) {
            return;
        }

        ChainPreviewGlBindings previous = ChainPreviewGlBindings.capture();
        try {
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glDeleteVertexArrays(deletedVao);
            GL15.glDeleteBuffers(deletedVbo);
            GL15.glDeleteBuffers(deletedCbo);
            GL15.glDeleteBuffers(deletedAbo);
            GL15.glDeleteBuffers(deletedEbo);
        } finally {
            previous.withoutDeletedBuffers(
                deletedVao, deletedVbo, deletedCbo, deletedAbo, deletedEbo).restore();
        }
    }

    private static int calculateNewCapacity(int requiredSize) {
        int newCapacity = Math.max(INITIAL_CAPACITY, 16);
        while (newCapacity < requiredSize) {
            if (newCapacity > Integer.MAX_VALUE / 2) {
                return requiredSize;
            }
            newCapacity *= 2;
        }
        return newCapacity;
    }

    private FloatBuffer prepareFloatBuffer(FloatBuffer buffer, float[] values, int count) {
        int required = Math.max(count, 1);
        if (buffer == null || buffer.capacity() < required) {
            buffer = allocator.floatBuffer(calculateElementCapacity(required));
        }
        buffer.clear();
        if (count > 0) {
            buffer.put(values, 0, count);
        }
        buffer.flip();
        return buffer;
    }

    private IntBuffer prepareIntBuffer(IntBuffer buffer, int[] values, int count) {
        int required = Math.max(count, 1);
        if (buffer == null || buffer.capacity() < required) {
            buffer = allocator.intBuffer(calculateElementCapacity(required));
        }
        buffer.clear();
        if (count > 0) {
            buffer.put(values, 0, count);
        }
        buffer.flip();
        return buffer;
    }

    private ByteBuffer prepareByteBuffer(ByteBuffer buffer, byte[] values) {
        int required = Math.max(values.length, 1);
        if (buffer == null || buffer.capacity() < required) {
            buffer = allocator.byteBuffer(calculateElementCapacity(required));
        }
        buffer.clear();
        buffer.put(values, 0, values.length);
        buffer.flip();
        return buffer;
    }

    private static int calculateElementCapacity(int required) {
        int capacity = 4096;
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }
            capacity *= 2;
        }
        return capacity;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 1.0F;
        }
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }

    /** 无语义流时的填充缓冲（惰性、可复用）。 */
    private ByteBuffer undefinedAuxStaging;
}
