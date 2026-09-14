package club.heiqi.qz_miner.network;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.CommonProxy;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import net.minecraft.network.INetHandler;

/**
 * common 网络边界：CommonProxy / NetworkMain / S2C packet 签名不得引用 client / LWJGL。
 *
 * <p>JVM 反射只能证明<strong>已解析</strong>类型；对未加载 client 类的保证改用编译产物常量池文本判定
 * （{@link CompiledClasses#references(Class, String)}：按类加载器资源定位 .class，整池按 ISO-8859-1
 * 解码后做子串判定），既不触发 Class.forName 解析 client 类，也不实例化 {@link NetworkMain}——
 * 其构造函数会触碰 FML NetworkRegistry，纯 JVM 无 LaunchClassLoader）。</p>
 *
 * <p><b>这一形态证伪什么</b>：common 侧产物里出现 client / LWJGL 的类型引用、成员引用，或仅仅把它们
 * 写进字符串——{@code Class.forName("net.minecraft.client...")} 这类反射式依赖同样被拦下；
 * <b>守不到什么</b>：它也会命中「恰好提到该名字」的调试文案，故失败时先分辨是引用还是文案。</p>
 */
public class CommonNetworkClassBoundaryTest {

    private static final String[] FORBIDDEN_SUBSTRINGS = {
            "net/minecraft/client/",
            "net.minecraft.client.",
            "NetHandlerPlayClient",
            "ClientMainThreadDispatcher",
            "ClientProxy",
            "org/lwjgl/",
            "org.lwjgl.",
            "LWJGL",
    };

    @Test
    public void commonProxyMethodsAcceptINetHandlerAndNoOpOnDedicated() {
        CommonProxy proxy = new CommonProxy();
        proxy.handleClientChainConfigSync(12, 345, 0, null);
        proxy.handleClientChainPhaseSnapshot(0, 1, 2L, null);
        proxy.handleClientObjectGroupConfigSync(1, 5L, 4L, 0, 0, true, null);
        proxy.handleClientAutoToolSwapRoundResult(1, 2L, 3L, 4, 5, 6L, 7L, true, null);
        proxy.handleClientAutoToolSwapActionResult(1, 2L, 3L, 4, 5, 6, 7, 8, 9L, 10L, true, null);
        proxy.handleClientAutoToolSwapRoundPhase(1, 2L, 3L, 4, 5, 6L, true, null);
        proxy.handleClientCuboidSelectionSync(1, 2L, 1, 0, 3,
                0, 1, 2, 3, 0, 4, 5, 6, true, null);
        proxy.handleClientLootGamesMinesweeperPreview(
                1, new ChainTarget(0, 0, 0), new ArrayList<ChainTarget>(), null);

        Method config = findMethod(CommonProxy.class, "handleClientChainConfigSync");
        Method phase = findMethod(CommonProxy.class, "handleClientChainPhaseSnapshot");
        Method objectGroup = findMethod(CommonProxy.class, "handleClientObjectGroupConfigSync");
        Method autoToolRound = findMethod(CommonProxy.class, "handleClientAutoToolSwapRoundResult");
        Method autoToolAction = findMethod(CommonProxy.class, "handleClientAutoToolSwapActionResult");
        Method autoToolPhase = findMethod(CommonProxy.class, "handleClientAutoToolSwapRoundPhase");
        Method cuboidSelection = findMethod(CommonProxy.class, "handleClientCuboidSelectionSync");
        Method preview = findMethod(CommonProxy.class, "handleClientLootGamesMinesweeperPreview");
        assertLastParamIsINetHandler(config);
        assertLastParamIsINetHandler(phase);
        assertLastParamIsINetHandler(objectGroup);
        assertLastParamIsINetHandler(autoToolRound);
        assertLastParamIsINetHandler(autoToolAction);
        assertLastParamIsINetHandler(autoToolPhase);
        assertLastParamIsINetHandler(cuboidSelection);
        assertLastParamIsINetHandler(preview);
    }

