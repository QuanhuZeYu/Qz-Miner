package club.heiqi.qz_miner.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 守卫 Miner 不再自行渲染 HUD，且 UILib HUD API 不泄漏到 common/server。 */
public class HudArchitectureBoundaryTest {

    private static final String[] FORBIDDEN_RENDERING = {
            "RenderGameOverlayEvent", "ScaledResolution", "FontRenderer", "drawString",
            "drawStringWithShadow", "hudX", "hudY"
    };

    @Test
    public void productionSourcesContainNoLegacyHudRendering() throws Exception {
        File root = new File("src/main/java/club/heiqi/qz_miner");
        assertJavaSources(root, new SourceAssertion() {
            @Override
            public void check(File file, String source) {
                for (String forbidden : FORBIDDEN_RENDERING) {
                    Assert.assertFalse(file + " must not contain " + forbidden, source.contains(forbidden));
                }
            }
        });
        assertClassFilesContainNoSectionStyle(new File("build/classes/java/main/club/heiqi/qz_miner"));
    }

    @Test
    public void hudApiIsClientOnlyAndRegistrationHasSingleOwner() throws Exception {
        File root = new File("src/main/java/club/heiqi/qz_miner");
        assertJavaSources(root, new SourceAssertion() {
            @Override
            public void check(File file, String source) {
                String path = file.getPath().replace('\\', '/');
                if (!path.contains("/client/") && !path.endsWith("/ClientProxy.java")) {
                    Assert.assertFalse(file + " must not reference UILib HUD API",
                            source.contains("club.heiqi.uilib.ui.hud.api"));
                }
                if (!path.endsWith("/ClientProxy.java")) {
                    Assert.assertFalse(file + " must not register compact HUD", source.contains("CompactHud.register("));
                }
            }
        });
        String proxy = read(new File(root, "ClientProxy.java"));
        Assert.assertEquals("ClientProxy.init owns exactly one registration", 1,
                occurrences(proxy, "CompactHud.register("));
        Assert.assertFalse("HUD registration survives disconnects", proxy.contains("chainStatusHudRegistration.close("));
        String keyListener = read(new File(root, "client/KeyListener.java"));
        Assert.assertFalse("KeyListener only updates state", keyListener.contains("HudRegistration"));
        Assert.assertFalse("KeyListener only updates state", keyListener.contains("CompactHud"));
    }

    private static void assertJavaSources(File file, SourceAssertion assertion) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull(children);
            for (File child : children) {
                assertJavaSources(child, assertion);
            }
        } else if (file.getName().endsWith(".java")) {
            assertion.check(file, read(file));
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    private static void assertClassFilesContainNoSectionStyle(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull(children);
            for (File child : children) {
                assertClassFilesContainNoSectionStyle(child);
            }
        } else if (file.getName().endsWith(".class")) {
            String bytes = new String(Files.readAllBytes(file.toPath()), Charset.forName("ISO-8859-1"));
            Assert.assertFalse(file + " must not contain section-sign HUD styling", bytes.contains("\u00c2\u00a7"));
        }
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private interface SourceAssertion {
        void check(File file, String source);
    }
}
