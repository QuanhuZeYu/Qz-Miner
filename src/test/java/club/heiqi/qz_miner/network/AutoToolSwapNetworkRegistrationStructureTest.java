package club.heiqi.qz_miner.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 自动工具换位 packet 仅追加注册且 side 固定的结构门禁。
 *
 * <p><b>断言对象</b>：{@code NetworkMain.register()} 方法体内 {@code registerMessage(...)} 的调用顺序与实参 Side。
 * 顺序取自「解析出的注册清单下标」，不再拿整文件 {@code indexOf} 当位置——注释里提到某个 packet 名、
 * 或别处出现同样字串都不会再误判；而真的换位、把换位包插到既有包之间、或把 Side 翻面都会红。</p>
 *
 * <p><b>守不到什么</b>：注册是否真的被 FML 接受需要 NetworkRegistry 运行时；本用例只证注册表内容与冻结协议序一致。</p>
 */
public class AutoToolSwapNetworkRegistrationStructureTest {

    private static final String NETWORK_MAIN_PATH =
            "src/main/java/club/heiqi/qz_miner/network/NetworkMain.java";

    /** 协议序尾段：既有注册尾锚点之后依次追加的换位 packet，相邻两项必须保持先后。 */
    private static final String[] APPENDED_IN_ORDER = {
            "PacketObjectGroupConfigSync.Handler",
            "PacketAutoToolSwapRoundStart.Handler",
            "PacketAutoToolSwapIntent.Handler",
            "PacketAutoToolSwapRoundResult.Handler",
            "PacketAutoToolSwapActionResult.Handler",
            "PacketAutoToolSwapRoundPhase.Handler",
            "PacketCuboidSelectionRequest.Handler",
            "PacketCuboidSelectionSync.Handler",
    };

    /** 换位 packet 的接收 Side：C2S 只收 SERVER 侧，S2C 只收 CLIENT 侧。 */
    private static final Map<String, String> EXPECTED_SIDES = new LinkedHashMap<String, String>();

    static {
        EXPECTED_SIDES.put("PacketAutoToolSwapRoundStart.Handler", "SERVER");
        EXPECTED_SIDES.put("PacketAutoToolSwapIntent.Handler", "SERVER");
        EXPECTED_SIDES.put("PacketAutoToolSwapRoundResult.Handler", "CLIENT");
        EXPECTED_SIDES.put("PacketAutoToolSwapActionResult.Handler", "CLIENT");
        EXPECTED_SIDES.put("PacketAutoToolSwapRoundPhase.Handler", "CLIENT");
        EXPECTED_SIDES.put("PacketCuboidSelectionRequest.Handler", "SERVER");
        EXPECTED_SIDES.put("PacketCuboidSelectionSync.Handler", "CLIENT");
    }

    @Test
    public void toolSwapPacketsAppendAfterExistingPacketsInProtocolOrder() throws Exception {
        List<List<String>> registrations = registrations();
        List<String> handlerOrder = new ArrayList<String>();
        for (List<String> arguments : registrations) {
            handlerOrder.add(stripClassSuffix(arguments.get(0)));
        }

        for (String handler : APPENDED_IN_ORDER) {
            Assert.assertTrue("协议序清单缺少注册项: " + handler + "（实际=" + handlerOrder + "）",
                    handlerOrder.contains(handler));
        }
        for (int index = 1; index < APPENDED_IN_ORDER.length; index++) {
            String earlier = APPENDED_IN_ORDER[index - 1];
            String later = APPENDED_IN_ORDER[index];
            Assert.assertTrue("协议序被破坏: " + earlier + " 必须先于 " + later + "（实际=" + handlerOrder + "）",
                    handlerOrder.indexOf(earlier) < handlerOrder.indexOf(later));
        }
        for (Map.Entry<String, String> entry : EXPECTED_SIDES.entrySet()) {
            int at = handlerOrder.indexOf(entry.getKey());
            Assert.assertTrue("协议序清单缺少注册项: " + entry.getKey(), at >= 0);
            Assert.assertEquals(entry.getKey() + " 的接收 Side 已漂移",
                    entry.getValue(), stripSide(registrations.get(at).get(3)));
        }
    }

    /** {@code register()} 方法体内每个 {@code registerMessage(...)} 的实参清单，按注册顺序返回。 */
    private static List<List<String>> registrations() throws Exception {
        String body = JavaSourceSlices.methodBody(
                JavaSourceSlices.maskedMainSource(NETWORK_MAIN_PATH),
                "public void register()", "NetworkMain.register");
        List<List<String>> calls = new ArrayList<List<String>>();
        int from = 0;
        while (true) {
            int at = JavaSourceSlices.wordIndexOf(body, "registerMessage", from);
            if (at < 0) {
                return calls;
            }
            calls.add(JavaSourceSlices.splitCallArguments(body.substring(at), "registerMessage"));
            from = at + 1;
        }
    }

    private static String stripClassSuffix(String argument) {
        String value = argument.trim();
        return value.endsWith(".class") ? value.substring(0, value.length() - ".class".length()) : value;
    }

    private static String stripSide(String argument) {
        String value = argument.trim();
        return value.startsWith("Side.") ? value.substring("Side.".length()) : value;
    }
}
