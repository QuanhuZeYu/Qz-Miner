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

/**
 * 守卫 Miner 不自行渲染 HUD、HUD 接入只走 UILib 客户端 API（4.9 虚拟窗口契约），
 * 且 HUD 注册与内容刷新只有一个所有者。
 */
public class HudArchitectureBoundaryTest {

    /** 生产源码不得出现的原版 HUD 渲染符号（Miner 只描述内容，渲染归 UILib 宿主）。 */
    private static final String[] FORBIDDEN_RENDERING = {
            "RenderGameOverlayEvent", "ScaledResolution", "FontRenderer", "drawString",
            "drawStringWithShadow", "hudX", "hudY"
    };

    /** 4.9 已删除的旧行式快照协议符号；生产源码不得回退引用。 */
    private static final String[] FORBIDDEN_LEGACY_HUD_API = {
            "CompactHud", "HudSnapshotProvider", "HudSnapshot", "HudLine", "HudSpan", "HudTone"
    };

    /** 4.9 唯一注册入口（锚点）。 */
    private static final String HUD_REGISTRATION_CALL = "ClientHudService.getInstance().register(";

    /** 外接工具栏唯一注册入口（锚点）。 */
    private static final String TOOLBAR_REGISTRATION_CALL = "HudToolbarService.getInstance().register(";

    /** UILib 非公开实现包：生产源码只允许依赖公开 API。 */
    private static final String FORBIDDEN_UILIB_INTERNAL = "club.heiqi.uilib.internal";

    /** 缩放按钮与倍率状态归 UILib 公共层；Miner 不得自绘缩放按钮或直连倍率状态。 */
    private static final String[] FORBIDDEN_SELF_SCALE_CONTROLS = {
            "HudScaleState", "zoomIn", "zoomOut", "\"1:1\"", "SceneButtonPrimitive"
    };

    /** 编辑入口唯一注册锚点（可编辑目标 / 聊天工具栏动作）。 */
    private static final String EDIT_TARGET_REGISTRATION_CALL = "HudEditService.getInstance().register(";
    private static final String CHAT_ACTION_REGISTRATION_CALL = "ChatActionService.getInstance().register(";

    /** 拖动/放置/夹取数学归 UILib 编辑宿主；Miner 不得自行实现编辑链路。 */
    private static final String[] FORBIDDEN_SELF_MADE_EDIT_IMPORTS = {
            "import club.heiqi.uilib.ui.hud.api.HudLayoutService;",
            "import club.heiqi.uilib.ui.hud.api.HudLayoutResolver;",
            "import club.heiqi.uilib.ui.scene.input.SceneEventContext;"
    };

    /** 运行期 UILib 下界：当前交接制品版本 4.9.1（公开编辑契约自 4.9.1 起才有）。 */
    private static final String UILIB_RUNTIME_RANGE = "required-after:qz_uilib@[4.9.1,5.0.0)";

    /**
     * 已退役的 UILib 运行期下界（历史编号）。
     *
     * <p>只列历史真值、不写「当前下界 - 1」这类推导：下界语义是「当前交接制品版本」，
     * 每次定档都需要显式更新本清单；当前下界另由 {@link #UILIB_RUNTIME_RANGE} 正向断言。</p>
     */
    private static final String[] RETIRED_UILIB_RUNTIME_BOUNDS = {
            "required-after:qz_uilib@[4.9.0,5.0.0)",
            "required-after:qz_uilib@[4.10.0,5.0.0)"
    };

    @Test
    public void productionSourcesContainNoLegacyHudRendering() throws Exception {
        File root = new File("src/main/java/club/heiqi/qz_miner");
        int scannedSources = assertJavaSources(root, new SourceAssertion() {
            @Override
            public void check(File file, String source) {
                for (String forbidden : FORBIDDEN_RENDERING) {
                    Assert.assertFalse(file + " must not contain " + forbidden, source.contains(forbidden));
                }
                for (String legacy : FORBIDDEN_LEGACY_HUD_API) {
                    Assert.assertFalse(file + " must not reference removed HUD snapshot API " + legacy,
                            source.contains(legacy));
                }
            }
        });
        Assert.assertTrue("生产源码必须真实被扫描（守卫不得空跑）", scannedSources > 0);
        int scannedClasses = assertClassFilesContainNoSectionStyle(
                new File("build/classes/java/main/club/heiqi/qz_miner"));
        Assert.assertTrue("编译产物必须真实被扫描（§ 门禁不得空跑）", scannedClasses > 0);
    }

