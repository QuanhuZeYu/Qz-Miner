package club.heiqi.qz_miner.chain.client.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 固定管线（legacy）预览后端：从 ChainPreviewMeshCache 提取的 GL 缓冲实现。
 *
 * <p>GL 调用序列与历史 ChainPreviewMeshCache 逐条等价：VAO + attribute 0 位置流 +
 * client state 颜色流（glColorPointer）+ GL_QUADS 索引绘制；颜色语义保持 CPU 端
 * builtin 常量色 + 距离 α。</p>
 *
 * <p>绑定围栏：帧内 upload / draw 由调用方帧级围栏统一捕获 / 恢复（0 次 glGetInteger）；
 * 只有帧外入口 {@link #dispose()} 自带一次捕获。</p>
 *
 * <p>能力要求（T8-D9 登记）：本后端与历史 ChainPreviewMeshCache 同源，使用 VAO（GL30）与
 * glVertexAttribPointer（GL20）。无 GL20 / GL30 时 {@link #ensureReady()} 返回 false 并记录
 * failure（见 {@link #describe()}），预览整体不可用；真正的无 VAO 固定管线回退路径属下一批
 * （B4.3）评估，本轮不做。</p>
 *
 * <p>淡入淡出（T14/B3.2）：legacy 的逐顶点 α 是 CPU 烘焙值，plan 的 {@code fadeAlpha}
 * 乘子无法通过 uniform 施加；因此 fadeAlpha &lt; 1 时启用「1×1 白纹理 + GL_MODULATE」把乘子
 * 精确乘进逐顶点 α（fadeAlpha == 1 时零额外 GL 调用，逐字等于历史行为）。纹理在渲染线程
 * 惰性创建、dispose 释放；创建失败降级为「无过渡、retiring 只延迟消失」并写入 describe()，
 * 不每帧重试、不抛异常。</p>
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
     * fadeAlpha == 1（默认档 / 动画结束）零额外 GL 调用，逐字等于历史行为。
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

        boolean fadeRequested = shouldApplyFadeModulation(plan.getFadeAlpha());
        boolean textureStateCaptured = false;
        boolean previousTextureEnabled = false;
        int previousTextureBinding = 0;
        if (fadeRequested) {
            try {
                previousTextureEnabled = GL11.glGetBoolean(GL11.GL_TEXTURE_2D);
                previousTextureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                textureStateCaptured = true;
            } catch (Throwable captureFailure) {
                // 无法安全读取纹理状态：降级为无过渡，不冒险改状态
                fadeRequested = false;
            }
        }
        try {
            if (fadeRequested) {
                try {
                    applyFadeModulation(plan.getFadeAlpha());
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
            if (textureStateCaptured) {
                // T18-L1：GL_TEXTURE_BINDING_2D 不受 glPushAttrib 覆盖，必须显式恢复到进入前状态；
                // enable 状态一并恢复，异常路径同样执行（finally）。
                try {
                    restoreTextureState(previousTextureEnabled, previousTextureBinding);
                } catch (Throwable ignored) {
                    // 上下文失效：不得逃逸渲染帧
                }
            }
        }
    }

    private static void restoreTextureState(boolean enabled, int binding) {
        if (enabled) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        } else {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
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

        ChainPreviewGlBindings previous;
        try {
            previous = ChainPreviewGlBindings.capture();
        } catch (Throwable failure) {
            previous = null;
        }
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
            if (previous != null) {
                try {
                    previous.withoutDeleted(deletedVao, deletedVbo, deletedCbo, deletedEbo).restore();
                } catch (Throwable ignored) {
                    // 恢复失败同上：句柄已清零，交由 lifecycle 兜底
                }
            }
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
     * 启用帧内淡出乘子：1×1 白纹理 + GL_MODULATE 把 fadeAlpha 精确乘进逐顶点 α。
     * 失败（驱动 / 上下文异常）时降级为无过渡并记录原因，不每帧重试、不抛异常；
     * 纹理绑定 / TEXTURE_ENV / enable 状态由调用方帧级围栏恢复。
     */
    private void applyFadeModulation(float fadeAlpha) {
        if (fadeTextureUnavailable || !ensureFadeTexture()) {
            return;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fadeTexture);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        uploadFadeTexel(fadeAlpha);
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
