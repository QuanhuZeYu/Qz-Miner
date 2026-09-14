package club.heiqi.qz_miner.client.toolswap;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.network.NetworkMain;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;

/**
 * Qz C2S transport 的已接线发送边界。
 *
 * <p>无网络降级与「只发 round-start / intent 两类报文」用真实对象断言（{@code MyMod.networkMain}
 * 为 null 即纯 JVM 可达路径）；报文类型无法在 headless 捕获（{@code NetworkMain} 与
 * {@code SimpleNetworkWrapper} 均为 final，且不得为测试新增 seam，见 Lead 裁定 A），
 * 因此改为「发送方法体内构造的报文标识符 + 类级发送点计数」的结构契约。</p>
 */
public class QzAutoToolSwapClientTransportStructureTest {

    private static final String TRANSPORT_SOURCE =
            "src/main/java/club/heiqi/qz_miner/client/toolswap/QzAutoToolSwapClientTransport.java";

    private NetworkMain previousNetworkMain;

    @Before
    public void clearNetwork() {
        previousNetworkMain = MyMod.networkMain;
        MyMod.networkMain = null;
    }

    @After
    public void restoreNetwork() {
        MyMod.networkMain = previousNetworkMain;
    }

    @Test
    public void transportOnlySendsQzRoundStartAndIntentPackets() throws Exception {
        AutoToolSwapClientTransport transport = new QzAutoToolSwapClientTransport();

        // 行为：无网络时两个 API 都降级为 false（不抛、不假装成功）。
        Assert.assertFalse("无网络时 round-start 必须降级为 false", transport.sendRoundStart(7L));
        Assert.assertFalse("无网络时 intent 必须降级为 false", transport.sendIntent(intent(1L)));
        try {
            transport.sendIntent(null);
            Assert.fail("null intent 必须在发送前快速失败");
        } catch (IllegalArgumentException expected) {
            // 预期：参数校验先于网络判定。
        }

        // 结构契约：发送只发生在两个 API 的方法体内，且各自构造固定报文类型。
        String source = JavaSourceSlices.stripCommentsIgnoringStringLiterals(JavaSourceSlices.read(TRANSPORT_SOURCE));
        String roundStart = JavaSourceSlices.methodBody(source,
                "public boolean sendRoundStart(long clientNonce)", "sendRoundStart");
        JavaSourceSlices.requireAt(roundStart, "round-start 发送", "sendToServer(");
        JavaSourceSlices.requireAt(roundStart, "round-start 报文", "new PacketAutoToolSwapRoundStart(");
        Assert.assertEquals("sendRoundStart 只允许一个发送点", 1,
                JavaSourceSlices.count(roundStart, "sendToServer("));

        String sendIntent = JavaSourceSlices.methodBody(source,
                "public boolean sendIntent(AutoToolSwapIntent intent)", "sendIntent");
        JavaSourceSlices.requireAt(sendIntent, "intent 发送", "sendToServer(");
        JavaSourceSlices.requireAt(sendIntent, "intent 报文", "new PacketAutoToolSwapIntent(");
        Assert.assertEquals("sendIntent 只允许一个发送点", 1,
                JavaSourceSlices.count(sendIntent, "sendToServer("));
        Assert.assertEquals("除这两个 API 外不得再有发送点", 2,
                JavaSourceSlices.count(source, "sendToServer("));

        // 边界：transport 不碰库存点击（结构化产物扫描，名称无关）。
        CompiledClasses.Refs refs = CompiledClasses.refs(CompiledClasses.forInternalName(
                "club/heiqi/qz_miner/client/toolswap/QzAutoToolSwapClientTransport"));
        for (String forbiddenMember : new String[] { "windowClick", "slotClick", "clickWindow" }) {
            for (String member : union(refs.methodRefs, refs.fieldRefs)) {
                Assert.assertFalse("transport 不得触碰库存点击 API: " + member,
                        member.endsWith("#" + forbiddenMember));
            }
        }
        Assert.assertFalse("transport 不得链接背包/容器界面类型（只做报文发送）",
                refs.hasClassRefUnder("net/minecraft/inventory")
                        || refs.hasClassRefUnder("net/minecraft/client/gui/inventory")
                        || refs.hasClassRefUnder("net/minecraft/entity/player/InventoryPlayer"));
    }

    @Test
    public void transportBoundaryDoesNotExposeForgePacketsToController() throws Exception {
        Method[] methods = AutoToolSwapClientTransport.class.getDeclaredMethods();
        Assert.assertEquals("传输边界必须只有两个 API", 2, methods.length);
        for (Method method : methods) {
            Assert.assertTrue(method + " 必须是 public abstract", Modifier.isPublic(method.getModifiers())
                    && Modifier.isAbstract(method.getModifiers()));
            Assert.assertEquals(method + " 必须返回 boolean", boolean.class, method.getReturnType());
            for (Class<?> parameter : method.getParameterTypes()) {
                Assert.assertTrue("参数类型不得暴露 Forge packet: " + method + " -> " + parameter,
                        parameter == long.class || parameter == AutoToolSwapIntent.class);
            }
        }
        Assert.assertNotNull(AutoToolSwapClientTransport.class.getMethod("sendRoundStart", long.class));
        Assert.assertNotNull(AutoToolSwapClientTransport.class
                .getMethod("sendIntent", AutoToolSwapIntent.class));
    }

    private static AutoToolSwapIntent intent(long serverRoundId) {
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, serverRoundId,
                AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, AutoToolSwapAction.FREEZE, 0, 1,
                AutoToolSwapContentFingerprint.canonicalEmpty(),
                AutoToolSwapContentFingerprint.canonicalEmpty());
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.Set<String> merged = new java.util.LinkedHashSet<String>(left);
        merged.addAll(right);
        return merged;
    }
}
