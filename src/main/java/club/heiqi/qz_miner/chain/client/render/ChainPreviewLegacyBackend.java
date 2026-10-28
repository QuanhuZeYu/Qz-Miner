package club.heiqi.qz_miner.chain.client.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 渲染线程持有 legacy 的 VAO 与位置、CPU 颜色、quad 索引缓冲，能力要求包含 GL20/GL30。
 * 帧内 upload/draw 复用 renderer 围栏，帧外 dispose 经统一绑定围栏释放资源。
 *
 * <p>距离透明阶段以白纹理 GL_MODULATE 施加淡出，近处不透明阶段以 COMBINE 替换烘焙 alpha。
 * 纹理惰性创建，失败锁存原因并保留距离透明显示；dispose 释放纹理和缓冲。</p>
 */
public final class ChainPreviewLegacyBackend implements ChainPreviewRenderBackend {

    public static final String ID = "legacy";

    private static final int INITIAL_CAPACITY = 16 * 1024;

    private int vao;
    private int vbo;
    private int cbo;
    private int ebo;
    private int vboCapacity;
    private int cboCapacity;
    private int eboCapacity;
    private int indexCount;
    private boolean initialized;
    private boolean initFailed;
    private String failureReason = "";
    private FloatBuffer vertexStaging;
    private FloatBuffer colorStaging;
    private IntBuffer indexStaging;
    private ByteBuffer fadeTexel;
    private int fadeTexture;
    private boolean fadeTextureHasImage;
    private boolean fadeTextureUnavailable;
    private String fadeTextureFailure = "";
    private final ChainPreviewGlFences.Access glAccess;

    /** 生产构造：使用 {@link ChainPreviewGlFences#LWJGL} 状态访问点。 */
    public ChainPreviewLegacyBackend() {
        this(ChainPreviewGlFences.LWJGL);
    }

