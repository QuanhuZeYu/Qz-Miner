package club.heiqi.qz_miner.chain.client.render;

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

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean usesCpuColors() {
        return true;
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
            return false;
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
    }

    @Override
    public void dispose() {
        indexCount = 0;
        int deletedVao = vao;
        int deletedVbo = vbo;
        int deletedCbo = cbo;
        int deletedEbo = ebo;
        boolean release = initialized
            || deletedVao != 0
            || deletedVbo != 0
            || deletedCbo != 0
            || deletedEbo != 0;
        vao = 0;
        vbo = 0;
        cbo = 0;
        ebo = 0;
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

        ChainPreviewGlBindings previous = ChainPreviewGlBindings.capture();
        try {
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glDeleteVertexArrays(deletedVao);
            GL15.glDeleteBuffers(deletedVbo);
            GL15.glDeleteBuffers(deletedCbo);
            GL15.glDeleteBuffers(deletedEbo);
        } finally {
            previous.withoutDeleted(deletedVao, deletedVbo, deletedCbo, deletedEbo).restore();
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
        return text.toString();
    }

    /** @return 当前已上传的索引数量（诊断用） */
    public int getIndexCount() {
        return indexCount;
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
