package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
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

    private final ChainPreviewRenderCache renderCache;
    private final ChainPreviewMeshCache meshCache;
    private int uploadedGeneration = -1;
    private long uploadedStateRevision = -1L;
    private int uploadedOriginX;
    private int uploadedOriginY;
    private int uploadedOriginZ;

    public ChainPreviewRenderer(ChainPreviewState previewState) {
        this(ChainPreviewRenderCache.createProduction(previewState), new ChainPreviewMeshCache());
    }

    ChainPreviewRenderer(ChainPreviewRenderCache renderCache, ChainPreviewMeshCache meshCache) {
        this.renderCache = renderCache;
        this.meshCache = meshCache;
    }

    /**
     * 注册客户端世界渲染事件。
     */
    public void register() {
        renderCache.observeState();
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 在客户端生命周期结束时释放预览网格缓存。
     */
    public void disposeForLifecycle() {
        renderCache.resetForLifecycle();
        meshCache.dispose();
        uploadedGeneration = -1;
        uploadedStateRevision = -1L;
        uploadedOriginX = 0;
        uploadedOriginY = 0;
        uploadedOriginZ = 0;
    }

    /**
     * 在世界最后渲染阶段发布已完成的 CPU cache 并绘制 quad 条柱。
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

        if (!renderCache.isPreviewActive()) {
            clearMesh();
            return;
        }

        renderCache.refreshForCamera(
            RenderManager.renderPosX,
            RenderManager.renderPosY,
            RenderManager.renderPosZ,
            System.nanoTime());
        publishCompletedMesh();
        if (meshCache.getIndexCount() <= 0) {
            return;
        }

        renderMesh();
    }

    private void publishCompletedMesh() {
        ChainPreviewRenderCache.MeshPublication publication = renderCache.pollPublication();
        if (publication != null) {
            ChainPreviewMesh mesh = publication.getMesh();
            boolean colorOnly = !mesh.isEmpty()
                && publication.getGeneration() == uploadedGeneration
                && publication.getStateRevision() == uploadedStateRevision;
            if (!colorOnly || !meshCache.uploadColors(mesh)) {
                meshCache.upload(mesh);
            }
            uploadedGeneration = publication.getGeneration();
            uploadedStateRevision = publication.getStateRevision();
            uploadedOriginX = mesh.getOriginX();
            uploadedOriginY = mesh.getOriginY();
            uploadedOriginZ = mesh.getOriginZ();
        }
    }

    /**
     * 执行条柱绘制，并完整恢复 matrix/client/attribute 状态。
     */
    private void renderMesh() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            try {
                int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                try {
                    GL11.glDisable(GL11.GL_TEXTURE_2D);
                    GL11.glDisable(GL11.GL_LIGHTING);
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glFrontFace(GL11.GL_CCW);
                    GL11.glCullFace(GL11.GL_BACK);
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDisable(GL11.GL_FOG);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL11.glShadeModel(GL11.GL_SMOOTH);
                    GL11.glDepthMask(false);
                    GL11.glTranslated(
                        uploadedOriginX - RenderManager.renderPosX,
                        uploadedOriginY - RenderManager.renderPosY,
                        uploadedOriginZ - RenderManager.renderPosZ);
                    meshCache.render();
                } finally {
                    GL11.glPopMatrix();
                    GL11.glMatrixMode(previousMatrixMode);
                }
            } finally {
                GL11.glPopClientAttrib();
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * 清理当前缓存的预览网格。
     */
    private void clearMesh() {
        meshCache.clear();
        uploadedGeneration = -1;
        uploadedStateRevision = -1L;
        uploadedOriginX = 0;
        uploadedOriginY = 0;
        uploadedOriginZ = 0;
    }
}