    /**
     * 注入构造：GL 状态访问点由 {@link WorldOverlayBackend#glAccess()} 统一提供，
     * 使帧内纹理乘子围栏与 dispose 绑定围栏和 renderer 帧级围栏共用同一实现。
     *
     * @param glAccess GL 状态访问点，null 时兜底 LWJGL
     */
    public ChainPreviewLegacyBackend(ChainPreviewGlFences.Access glAccess) {
        this.glAccess = glAccess == null ? ChainPreviewGlFences.LWJGL : glAccess;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean usesCpuColors() {
        return true;
    }

    /**
     * 纯决策：只有 fadeAlpha &lt; 1 时才启用 1×1 白纹理 × GL_MODULATE 乘子路径；
     * 普通距离透明阶段 fadeAlpha == 1 时不启用纹理；可见不透明阶段另行请求 alpha 替换。
     *
     * @param fadeAlpha plan 的全局淡入淡出乘子
     * @return 是否启用纹理乘子
     */
    public static boolean shouldApplyFadeModulation(float fadeAlpha) {
        return fadeAlpha < 1.0F;
    }

    /** @return 淡入淡出乘子纹理是否不可用（失败后不重试，原因见 {@link #describe()}） */
    public boolean isFadeModulationUnavailable() {
        return fadeTextureUnavailable;
    }

    @Override
    public boolean ensureReady() {
        if (initialized) {
            return true;
        }
        if (initFailed) {
            return false;
        }
        try {
            initializeGl();
            initialized = true;
            failureReason = "";
            return true;
        } catch (Throwable failure) {
            initFailed = true;
            failureReason = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            releaseGlObjects();
            return false;
        }
    }

    /**
     * 初始化中途失败时的静默释放（T8-D7）：句柄先清零，再尽力删除；
     * 上下文失效时无法回收，交由 lifecycle dispose 兜底，不抛异常。
     */
    private void releaseGlObjects() {
        int handleVao = vao;
        int handleVbo = vbo;
        int handleCbo = cbo;
        int handleEbo = ebo;
        int handleFadeTexture = fadeTexture;
        vao = 0;
        vbo = 0;
        cbo = 0;
        ebo = 0;
        fadeTexture = 0;
        fadeTexel = null;
        fadeTextureHasImage = false;
        vboCapacity = 0;
        cboCapacity = 0;
        eboCapacity = 0;
        vertexStaging = null;
        colorStaging = null;
        indexStaging = null;
        initialized = false;
        try {
            if (handleVao != 0) {
                GL30.glDeleteVertexArrays(handleVao);
            }
            if (handleVbo != 0) {
                GL15.glDeleteBuffers(handleVbo);
            }
            if (handleCbo != 0) {
                GL15.glDeleteBuffers(handleCbo);
            }
            if (handleEbo != 0) {
                GL15.glDeleteBuffers(handleEbo);
            }
            if (handleFadeTexture != 0) {
                GL11.glDeleteTextures(handleFadeTexture);
            }
        } catch (Throwable ignored) {
            // 上下文失效：句柄已清零，不再重复删除
        }
    }

    @Override
    public void uploadTopology(ChainPreviewMesh mesh) {
        if (mesh == null || mesh.isEmpty()) {
            indexCount = 0;
            return;
        }
        if (!ensureReady()) {
            return;
        }

        float[] vertices = mesh.vertexArray();
        float[] colors = mesh.colorArray();
        int[] indices = mesh.indexArray();
        int vertexFloatCount = mesh.getVertexFloatCount();
        int colorFloatCount = mesh.getColorFloatCount();
        indexCount = mesh.getIndexCount();

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

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        int requiredEboSize = indexCount * 4;
        if (requiredEboSize > eboCapacity) {
            eboCapacity = calculateNewCapacity(requiredEboSize);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        indexStaging = prepareIntBuffer(indexStaging, indices, indexCount);
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0, indexStaging);

        // T48c-B：与 shader 后端对称，上传收尾解绑 VAO（帧围栏仍负责恢复进入前绑定）
        unbindVertexArrayQuietly(glAccess);
    }

    @Override
    public boolean uploadColors(ChainPreviewMesh mesh) {
        if (!initialized || indexCount <= 0 || mesh == null || mesh.isEmpty()
                || mesh.getIndexCount() != indexCount) {
            return false;
        }

        float[] colors = mesh.colorArray();
        int colorFloatCount = mesh.getColorFloatCount();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
        int requiredCboSize = colorFloatCount * 4;
        if (requiredCboSize > cboCapacity) {
            cboCapacity = calculateNewCapacity(requiredCboSize);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        colorStaging = prepareFloatBuffer(colorStaging, colors, colorFloatCount);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, colorStaging);
        unbindVertexArrayQuietly(glAccess);
        return true;
    }

    /**
     * 整体绘制当前拓扑；legacy 不消费 waveEnds（索引顺序 != appearOrder 顺序，
     * 逐波生长只在 shader 路径按 aAux 顶点序号实现），仅消费可见索引范围。
     */
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

        boolean fadeRequested = shouldApplyFadeModulation(plan.getFadeAlpha()) || !plan.isDistanceFadeEnabled();
        ChainPreviewGlFences.Texture textureFence = null;
        if (fadeRequested) {
            textureFence = ChainPreviewGlFences.Texture.captureIfRequested(true, glAccess);
            // 无法安全读取纹理状态：降级为无过渡，不冒险改状态
            fadeRequested = textureFence.isActive();
        }
        try {
            if (fadeRequested) {
                try {
                    applyAlphaModulation(plan);
                } catch (Throwable modulationFailure) {
                    fadeTextureUnavailable = true;
                    fadeTextureFailure = modulationFailure.getClass().getSimpleName()
                        + ": " + String.valueOf(modulationFailure.getMessage());
                }
            }
            GL30.glBindVertexArray(vao);
            GL20.glEnableVertexAttribArray(0);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
            GL11.glColorPointer(4, GL11.GL_FLOAT, 0, 0);
            GL11.glDrawElements(
                GL11.GL_QUADS,
                visibleIndexCount,
                GL11.GL_UNSIGNED_INT,
                (long) indexOffset * 4L);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL20.glDisableVertexAttribArray(0);
        } finally {
            if (textureFence != null) {
                // T18-L1：GL_TEXTURE_BINDING_2D 不受 glPushAttrib 覆盖，必须显式恢复到进入前状态；
                // enable 状态一并恢复，异常路径同样执行（finally）；配对逻辑见 ChainPreviewGlFences.Texture
                textureFence.close();
            }
        }
    }

