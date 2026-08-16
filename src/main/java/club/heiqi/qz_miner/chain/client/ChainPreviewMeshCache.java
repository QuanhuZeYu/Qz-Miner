package club.heiqi.qz_miner.chain.client;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 预览 quad 条柱 GPU 缓存。
 */
public class ChainPreviewMeshCache {

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
    private FloatBuffer vertexStaging;
    private FloatBuffer colorStaging;
    private IntBuffer indexStaging;

    /**
     * 初始化 GPU 缓冲。
     */
    public void ensureInitialized() {
        if (initialized) {
            return;
        }

        BindingState previous = captureBindings();
        try {
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
            initialized = true;
        } finally {
            restoreBindings(previous);
        }
    }

    /**
     * 上传新的网格数据。
     *
     * @param mesh 预览网格
     */
    public void upload(ChainPreviewMesh mesh) {
        if (mesh == null || mesh.isEmpty()) {
            indexCount = 0;
            return;
        }
        ensureInitialized();

        float[] vertices = mesh.vertexArray();
        float[] colors = mesh.colorArray();
        int[] indices = mesh.indexArray();
        int vertexFloatCount = mesh.getVertexFloatCount();
        int colorFloatCount = mesh.getColorFloatCount();
        indexCount = mesh.getIndexCount();

        BindingState previous = captureBindings();
        try {
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
        } finally {
            restoreBindings(previous);
        }
    }

    /** 相同 topology revision 的距离效果刷新只更新 color stream。 */
    public boolean uploadColors(ChainPreviewMesh mesh) {
        if (!initialized || indexCount <= 0 || mesh == null || mesh.isEmpty()
                || mesh.getIndexCount() != indexCount) {
            return false;
        }

        float[] colors = mesh.colorArray();
        int colorFloatCount = mesh.getColorFloatCount();
        BindingState previous = captureBindings();
        try {
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
        } finally {
            restoreBindings(previous);
        }
    }

    /**
     * 渲染当前缓存中的 quad 条柱。
     */
    public void render() {
        if (!initialized || indexCount <= 0) {
            return;
        }

        BindingState previous = captureBindings();
        try {
            GL30.glBindVertexArray(vao);
            GL20.glEnableVertexAttribArray(0);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cbo);
            GL11.glColorPointer(4, GL11.GL_FLOAT, 0, 0);
            GL11.glDrawElements(GL11.GL_QUADS, indexCount, GL11.GL_UNSIGNED_INT, 0);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL20.glDisableVertexAttribArray(0);
        } finally {
            restoreBindings(previous);
        }
    }

    /**
     * 清空当前缓存的索引数量，但保留已分配的 GPU 缓冲。
     */
    public void clear() {
        indexCount = 0;
    }

    /**
     * 释放 GPU 资源。
     */
    public void dispose() {
        indexCount = 0;
        if (!initialized) {
            return;
        }

        BindingState previous = captureBindings();
        int deletedVao = vao;
        int deletedVbo = vbo;
        int deletedCbo = cbo;
        int deletedEbo = ebo;
        try {
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL30.glDeleteVertexArrays(deletedVao);
            GL15.glDeleteBuffers(deletedVbo);
            GL15.glDeleteBuffers(deletedCbo);
            GL15.glDeleteBuffers(deletedEbo);
        } finally {
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
            restoreBindings(previous.without(deletedVao, deletedVbo, deletedCbo, deletedEbo));
        }
    }

    /**
     * 获取当前索引数量。
     *
     * @return 索引数量
     */
    public int getIndexCount() {
        return indexCount;
    }

    /**
     * 计算缓冲区扩容后的容量。
     *
     * @param requiredSize 最小所需容量
     * @return 扩容后的容量
     */
    private int calculateNewCapacity(int requiredSize) {
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

    private static BindingState captureBindings() {
        return new BindingState(
            GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
            GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
    }

    private static void restoreBindings(BindingState bindings) {
        GL30.glBindVertexArray(bindings.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bindings.arrayBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, bindings.elementArrayBuffer);
    }

    private static final class BindingState {

        private final int vao;
        private final int arrayBuffer;
        private final int elementArrayBuffer;

        private BindingState(int vao, int arrayBuffer, int elementArrayBuffer) {
            this.vao = vao;
            this.arrayBuffer = arrayBuffer;
            this.elementArrayBuffer = elementArrayBuffer;
        }

        private BindingState without(int deletedVao, int deletedVbo, int deletedCbo, int deletedEbo) {
            int safeArrayBuffer = arrayBuffer == deletedVbo || arrayBuffer == deletedCbo ? 0 : arrayBuffer;
            return new BindingState(
                vao == deletedVao ? 0 : vao,
                safeArrayBuffer,
                elementArrayBuffer == deletedEbo ? 0 : elementArrayBuffer);
        }
    }
}