    @Test
    public void commonProxyAndNetworkMainBytecodeHasNoClientOrLwjglRefs() throws Exception {
        assertClassBytecodeClean(CommonProxy.class);
        assertClassBytecodeClean(NetworkMain.class);
        assertClassBytecodeClean(PacketChainConfigSync.class);
        assertClassBytecodeClean(PacketChainConfigSync.Handler.class);
        assertClassBytecodeClean(PacketObjectGroupConfigRequest.class);
        assertClassBytecodeClean(PacketObjectGroupConfigRequest.Handler.class);
        assertClassBytecodeClean(PacketObjectGroupConfigSync.class);
        assertClassBytecodeClean(PacketObjectGroupConfigSync.Handler.class);
        assertClassBytecodeClean(PacketChainPhaseSnapshot.class);
        assertClassBytecodeClean(PacketChainPhaseSnapshot.Handler.class);
        assertClassBytecodeClean(PacketLootGamesMinesweeperPreviewResponse.class);
        assertClassBytecodeClean(PacketLootGamesMinesweeperPreviewResponse.Handler.class);
        assertClassBytecodeClean(PacketAutoToolSwapRoundResult.class);
        assertClassBytecodeClean(PacketAutoToolSwapRoundResult.Handler.class);
        assertClassBytecodeClean(PacketAutoToolSwapActionResult.class);
        assertClassBytecodeClean(PacketAutoToolSwapActionResult.Handler.class);
        assertClassBytecodeClean(PacketAutoToolSwapRoundPhase.class);
        assertClassBytecodeClean(PacketAutoToolSwapRoundPhase.Handler.class);
        assertClassBytecodeClean(PacketCuboidSelectionSync.class);
        assertClassBytecodeClean(PacketCuboidSelectionSync.Handler.class);
    }

    @Test
    public void s2cHandlersAndProxySignaturesUseCommonINetHandlerOnly() {
        // 方法描述符侧：CommonProxy 公开 API 末参为 INetHandler（非 NetHandlerPlayClient）
        Class<?>[] params = findMethod(CommonProxy.class, "handleClientChainConfigSync").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);
        params = findMethod(CommonProxy.class, "handleClientChainPhaseSnapshot").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);
        params = findMethod(CommonProxy.class, "handleClientLootGamesMinesweeperPreview").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);
        params = findMethod(CommonProxy.class, "handleClientObjectGroupConfigSync").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);
        params = findMethod(CommonProxy.class, "handleClientAutoToolSwapRoundResult").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);
        params = findMethod(CommonProxy.class, "handleClientAutoToolSwapActionResult").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);
        params = findMethod(CommonProxy.class, "handleClientAutoToolSwapRoundPhase").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);
        params = findMethod(CommonProxy.class, "handleClientCuboidSelectionSync").getParameterTypes();
        Assert.assertEquals(INetHandler.class, params[params.length - 1]);

        // Handler 类本身可加载且 onMessage 存在（不 new NetworkMain / 不跑 FML channel）
        Assert.assertNotNull(PacketChainConfigSync.Handler.class.getName());
        Assert.assertNotNull(PacketChainPhaseSnapshot.Handler.class.getName());
        Assert.assertNotNull(PacketLootGamesMinesweeperPreviewResponse.Handler.class.getName());
        Assert.assertNotNull(PacketObjectGroupConfigSync.Handler.class.getName());
        Assert.assertNotNull(PacketAutoToolSwapRoundResult.Handler.class.getName());
        Assert.assertNotNull(PacketAutoToolSwapActionResult.Handler.class.getName());
        Assert.assertNotNull(PacketAutoToolSwapRoundPhase.Handler.class.getName());
        Assert.assertNotNull(PacketCuboidSelectionSync.Handler.class.getName());
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        Assert.fail("missing method " + name + " on " + type.getName());
        return null;
    }

    private static void assertLastParamIsINetHandler(Method method) {
        Class<?>[] params = method.getParameterTypes();
        Assert.assertTrue(params.length > 0);
        Assert.assertEquals(
                "last parameter must be common INetHandler, not client NetHandlerPlayClient",
                INetHandler.class,
                params[params.length - 1]);
        for (Class<?> param : params) {
            String n = param.getName();
            Assert.assertFalse("client type in signature: " + n, n.startsWith("net.minecraft.client."));
            Assert.assertFalse("lwjgl type in signature: " + n, n.startsWith("org.lwjgl."));
        }
    }

    /**
     * 编译产物常量池不得出现 client / LWJGL 名字。定位（类加载器资源）与判定（ISO-8859-1 常量池文本子串）
     * 全部走共享工具 {@link CompiledClasses#references(Class, String)}——本类不再自带私有常量池读取副本。
     */
    private static void assertClassBytecodeClean(Class<?> type) {
        for (String forbidden : FORBIDDEN_SUBSTRINGS) {
            Assert.assertFalse(
                    type.getName() + " bytecode must not contain " + forbidden,
                    CompiledClasses.references(type, forbidden));
        }
    }
}
