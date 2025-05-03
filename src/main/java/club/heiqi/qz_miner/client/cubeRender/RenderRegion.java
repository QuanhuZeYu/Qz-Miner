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

    FloatBuffer verticesBuffer = BufferUtils.createFloatBuffer(Integer.MAX_VALUE/10);
    IntBuffer indexesBuffer = BufferUtils.createIntBuffer(Integer.MAX_VALUE/10);


    public void render(float[] vertices, int[] indexes) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        verticesBuffer.clear();
        verticesBuffer.put(vertices).flip();
        indexesBuffer.clear();
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
