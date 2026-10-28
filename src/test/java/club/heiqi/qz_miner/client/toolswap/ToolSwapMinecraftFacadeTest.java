package club.heiqi.qz_miner.client.toolswap;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 以源码方法切片检查 null 降级入口，以文本匹配检查库存、准星与候选类型引用。
 * 此守卫覆盖源码形态，Minecraft 库存和连接的实际副作用由运行验证确认。
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
