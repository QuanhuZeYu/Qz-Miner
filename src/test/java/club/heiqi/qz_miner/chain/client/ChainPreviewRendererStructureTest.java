package club.heiqi.qz_miner.chain.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewRendererStructureTest {

    @Test
    public void rendererUsesObserverCacheAndQuadDrawWithoutCorePolling() throws Exception {
        String renderer = source("ChainPreviewRenderer.java");
        String gpuCache = source("ChainPreviewMeshCache.java");
        String renderCache = source("ChainPreviewRenderCache.java");

        Assert.assertTrue(renderer.contains("renderCache.refreshForCamera("));
        Assert.assertTrue(renderer.contains("renderCache.pollPublication()"));
        Assert.assertFalse(renderer.contains("getRenderRevision()"));
        Assert.assertFalse(renderer.contains("getPreviewTargetsSnapshot()"));
        Assert.assertFalse(renderer.contains("meshBuilder.build("));
        Assert.assertTrue(renderCache.contains("registerClientPost("));
        Assert.assertTrue(renderCache.contains("meshBuilder.beginRecolor("));
        Assert.assertTrue(renderer.contains("meshCache.uploadColors(mesh)"));
        Assert.assertTrue(renderer.contains("uploadedOriginX - RenderManager.renderPosX"));
        Assert.assertTrue(gpuCache.contains("GL20.glVertexAttribPointer(0"));
        Assert.assertTrue(gpuCache.contains("GL20.glEnableVertexAttribArray(0)"));
        Assert.assertFalse(gpuCache.contains("GL11.glVertexPointer("));
        Assert.assertTrue(gpuCache.contains("GL11.GL_QUADS"));
        Assert.assertFalse(gpuCache.contains("GL11.GL_LINES"));
    }

    @Test
    public void rendererAndGpuCacheRestoreMatrixClientAndBufferBindings() throws Exception {
        String renderer = source("ChainPreviewRenderer.java");
        String gpuCache = source("ChainPreviewMeshCache.java");

        Assert.assertTrue(renderer.contains("glPushClientAttrib"));
        Assert.assertTrue(renderer.contains("glMatrixMode(previousMatrixMode)"));
        Assert.assertTrue(renderer.contains("glEnable(GL11.GL_CULL_FACE)"));
        Assert.assertTrue(renderer.contains("glFrontFace(GL11.GL_CCW)"));
        Assert.assertTrue(renderer.contains("glShadeModel(GL11.GL_SMOOTH)"));
        Assert.assertTrue(gpuCache.contains("GL30.GL_VERTEX_ARRAY_BINDING"));
        Assert.assertTrue(gpuCache.contains("GL15.GL_ARRAY_BUFFER_BINDING"));
        Assert.assertTrue(gpuCache.contains("GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING"));
        Assert.assertTrue(gpuCache.contains("restoreBindings(previous)"));
    }

    private static String source(String fileName) throws Exception {
        Path path = Paths.get(
            "src/main/java/club/heiqi/qz_miner/chain/client/" + fileName);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
