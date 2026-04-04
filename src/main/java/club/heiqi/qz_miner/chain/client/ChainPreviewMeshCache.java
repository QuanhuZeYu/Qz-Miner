package club.heiqi.qz_miner.chain.client;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 预览线框 GPU 缓存。
 */
public class ChainPreviewMeshCache {

    private static final int INITIAL_CAPACITY = 16 * 1024;

    private int vao;
    private int vbo;
    private int ebo;
    private int vboCapacity;
    private int eboCapacity;
    private int indexCount;
    private boolean initialized;

    /**
     * 初始化 GPU 缓冲。
     */
    public void ensureInitialized() {
        if (initialized) {
            return;
        }

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
        vboCapacity = INITIAL_CAPACITY;
        eboCapacity = INITIAL_CAPACITY;

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        initialized = true;
    }

    /**
     * 上传新的网格数据。
     *
     * @param mesh 预览网格
     */
    public void upload(ChainPreviewMesh mesh) {
        ensureInitialized();
        if (mesh == null || mesh.isEmpty()) {
            indexCount = 0;
            return;
        }

        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        indexCount = indices.length;

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int requiredVboSize = vertices.length * 4;
        if (requiredVboSize > vboCapacity) {
            vboCapacity = calculateNewCapacity(requiredVboSize);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertexBuffer);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        int requiredEboSize = indices.length * 4;
        if (requiredEboSize > eboCapacity) {
            eboCapacity = calculateNewCapacity(requiredEboSize);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0, indexBuffer);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * 渲染当前缓存中的线框。
     */
    public void render() {
        if (!initialized || indexCount <= 0) {
            return;
        }

        GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(0);
        GL11.glDrawElements(GL11.GL_LINES, indexCount, GL11.GL_UNSIGNED_INT, 0);
        GL20.glDisableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    /**
     * 释放 GPU 资源。
     */
    public void dispose() {
        indexCount = 0;
        if (!initialized) {
            return;
        }

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL15.glDeleteBuffers(ebo);
        vao = 0;
        vbo = 0;
        ebo = 0;
        vboCapacity = 0;
        eboCapacity = 0;
        initialized = false;
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
            newCapacity *= 2;
        }
        return newCapacity;
    }
}
