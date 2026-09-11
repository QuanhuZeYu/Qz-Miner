package club.heiqi.qz_miner;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;

/**
 * 验证最终 Forge mod 元数据中的运行依赖范围。
 *
 * <p>下界必须是<b>当前交接制品版本 4.9.1</b>：Miner 直接引用 {@code HudEditTarget} /
 * {@code HudEditService} / {@code ChatActionService}，这些类 4.9.0 制品里不存在（UILib 4.9.1
 * 相对 4.9.0 为纯增量）。留 4.9.0 下界会被 Forge 依赖检查放行、随后类加载期
 * {@code NoClassDefFoundError}；留已退役的 4.10.0 等旧编号会让声明的兼容面与交付制品脱节。</p>
 */
public class MyModMetadataTest {

    @Test
    public void qzUiLibRuntimeRangeIs491ToBefore500() {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);

        Assert.assertNotNull("MyMod must retain @Mod metadata", metadata);
        Assert.assertEquals("required-after:qz_uilib@[4.9.1,5.0.0);", metadata.dependencies());
    }
}
