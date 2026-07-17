package club.heiqi.qz_miner.client.picker;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.CommonProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;

/** common 类不得反向引用 client picker、GuiScreen 或 LWJGL。 */
public class PickerClassBoundaryTest {
    @Test
    public void commonBytecodeHasNoClientPickerOrGraphicsReferences() throws Exception {
        assertClean(QzMinerConfigSchema.class);
        assertClean(ConfigBootstrap.class);
        assertClean(CommonProxy.class);
        assertClean(MyMod.class);
        assertClean(ObjectGroupParser.class);
    }

    private static void assertClean(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        Assert.assertNotNull(in);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int size;
        while ((size = in.read(buffer)) >= 0) output.write(buffer, 0, size);
        in.close();
        String content = new String(output.toByteArray(), "ISO-8859-1");
        Assert.assertFalse(content.contains("club/heiqi/qz_miner/client/picker"));
        Assert.assertFalse(content.contains("net/minecraft/client/"));
        Assert.assertFalse(content.contains("ConfigUI"));
        Assert.assertFalse(content.contains("org/lwjgl/"));
    }
}
