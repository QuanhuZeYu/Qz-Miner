package club.heiqi.qz_miner.client.picker;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLModIdMappingEvent;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 事件注册通道契约（防复发：真机崩溃 crash-2026-09-12_08.46.25-client.txt）。
 *
 * <p>事故：{@code BlockPickerRegistryWatcher} 曾把 FML 生命周期事件
 * （{@link FMLLoadCompleteEvent} / {@link FMLModIdMappingEvent}）标注 {@code @SubscribeEvent} 并注册进
 * {@code FMLCommonHandler.instance().bus()}；FML 生命周期事件<b>不是</b>
 * {@link Event} 的子类，{@code EventBus.register} 在遍历方法时抛
 * {@code IllegalArgumentException: ... takes a argument that is not an Event class}，
 * 导致客户端在 {@code ClientProxy.init} 阶段启动即崩。</p>
 *
 * <p>本类把正确通道钉死：FML 生命周期事件只能经 {@code @Mod} 类的 {@code @Mod.EventHandler} 方法
 * → SidedProxy 转发；只有 Forge/FML 的 {@code Event} 子类（如 tick）才允许 {@code @SubscribeEvent}。</p>
 */
public class BlockPickerEventRegistrationContractTest {

    /** 任何 {@code @SubscribeEvent} 方法的参数都必须是 Event 子类（否则注册期即崩）。 */
    @Test
    public void subscribeEventMethodsOnlyAcceptEventSubtypes() {
        int scanned = 0;
        for (Method method : BlockPickerRegistryWatcher.class.getMethods()) {
            if (!method.isAnnotationPresent(SubscribeEvent.class)) {
                continue;
            }
            scanned++;
            Class<?>[] params = method.getParameterTypes();
            Assert.assertEquals("@SubscribeEvent 方法必须恰好一个参数: " + method, 1, params.length);
            Assert.assertTrue(
                    "@SubscribeEvent 的参数必须是 Event 子类（FML 生命周期事件不是）: " + method
                            + " -> " + params[0].getName(),
                    Event.class.isAssignableFrom(params[0]));
        }
        Assert.assertTrue("至少应保留 tick 兜底订阅（否则兜底探测失效）", scanned > 0);
    }

    /** FML 生命周期事件方法存在且<b>没有</b> {@code @SubscribeEvent}。 */
    @Test
    public void fmlLifecycleHandlersAreNotSubscribeEventAnnotated() throws Exception {
        Method loadComplete = BlockPickerRegistryWatcher.class
                .getMethod("onLoadComplete", FMLLoadCompleteEvent.class);
        Method modIdMapping = BlockPickerRegistryWatcher.class
                .getMethod("onModIdMapping", FMLModIdMappingEvent.class);
        Assert.assertFalse("FML 生命周期事件不得标 @SubscribeEvent（注册期会抛异常）",
                loadComplete.isAnnotationPresent(SubscribeEvent.class));
        Assert.assertFalse("FML 生命周期事件不得标 @SubscribeEvent（注册期会抛异常）",
                modIdMapping.isAnnotationPresent(SubscribeEvent.class));
    }

    /** 转发链：{@code @Mod} 类用 {@code @Mod.EventHandler} 接收 FML 生命周期事件。 */
    @Test
    public void modClassForwardsFmlLifecycleEventsToProxy() throws Exception {
        Method loadComplete = MyMod.class.getMethod("onLoadComplete", FMLLoadCompleteEvent.class);
        Method modIdMapping = MyMod.class.getMethod("onModIdMapping", FMLModIdMappingEvent.class);
        Assert.assertTrue("FML 事件必须在 @Mod 类上用 @Mod.EventHandler 接收",
                loadComplete.isAnnotationPresent(Mod.EventHandler.class));
        Assert.assertTrue("FML 事件必须在 @Mod 类上用 @Mod.EventHandler 接收",
                modIdMapping.isAnnotationPresent(Mod.EventHandler.class));
    }

    /**
     * 全仓源码级守卫：任何 {@code @SubscribeEvent} 都不得绑定 FML 生命周期事件包
     * （{@code cpw.mods.fml.common.event.*}）。
     *
     * <p>判据来源：Forge 的 {@code EventBus.register} 要求参数类型是
     * {@code cpw.mods.fml.common.eventhandler.Event} 的子类，而 {@code cpw.mods.fml.common.event} 包下的
     * FML 生命周期事件（{@code FMLLoadCompleteEvent} / {@code FMLModIdMappingEvent} /
     * {@code FMLServerStartingEvent} …）都不继承它。因此「{@code @SubscribeEvent} 方法参数里出现该包」
     * 一律判违约——不管出现在哪个类、哪个包（本轮事故就是这样漏过单类反射守卫的）。</p>
     */
    @Test
    public void noSubscribeEventBindsFmlLifecycleEventPackage() throws IOException {
        Path root = Paths.get("src/main/java");
        Assert.assertTrue("找不到源码根（测试工作目录应为项目根）: " + root.toAbsolutePath(),
                Files.isDirectory(root));
        List<String> violations = new ArrayList<String>();
        final String forbidden = "cpw.mods.fml.common.event.";
        // 源码通常用简名 import，故同时按「FML 事件简名」判定（窗口内出现 FMLxxxEvent 即违约）。
        final java.util.regex.Pattern fmlEventName =
                java.util.regex.Pattern.compile("\\bFML\\w+Event\\b");
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                int index = source.indexOf("@SubscribeEvent");
                while (index >= 0) {
                    int window = Math.min(source.length(), index + 600);
                    String slice = source.substring(index, window);
                    if (slice.contains(forbidden) || fmlEventName.matcher(slice).find()) {
                        violations.add(path.toString().replace('\\', '/') + " @ " + index);
                    }
                    index = source.indexOf("@SubscribeEvent", index + 1);
                }
            }
        }
        Assert.assertTrue("@SubscribeEvent 不得绑定 FML 生命周期事件（会致启动崩溃）: " + violations,
                violations.isEmpty());
    }

    /** tick 事件（Forge Event 子类）继续走 {@code @SubscribeEvent} 事件总线路径。 */
    @Test
    public void tickHandlerStaysOnEventBusPath() throws Exception {
        Method tick = BlockPickerRegistryWatcher.class
                .getMethod("onClientTick", TickEvent.ClientTickEvent.class);
        Assert.assertTrue("tick 是 Event 子类，应继续走 @SubscribeEvent",
                tick.isAnnotationPresent(SubscribeEvent.class));
    }
}