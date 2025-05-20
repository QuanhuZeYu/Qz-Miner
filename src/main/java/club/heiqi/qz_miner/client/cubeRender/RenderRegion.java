package club.heiqi.qz_miner.client.cubeRender;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/**
 * 用于单次drawCall所有渲染的立方体
 */
public class RenderRegion {
    public int vao, vbo, ebo;

    public RenderRegion() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        ebo = glGenBuffers();

        // 重新绑定vbo
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW); // 初始为空
        glVertexPointer(3, GL_FLOAT, 7 * Float.BYTES, 0); // 步长7个float，偏移0
        glEnableClientState(GL_VERTEX_ARRAY); // 启用顶点数组

        glColorPointer(4, GL_FLOAT, 7 * Float.BYTES, 3 * Float.BYTES); // 步长7个float，偏移3个float
        glEnableClientState(GL_COLOR_ARRAY);  // 颜色（RGBA）

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW); // 初始为空
        //解绑
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    FloatBuffer verticesBuffer = BufferUtils.createFloatBuffer(8192);
    long verticesDownSizeTime;
    IntBuffer indexesBuffer = BufferUtils.createIntBuffer(8192);
    long indexesDownSizeTime;

    public void render(float[] vertices, int[] indexes) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        verticesBuffer.clear();
        // 动态扩容
        if (vertices.length > verticesBuffer.capacity()) {
            verticesBuffer = BufferUtils.createFloatBuffer(vertices.length + Math.min(1024,vertices.length/2));
        }
        // 动态缩容 - 超过10s使用时间后才进行一次缩容
        else if (verticesBuffer.capacity() > Math.max(8192,vertices.length / 2) && System.currentTimeMillis() - verticesDownSizeTime >= 10_000) {
            verticesDownSizeTime = System.currentTimeMillis();
            // 最小调整到8192
            verticesBuffer = BufferUtils.createFloatBuffer(Math.max(vertices.length + Math.max(1024,vertices.length/2),8192));
        }
        verticesBuffer.put(vertices).flip();

        indexesBuffer.clear();
        // 动态扩容
        if (indexes.length > indexesBuffer.capacity()) {
            indexesBuffer = BufferUtils.createIntBuffer(indexes.length + Math.min(1024,indexes.length/2));
        }
        // 动态缩容
        else if (verticesBuffer.capacity() > Math.max(8192,vertices.length / 2) && System.currentTimeMillis() - indexesDownSizeTime >= 10_000) {
            indexesDownSizeTime = System.currentTimeMillis();
            // 最小调整到8192
            indexesBuffer = BufferUtils.createIntBuffer(Math.max(indexes.length + Math.min(1024,indexes.length/2),8192));
        }
        indexesBuffer.put(indexes).flip();

        // 动态更新VBO
        glBindVertexArray(vao);
        if (glGetBufferParameteri(GL_ARRAY_BUFFER, GL_BUFFER_SIZE) < vertices.length * Float.BYTES) {
            glBufferData(GL_ARRAY_BUFFER, verticesBuffer, GL_DYNAMIC_DRAW);
        } else {
            glBufferSubData(GL_ARRAY_BUFFER, 0, verticesBuffer);
        }
        // 动态更新EBO
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        if (glGetBufferParameteri(GL_ELEMENT_ARRAY_BUFFER, GL_BUFFER_SIZE) < indexes.length * Integer.BYTES) {
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexesBuffer, GL_DYNAMIC_DRAW);
        } else {
            glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, indexesBuffer);
        }

        // 启用混合（关键！）
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnableClientState(GL_COLOR_ARRAY);  // 颜色（RGBA）
        glEnableClientState(GL_VERTEX_ARRAY); // 启用顶点数组
        glDrawElements(GL_LINES, indexes.length, GL_UNSIGNED_INT, 0);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
}