    @Override
    public void dispose() {
        indexCount = 0;
        int deletedVao = vao;
        int deletedVbo = vbo;
        int deletedCbo = cbo;
        int deletedEbo = ebo;
        int deletedFadeTexture = fadeTexture;
        boolean release = initialized
            || deletedVao != 0
            || deletedVbo != 0
            || deletedCbo != 0
            || deletedEbo != 0
            || deletedFadeTexture != 0;
        vao = 0;
        vbo = 0;
        cbo = 0;
        ebo = 0;
        fadeTexture = 0;
        fadeTexel = null;
        fadeTextureHasImage = false;
        fadeTextureUnavailable = false;
        fadeTextureFailure = "";
        vboCapacity = 0;
        cboCapacity = 0;
        eboCapacity = 0;
        vertexStaging = null;
        colorStaging = null;
        indexStaging = null;
        initialized = false;
        initFailed = false;
        failureReason = "";
        if (!release) {
            return;
        }

        ChainPreviewGlBindings.Access bindingAccess = ChainPreviewGlFences.bindingsQuietly(glAccess);
        ChainPreviewGlBindings previous = ChainPreviewGlFences.captureBindingsQuietly(bindingAccess);
        try {
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glDeleteVertexArrays(deletedVao);
            GL15.glDeleteBuffers(deletedVbo);
            GL15.glDeleteBuffers(deletedCbo);
            GL15.glDeleteBuffers(deletedEbo);
            if (deletedFadeTexture != 0) {
                GL11.glDeleteTextures(deletedFadeTexture);
            }
        } catch (Throwable ignored) {
            // T8-D8：dispose 可能在帧围栏外调用（配置热切换），上下文失效不得逃逸渲染帧
        } finally {
            // 恢复失败静默：句柄已清零，交由 lifecycle 兜底
            ChainPreviewGlFences.restoreBindingsQuietly(
                previous == null
                    ? null
                    : previous.withoutDeleted(deletedVao, deletedVbo, deletedCbo, deletedEbo),
                bindingAccess);
        }
    }

    @Override
    public String describe() {
        StringBuilder text = new StringBuilder("legacy{vao=").append(vao)
            .append(", vbo=").append(vbo)
            .append(", cbo=").append(cbo)
            .append(", ebo=").append(ebo)
            .append(", indexCount=").append(indexCount)
            .append(", initialized=").append(initialized)
            .append('}');
        if (!failureReason.isEmpty()) {
            text.append(" failure=").append(failureReason);
        }
        if (fadeTextureUnavailable) {
            text.append(" fadeModulation=unavailable(").append(fadeTextureFailure).append(')');
        }
        return text.toString();
    }

    /** @return 当前已上传的索引数量（诊断用） */
    public int getIndexCount() {
        return indexCount;
    }

    /**
     * 复用淡出纹理施加阶段 alpha：普通段乘烘焙 alpha，可见不透明段替换烘焙 alpha。
     * 失败（驱动 / 上下文异常）时降级为无过渡并记录原因，不每帧重试、不抛异常；
     * 纹理绑定 / TEXTURE_ENV / enable 状态由调用方帧级围栏恢复。
     */
    private void applyAlphaModulation(ChainPreviewDrawPlan plan) {
        if (fadeTextureUnavailable || !ensureFadeTexture()) {
            return;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fadeTexture);
        uploadFadeTexel(plan.getFadeAlpha());
        if (plan.isDistanceFadeEnabled()) {
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        } else {
            applyVisibleAlphaEnvironment();
        }
    }

