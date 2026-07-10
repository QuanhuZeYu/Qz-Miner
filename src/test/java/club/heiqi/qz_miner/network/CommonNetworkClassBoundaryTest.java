package club.heiqi.qz_miner.network;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.CommonProxy;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import net.minecraft.network.INetHandler;

/**
 * common 网络边界：CommonProxy / NetworkMain / 三个 S2C packet 签名不得引用 client / LWJGL。
 *
 * <p>JVM 反射只能证明<strong>已解析</strong>类型；对未加载 client 类的保证改用 class 文件
 * ISO-8859-1 常量池字节串断言（不触发 Class.forName 解析 client 类，也不实例化
 * {@link NetworkMain}——其构造会触碰 FML NetworkRegistry，纯 JVM 无 LaunchClassLoader）。</p>
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
        proxy.handleClientLootGamesMinesweeperPreview(
                1, new ChainTarget(0, 0, 0), new ArrayList<ChainTarget>(), null);

        Method config = findMethod(CommonProxy.class, "handleClientChainConfigSync");
        Method phase = findMethod(CommonProxy.class, "handleClientChainPhaseSnapshot");
        Method preview = findMethod(CommonProxy.class, "handleClientLootGamesMinesweeperPreview");
        assertLastParamIsINetHandler(config);
        assertLastParamIsINetHandler(phase);
        assertLastParamIsINetHandler(preview);
    }

    @Test
    public void commonProxyAndNetworkMainBytecodeHasNoClientOrLwjglRefs() throws Exception {
        assertClassBytecodeClean(CommonProxy.class);
        assertClassBytecodeClean(NetworkMain.class);
        assertClassBytecodeClean(PacketChainConfigSync.class);
        assertClassBytecodeClean(PacketChainConfigSync.Handler.class);
        assertClassBytecodeClean(PacketChainPhaseSnapshot.class);
        assertClassBytecodeClean(PacketChainPhaseSnapshot.Handler.class);
        assertClassBytecodeClean(PacketLootGamesMinesweeperPreviewResponse.class);
        assertClassBytecodeClean(PacketLootGamesMinesweeperPreviewResponse.Handler.class);
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

        // Handler 类本身可加载且 onMessage 存在（不 new NetworkMain / 不跑 FML channel）
        Assert.assertNotNull(PacketChainConfigSync.Handler.class.getName());
        Assert.assertNotNull(PacketChainPhaseSnapshot.Handler.class.getName());
        Assert.assertNotNull(PacketLootGamesMinesweeperPreviewResponse.Handler.class.getName());
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

    private static void assertClassBytecodeClean(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream in = type.getClassLoader().getResourceAsStream(resource);
        Assert.assertNotNull("class bytes missing for " + type.getName(), in);
        byte[] bytes;
        try {
            bytes = readAll(in);
        } finally {
            in.close();
        }
        // class 常量池以 Modified UTF-8 存内部名；用 ISO-8859-1 扫描足够覆盖路径串
        String ascii = new String(bytes, "ISO-8859-1");
        for (String forbidden : FORBIDDEN_SUBSTRINGS) {
            Assert.assertFalse(
                    type.getName() + " bytecode must not contain " + forbidden,
                    ascii.contains(forbidden));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        List<byte[]> chunks = new ArrayList<byte[]>();
        int total = 0;
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) {
                continue;
            }
            byte[] chunk = new byte[n];
            System.arraycopy(buf, 0, chunk, 0, n);
            chunks.add(chunk);
            total += n;
        }
        byte[] all = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, all, pos, chunk.length);
            pos += chunk.length;
        }
        return all;
    }
}
