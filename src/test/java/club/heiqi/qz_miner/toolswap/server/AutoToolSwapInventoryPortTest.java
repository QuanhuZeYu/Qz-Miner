package club.heiqi.qz_miner.toolswap.server;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

/** 库存端口保持纯 Java 边界的结构合同。 */
public class AutoToolSwapInventoryPortTest {

    @Test
    public void portHasNoMinecraftForgeOrClientDescriptorsAndDocumentsAppliedBeforeSync() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/toolswap/server/AutoToolSwapInventoryPort.java").toPath()),
                Charset.forName("UTF-8"));
        for (String forbidden : Arrays.asList("net.minecraft", "cpw.mods", "io.netty", "client.toolswap")) {
            Assert.assertFalse("forbidden descriptor: " + forbidden, source.contains(forbidden));
        }
        Assert.assertTrue(source.contains("交换已经应用"));
        Assert.assertTrue(source.contains("不得通过再次调用本方法来重试交换"));
        Assert.assertTrue(source.contains("syncInventoryDifference"));
        Assert.assertTrue(source.contains("完整个人库存 publication"));
        Assert.assertEquals(Void.TYPE, AutoToolSwapInventoryPort.class.getMethod(
                "swapInventorySlotsAtomically", Integer.TYPE, Integer.TYPE).getReturnType());
        Assert.assertEquals(Void.TYPE, AutoToolSwapInventoryPort.class.getMethod(
                "rotateInventorySlotsAtomically", Integer.TYPE, Integer.TYPE, Integer.TYPE).getReturnType());
        Assert.assertTrue(source.contains("三槽引用轮转"));
    }
}
