package club.heiqi.qz_miner;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;

/** 验证最终 Forge mod 元数据中的运行依赖下限。 */
public class MyModMetadataTest {

    @Test
    public void qzUiLibMinimumVersionIsBeta11() {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);

        Assert.assertNotNull("MyMod must retain @Mod metadata", metadata);
        Assert.assertEquals("required-after:qz_uilib@[4.5.3-beta-12,);", metadata.dependencies());
    }
}
