package club.heiqi.qz_miner.client.toolswap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 客户端 facade 不再具有库存扫描、target rematch 或候选选择代码。 */
public class ToolSwapMinecraftFacadeTest {
    @Test
    public void sourceOnlyCapturesLightFacts() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/client/toolswap/ToolSwapMinecraftFacade.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertFalse(source.contains("mainInventory"));
        Assert.assertFalse(source.contains("objectMouseOver"));
        Assert.assertFalse(source.contains("ToolCandidate"));
        Assert.assertFalse(source.contains("captureContext"));
        Assert.assertTrue(source.contains("captureLightContext"));
    }
}
