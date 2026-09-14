package club.heiqi.qz_miner.client.toolswap;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 客户端 facade 不再具有库存扫描、target rematch 或候选选择代码，且无客户端时必须降级为 null。
 *
 * <p><b>守卫强度说明（Lead 裁定 27）</b>：下面三条边界断言在整份源码上做子串检索，
 * 因此<b>只能</b>证伪「把库存/准星/候选类型重新写回本类」这一形态——它<b>不能</b>证明
 * 「facade 只产出 {@link ToolSwapLightContext}」这一全部语义（换一种写法读取库存字段、或经
 * 其它类型间接采样，本类看不到）。真正的行为边界由 {@link AutoToolSwapClientAdapterTest}
 * 的 adapter 行为矩阵（facade 只被要求提供 light context）承担。</p>
 *
 * <p><b>为什么降级路径只做结构断言</b>：实测（headless 测试 JVM）执行
 * {@code new ToolSwapMinecraftFacade().captureLightContext(1L, false)} 会在
 * {@code net.minecraft.client.Minecraft.<clinit>} 抛
 * {@code NoSuchMethodError: org.lwjgl.opengl.DisplayMode.<init>(int, int)}（测试运行时 LWJGL 与
 * 编译基线不一致），即「无客户端降级」在纯 JVM 内不可达；按裁定 F 退化为方法体切片：
 * 断言降级分支的判定标识符与返回点存在。</p>
 */
public class ToolSwapMinecraftFacadeTest {

    private static final String FACADE_SOURCE =
            "src/main/java/club/heiqi/qz_miner/client/toolswap/ToolSwapMinecraftFacade.java";
    private static final String FACADE_CLASS = "club/heiqi/qz_miner/client/toolswap/ToolSwapMinecraftFacade";

    /** 无客户端/无玩家/无世界时必须存在显式降级返回点（结构契约，实测不可达故不行为化）。 */
    @Test
    public void captureLightContextKeepsExplicitNullDegradation() throws Exception {
        String source = JavaSourceSlices.stripCommentsIgnoringStringLiterals(JavaSourceSlices.read(FACADE_SOURCE));
        String body = JavaSourceSlices.methodBody(source,
                "public ToolSwapLightContext captureLightContext(long tick, boolean chainActive)",
                "captureLightContext");
        JavaSourceSlices.requireAt(body, "必须读取当前客户端", "Minecraft.getMinecraft()");
        JavaSourceSlices.requireAt(body, "必须判定玩家事实", "thePlayer");
        JavaSourceSlices.requireAt(body, "必须判定世界事实", "theWorld");
        JavaSourceSlices.requireAt(body, "无客户端事实时必须降级返回 null", "return null;");
    }

    @Test
    public void sourceOnlyCapturesLightFacts() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(new File(FACADE_SOURCE).toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertFalse("facade 不得读取背包：见类注释的守卫强度说明", source.contains("mainInventory"));
        Assert.assertFalse("facade 不得读取准星目标：见类注释的守卫强度说明", source.contains("objectMouseOver"));
        Assert.assertFalse("facade 不得引入候选选择类型：见类注释的守卫强度说明", source.contains("ToolCandidate"));
    }
}