    /**
     * RGB 保留颜色流（含面明暗），alpha 只取动画包络纹理；不从已烘焙的距离 alpha 反推原值。
     * COMBINE 参数由调用方的 Frame 全状态围栏恢复，Texture 围栏另行恢复绑定与启用状态。
     */
    private static void applyVisibleAlphaEnvironment() {
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_PRIMARY_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_RGB_SCALE, 1);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL11.GL_TEXTURE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_ALPHA_SCALE, 1);
    }

    private boolean ensureFadeTexture() {
        if (fadeTexture != 0) {
            return true;
        }
        try {
            fadeTexel = BufferUtils.createByteBuffer(4);
            fadeTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fadeTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            return true;
        } catch (Throwable failure) {
            // T18-L3：失败窗口里已 gen 的纹理必须立刻删除，避免句柄泄漏
            if (fadeTexture != 0) {
                try {
                    GL11.glDeleteTextures(fadeTexture);
                } catch (Throwable ignored) {
                    // 上下文失效：句柄置 0 即可，交由 lifecycle 兜底
                }
            }
            fadeTextureUnavailable = true;
            fadeTextureFailure = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            fadeTexture = 0;
            fadeTexel = null;
            fadeTextureHasImage = false;
            return false;
        }
    }

    private void uploadFadeTexel(float fadeAlpha) {
        ByteBuffer texel = fadeTexel;
        if (texel == null) {
            return;
        }
        int alphaByte = Math.round(Math.max(0.0F, Math.min(1.0F, fadeAlpha)) * 255.0F);
        texel.clear();
        texel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) alphaByte);
        texel.flip();
        if (fadeTextureHasImage) {
            // T18-L2：首建用 TexImage2D 定义存储，之后逐帧只 TexSubImage2D 更新 1 像素
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texel);
        } else {
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texel);
            fadeTextureHasImage = true;
        }
    }

    private void initializeGl() {
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        cbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
        vboCapacity = INITIAL_CAPACITY;
        cboCapacity = INITIAL_CAPACITY;
        eboCapacity = INITIAL_CAPACITY;

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL11.glColorPointer(4, GL11.GL_FLOAT, 0, 0);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);
    }

    /**
     * T48c-B：上传收尾解绑 VAO（与 shader 后端的 glBindVertexArray(0) 对称）。
     *
     * <p>今日所有上传路径都在帧围栏内，解绑只是契约对齐；访问点不可用 / 上下文失效时静默，
     * 不改变上传语义。</p>
     *
     * @param access GL 状态访问点，可为 null
     */
    static void unbindVertexArrayQuietly(ChainPreviewGlFences.Access access) {
        ChainPreviewGlBindings.Access bindingAccess = ChainPreviewGlFences.bindingsQuietly(access);
        if (bindingAccess == null) {
            return;
        }
        try {
            bindingAccess.bindVertexArray(0);
        } catch (Throwable ignored) {
            // 上下文失效：解绑失败不得逃逸渲染帧
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

    private static FloatBuffer prepareFloatBuffer(FloatBuffer buffer, float[] values, int count) {
        if (buffer == null || buffer.capacity() < count) {
            buffer = BufferUtils.createFloatBuffer(calculateElementCapacity(count));
        }
        buffer.clear();
        buffer.put(values, 0, count);
        buffer.flip();
        return buffer;
    }

    private static IntBuffer prepareIntBuffer(IntBuffer buffer, int[] values, int count) {
        if (buffer == null || buffer.capacity() < count) {
            buffer = BufferUtils.createIntBuffer(calculateElementCapacity(count));
        }
        buffer.clear();
        buffer.put(values, 0, count);
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
}