    @Test
    public void hudApiIsClientOnlyAndRegistrationHasSingleOwner() throws Exception {
        File root = new File("src/main/java/club/heiqi/qz_miner");
        int scannedSources = assertJavaSources(root, new SourceAssertion() {
            @Override
            public void check(File file, String source) {
                String path = file.getPath().replace('\\', '/');
                if (!path.contains("/client/") && !path.endsWith("/ClientProxy.java")) {
                    Assert.assertFalse(file + " must not reference UILib HUD API",
                            source.contains("club.heiqi.uilib.ui.hud.api"));
                }
                if (!path.endsWith("/ClientProxy.java")) {
                    Assert.assertFalse(file + " must not register a HUD window",
                            source.contains(HUD_REGISTRATION_CALL));
                    Assert.assertFalse(file + " must not own the HUD refresh driver",
                            source.contains("new QzMinerHudTicker("));
                }
            }
        });
        Assert.assertTrue("生产源码必须真实被扫描（守卫不得空跑）", scannedSources > 0);

        String proxy = read(new File(root, "ClientProxy.java"));
        Assert.assertEquals("ClientProxy.init owns exactly one HUD window registration", 1,
                occurrences(proxy, HUD_REGISTRATION_CALL));
        Assert.assertEquals("exactly one client tick driver refreshes the HUD", 1,
                occurrences(proxy, "new QzMinerHudTicker("));
        Assert.assertFalse("HUD registration survives disconnects", proxy.contains("chainStatusHudRegistration.close("));
        Assert.assertTrue("card paints its own glass; host chrome stays off", proxy.contains(".chrome(false)"));

        Assert.assertEquals("exactly one HudWindowFactory in production sources", 1,
                countOccurrences(root, "implements HudWindowFactory"));
        String window = read(new File(root, "client/QzMinerHudWindow.java"));
        Assert.assertTrue("HUD content must be declared as a UILib window factory",
                window.contains("implements HudWindowFactory"));
        Assert.assertTrue("HUD content must be built from scene nodes",
                window.contains("SceneNode") && window.contains("SceneRuntime"));
        Assert.assertTrue("HUD card must use the public liquid glass material API",
                window.contains("UiBackdrop.liquidGlass("));
        Assert.assertTrue("HUD card surface must be bound through the public surface binder",
                window.contains("SceneSurfaceBinder.bind("));

        String keyListener = read(new File(root, "client/KeyListener.java"));
        Assert.assertFalse("KeyListener only updates state", keyListener.contains("HudRegistration"));
        Assert.assertFalse("KeyListener only updates state", keyListener.contains("ClientHudService"));
        Assert.assertFalse("KeyListener only updates state", keyListener.contains("QzMinerHudWindow"));
    }

    @Test
    public void productionSourcesUseOnlyPublicUiLibApi() throws Exception {
        File root = new File("src/main/java/club/heiqi/qz_miner");
        int scannedSources = assertJavaSources(root, new SourceAssertion() {
            @Override
            public void check(File file, String source) {
                Assert.assertFalse(file + " must not depend on UILib internal packages",
                        source.contains(FORBIDDEN_UILIB_INTERNAL));
                for (String forbidden : FORBIDDEN_SELF_SCALE_CONTROLS) {
                    Assert.assertFalse(file + " must not self-implement HUD scale controls: " + forbidden,
                            source.contains(forbidden));
                }
                for (String forbiddenImport : FORBIDDEN_SELF_MADE_EDIT_IMPORTS) {
                    Assert.assertFalse(file + " must not implement HUD edit/drag math itself: "
                            + forbiddenImport, source.contains(forbiddenImport));
                }
                for (String retired : RETIRED_UILIB_RUNTIME_BOUNDS) {
                    Assert.assertFalse(file + " must not declare a retired UILib lower bound: " + retired,
                            source.contains(retired));
                }
            }
        });
        Assert.assertTrue("生产源码必须真实被扫描（守卫不得空跑）", scannedSources > 0);
    }

