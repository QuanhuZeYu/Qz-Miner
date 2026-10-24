package club.heiqi.qz_miner;

import org.junit.Assert;
import org.junit.Test;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.common.versioning.VersionRange;

/** Forge 必须拒绝不满足当前设置与 HUD 运行基线的旧宿主。 */
public class MyModMetadataTest {
    @Test
    public void runtimeDependencyRequiresUilibFive() throws Exception {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);
        String declaration = metadata.dependencies();
        String prefix = "required-after:qz_uilib@";
        Assert.assertTrue(declaration.startsWith(prefix));
        String range = declaration.substring(prefix.length()).replace(";", "");
        VersionRange versions = VersionRange.createFromVersionSpec(range);
        Assert.assertTrue(versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "5.0.0-beta3-miner-ui-r13")));
        Assert.assertFalse(versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "5.0.0-beta3-miner-ui-r10")));
        Assert.assertFalse("必须拒绝存在跨浮层合批遮挡问题的 r9 组合",
                versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "5.0.0-beta3-miner-ui-r9")));
        Assert.assertFalse("必须拒绝没有原生像素 HUD API 的 r7 组合",
                versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "5.0.0-beta3-miner-ui-r7")));
        Assert.assertFalse("必须拒绝仍有选择器与 HUD 编辑已知崩溃的 r6 组合",
                versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "5.0.0-beta3-miner-ui-r6")));
        Assert.assertFalse(versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "5.0.0-beta3-miner-ui-r5")));
        Assert.assertFalse(versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "5.0.0-beta3")));
        Assert.assertFalse(versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "4.11.0")));
        Assert.assertFalse(versions.containsVersion(new DefaultArtifactVersion("qz_uilib", "6.0.0")));
        Assert.assertEquals("配置工厂只用字符串声明，FML 在客户端按需加载",
                "club.heiqi.qz_miner.client.settings.MinerConfigGuiFactory", metadata.guiFactory());
    }
}
