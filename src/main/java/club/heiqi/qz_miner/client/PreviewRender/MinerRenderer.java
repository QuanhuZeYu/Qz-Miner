package club.heiqi.qz_miner.client.PreviewRender;

import club.heiqi.qz_miner.core.BaseChainViewer;
import club.heiqi.qz_miner.utils.MatrixUtils;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

public class MinerRenderer {
    public Logger LOG = LogManager.getLogger();
    public boolean inPressChainKey = false;

    public BaseChainViewer viewer;


    public Vector3i lastTarget = new Vector3i(Integer.MIN_VALUE);
    @SubscribeEvent
    public void onBlockHighLight(DrawBlockHighlightEvent event) {
        renderAxis();
        // 如果没有按下连锁键，不执行逻辑
        if (!inPressChainKey) {
            onNotPressChainKey();
            return;
        }

        // 检查看向的目标是否变更
        Vector3i target = new Vector3i(event.target.blockX, event.target.blockY, event.target.blockZ);
        if (!lastTarget.equals(target)) {
            lastTarget = target;
            onPressButChangeTarget();
        }
        previewRender();
    }

    private void previewRender() {
        if (viewer == null) {
            viewer = new BaseChainViewer(lastTarget);
        }
    }

    private void onPressButChangeTarget() {
        if (viewer != null) {
            viewer.unRegistry();

            viewer = new BaseChainViewer(lastTarget);
        }
    }

    private void onNotPressChainKey() {
        if (viewer != null) {
            viewer.inPressChainKey = false;
            viewer.unRegistry();

            viewer = null;
        }
    }


    public float[][] axisVertex = {
    //      0          1          2          3
            {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}
    };
    public void renderAxis() {

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        // 保存当前矩阵模式并切换到模型视图
        int prevMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        Matrix4f modelView = MatrixUtils.getModelViewByOriginal();
        FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
        floatBuffer.put(modelView.get(new float[16])).flip();
        GL11.glLoadMatrix(floatBuffer);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        GL11.glBegin(GL11.GL_LINES);

        GL11.glColor3f(1, 0, 0); // X轴红色
        GL11.glVertex3f(axisVertex[0][0], axisVertex[0][1], axisVertex[0][2]);
        GL11.glVertex3f(axisVertex[1][0], axisVertex[1][1], axisVertex[1][2]);

        GL11.glColor3f(0, 1, 0); // Y轴绿色
        GL11.glVertex3f(axisVertex[0][0], axisVertex[0][1], axisVertex[0][2]);
        GL11.glVertex3f(axisVertex[2][0], axisVertex[2][1], axisVertex[2][2]);

        GL11.glColor3f(0, 0, 1); // Z轴蓝色
        GL11.glVertex3f(axisVertex[0][0], axisVertex[0][1], axisVertex[0][2]);
        GL11.glVertex3f(axisVertex[3][0], axisVertex[3][1], axisVertex[3][2]);

        GL11.glEnd();

        GL11.glPopMatrix();
        GL11.glMatrixMode(prevMatrixMode); // 显式恢复矩阵模式
        GL11.glPopAttrib();
    }


    public void registry() {
        // FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void unRegistry() {
        // FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
    }


}
