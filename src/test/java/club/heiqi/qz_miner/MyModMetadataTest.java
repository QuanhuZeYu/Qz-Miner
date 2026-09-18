package club.heiqi.qz_miner;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;

/**
 * 验证最终 Forge mod 元数据中的运行依赖范围。
 *
 * <p>下界必须是<b>本版编译所用制品版本 4.11.0</b>：Miner 直接引用 {@code HudEditTarget} /
 * {@code HudEditService} / {@code ChatActionService}（4.9.0 制品里不存在）、颜色字段的
 * {@code config.schema.ColorSpec} 族（4.10.0 起存在），且本版已按 4.11.0 的签名变更接入环境端口
 * （{@code SceneRuntime(SceneTextMeasurer, UiEnvironment)}、{@code PickerIconResolver.of(provider,
 * ResourceEnvironment)}、{@code PickerRevisionBridge.forSource(source, UiEnvironment)} —— 旧签名在
 * 4.11.0 已删除）。留更低下界会被 Forge 依赖检查放行、随后类加载期 {@code NoClassDefFoundError} /
 * {@code NoSuchMethodError}；且上游 4.11.0 的远端范围已收紧为 {@code [4.11.0,4.12.0)}，与 4.10.x
 * 双向不承诺混用，下界低于 4.11.0 等于声明一个上游自己都不接受的组合；留比交付制品更高的下界则会
 * 让声明的兼容面与制品脱节。</p>
 */
public class MyModMetadataTest {

    @Test
    public void qzUiLibRuntimeRangeIs4110ToBefore500() {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);

        Assert.assertNotNull("MyMod must retain @Mod metadata", metadata);
        Assert.assertEquals("required-after:qz_uilib@[4.11.0,5.0.0);", metadata.dependencies());
    }
}
