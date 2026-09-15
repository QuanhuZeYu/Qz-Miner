package club.heiqi.qz_miner;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;

/**
 * 验证最终 Forge mod 元数据中的运行依赖范围。
 *
 * <p>下界必须是<b>本版编译所用制品版本 4.10.0</b>：Miner 直接引用 {@code HudEditTarget} /
 * {@code HudEditService} / {@code ChatActionService}（4.9.0 制品里不存在），且本版颜色字段已改用
 * {@code config.schema.ColorSpec} / {@code HexColorCodec} / {@code ColorFieldRenderer}——这些公共面只在
 * Qz-UILib 4.10.0 起存在。留更低下界会被 Forge 依赖检查放行、随后类加载期 {@code NoClassDefFoundError}
 * （4.9.x 件不含上述类型）；留比交付制品更高的下界则会让声明的兼容面与制品脱节。</p>
 */
public class MyModMetadataTest {

    @Test
    public void qzUiLibRuntimeRangeIs4100ToBefore500() {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);

        Assert.assertNotNull("MyMod must retain @Mod metadata", metadata);
        Assert.assertEquals("required-after:qz_uilib@[4.10.0,5.0.0);", metadata.dependencies());
    }
}
