package club.heiqi.qz_miner.client.picker;

import java.io.InputStream;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;

/** common 类不得反向引用 client picker、GuiScreen 或 LWJGL。 */
public class PickerClassBoundaryTest {
    @Test
    public void commonBytecodeHasNoClientPickerOrGraphicsReferences() throws Exception {
        assertClean(QzMinerConfigSchema.class);
        assertClean(ObjectGroupParser.class);
    }

    private static void assertClean(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        Assert.assertNotNull(in);
        byte[] bytes = new byte[65536];
        int size = in.read(bytes);
        in.close();
        String content = new String(bytes, 0, size, "ISO-8859-1");
        Assert.assertFalse(content.contains("club/heiqi/qz_miner/client/picker"));
        Assert.assertFalse(content.contains("net/minecraft/client/gui/GuiScreen"));
        Assert.assertFalse(content.contains("org/lwjgl/"));
    }
}
