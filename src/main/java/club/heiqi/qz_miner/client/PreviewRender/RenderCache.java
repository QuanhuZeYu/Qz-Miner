package club.heiqi.qz_miner.client.PreviewRender;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class RenderCache {
    public static Logger LOG = LogManager.getLogger();

    public int vao;
    public int vbo;
    public int ebo;
    // 默认使用完整方块顶点数据
    public float[] vertices = SpaceCalculator.vertex;
    public int[] indices = SpaceCalculator.index;

    // 记录当前分配的缓冲区大小（字节）
    public int vboCapacity = 0;
    public int eboCapacity = 0;

    public RenderCache() {
        // 初始化
        init();
    }

    public void init() {
        // 生成VAO和VBO
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();

        // 初始分配10MB缓冲区
        final int INITIAL_CAPACITY = 10 * 1024 * 1024; // 10MB
        vboCapacity = INITIAL_CAPACITY;
        eboCapacity = INITIAL_CAPACITY;

        //  绑定VAO
        GL30.glBindVertexArray(vao);
        //  绑定VBO
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        //  将顶点数据复制到缓冲中
        FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(vertices.length);
        floatBuffer.put(vertices);
        floatBuffer.flip();
        //  将缓冲绑定到GL_ARRAY_BUFFER
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, floatBuffer);

        //  设置顶点属性指针
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);

        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer intBuffer = BufferUtils.createIntBuffer(indices.length);
        intBuffer.put(indices);
        intBuffer.flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0, intBuffer);

        //  解绑VAO和VBO EBO
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public void updateData(float[] vertices, int[] indices) {
        this.vertices = vertices;
        this.indices = indices;

        GL30.glBindVertexArray(vao);

        // 检查并扩容VBO
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int requiredVboSize = vertices.length * 4; // 每个float占4字节
        if (requiredVboSize > vboCapacity) {
            vboCapacity = calculateNewCapacity(requiredVboSize);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        // 绑定实际数据
        FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(vertices.length);
        floatBuffer.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, floatBuffer, GL15.GL_DYNAMIC_DRAW);

        // 检查并扩容EBO
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        int requiredEboSize = indices.length * 4; // 每个int占4字节
        if (requiredEboSize > eboCapacity) {
            eboCapacity = calculateNewCapacity(requiredEboSize);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, eboCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        // 绑定实际数据
        IntBuffer intBuffer = BufferUtils.createIntBuffer(indices.length);
        intBuffer.put(indices).flip();
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, intBuffer, GL15.GL_DYNAMIC_DRAW);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    public void render() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(0);
        GL11.glDrawElements(GL11.GL_LINES, indices.length, GL11.GL_UNSIGNED_INT, 0);
        GL20.glDisableVertexAttribArray(0);

        GL20.glDisableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);

        GL11.glPopAttrib();
    }

    // 计算新的缓冲区容量（当前需求的2倍）
    public int calculateNewCapacity(int requiredSize) {
        int newCapacity = Math.max(10 * 1024 * 1024, 16); // 最小16字节
        while (newCapacity < requiredSize) {
            newCapacity *= 2; // 每次容量翻倍
        }
        LOG.info("扩容渲染缓冲完毕，新容量: {}", newCapacity);
        return newCapacity;
    }
}
