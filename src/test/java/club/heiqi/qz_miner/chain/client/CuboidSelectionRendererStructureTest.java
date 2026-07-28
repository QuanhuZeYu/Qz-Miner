package club.heiqi.qz_miner.chain.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assert;
import org.junit.Test;

/** 框选 renderer 必须恢复调用前的 matrix mode。 */
public class CuboidSelectionRendererStructureTest {

    @Test
    public void restoresPreviousMatrixMode() throws Exception {
        Path source = Paths.get("src/main/java/club/heiqi/qz_miner/chain/client/CuboidSelectionRenderer.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("glGetInteger(GL11.GL_MATRIX_MODE)"));
        Assert.assertTrue(text.contains("glMatrixMode(previousMatrixMode)"));
        Assert.assertTrue(text.indexOf("glPopMatrix()") < text.indexOf("glMatrixMode(previousMatrixMode)"));
    }
}
