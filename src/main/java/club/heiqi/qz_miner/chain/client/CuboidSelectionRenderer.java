package club.heiqi.qz_miner.chain.client;

import org.lwjgl.opengl.GL11;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.selection.CuboidBounds;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

/** 以固定 6 面和 12 边绘制服务端确认的常驻框选。 */
@SideOnly(Side.CLIENT)
public final class CuboidSelectionRenderer {

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.theWorld == null || minecraft.thePlayer == null
                || minecraft.theWorld.provider == null || !Config.clientEnablePreviewRender
                || MyMod.chainStateService == null || ClientProxy.clientCuboidSelectionState == null
                || MyMod.chainStateService.getClientState().getSelectedMode() != ChainMode.AREA
                || MyMod.chainStateService.getClientState().getSelectedSubMode() != ChainSubMode.AREA_CUBOID_CLEAR) {
            return;
        }
        CuboidBounds bounds = ClientProxy.clientCuboidSelectionState.bounds();
        if (bounds == null || bounds.getDimensionId() != minecraft.theWorld.provider.dimensionId) return;
        render(bounds);
    }

    private static void render(CuboidBounds bounds) {
        double minX = (double) bounds.getMinX() - RenderManager.renderPosX;
        double minY = (double) bounds.getMinY() - RenderManager.renderPosY;
        double minZ = (double) bounds.getMinZ() - RenderManager.renderPosZ;
        double maxX = (double) bounds.getMaxX() + 1.0D - RenderManager.renderPosX;
        double maxY = (double) bounds.getMaxY() + 1.0D - RenderManager.renderPosY;
        double maxZ = (double) bounds.getMaxZ() + 1.0D - RenderManager.renderPosZ;

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
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDisable(GL11.GL_FOG);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL11.glDepthMask(false);
                    GL11.glLineWidth(2.5F);
                    drawFaces(minX, minY, minZ, maxX, maxY, maxZ);
                    drawEdges(minX, minY, minZ, maxX, maxY, maxZ);
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

    private static void drawFaces(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        GL11.glColor4f(1.0F, 0.28F, 0.06F, 0.10F);
        GL11.glBegin(GL11.GL_QUADS);
        quad(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
        quad(minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        quad(minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ);
        quad(minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ);
        quad(minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ);
        quad(maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        GL11.glEnd();
    }

    private static void drawEdges(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        GL11.glColor4f(1.0F, 0.42F, 0.08F, 0.95F);
        GL11.glBegin(GL11.GL_LINES);
        line(minX, minY, minZ, maxX, minY, minZ); line(maxX, minY, minZ, maxX, minY, maxZ);
        line(maxX, minY, maxZ, minX, minY, maxZ); line(minX, minY, maxZ, minX, minY, minZ);
        line(minX, maxY, minZ, maxX, maxY, minZ); line(maxX, maxY, minZ, maxX, maxY, maxZ);
        line(maxX, maxY, maxZ, minX, maxY, maxZ); line(minX, maxY, maxZ, minX, maxY, minZ);
        line(minX, minY, minZ, minX, maxY, minZ); line(maxX, minY, minZ, maxX, maxY, minZ);
        line(maxX, minY, maxZ, maxX, maxY, maxZ); line(minX, minY, maxZ, minX, maxY, maxZ);
        GL11.glEnd();
    }

    private static void quad(double x1, double y1, double z1, double x2, double y2, double z2,
            double x3, double y3, double z3, double x4, double y4, double z4) {
        GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x3, y3, z3); GL11.glVertex3d(x4, y4, z4);
    }

    private static void line(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1); GL11.glVertex3d(x2, y2, z2);
    }
}
