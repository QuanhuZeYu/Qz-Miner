package club.heiqi.qz_miner.client;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainStateService;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientAdapter;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientTransport;
import club.heiqi.qz_miner.client.toolswap.ClientAutoToolSwapPacketDispatch;
import club.heiqi.qz_miner.client.toolswap.ToolSwapLightContext;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigTestSupport;
import club.heiqi.qz_miner.network.NetworkMain;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;

/** 自动工具客户端的按键边沿、配置发布、生命周期及连接 gate 行为；不约束初始化方法的源码拼写。 */
public class AutoToolClientWiringStructureTest {

    private AutoToolSwapClientAdapter previousAdapter;
    private NetworkMain previousNetworkMain;
    private ChainStateService previousChainStateService;

    @Before
    public void captureWiring() {
        previousAdapter = ClientProxy.autoToolSwapAdapter;
        previousNetworkMain = MyMod.networkMain;
        previousChainStateService = MyMod.chainStateService;
    }

    @After
    public void restoreWiring() throws Exception {
        ClientConnectionLifecycle.resetForTests();
        clearConfigSubscription();
        ConfigTestSupport.reset();
        ClientProxy.autoToolSwapAdapter = previousAdapter;
        MyMod.networkMain = previousNetworkMain;
        MyMod.chainStateService = previousChainStateService;
    }

    /** 物理按键边沿必须下传到唯一 adapter，重复按下不得重复起轮。 */
    @Test
    public void keyListenerNotifiesPressEdgesOnce() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        AutoToolSwapClientAdapter adapter = new AutoToolSwapClientAdapter(true, new BreakCapableGame(), transport);
        KeyListener listener = new KeyListener(adapter);

