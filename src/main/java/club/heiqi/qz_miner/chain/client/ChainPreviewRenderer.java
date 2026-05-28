package club.heiqi.qz_miner.chain.client;

import java.util.List;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

/**
 * 连锁目标预览渲染器。
 */
@SideOnly(Side.CLIENT)
public class ChainPreviewRenderer {

    private final ChainPreviewMeshBuilder meshBuilder = new ChainPreviewMeshBuilder();
    private final ChainPreviewMeshCache meshCache = new ChainPreviewMeshCache();

    private int lastGeneration = -1;
    private int lastRenderRevision = -1;

    /**
     * 注册客户端世界渲染事件。
     */
    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 在客户端生命周期结束时释放预览网格缓存。
     */
    public void disposeForLifecycle() {
        meshCache.dispose();
        lastGeneration = -1;
        lastRenderRevision = -1;
    }

    /**
     * 在世界最后渲染阶段绘制基础线框预览。
     *
     * @param event 世界渲染事件
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null) {
            clearMesh();
            return;
        }
        if (!Config.clientEnablePreviewRender) {
            clearMesh();
            return;
        }
        if (ClientProxy.chainPreviewController == null || MyMod.chainStateService == null) {
            clearMesh();
            return;
        }

        ChainPreviewState previewState = ClientProxy.chainPreviewController.getPreviewState();
        if (previewState == null || !previewState.isActive()) {
            clearMesh();
            return;
        }

        rebuildMeshIfNeeded(previewState);
        if (meshCache.getIndexCount() <= 0) {
            return;
        }

        renderMesh();
    }

    /**
     * 在预览快照变化时重建网格并上传到缓存。
     *
     * @param previewState 当前预览状态
     */
    private void rebuildMeshIfNeeded(ChainPreviewState previewState) {
        int currentGeneration = previewState.getGeneration();
        int currentRenderRevision = previewState.getRenderRevision();
        if (currentGeneration == lastGeneration && currentRenderRevision == lastRenderRevision) {
            return;
        }

        List<ChainTarget> previewTargets = previewState.getPreviewTargetsSnapshot();
        ChainPreviewMesh mesh = meshBuilder.build(
            previewTargets,
            RenderManager.renderPosX,
            RenderManager.renderPosY,
            RenderManager.renderPosZ);
        meshCache.upload(mesh);
        lastGeneration = currentGeneration;
        lastRenderRevision = currentRenderRevision;
    }

    /**
     * 执行基础线框绘制。
     */
    private void renderMesh() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glLineWidth(2.0F);
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);

        meshCache.render();

        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    /**
     * 清理当前缓存的预览网格。
     */
    private void clearMesh() {
        meshCache.clear();
        lastGeneration = -1;
        lastRenderRevision = -1;
    }
}