    @Test
    public void externalToolbarIsRegisteredOnceAndFailureIsolated() throws Exception {
        File root = new File("src/main/java/club/heiqi/qz_miner");
        int scannedSources = assertJavaSources(root, new SourceAssertion() {
            @Override
            public void check(File file, String source) {
                String path = file.getPath().replace('\\', '/');
                if (!path.endsWith("/ClientProxy.java")) {
                    Assert.assertFalse(file + " must not register a HUD toolbar",
                            source.contains(TOOLBAR_REGISTRATION_CALL));
                }
            }
        });
        Assert.assertTrue("生产源码必须真实被扫描（守卫不得空跑）", scannedSources > 0);

        String proxy = read(new File(root, "ClientProxy.java"));
        Assert.assertEquals("ClientProxy.init owns exactly one HUD toolbar registration", 1,
                occurrences(proxy, TOOLBAR_REGISTRATION_CALL));
        Assert.assertTrue("toolbar registration must use the frozen public spec builder",
                proxy.contains("HudToolbarSpec.builder()"));
        Assert.assertFalse("scale controls must stay enabled for the public layer to append -/1:1/+",
                proxy.contains("scaleControls(false)"));
        Assert.assertFalse("toolbar registration survives disconnects",
                proxy.contains("chainStatusHudToolbarRegistration.close("));

        // 注册失败隔离：工具栏注册必须自带 RuntimeException 捕获，异常不得冒泡影响 HUD 主体。
        int registration = proxy.indexOf(TOOLBAR_REGISTRATION_CALL);
        int handler = proxy.indexOf("catch (RuntimeException", registration);
        int marker = proxy.indexOf("[ClientInit] stage=uilib-integrations-ready");
        Assert.assertTrue("toolbar registration must be wrapped in a RuntimeException guard", handler > registration);
        Assert.assertTrue("toolbar failure guard must sit before the ready marker", marker > handler);
    }

    @Test
    public void hudEditEntryRegistersOnceThroughPublicApi() throws Exception {
        File root = new File("src/main/java/club/heiqi/qz_miner");
        String entry = read(new File(root, "client/QzMinerHudEditEntry.java"));

        // 注册单点：两个注册调用只允许出现在编辑入口类里，各一次。
        int scannedSources = assertJavaSources(root, new SourceAssertion() {
            @Override
            public void check(File file, String source) {
                String path = file.getPath().replace('\\', '/');
                if (path.endsWith("/QzMinerHudEditEntry.java")) {
                    return;
                }
                Assert.assertFalse(file + " must not register a HUD edit target",
                        source.contains(EDIT_TARGET_REGISTRATION_CALL));
                Assert.assertFalse(file + " must not register a chat toolbar action",
                        source.contains(CHAT_ACTION_REGISTRATION_CALL));
            }
        });
        Assert.assertTrue("生产源码必须真实被扫描（守卫不得空跑）", scannedSources > 0);
        Assert.assertEquals("可编辑目标注册单点", 1, occurrences(entry, EDIT_TARGET_REGISTRATION_CALL));
        Assert.assertEquals("聊天工具栏动作注册单点", 1, occurrences(entry, CHAT_ACTION_REGISTRATION_CALL));

        // 公开 API 接线：目标走 builder、预览复用窗口工厂与共享工具栏规格、label/tooltip 走 ClientI18n。
        Assert.assertTrue("edit target must use the public builder", entry.contains("HudEditTarget.builder("));
        Assert.assertTrue("preview factory must come from the HUD window", entry.contains(".previewFactory("));
        Assert.assertTrue("edit preview must reuse the closed-state toolbar spec", entry.contains(".toolbarSpec("));
        Assert.assertTrue("edit default placement must reuse the HUD margin",
                entry.contains("HudPlacement.defaultOf(HudAnchor.TOP_LEFT, QzMinerHudWindow.HUD_MARGIN_PX)"));
        Assert.assertTrue("label/tooltip must go through ClientI18n",
                entry.contains("ClientI18n.tr(\"hud.qz_miner.edit_action.label\")")
                        && entry.contains("ClientI18n.tr(\"hud.qz_miner.edit_action.tooltip\")"));
        Assert.assertTrue("edit intent must be published to the public service",
                entry.contains("HudEditService.getInstance().requestEdit("));

        // ClientProxy.init 单点安装；句柄常驻（断线不重注册、不 close）。
        String proxy = read(new File(root, "ClientProxy.java"));
        Assert.assertEquals("ClientProxy.init owns exactly one edit entry install", 1,
                occurrences(proxy, "QzMinerHudEditEntry.install("));
        Assert.assertFalse("edit registrations survive disconnects",
                proxy.contains("editTargetRegistration.close("));
        Assert.assertFalse("edit registrations survive disconnects",
                entry.contains("editTargetRegistration.close("));
        Assert.assertFalse("edit registrations survive disconnects",
                entry.contains("chatActionRegistration.close("));

        // 预览根必须可命中（拖动 handler 挂在预览内容根上）；关闭态 HUD 根保持不可命中。
        String window = read(new File(root, "client/QzMinerHudWindow.java"));
        Assert.assertTrue("preview factory must exist",
                window.contains("public HudWindowFactory previewFactory()"));
        Assert.assertTrue("preview drag surface must be hit-testable", window.contains(".setHitTestable(true)"));

        // 运行期下界必须同步到「当前交接制品版本」（编辑契约自 4.9.1 起才有：4.9.0 会
        // NoClassDefFoundError，已退役的 4.10.0 等旧编号会让声明与交付制品脱节）。
        String myMod = read(new File(root, "MyMod.java"));
        Assert.assertTrue("runtime lower bound must be the handed-off artifact version",
                myMod.contains(UILIB_RUNTIME_RANGE));
        for (String retired : RETIRED_UILIB_RUNTIME_BOUNDS) {
            Assert.assertFalse("runtime lower bound must not stay retired: " + retired,
                    myMod.contains(retired));
        }

        // label/tooltip 中英都要有。
        String zhLang = read(new File("src/main/resources/assets/qz_miner/lang/zh_CN.lang"));
        String enLang = read(new File("src/main/resources/assets/qz_miner/lang/en_US.lang"));
        Assert.assertTrue(zhLang.contains("hud.qz_miner.edit_action.label="));
        Assert.assertTrue(zhLang.contains("hud.qz_miner.edit_action.tooltip="));
        Assert.assertTrue(enLang.contains("hud.qz_miner.edit_action.label="));
        Assert.assertTrue(enLang.contains("hud.qz_miner.edit_action.tooltip="));
    }

