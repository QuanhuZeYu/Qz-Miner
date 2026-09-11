package club.heiqi.qz_miner;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;

/**
 * 验证最终 Forge mod 元数据中的运行依赖范围。
 *
 * <p>下界必须是 4.10.0：Miner 自本轮起直接引用 {@code HudEditTarget} / {@code HudEditService} /
 * {@code ChatActionService}，这些类 4.9.0 制品里不存在。留 4.9.0 下界会让 Forge 依赖检查放行
 * 旧 UILib，随后在类加载期以 {@code NoClassDefFoundError} 失败。</p>
 */
public class MyModMetadataTest {

    @Test
    public void qzUiLibRuntimeRangeIs4100ToBefore500() {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);

        Assert.assertNotNull("MyMod must retain @Mod metadata", metadata);
        Assert.assertEquals("required-after:qz_uilib@[4.10.0,5.0.0);", metadata.dependencies());
    }
}
