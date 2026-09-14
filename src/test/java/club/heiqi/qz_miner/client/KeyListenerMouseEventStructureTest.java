package club.heiqi.qz_miner.client;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.MouseEvent;

/**
 * 守卫滚轮事件使用 Forge 可取消入口并先消费后切换。
 *
 * <p>形态：注解/签名走反射真实制品；方法体内的先后关系走 methodBody 切片；「不用旧输入通道、
 * 潜行取自真实按键绑定」走编译产物依赖集合。原「硬编码 {@code Keyboard.KEY_LSHIFT}」黑名单已删除——
 * LWJGL 的按键常量是编译期内联常量，字节码里不会留下任何类引用，该断言既无法证伪也无法覆盖。</p>
 */
public class KeyListenerMouseEventStructureTest {

    private static final String KEY_LISTENER_SOURCE = "src/main/java/club/heiqi/qz_miner/client/KeyListener.java";
    private static final String KEY_LISTENER_CLASS = "club/heiqi/qz_miner/client/KeyListener";

    @Test
    public void forgeMouseEventIsCanceledBeforeModeInspection() throws Exception {
        Method onWheel = KeyListener.class.getMethod("onMouseWheel", MouseEvent.class);
        Assert.assertEquals("滚轮处理器必须返回 void", Void.TYPE, onWheel.getReturnType());
        SubscribeEvent annotation = onWheel.getAnnotation(SubscribeEvent.class);
        Assert.assertNotNull("onMouseWheel 必须挂在事件总线上", annotation);
        Assert.assertEquals("滚轮消费必须排在其它处理器之前", EventPriority.HIGHEST, annotation.priority());

        String source = JavaSourceSlices.stripCommentsIgnoringStringLiterals(JavaSourceSlices.read(KEY_LISTENER_SOURCE));
        String register = JavaSourceSlices.methodBody(source, "public void register()", "register");
        JavaSourceSlices.requireAt(register, "register 必须挂到 Forge 可取消总线",
                "MinecraftForge.EVENT_BUS.register(this)");
        JavaSourceSlices.requireAt(register, "register 必须挂到 FML 事件总线",
                "FMLCommonHandler.instance().bus().register(this)");

        String body = JavaSourceSlices.methodBody(source,
                "public void onMouseWheel(MouseEvent event)", "onMouseWheel");
        JavaSourceSlices.assertBefore(body, "setCanceled(true)", "getSelectedMode()", "必须先消费事件再检查模式");
        String consumeArguments = JavaSourceSlices.callArgumentsFromPrefixEnd(body,
                "WheelChordPolicy.shouldConsume(", "shouldConsume 实参");
        Assert.assertTrue("无界面判定必须参与滚轮消费（currentScreen 作为实参）",
                consumeArguments.contains("currentScreen"));
    }

    @Test
    public void legacyMouseInputAndHardCodedSneakAreAbsent() throws Exception {
        java.io.File classFile = CompiledClasses.forInternalName(KEY_LISTENER_CLASS);
        Set<String> classRefs = CompiledClasses.classRefs(classFile);
        Set<String> methodRefs = CompiledClasses.methodRefs(classFile);
        Set<String> fieldRefs = CompiledClasses.fieldRefs(classFile);

        Assert.assertFalse("不得回到 FML 旧输入事件（MouseInputEvent 已废弃）",
                classRefs.contains("cpw/mods/fml/common/gameevent/InputEvent")
                        || classRefs.contains("cpw/mods/fml/common/gameevent/InputEvent$MouseInputEvent"));
        for (String reference : methodRefs) {
            Assert.assertFalse("不得直读 LWJGL 滚轮（绕过 Forge 可取消事件）: " + reference,
                    reference.startsWith("org/lwjgl/input/Mouse#"));
        }
        Assert.assertTrue("潜行必须取自真实按键绑定而不是硬编码按键码",
                methodRefs.contains("net/minecraft/client/settings/KeyBinding#getIsKeyPressed"));
        Assert.assertTrue("潜行判定必须读 gameSettings.keyBindSneak",
                fieldRefs.contains("net/minecraft/client/settings/GameSettings#keyBindSneak"));
    }
}
