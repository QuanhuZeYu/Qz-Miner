package club.heiqi.qz_miner.client;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class RenderCache {
    public int vao;
    public int vbo;
    public int ebo;
    // 默认使用完整方块顶点数据
    public float[] vertices = {
            // 前左下 0   右下 1   右上 2   左上 3
            0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0,
            // 后左下 4   右下 5   右上 6   左上 7
            0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1,
    };
    public int[] indices = {
            // 三角形顺序
            // 2,1,0, 0,3,2,  // 前面
            // 4,5,6, 6,7,4,  // 后面
            // 0,4,7, 7,3,0,  // 左面
            // 1,2,6, 6,5,1,  // 右面
            // 2,3,7, 7,6,2,  // 上面
            // 0,4,5, 5,1,0,  // 下面
            // 正方形顺序
            // 3,2,1,0, 4,5,6,7,
            // 0,4,7,3, 1,2,6,5,
            // 2,3,7,6, 0,4,5,1,
            // 线顺序
            3, 2, 2, 1, 1, 0, 0, 3, // 前面
            4, 5, 5, 6, 6, 7, 7, 4, // 后面
            2, 6, 3, 7, // 上连接
            0, 4, 1, 5, // 下连接
    };

    public RenderCache() {
        // 初始化
        init();
    }

    public void init() {
        // 生成VAO和VBO
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();

        //  绑定VAO
        GL30.glBindVertexArray(vao);
        //  绑定VBO
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        //  将顶点数据复制到缓冲中
        FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(vertices.length);
        floatBuffer.put(vertices);
        floatBuffer.flip();
        //  将缓冲绑定到GL_ARRAY_BUFFER
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatBuffer, GL15.GL_STATIC_DRAW);

        //  设置顶点属性指针
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer intBuffer = BufferUtils.createIntBuffer(indices.length);
        intBuffer.put(indices);
        intBuffer.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, intBuffer, GL15.GL_STATIC_DRAW);

        //  解绑VAO和VBO EBO
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public void updateData(float[] vertices, int[] indices) {
        this.vertices = vertices;
        this.indices = indices;

        GL30.glBindVertexArray(vao);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(vertices.length);
        floatBuffer.put(vertices);
        floatBuffer.flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatBuffer, GL15.GL_STATIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer intBuffer = BufferUtils.createIntBuffer(indices.length);
        intBuffer.put(indices);
        intBuffer.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, intBuffer, GL15.GL_STATIC_DRAW);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public void render() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);

        GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(0);
        GL11.glDrawElements(GL11.GL_LINES, indices.length, GL11.GL_UNSIGNED_INT, 0);

        GL20.glDisableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }
}
