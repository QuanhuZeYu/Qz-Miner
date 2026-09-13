package club.heiqi.qz_miner.chain.client.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
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

    /** GLSL 侧生长过渡半宽（归一化序号单位）：约 1/32 代宽，避免硬切。 */
    private static final float ANIMATION_SPAN_NORMALIZED = 1.0F / 32.0F;

    private static final int INITIAL_CAPACITY = 16 * 1024;

    private final ChainPreviewShaderProgram program;

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

    /** 当前拓扑的 meshOrigin（由 uploadTopology 缓存，plan 的 origin 与此恒等）。 */
    private float originX;
    private float originY;
    private float originZ;

    /** 同代最大出现序号（扫描 aAux 得到）；0 表示无 aAux 或无归属序号。 */
    private float appearSpan;

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

    @Override
    public boolean ensureReady() {
        if (initialized) {
            return true;
        }
        if (unavailable) {
            return false;
        }
        try {
            if (!program.ensureReady()) {
                unavailable = true;
                failureReason = program.getLastFailureMessage().isEmpty()
                        ? "着色器程序不可用" : program.getLastFailureMessage();
                return false;
            }
            initializeGl();
            initialized = true;
            failureReason = "";
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
            appearSpan = 0.0F;
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

        indexCount = mesh.getIndexCount();
        vertexCount = vertexFloatCount / 3;
        originX = (float) mesh.getOriginX();
        originY = (float) mesh.getOriginY();
        originZ = (float) mesh.getOriginZ();
        appearSpan = maxAppearOrder(aux, vertexCount);

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int requiredVboSize = vertexFloatCount * 4;
        if (requiredVboSize > vboCapacity) {
            vboCapacity = calculateNewCapacity(requiredVboSize);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        vertexStaging = prepareFloatBuffer(vertexStaging, vertices, vertexFloatCount);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertexStaging);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
        int requiredCboSize = colorFloatCount * 4;
        if (requiredCboSize > cboCapacity) {
            cboCapacity = calculateNewCapacity(requiredCboSize);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        colorStaging = prepareFloatBuffer(colorStaging, colors, colorFloatCount);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, colorStaging);

        uploadAux(aux, vertexCount);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        int requiredEboSize = indexCount * 4;
        if (requiredEboSize > eboCapacity) {
            eboCapacity = calculateNewCapacity(requiredEboSize);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        indexStaging = prepareIntBuffer(indexStaging, indices, indexCount);
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0, indexStaging);

        GL30.glBindVertexArray(0);
    }

    /** shader 路径不消费 CPU 颜色流：颜色在 GPU 侧由 aAux + uniform 调色板产生。 */
    @Override
    public boolean uploadColors(ChainPreviewMesh mesh) {
        return false;
    }

    @Override
    public void draw(ChainPreviewDrawPlan plan) {
        if (!initialized || indexCount <= 0 || plan == null) {
            return;
        }
        int visibleIndexCount = plan.getVisibleIndexCount();
        if (visibleIndexCount <= 0) {
            return;
        }
        int indexOffset = Math.max(0, plan.getIndexOffset());
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
            applyUniforms(plan);

            GL30.glBindVertexArray(vao);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            GL11.glDrawElements(
                GL11.GL_QUADS,
                visibleIndexCount,
                GL11.GL_UNSIGNED_INT,
                (long) indexOffset * 4L);
            // attrib 的 enable 状态属于 VAO：必须在自绑 VAO 还绑定时成对关闭，
            // 否则关掉的是外部默认 VAO 的 attrib 数组（D1）。
            GL20.glDisableVertexAttribArray(2);
            GL20.glDisableVertexAttribArray(1);
            GL20.glDisableVertexAttribArray(0);
            GL30.glBindVertexArray(0);
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
        appearSpan = 0.0F;
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
            .append(", ").append(program.describePixelScaleCache())
            .append('}');
        if (!failureReason.isEmpty()) {
            text.append(" failure=").append(failureReason);
        }
        if (program.isUnavailable() && !program.getLastFailureMessage().isEmpty()) {
            text.append(" shaderFailure=").append(program.getLastFailureMessage());
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 逐帧 uniform：距离淡出曲线（与 CPU 端 alphaFor 同形）、最小宽度、生长与语义调色板。
     *
     * <p>相机相对原点在 CPU 侧用 double 相减，避免大坐标在 GPU 端相减丢精度。</p>
     */
    private void applyUniforms(ChainPreviewDrawPlan plan) {
        double originRelativeX = (double) originX - RenderManager.renderPosX;
        double originRelativeY = (double) originY - RenderManager.renderPosY;
        double originRelativeZ = (double) originZ - RenderManager.renderPosZ;
        program.setOriginRel((float) originRelativeX, (float) originRelativeY, (float) originRelativeZ);

        program.setFadeCurve(
            plan.getFadeStartRadius(),
            plan.getFadeEndRadius(),
            plan.getAlphaEnd(),
            plan.getAlphaStart());
        program.setMinScreenWidthPx(plan.getMinScreenWidthPx());
        program.setBarThickness(plan.getBarThickness());
        program.setPixelScale(program.readPixelScale());

        float animationU = plan.getAnimationU();
        if (animationU >= ChainPreviewDrawPlan.ANIMATION_COMPLETE || appearSpan <= 0.0F) {
            // 本轮 plan 恒 animationU=1：整段可见，shader 不读 appearOrder（B3.x 只改取值）。
            program.setAnimation(1.0F, 0.0F, 0.0F);
        } else {
            program.setAnimation(clamp01(animationU), appearSpan, ANIMATION_SPAN_NORMALIZED);
        }

        // builtin 档：四类同传精确基线常量，逐位等于 legacy 颜色流。
        // 语义类别本轮恒为 255（未接线），片元选择器落到 uColorPrimary 兜底；
        // B2.3 接线语义类别时，四色改由 config 提供（本方法的着色点不变）。
        float red = BUILTIN_COLOR_RED;
        float green = BUILTIN_COLOR_GREEN;
        float blue = BUILTIN_COLOR_BLUE;
        program.setSemanticColor(0, red, green, blue);
        program.setSemanticColor(1, red, green, blue);
        program.setSemanticColor(2, red, green, blue);
        program.setSemanticColor(3, red, green, blue);
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
        if (requiredAboSize > aboCapacity) {
            aboCapacity = calculateNewCapacity(requiredAboSize);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, aboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
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
        if (aux == null) {
            return 0.0F;
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
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);

        // 接口冻结 §A：aAux = 4 x uint8 normalized（semanticClass / tubeEdge / appearOrder u16 LE）
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, abo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, aboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, 0, 0);
        GL20.glEnableVertexAttribArray(1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(2);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);

        GL30.glBindVertexArray(0);
    }

    private void releaseGl() {
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
