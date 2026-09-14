package club.heiqi.qz_miner.chain.client.verify;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.chain.client.ChainPreviewRenderer;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T35 波次 7 高亮协同接线契约（B2.5 / task-34）：行为判据走 renderer 的公开判定入口
 * （mixin 的唯一委派点），注册判据走 mixin 清单（AGENTS.md:16 要求 client mixin 落在 client 数组）。
 */
public class HighlightWiringContractTest {

    private static final String MIXIN_CLASS = "MixinRenderGlobalVanillaHighlight";

    @Test
    public void defaultOffNeverSuppressesRegardlessOfAiming() {
        // 默认档（clientPreviewSuppressVanillaHighlight=off）：任何坐标都不得抑制
        ChainPreviewRenderer renderer = new ChainPreviewRenderer(new ChainPreviewState());
        Assert.assertFalse("默认关闭档不得抑制",
            renderer.shouldSuppressVanillaHighlight(0, 0, 0));
        Assert.assertFalse(renderer.shouldSuppressVanillaHighlight(5, 64, -3));
        Assert.assertFalse(renderer.shouldSuppressVanillaHighlight(Integer.MIN_VALUE, 0, 0));
    }

    @Test
    public void suppressionRequiresFlagActiveAndOriginMatch() throws Exception {
        ChainPreviewController controller = new ChainPreviewController();
        ChainPreviewState state = controller.getPreviewState();
        VerifyRenderCacheHarness harness = new VerifyRenderCacheHarness(state);
        harness.observe();
        Object renderer = newRenderer(harness.cache());

        boolean savedFlag = Config.clientPreviewSuppressVanillaHighlight;
        Object savedController = ClientProxy.chainPreviewController;
        try {
            ClientProxy.chainPreviewController = controller;

            // 预览未激活 + 开关开启 -> 不抑制
            Config.clientPreviewSuppressVanillaHighlight = true;
            harness.setVisualSettings(ChainPreviewVisualSettings.fromConfig());
            Assert.assertFalse("预览未激活不得抑制",
                suppress(renderer, 5, 64, -3));

            // 激活但瞄准其它方块 -> 不抑制；瞄准 origin -> 抑制
            int generation = state.begin(new ChainTarget(5, 64, -3));
            Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(5, 64, -3)));
            harness.runUntilPublication(8);
            Assert.assertFalse("瞄准非 origin 不得抑制", suppress(renderer, 6, 64, -3));
            Assert.assertFalse("瞄准非 origin（Y 轴差 1）不得抑制", suppress(renderer, 5, 65, -3));
            Assert.assertFalse("瞄准非 origin（Z 轴差 1）不得抑制", suppress(renderer, 5, 64, -2));
            Assert.assertTrue("开关开 + 激活 + 命中 origin 必须抑制", suppress(renderer, 5, 64, -3));

            // 预览结束 -> 恢复不抑制
            state.clear();
            harness.runUntilPublication(8);
            Assert.assertFalse("预览结束后不得抑制", suppress(renderer, 5, 64, -3));

            // 开关关闭 -> 即使激活且命中 origin 也不抑制
            Config.clientPreviewSuppressVanillaHighlight = false;
            harness.setVisualSettings(ChainPreviewVisualSettings.fromConfig());
            int next = state.begin(new ChainTarget(5, 64, -3));
            state.addPreviewTarget(next, new ChainTarget(5, 64, -3));
            harness.runUntilPublication(8);
            Assert.assertFalse("开关关闭档必须短路不抑制", suppress(renderer, 5, 64, -3));
        } finally {
            Config.clientPreviewSuppressVanillaHighlight = savedFlag;
            ClientProxy.chainPreviewController = (ChainPreviewController) savedController;
        }
    }

    /**
     * mixin 归属表契约：高亮 mixin 必须注册在 {@code client} 数组，且不得出现在通用 {@code mixins}
     * 或 {@code server} 数组里（放错表 = 静默不生效 / 服务端崩溃）。
     *
     * <p>旧写法自写文本切片从 JSON 里抠数组再 {@code contains}：多一层缩进、换个键序、
     * 或类名恰好是另一个更长的类名的前缀都会给出错误结论。现在按 JSON 解析后取条目<strong>简单名</strong>集合，
     * 判的是清单归属而不是字符出现。</p>
     */
    @Test
    public void highlightMixinIsRegisteredInClientArray() throws Exception {
        JsonObject config = new JsonParser()
            .parse(readResource("mixins.qz_miner.json")).getAsJsonObject();

        Assert.assertTrue("client mixin 必须注册在 client 数组: " + MIXIN_CLASS,
            mixinNames(config, "client").contains(MIXIN_CLASS));
        Assert.assertFalse("client mixin 不得注册在通用 mixins 数组",
            mixinNames(config, "mixins").contains(MIXIN_CLASS));
        Assert.assertFalse("client mixin 不得注册在 server 数组",
            mixinNames(config, "server").contains(MIXIN_CLASS));
    }

    /** 取某张 mixin 表的条目简单名集合（条目可带子包前缀，如 {@code client.X}）。 */
    private static Set<String> mixinNames(JsonObject config, String arrayName) {
        JsonArray entries = config.getAsJsonArray(arrayName);
        Assert.assertNotNull("mixin 清单必须包含数组: " + arrayName, entries);
        Set<String> names = new LinkedHashSet<String>();
        for (JsonElement entry : entries) {
            String name = entry.getAsString();
            int separator = name.lastIndexOf('.');
            names.add(separator < 0 ? name : name.substring(separator + 1));
        }
        return names;
    }

    private static boolean suppress(Object renderer, int x, int y, int z) throws Exception {
        java.lang.reflect.Method method = renderer.getClass().getMethod(
            "shouldSuppressVanillaHighlight", int.class, int.class, int.class);
        return ((Boolean) method.invoke(renderer, Integer.valueOf(x), Integer.valueOf(y),
            Integer.valueOf(z))).booleanValue();
    }

    private static Object newRenderer(Object cache) throws Exception {
        Class<?> cacheType = Class.forName("club.heiqi.qz_miner.chain.client.ChainPreviewRenderCache");
        Constructor<?> constructor =
            ChainPreviewRenderer.class.getDeclaredConstructor(cacheType);
        constructor.setAccessible(true);
        return constructor.newInstance(cache);
    }

    private static String readResource(String name) throws Exception {
        InputStream stream = HighlightWiringContractTest.class.getClassLoader().getResourceAsStream(name);
        Assert.assertNotNull("资源必须存在: " + name, stream);
        Reader reader = new InputStreamReader(stream, "UTF-8");
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[2048];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            text.append(buffer, 0, read);
        }
        reader.close();
        return text.toString();
    }

}
