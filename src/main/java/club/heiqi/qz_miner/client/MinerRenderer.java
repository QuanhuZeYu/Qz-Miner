package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.BasePositionFounder;
import club.heiqi.qz_miner.shaderTools.ShaderManager;
import club.heiqi.qz_miner.utils.FileReadUtils;
import club.heiqi.qz_miner.utils.MatrixUtils;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class MinerRenderer {
    public Logger LOG = LogManager.getLogger();
    public boolean inPressChainKey = false;

    public ShaderManager minerShaderManager = new ShaderManager();
    public boolean shaderLoaded = false;


    @SubscribeEvent
    public void onBlockHighLight(DrawBlockHighlightEvent event) {
        initShader();
        // 如果没有按下连锁键，不执行逻辑
        if (!inPressChainKey) {
            onNotPressChainKey();
            return;
        }

        previewRender(event);
    }

    public void onNotPressChainKey() {
        spaceCalculator =  new SpaceCalculator();
        tool.updateData(SpaceCalculator.vertex, SpaceCalculator.index); // 重置为默认数据

        if (positionFounder != null) {
            positionFounder.interrupt(); // 停止搜索
            positionFounder = null;
            positions.clear();
        }
    }

    public SpaceCalculator spaceCalculator = new SpaceCalculator();
    public void previewRender(DrawBlockHighlightEvent event) {
        float partialTicks = event.partialTicks;

        collectBlock(event.target.blockX, event.target.blockY, event.target.blockZ);

        minerShaderManager.bind();

        Matrix4f modelMat = MatrixUtils.getModelMatrix(0,0,0);
        Matrix4f viewMat = MatrixUtils.getViewMatrix(partialTicks);
        Matrix4f projectionMat = MatrixUtils.getProjectionMatrix();

        minerShaderManager.setUniformM4f("model", modelMat);
        minerShaderManager.setUniformM4f("view", viewMat);
        minerShaderManager.setUniformM4f("projection", projectionMat);

        tool.render();

        minerShaderManager.unbind();
    }

    public BasePositionFounder positionFounder;
    public LinkedBlockingQueue<Vector3i> positions = new LinkedBlockingQueue<>();
    public void collectBlock(int x, int y, int z) {
        if (positionFounder == null) {
            positionFounder = new BasePositionFounder(new Vector3i(x, y, z), positions, Minecraft.getMinecraft().thePlayer, 10, 1000);
            MyMod.parallelTick.addNormalTask(positionFounder);
        }
        for (Vector3i pos : positions) {
            spaceCalculator.add(pos);
        }

        SpaceCalculator.VertexAndIndex vertexAndIndex = spaceCalculator.getVertexAndIndex();
        tool.updateData(vertexAndIndex.vertices, vertexAndIndex.indices);
    }











    public RenderTool tool;
    public void initShader() {
        if (shaderLoaded) {
            return;
        }

        String vertexSource = FileReadUtils.readText("assets/qz_miner/shader/MinerPreviewVertex.glsl");
        String fragmentSource = FileReadUtils.readText("assets/qz_miner/shader/MinerPreviewFragment.glsl");

        minerShaderManager.loadShader(vertexSource, fragmentSource);
        shaderLoaded = true;

        if (tool == null) {
            tool = new RenderTool();
        }
    }

    public void registry() {
        // FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void unRegistry() {
        // FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
    }




    public static class RenderTool {
        public int vao;
        public int vbo;
        public int ebo;
        // 默认使用完整方块顶点数据
        public float[] vertices = {
                //前左下 0   右下 1   右上 2   左上 3
                0,0,0,  1,0,0,  1,1,0,  0,1,0,
                //后左下 4   右下 5   右上 6   左上 7
                0,0,1,  1,0,1,  1,1,1,  0,1,1,
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
                3,2, 2,1, 1,0, 0,3, // 前面
                4,5, 5,6, 6,7, 7,4, // 后面
                2,6, 3,7, // 上连接
                0,4, 1,5, // 下连接
        };

        public RenderTool() {
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
}
