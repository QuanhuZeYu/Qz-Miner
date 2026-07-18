package club.heiqi.qz_miner.chain.client;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** seed 租约刷新与生命周期隔离的无 GL 结构合同。 */
public class ChainPreviewControllerTest {

    @Test
    public void verifiedLayoutRefreshReusesCapturedSeedAndBypassesPhaseLock() throws Exception {
        String source = source();
        Assert.assertEquals(1, count(source, "new BlockSeedSnapshot("));
        Assert.assertTrue(source.contains("startPreview(world, origin, previewSeedSnapshot, false)"));
        Assert.assertTrue(source.contains("final Block sampleBlock = seedSnapshot.getSampleBlock()"));
        Assert.assertTrue(source.contains("final int sampleMeta = seedSnapshot.getSampleMeta()"));
        Assert.assertTrue(source.contains("final TileEntity sampleTileEntity = seedSnapshot.getSampleTileEntity()"));
        Assert.assertFalse(source.substring(source.indexOf("public void onToolLayoutVerified"),
                source.indexOf("@SubscribeEvent")).contains("shouldLockCurrentPreview"));
    }

    @Test
    public void onlyThreeInventoryActionsRefreshAndLeaseIdentityIsClearedOnStop() throws Exception {
        String source = source();
        Assert.assertTrue(source.contains("action == AutoToolSwapAction.SWAP"));
        Assert.assertTrue(source.contains("action == AutoToolSwapAction.TAKEOVER"));
        Assert.assertTrue(source.contains("action == AutoToolSwapAction.RESTORE"));
        Assert.assertTrue(source.contains("world != previewSeedWorld"));
        Assert.assertTrue(source.contains("previewSeedSnapshot = null"));
        Assert.assertTrue(source.contains("previewSeedWorld = null"));
        Assert.assertTrue(source.contains("clearInvalidationIdentity()"));
        Assert.assertTrue(source.contains("previewState.getGeneration() != generation"));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/client/ChainPreviewController.java").toPath()),
                StandardCharsets.UTF_8);
    }

    private static int count(String value, String fragment) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            result++;
            offset += fragment.length();
        }
        return result;
    }
}