    @Test
    public void clientInitReadyMarkerFollowsEveryUiLibIntegrationRegistration() throws Exception {
        String proxy = read(new File("src/main/java/club/heiqi/qz_miner/ClientProxy.java"));
        int marker = proxy.indexOf("[ClientInit] stage=uilib-integrations-ready");
        Assert.assertTrue("ClientInit marker must exist", marker >= 0);

        assertMarkerFollows(proxy, marker, "AutoToolSwapHooks.install(autoToolSwapAdapter)");
        assertMarkerFollows(proxy, marker, "chainPreviewController.register()");
        assertMarkerFollows(proxy, marker, "chainPreviewRenderer.register()");
        assertMarkerFollows(proxy, marker, "cuboidSelectionRenderer.register()");
        assertMarkerFollows(proxy, marker, "connectionListener.register()");
        assertMarkerFollows(proxy, marker, "new ClientConfigChangeListener().register()");
        assertMarkerFollows(proxy, marker, HUD_REGISTRATION_CALL);
        assertMarkerFollows(proxy, marker, TOOLBAR_REGISTRATION_CALL);
        assertMarkerFollows(proxy, marker, "QzMinerHudEditEntry.install(");
        assertMarkerFollows(proxy, marker, "new QzMinerHudTicker(chainStatusHud).register()");
        assertMarkerFollows(proxy, marker, "new KeyListener(autoToolSwapAdapter).register()");
    }

    @Test
    public void classFileSectionStyleCheckOnlyInspectsUtf8Constants() throws Exception {
        assertClassBytesContainNoSectionStyle(classFileWithUtf8("safe", new byte[] { (byte) 0xc2, (byte) 0xa7 }));
        assertClassBytesContainNoSectionStyle(classFileWithLongAndDoubleConstants());

        boolean sectionSignRejected = false;
        try {
            assertClassBytesContainNoSectionStyle(classFileWithUtf8("\u00a7cstyled", new byte[0]));
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

    /**
     * 锚点必须真实存在，且 ClientInit marker 位于其后。
     *
     * <p>不用裸 {@code marker > proxy.indexOf(anchor)}：锚点拼写漂移时 indexOf 返回 -1，
     * 断言会静默通过（守卫失效）。</p>
     */
    private static void assertMarkerFollows(String proxy, int marker, String anchor) {
        int index = proxy.indexOf(anchor);
        Assert.assertTrue("integration anchor must exist: " + anchor, index >= 0);
        Assert.assertTrue("ClientInit marker must follow: " + anchor, marker > index);
    }

    private static int countOccurrences(File file, String needle) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull(children);
            int count = 0;
            for (File child : children) {
                count += countOccurrences(child, needle);
            }
            return count;
        }
        if (!file.getName().endsWith(".java")) {
            return 0;
        }
        return occurrences(read(file), needle);
    }

    private static int assertJavaSources(File file, SourceAssertion assertion) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull(children);
            int count = 0;
            for (File child : children) {
                count += assertJavaSources(child, assertion);
            }
            return count;
        }
        if (file.getName().endsWith(".java")) {
            assertion.check(file, read(file));
            return 1;
        }
        return 0;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int assertClassFilesContainNoSectionStyle(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull(children);
            int count = 0;
            for (File child : children) {
                count += assertClassFilesContainNoSectionStyle(child);
            }
            return count;
        }
        if (file.getName().endsWith(".class")) {
            assertClassBytesContainNoSectionStyle(Files.readAllBytes(file.toPath()));
            return 1;
        }
        return 0;
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
