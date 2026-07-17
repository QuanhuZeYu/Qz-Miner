package club.heiqi.qz_miner.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @Test
    public void clientInitReadyMarkerFollowsEveryUiLibIntegrationRegistration() throws Exception {
        String proxy = read(new File("src/main/java/club/heiqi/qz_miner/ClientProxy.java"));
        int marker = proxy.indexOf("[ClientInit] stage=uilib-integrations-ready");

        Assert.assertTrue("ClientInit marker must exist", marker >= 0);
        Assert.assertTrue(marker > proxy.indexOf("AutoToolSwapHooks.install(autoToolSwapAdapter)"));
        Assert.assertTrue(marker > proxy.indexOf("chainPreviewController.register()"));
        Assert.assertTrue(marker > proxy.indexOf("chainPreviewRenderer.register()"));
        Assert.assertTrue(marker > proxy.indexOf("new ClientConnectionListener().register()"));
        Assert.assertTrue(marker > proxy.indexOf("new ClientConfigChangeListener().register()"));
        Assert.assertTrue(marker > proxy.indexOf("CompactHud.register("));
        Assert.assertTrue(marker > proxy.indexOf("new KeyListener(autoToolSwapAdapter).register()"));
    }

    @Test
    public void classFileSectionStyleCheckOnlyInspectsUtf8Constants() throws Exception {
        assertClassBytesContainNoSectionStyle(classFileWithUtf8("safe", new byte[] { (byte) 0xc2, (byte) 0xa7 }));
        assertClassBytesContainNoSectionStyle(classFileWithLongAndDoubleConstants());

        boolean sectionSignRejected = false;
        try {
            assertClassBytesContainNoSectionStyle(classFileWithUtf8("§cstyled", new byte[0]));
        } catch (AssertionError expected) {
            // 预期：真实 HUD 样式字符串仍应触发门禁。
            sectionSignRejected = true;
        }
        Assert.assertTrue("CONSTANT_Utf8 中的 section sign 必须被阻断", sectionSignRejected);
    }

    @Test
    public void classFileSectionStyleCheckFailsClosedForMalformedConstantPools() throws Exception {
        assertMalformedClassFileRejected(new byte[] {
                (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
                0, 0, 0, 52, 0, 2, 99
        });
        assertMalformedClassFileRejected(new byte[] {
                (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
                0, 0, 0, 52, 0, 2, 1, 0
        });
        assertMalformedClassFileRejected(new byte[] {
                (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
                0, 0, 0, 52, 0, 2, 5
        });
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
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void assertClassFilesContainNoSectionStyle(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull(children);
            for (File child : children) {
                assertClassFilesContainNoSectionStyle(child);
            }
        } else if (file.getName().endsWith(".class")) {
            assertClassBytesContainNoSectionStyle(Files.readAllBytes(file.toPath()));
        }
    }

    /**
     * 只检查 ClassFile 常量池中的 CONSTANT_Utf8，避免将操作码或属性误判为 HUD 样式文本。
     *
     * @param classBytes 待检查的 ClassFile 字节
     * @throws IOException ClassFile 头、常量池 tag 或长度非法时失败关闭
     */
    private static void assertClassBytesContainNoSectionStyle(byte[] classBytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes));
        if (input.readInt() != 0xcafebabe) {
            throw new IOException("invalid ClassFile magic");
        }
        input.readUnsignedShort();
        input.readUnsignedShort();
        int constantPoolCount = input.readUnsignedShort();
        if (constantPoolCount == 0) {
            throw new IOException("invalid constant_pool_count");
        }
        for (int index = 1; index < constantPoolCount; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1:
                    Assert.assertFalse("ClassFile CONSTANT_Utf8 must not contain section-sign HUD styling",
                            input.readUTF().indexOf('\u00a7') >= 0);
                    break;
                case 3:
                case 4:
                    input.readInt();
                    break;
                case 5:
                case 6:
                    input.readLong();
                    index++;
                    if (index >= constantPoolCount) {
                        throw new IOException("long or double constant exceeds constant pool");
                    }
                    break;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    input.readUnsignedShort();
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    input.readUnsignedShort();
                    input.readUnsignedShort();
                    break;
                case 15:
                    input.readUnsignedByte();
                    input.readUnsignedShort();
                    break;
                default:
                    throw new IOException("unsupported constant pool tag: " + tag);
            }
        }
    }

    private static byte[] classFileWithUtf8(String constant, byte[] tail) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0xcafebabe);
        output.writeShort(0);
        output.writeShort(52);
        output.writeShort(2);
        output.writeByte(1);
        output.writeUTF(constant);
        output.write(tail);
        output.close();
        return bytes.toByteArray();
    }

    private static byte[] classFileWithLongAndDoubleConstants() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0xcafebabe);
        output.writeShort(0);
        output.writeShort(52);
        output.writeShort(6);
        output.writeByte(1);
        output.writeUTF("safe");
        output.writeByte(5);
        output.writeLong(1L);
        output.writeByte(6);
        output.writeDouble(1.0d);
        output.close();
        return bytes.toByteArray();
    }

    private static void assertMalformedClassFileRejected(byte[] classBytes) throws Exception {
        try {
            assertClassBytesContainNoSectionStyle(classBytes);
            Assert.fail("损坏 ClassFile 必须失败关闭");
        } catch (IOException expected) {
            // 预期：未知 tag、截断和非法双槽常量池均不允许通过。
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