        invokeUpdateChainKeyState(listener, true);
        Assert.assertEquals("物理上升沿必须经 adapter 提交 RoundStart", 1, transport.nonces.size());
        invokeUpdateChainKeyState(listener, true);
        Assert.assertEquals("重复的按下通知不得产生第二个 round", 1, transport.nonces.size());
        invokeUpdateChainKeyState(listener, false);
        Assert.assertEquals("松开边沿不得凭空发包", 1, transport.nonces.size());
    }

    /** 保留可观测的接入签名：输入接受唯一 adapter，入包保留真实连接身份。 */
    @Test
    public void clientInputAndPacketHandlersKeepConnectionAwareSignatures() throws Exception {
        Assert.assertTrue("KeyListener 必须存在接受唯一 adapter 的公开构造器",
                hasConstructor(KeyListener.class, AutoToolSwapClientAdapter.class));

        for (String handler : new String[] { "handleClientAutoToolSwapRoundResult",
                "handleClientAutoToolSwapActionResult", "handleClientAutoToolSwapRoundPhase" }) {
            Assert.assertTrue("ClientProxy 必须实现 " + handler + "(..., INetHandler)",
                    hasPacketHandler(ClientProxy.class, handler));
        }
    }

    /** 配置发布必须真的流到已接线的 adapter：关闭后按键不再发 round，重新打开后又发。 */
    @Test
    public void configPublicationReachesWiredAdapter() throws Exception {
        File tempDir = Files.createTempDirectory("qz-miner-wiring-").toFile();
        try {
            ConfigTestSupport.reset();
            MyMod.chainStateService = null;
            MyMod.networkMain = null;
            ConfigBootstrap.bootstrap(tempDir, null);
            ClientConfigChangeListener listener = new ClientConfigChangeListener();

            RecordingAdapter disabled = new RecordingAdapter();
            ClientProxy.autoToolSwapAdapter = disabled.adapter;
            publishClientAndRequest(listener, saveAutoToolSwap(false));
            invokeUpdateChainKeyState(new KeyListener(disabled.adapter), true);
            Assert.assertEquals("配置关闭后按键不得再发 round（配置必须真的到达 adapter）",
                    0, disabled.transport.nonces.size());

            RecordingAdapter enabled = new RecordingAdapter();
            ClientProxy.autoToolSwapAdapter = enabled.adapter;
            publishClientAndRequest(listener, saveAutoToolSwap(true));
            invokeUpdateChainKeyState(new KeyListener(enabled.adapter), true);
            Assert.assertEquals("配置打开后按键必须重新发 round", 1, enabled.transport.nonces.size());
        } finally {
            clearConfigSubscription();
            ConfigTestSupport.reset();
            deleteRecursively(tempDir);
        }
    }

    /** 生命周期清理必须复位已接线 adapter 的 projection（否则清理后仍会重传旧 round）。 */
    @Test
    public void lifecycleCleanupResetsWiredAdapter() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        AutoToolSwapClientAdapter adapter = new AutoToolSwapClientAdapter(true, new BreakCapableGame(), transport);
        ClientProxy.autoToolSwapAdapter = adapter;
        invokeUpdateChainKeyState(new KeyListener(adapter), true);
        Assert.assertEquals(1, transport.nonces.size());

        new ClientConnectionListener().cleanupLifecycleResources("test-cleanup");

        for (int tick = 0; tick <= AutoToolSwapClientReducer.RETRANSMIT_TICKS + 1; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals("清理后不得再重传旧 round", 1, transport.nonces.size());
    }

    /** S2C world gate：陈旧连接、未绑世界、世界解绑都必须使发布失效（行为，用真实 token）。 */
    @Test
    public void worldGateRejectsStaleEndpointOrUnboundWorld() throws Exception {
        ClientAutoToolSwapPacketDispatch.LifecycleGate gate = lifecycleGate();
        Object handler = new Object();
        ClientConnectionLifecycle.Token connectionToken = ClientConnectionLifecycle.connect(handler).token();
        Assert.assertFalse("只有连接、未绑远端世界时不得发布", gate.isActive(connectionToken));

        Object world = new Object();
        ClientConnectionLifecycle.bindWorld(world);
        final ClientConnectionLifecycle.Token worldToken = ClientConnectionLifecycle.captureForConnection(handler);
        Assert.assertTrue("连接与远端世界均当前时必须放行", gate.isActive(worldToken));

        final int[] published = { 0 };
        Runnable publication = new Runnable() {
            @Override
            public void run() {
                published[0]++;
            }
        };
        Assert.assertTrue(gate.publishIfCurrentAndActive(worldToken, publication));
        Assert.assertEquals(1, published[0]);

        // isActive 只反映 token 快照（Netty 入队期判定），真正「当前是否仍可发布」由
        // publishIfCurrentAndActive 在 lifecycle 线性化边界内复核；世界解绑后必须拒绝执行。
        ClientConnectionLifecycle.unbindWorld(world);
        Assert.assertFalse("世界解绑后不得再执行 publication",
                gate.publishIfCurrentAndActive(worldToken, publication));
        Assert.assertEquals("失效 token 不得产生副作用", 1, published[0]);

        ClientConnectionLifecycle.disconnect(handler);
        Assert.assertFalse("断线后旧连接 token 不得再发布",
                gate.publishIfCurrentAndActive(connectionToken, publication));
        Assert.assertEquals("失效 token 不得产生副作用", 1, published[0]);
    }

    /** 薄 adapter：声明字段集合恰为 {reducer, game, transport} 且全 final，reducer 类型形状固定。 */
    @Test
    public void adapterIsThinAndReducerIsTheOnlyMutableBusinessAuthority() throws Exception {
        List<Field> fields = new ArrayList<Field>();
        for (Field field : AutoToolSwapClientAdapter.class.getDeclaredFields()) {
            Assert.assertFalse("adapter 不得持有静态字段: " + field, Modifier.isStatic(field.getModifiers()));
            fields.add(field);
        }
        Assert.assertEquals("薄 adapter 只允许三个依赖字段", 3, fields.size());
        Set<Class<?>> types = new LinkedHashSet<Class<?>>();
        for (Field field : fields) {
            Assert.assertTrue("依赖必须 final: " + field, Modifier.isFinal(field.getModifiers()));
            types.add(field.getType());
        }
        Set<Class<?>> expected = new LinkedHashSet<Class<?>>();
        expected.add(AutoToolSwapClientReducer.class);
        expected.add(AutoToolSwapClientAdapter.GameFacade.class);
        expected.add(AutoToolSwapClientTransport.class);
        Assert.assertEquals("字段类型集合即「无业务状态」的名称无关证据", expected, types);

        Assert.assertTrue("事件基类必须抽象",
                Modifier.isAbstract(nestedType(AutoToolSwapClientReducer.class, "Event").getModifiers()));
        Assert.assertTrue("Effect 必须 final",
                Modifier.isFinal(nestedType(AutoToolSwapClientReducer.class, "Effect").getModifiers()));
        Assert.assertNotNull("RoundContext 必须存在",
                nestedType(AutoToolSwapClientReducer.class, "RoundContext"));
        Assert.assertEquals("协议校验器必须无状态（声明字段数 = 0）", 0,
                club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientProtocolValidator.class
                        .getDeclaredFields().length);
    }

    /** common 分侧：CommonProxy 编译产物常量池不得出现客户端 adapter / 客户端 mixin 类型。 */
    @Test
    public void commonProxyConstantPoolDoesNotLinkNewClientTypes() throws Exception {
        Set<String> classRefs = new LinkedHashSet<String>();
        for (File classFile : CompiledClasses.classFiles()) {
            if (CompiledClasses.relative(classFile).startsWith("club/heiqi/qz_miner/CommonProxy")) {
                classRefs.addAll(CompiledClasses.classRefs(classFile));
            }
        }
        Assert.assertTrue("CommonProxy 编译产物必须真实被扫描（守卫不得空跑）", !classRefs.isEmpty());
        Assert.assertFalse("common 代理不得链接客户端 adapter 类型",
                classRefs.contains("club/heiqi/qz_miner/client/toolswap/AutoToolSwapClientAdapter"));
        Assert.assertFalse("common 代理不得链接客户端 tool swap mixin",
                classRefs.contains("club/heiqi/qz_miner/mixins/client/MixinPlayerControllerMPToolSwap"));
    }

    private static void invokeUpdateChainKeyState(KeyListener listener, boolean pressed) throws Exception {
        Method method = KeyListener.class.getDeclaredMethod("updateChainKeyState", boolean.class);
        method.setAccessible(true);
        method.invoke(listener, Boolean.valueOf(pressed));
    }

    private static void publishClientAndRequest(ClientConfigChangeListener listener, CommittedSnapshot committed)
            throws Exception {
        Method method = ClientConfigChangeListener.class
                .getDeclaredMethod("publishClientAndRequest", CommittedSnapshot.class);
        method.setAccessible(true);
        method.invoke(listener, committed);
    }

    private static CommittedSnapshot saveAutoToolSwap(boolean enabled) throws Exception {
        return ConfigBootstrap.commit(ConfigTestSupport.changes("client.autoToolSwapEnabled", Boolean.valueOf(enabled)));
    }

    private static void clearConfigSubscription() throws Exception {
        Field subscribed = ClientConfigChangeListener.class.getDeclaredField("subscribedListener");
        subscribed.setAccessible(true);
        ClientConfigChangeListener listener = (ClientConfigChangeListener) subscribed.get(null);
        if (listener != null) listener.unregister();
    }

    private static ClientAutoToolSwapPacketDispatch.LifecycleGate lifecycleGate() throws Exception {
        Field field = ClientProxy.class.getDeclaredField("AUTO_TOOL_SWAP_LIFECYCLE_GATE");
        field.setAccessible(true);
        Object gate = field.get(null);
        Assert.assertTrue("AUTO_TOOL_SWAP_LIFECYCLE_GATE 必须是 LifecycleGate: " + gate,
                gate instanceof ClientAutoToolSwapPacketDispatch.LifecycleGate);
        return (ClientAutoToolSwapPacketDispatch.LifecycleGate) gate;
    }

    private static Class<?> nestedType(Class<?> owner, String simpleName) {
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                return nested;
            }
        }
        Assert.fail(owner.getName() + " 缺少嵌套类型 " + simpleName);
        return null;
    }

    private static boolean hasConstructor(Class<?> owner, Class<?> parameter) {
        for (Constructor<?> constructor : owner.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == parameter) {
                return true;
            }
        }
        return false;
    }

    /** 包处理器签名契约：末参必须是 {@code INetHandler}（Netty 线程按 ctx.netHandler 捕获 token）。 */
    private static boolean hasPacketHandler(Class<?> owner, String name) {
        for (Method method : owner.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length > 0
                    && parameters[parameters.length - 1] == net.minecraft.network.INetHandler.class) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    /** 可观测「按键边沿真的到了 adapter」的最小上下文：可破坏、非创造、链激活。 */
    private static final class BreakCapableGame implements AutoToolSwapClientAdapter.GameFacade {
        @Override
        public ToolSwapLightContext captureLightContext(long tick, boolean active) {
            return new ToolSwapLightContext(tick, true, false, false, active, 0);
        }

        @Override
        public boolean isChainKeyPhysicallyDown() {
            return true;
        }
    }

    /** 记录型 transport：非零 nonce 数量即「真的发起了新 round」。 */
    private static final class RecordingTransport implements AutoToolSwapClientTransport {
        private final List<Long> nonces = new ArrayList<Long>();
        private final List<AutoToolSwapIntent> intents = new ArrayList<AutoToolSwapIntent>();

        @Override
        public boolean sendRoundStart(long clientNonce) {
            nonces.add(Long.valueOf(clientNonce));
            return true;
        }

        @Override
        public boolean sendIntent(AutoToolSwapIntent intent) {
            intents.add(intent);
            return true;
        }
    }

    /** 真实 adapter + 记录型 transport 的组合（adapter 为 final，不能继承）。 */
    private static final class RecordingAdapter {
        private final RecordingTransport transport = new RecordingTransport();
        private final AutoToolSwapClientAdapter adapter =
                new AutoToolSwapClientAdapter(true, new BreakCapableGame(), transport);
    }
}
