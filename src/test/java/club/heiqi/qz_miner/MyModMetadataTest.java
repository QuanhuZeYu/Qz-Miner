package club.heiqi.qz_miner;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;

/** 验证最终 Forge mod 元数据中的运行依赖范围。 */
public class MyModMetadataTest {

    @Test
    public void qzUiLibRuntimeRangeRequiresFiveX() {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);

        Assert.assertNotNull("MyMod must retain @Mod metadata", metadata);
        Assert.assertEquals("required-after:qz_uilib@[5.0.0,6.0.0);", metadata.dependencies());
    }
}
