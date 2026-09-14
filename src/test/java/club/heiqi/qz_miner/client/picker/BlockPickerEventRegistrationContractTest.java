package club.heiqi.qz_miner.client.picker;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
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
     * 全仓产物级守卫：任何 {@code @SubscribeEvent} 方法的参数都必须是 Forge/FML {@code Event} 子类
     * （{@code cpw.mods.fml.common.event.*} 下的生命周期事件不是）。
     *
     * <p>判据来源：Forge 的 {@code EventBus.register} 要求参数类型是
     * {@code cpw.mods.fml.common.eventhandler.Event} 的子类，而 {@code cpw.mods.fml.common.event} 包下的
     * FML 生命周期事件（{@code FMLLoadCompleteEvent} / {@code FMLModIdMappingEvent} /
     * {@code FMLServerStartingEvent} …）都不继承它；一旦标注，注册期即抛异常——真机实证为客户端
     * init 阶段直接崩溃（crash-2026-09-12_08.46.25-client.txt）。</p>
     *
     * <p>形态：扫全部生产编译产物的方法注解表与参数描述符，按类型层次判定参数是否为
     * {@code Event} 子类型。不再扫 .java 源码、也不依赖「FML 生命周期事件类名清单」——
     * 生命周期事件改名、挪包、经其它类型间接绑定都在判定范围内（原来的 600 字符窗口源码扫描
     * 既会漏（换包名/换写法），也会误报（注释里的字样））。</p>
     */
    @Test
    public void noSubscribeEventBindsFmlLifecycleEventPackage() throws Exception {
        List<CompiledClasses.SubscriberMethod> subscriptions = CompiledClasses.subscribeEventMethods();
        Assert.assertTrue("必须真的扫到事件总线订阅（守卫不得空跑），实际 " + subscriptions.size(),
                subscriptions.size() >= 8);
        List<String> violations = new ArrayList<String>();
        for (CompiledClasses.SubscriberMethod subscription : subscriptions) {
            List<String> parameters = subscription.method.parameterInternalNames();
            if (parameters.size() != 1) {
                violations.add(subscription + " 的参数个数必须为 1，实际 " + parameters.size());
                continue;
            }
            if (!CompiledClasses.isSubtypeOf(parameters.get(0), CompiledClasses.EVENT_BASE)) {
                violations.add(subscription + " -> " + parameters.get(0) + " 不是 Event 子类");
            }
        }
        Assert.assertTrue("@SubscribeEvent 的参数必须是 Forge Event 子类（FML 生命周期事件不是，注册期即崩）: "
                + violations, violations.isEmpty());
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