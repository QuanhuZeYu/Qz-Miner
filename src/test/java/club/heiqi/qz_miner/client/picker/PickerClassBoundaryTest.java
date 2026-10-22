package club.heiqi.qz_miner.client.picker;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.CommonProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.testsupport.CompiledClasses;

/** 旧 picker 退役后，仍保留配置、解析器和 common 入口不得链接客户端/GL 类型的分侧契约。 */
public class PickerClassBoundaryTest {

    @Test
    public void commonBytecodeHasNoClientOrGraphicsReferences() {
        assertPlatformNeutral(QzMinerConfigSchema.class);
        assertPlatformNeutral(ConfigBootstrap.class);
        assertPlatformNeutral(CommonProxy.class);
        assertPlatformNeutral(MyMod.class);
        assertPlatformNeutral(ObjectGroupParser.class);
    }

    private static void assertPlatformNeutral(Class<?> type) {
        Assert.assertFalse(type.getName() + " must not reference client classes",
                CompiledClasses.references(type, "net/minecraft/client/"));
        Assert.assertFalse(type.getName() + " must not reference LWJGL",
                CompiledClasses.references(type, "org/lwjgl/"));
    }
}
