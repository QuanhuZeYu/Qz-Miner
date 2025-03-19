package club.heiqi.qz_miner.client.cubeRender;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

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
        glVertexPointer(3, GL_FLOAT, 0, 0);
        glEnableClientState(GL_VERTEX_ARRAY); // 启用顶点数组

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW); // 初始为空
        //解绑
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    FloatBuffer buffer = BufferUtils.createFloatBuffer(Integer.MAX_VALUE/10);
    IntBuffer buffer2 = BufferUtils.createIntBuffer(Integer.MAX_VALUE/10);


    public void render(float[] vertices, int[] indexes) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        buffer.clear();
        buffer.put(vertices).flip();
        buffer2.clear();
        buffer2.put(indexes).flip();
        // 动态更新VBO
        glBindVertexArray(vao);
        if (glGetBufferParameteri(GL_ARRAY_BUFFER, GL_BUFFER_SIZE) < vertices.length * Float.BYTES) {
            glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);
        } else {
            glBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
        }
        // 动态更新EBO
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        if (glGetBufferParameteri(GL_ELEMENT_ARRAY_BUFFER, GL_BUFFER_SIZE) < indexes.length * Integer.BYTES) {
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, buffer2, GL_DYNAMIC_DRAW);
        } else {
            glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, buffer2);
        }

        glDrawElements(GL_LINES, indexes.length, GL_UNSIGNED_INT, 0);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
}
