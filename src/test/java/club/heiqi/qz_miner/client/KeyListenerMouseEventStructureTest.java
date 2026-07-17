package club.heiqi.qz_miner.client;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 守卫滚轮事件使用 Forge 可取消入口并先消费后切换。 */
public class KeyListenerMouseEventStructureTest {

    @Test
    public void forgeMouseEventIsCanceledBeforeModeInspection() throws Exception {
        String source = source();
        Assert.assertTrue(source.contains("MinecraftForge.EVENT_BUS.register(this)"));
        Assert.assertTrue(source.contains("@SubscribeEvent(priority = EventPriority.HIGHEST)"));
        Assert.assertTrue(source.contains("onMouseWheel(MouseEvent event)"));
        Assert.assertTrue(source.indexOf("event.setCanceled(true)")
                < source.indexOf("getSelectedMode()"));
    }

    @Test
    public void legacyMouseInputAndHardCodedSneakAreAbsent() throws Exception {
        String source = source();
        Assert.assertFalse(source.contains("InputEvent.MouseInputEvent"));
        Assert.assertFalse(source.contains("Mouse.getEventDWheel"));
        Assert.assertFalse(source.contains("Keyboard.KEY_LSHIFT"));
        Assert.assertTrue(source.contains("gameSettings.keyBindSneak.getIsKeyPressed()"));
        Assert.assertTrue(source.contains("client.currentScreen == null"));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/client/KeyListener.java").toPath()),
                Charset.forName("UTF-8"));
    }
}
