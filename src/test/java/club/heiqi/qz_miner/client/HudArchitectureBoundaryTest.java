package club.heiqi.qz_miner.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.testsupport.CompiledClasses;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
import club.heiqi.uilib.api.chat.ChatActionRegistration;
import club.heiqi.uilib.api.chat.ChatActionService;
import club.heiqi.uilib.ui.hud.api.HudEditService;
import club.heiqi.uilib.ui.hud.api.HudRegistration;

/**
 * 守卫 Miner 不自行渲染 HUD、HUD 接入只走 UILib 客户端 API（4.9 虚拟窗口契约），
 * 且 HUD 注册与内容刷新只有一个所有者。
 *
 * <p><b>形态</b>：类型边界（不得链接原版渲染类型 / UILib 内部包 / 常驻工具栏 API / 自绘缩放控件）
 * 一律对<b>编译产物常量池</b>判定；注册单点（{@code ClientHudService.register} /
 * {@code QzMinerHudTicker} 构造 / 编辑入口两处注册 / {@code QzMinerHudEditEntry.install}）按
 * <b>方法引用归属类</b>判定；{@code ClientInit} 完成标记与各集成注册的先后按 {@code init}
 * 方法体切片判定；「注册句柄跨断线常驻」用真实句柄 + 真实清理路径断言。</p>
 *
 * <p><b>本轮删除的文本快照与其替代</b>：
 * <ul>
 *   <li>{@code drawString/hudX/hudY/zoomIn/zoomOut/"1:1"} 等通用词与字面量 ⇒ 删除（无法证伪，
 *       且注释/同名局部变量即误报）；同列表里的类型引用改常量池判定。</li>
 *   <li>已删除的旧行式 HUD 快照 API（{@code CompactHud/HudSnapshot*}）与退役 UILib 下界黑名单 ⇒ 删除
 *       （类型已不存在，引用即编译失败；下界真值由 {@code MyModMetadataTest} 用 {@code @Mod} 注解断言）。</li>
 *   <li>编辑入口的公开 builder/预览工厂/默认放置/i18n 接线与 lang 值 ⇒ 删除字面量，同一语义由
 *       {@code QzMinerHudEditEntryTest} 用真实注册表与 API 返回值断言（更强的同语义防线，避免重复）。</li>
 * </ul>
 * </p>
 */
public class HudArchitectureBoundaryTest {

    /** 生产类不得链接的原版 HUD 渲染类型（Miner 只描述内容，渲染归 UILib 宿主）。 */
    private static final String[] FORBIDDEN_RENDERING_TYPES = {
            "net/minecraftforge/client/event/RenderGameOverlayEvent",
            "net/minecraft/client/gui/ScaledResolution",
            "net/minecraft/client/gui/FontRenderer",
    };

    /** UILib HUD 公开 API 包：只允许客户端分侧链接（服务端加载即崩）。 */
    private static final String HUD_API_PACKAGE = "club/heiqi/uilib/ui/hud/api/";
    private static final String CLIENT_HUD_SERVICE = HUD_API_PACKAGE + "ClientHudService";
    private static final String HUD_EDIT_SERVICE = HUD_API_PACKAGE + "HudEditService";
    private static final String HUD_WINDOW_FACTORY = HUD_API_PACKAGE + "HudWindowFactory";
    private static final String CHAT_ACTION_SERVICE = "club/heiqi/uilib/api/chat/ChatActionService";

    /** 常驻工具栏 API：Miner 一律不得使用（缩放只在 UILib 编辑子模式提供）。 */
    private static final String TOOLBAR_SERVICE = HUD_API_PACKAGE + "HudToolbarService";
    private static final String[] FORBIDDEN_PERSISTENT_TOOLBAR_TYPES = {
            TOOLBAR_SERVICE, HUD_API_PACKAGE + "HudToolbarSpec", HUD_API_PACKAGE + "HudToolbarLayer",
    };

    /** UILib 非公开实现包：生产只允许依赖公开 API。 */
    private static final String FORBIDDEN_UILIB_INTERNAL = "club/heiqi/uilib/internal";

    /** 缩放按钮与倍率状态归 UILib 公共层（类型引用级判定）。 */
    private static final String[] FORBIDDEN_SELF_SCALE_TYPES = {
            HUD_API_PACKAGE + "HudScaleState",
            "club/heiqi/uilib/ui/scene/control/SceneButtonPrimitive",
    };

    /**
     * 拖动/放置/夹取数学归 UILib 编辑宿主（类型引用级判定）。
     *
     * <p>原清单里的 {@code SceneEventContext} 已移除：结构化扫描实测它在
     * {@code client/configGUI/objectgroup} 的 3 个面板类里被引用（经 UILib 输入回调的
     * 方法描述符间接出现，源码里并没有该 import），那是使用 UILib 公开输入 API 的正常接线，
     * 不是「自实现编辑/拖动数学」；原断言只是 import 拼写探针，保留它只会过度约束。</p>
     */
    private static final String[] FORBIDDEN_EDIT_MATH_TYPES = {
            HUD_API_PACKAGE + "HudLayoutService",
            HUD_API_PACKAGE + "HudLayoutResolver",
    };

    private static final String CLIENT_PROXY_CLASS = "club/heiqi/qz_miner/ClientProxy.class";
    private static final String EDIT_ENTRY_CLASS = "club/heiqi/qz_miner/client/QzMinerHudEditEntry.class";
    private static final String HUD_WINDOW_CLASS = "club/heiqi/qz_miner/client/QzMinerHudWindow.class";
    private static final String PROXY_SOURCE = "src/main/java/club/heiqi/qz_miner/ClientProxy.java";
    private static final String ZH_LANG = "src/main/resources/assets/qz_miner/lang/zh_CN.lang";
    private static final String EN_LANG = "src/main/resources/assets/qz_miner/lang/en_US.lang";

    /** ClientInit 完成标记：必须排在每个 UILib 集成注册之后（否则宿主拿到半装配状态）。 */
    private static final String CLIENT_INIT_MARKER = "[ClientInit] stage=uilib-integrations-ready";

    private static final String[] CLIENT_INIT_REGISTRATIONS = {
            "AutoToolSwapHooks.install(autoToolSwapAdapter)",
            "chainPreviewController.register()",
            "chainPreviewRenderer.register()",
            "cuboidSelectionRenderer.register()",
            "connectionListener.register()",
            "new ClientConfigChangeListener().register()",
            "ClientHudService.getInstance().register(",
            "QzMinerHudEditEntry.install(",
            "new QzMinerHudTicker(chainStatusHud).register()",
            "new KeyListener(autoToolSwapAdapter).register()",
    };

    private static final String[] EDIT_ACTION_LANG_KEYS = {
            "hud.qz_miner.edit_action.label", "hud.qz_miner.edit_action.tooltip",
    };

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
    }

    @After
    public void clearRegistries() {
        HudEditService.getInstance().clear();
        ChatActionService.getInstance().clear();
    }

    @Test
    public void productionSourcesContainNoLegacyHudRendering() throws Exception {
        List<String> offenders = new ArrayList<String>();
        List<String> scanned = new ArrayList<String>();
        for (File classFile : productionClasses()) {
            scanned.add(CompiledClasses.relative(classFile));
            Set<String> refs = CompiledClasses.classRefs(classFile);
            for (String forbidden : FORBIDDEN_RENDERING_TYPES) {
                for (String ref : refs) {
                    if (ref.equals(forbidden) || ref.startsWith(forbidden + "$")) {
                        offenders.add(CompiledClasses.relative(classFile) + " -> " + ref);
                    }
                }
            }
        }
        Assert.assertTrue("生产编译产物必须真实被扫描（守卫不得空跑），实际 " + scanned.size(),
                scanned.size() > 50);
        Assert.assertEquals("Miner 不得链接原版 HUD 渲染类型（自绘 HUD 回归）",
                Collections.<String>emptyList(), offenders);

        int scannedClasses = assertClassFilesContainNoSectionStyle(
                new File(CompiledClasses.root(), "club/heiqi/qz_miner"));
        Assert.assertTrue("编译产物必须真实被扫描（§ 门禁不得空跑）", scannedClasses > 0);
    }

    @Test
    public void hudApiIsClientOnlyAndRegistrationHasSingleOwner() throws Exception {
        int scanned = 0;
        List<String> offenders = new ArrayList<String>();
        for (File classFile : productionClasses()) {
            String path = CompiledClasses.relative(classFile);
            if (isClientSide(path)) {
                continue;
            }
            scanned++;
            if (CompiledClasses.refs(classFile).hasClassRefUnder(HUD_API_PACKAGE)) {
                offenders.add(path);
            }
        }
        Assert.assertTrue("生产编译产物必须真实被扫描（守卫不得空跑），实际 " + scanned, scanned > 50);
        Assert.assertEquals("UILib HUD API 只允许出现在客户端分侧（含 SidedProxy 客户端类）",
                Collections.<String>emptyList(), offenders);

        Assert.assertEquals("HUD 窗口注册必须单点（ClientProxy.init）",
                Collections.singletonList(CLIENT_PROXY_CLASS),
                ownersOfMethodRef(CLIENT_HUD_SERVICE + "#register"));
        Assert.assertEquals("HUD 刷新驱动的唯一构造位点必须是 ClientProxy",
                Collections.singletonList(CLIENT_PROXY_CLASS),
                ownersOfMethodRef("club/heiqi/qz_miner/client/QzMinerHudTicker#<init>"));
        Assert.assertEquals("生产只允许一个 HUD 窗口工厂实现",
                Collections.singletonList(HUD_WINDOW_CLASS), implementorsOf(HUD_WINDOW_FACTORY));

        String init = clientProxyInit();
        String registrationArguments = JavaSourceSlices.callArgumentsFromPrefixEnd(init,
                "ClientHudService.getInstance().register(", "HUD 窗口注册实参");
        JavaSourceSlices.requireAt(registrationArguments, "HUD 规格必须经公开 builder",
                "HudSpec.builder(");
        JavaSourceSlices.requireAt(registrationArguments, "卡片自绘玻璃、宿主外壳必须关闭", ".chrome(false)");

        for (Field field : KeyListener.class.getDeclaredFields()) {
            String type = field.getType().getName();
            Assert.assertFalse("KeyListener 只更新按键状态，不得持有 HUD 句柄/服务: " + field,
                    type.startsWith("club.heiqi.uilib.ui.hud")
                            || type.equals("club.heiqi.qz_miner.client.QzMinerHudWindow"));
        }
    }

    @Test
    public void productionSourcesUseOnlyPublicUiLibApi() throws Exception {
        int scanned = 0;
        List<String> offenders = new ArrayList<String>();
        for (File classFile : productionClasses()) {
            scanned++;
            String path = CompiledClasses.relative(classFile);
            Set<String> refs = CompiledClasses.classRefs(classFile);
            if (containsType(refs, FORBIDDEN_UILIB_INTERNAL)) {
                offenders.add(path + " -> UILib 内部实现包");
            }
            collectForbiddenTypes(path, refs, FORBIDDEN_SELF_SCALE_TYPES, offenders, "自绘缩放控件");
            collectForbiddenTypes(path, refs, FORBIDDEN_EDIT_MATH_TYPES, offenders, "自实现编辑/拖动数学");
        }
        Assert.assertTrue("生产编译产物必须真实被扫描（守卫不得空跑），实际 " + scanned, scanned > 50);
        Assert.assertEquals(Collections.<String>emptyList(), offenders);
        // 退役 UILib 下界黑名单已删除：下界真值（含「不得停在退役编号」）由 MyModMetadataTest
        // 用 @Mod.dependencies() 全等断言，比在源码里找历史编号字符串强且不会随格式漂移。
    }

    @Test
    public void productionSourcesRegisterNoPersistentHudToolbar() throws Exception {
        List<String> offenders = new ArrayList<String>();
        int scanned = 0;
        for (File classFile : productionClasses()) {
            scanned++;
            String path = CompiledClasses.relative(classFile);
            collectForbiddenTypes(path, CompiledClasses.classRefs(classFile),
                    FORBIDDEN_PERSISTENT_TOOLBAR_TYPES, offenders, "常驻工具栏 API");
            if (CompiledClasses.methodRefs(classFile).contains(TOOLBAR_SERVICE + "#register")) {
                offenders.add(path + " -> HudToolbarService.register");
            }
        }
        Assert.assertTrue("生产编译产物必须真实被扫描（守卫不得空跑），实际 " + scanned, scanned > 50);
        Assert.assertEquals(Collections.<String>emptyList(), offenders);
        // 「编辑目标不得声明 preview toolbarSpec」已删除字面量：同一语义由
        // QzMinerHudEditEntryTest#editTargetCarriesDefaultPlacementAndLeavesScalingToEditLayer
        // 用真实注册表断言 target.getToolbarSpec() == null（直接证伪，不依赖拼写）。
    }

    @Test
    public void hudEditEntryRegistersOnceThroughPublicApi() throws Exception {
        Assert.assertEquals("可编辑目标注册必须单点（QzMinerHudEditEntry）",
                Collections.singletonList(EDIT_ENTRY_CLASS),
                ownersOfMethodRef(HUD_EDIT_SERVICE + "#register"));
        Assert.assertEquals("聊天工具栏动作注册必须单点（QzMinerHudEditEntry）",
                Collections.singletonList(EDIT_ENTRY_CLASS),
                ownersOfMethodRef(CHAT_ACTION_SERVICE + "#register"));
        Assert.assertEquals("编辑入口装配必须单点（ClientProxy.init）",
                Collections.singletonList(CLIENT_PROXY_CLASS),
                ownersOfMethodRef("club/heiqi/qz_miner/client/QzMinerHudEditEntry#install"));

        assertRegistrationHandlesSurviveLifecycleCleanup();

        Map<String, String> zh = loadLang(ZH_LANG);
        Map<String, String> en = loadLang(EN_LANG);
        for (String key : EDIT_ACTION_LANG_KEYS) {
            assertLocalized(zh, key, "zh_CN");
            assertLocalized(en, key, "en_US");
        }
        // builder/预览工厂/默认放置/label-tooltip 走 ClientI18n/编辑意图发布等接线已删除字面量：
        // 同一语义由 QzMinerHudEditEntryTest（真实注册表 + 真实 ChatAction.run()）逐条断言。
    }

    @Test
    public void clientInitReadyMarkerFollowsEveryUiLibIntegrationRegistration() throws Exception {
        String init = clientProxyInit();
        int marker = JavaSourceSlices.requireAt(init, "ClientInit 完成标记", CLIENT_INIT_MARKER);
        for (String anchor : CLIENT_INIT_REGISTRATIONS) {
            int at = JavaSourceSlices.requireAt(init,
                    "集成注册锚点必须存在（否则先后判定会静默失效）", anchor);
            Assert.assertTrue("ClientInit marker 必须排在集成注册之后: " + anchor + "（marker="
                    + marker + ", anchor=" + at + "）", marker > at);
        }
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

    // ------------------------------------------------------------------ 结构判定设施

    /** 生产编译产物（本模组包内）。 */
    private static List<File> productionClasses() {
        List<File> classes = new ArrayList<File>();
        for (File classFile : CompiledClasses.classFiles()) {
            if (CompiledClasses.relative(classFile).startsWith("club/heiqi/qz_miner/")) {
                classes.add(classFile);
            }
        }
        Assert.assertTrue("必须定位到生产编译产物", !classes.isEmpty());
        return classes;
    }

    private static boolean isClientSide(String classPath) {
        // ClientProxy 及其匿名类（ClientProxy$1…）整体属客户端分侧入口。
        return classPath.contains("/client/") || classPath.startsWith("club/heiqi/qz_miner/ClientProxy");
    }

    private static String clientProxyInit() throws Exception {
        return JavaSourceSlices.methodBody(
                JavaSourceSlices.stripCommentsIgnoringStringLiterals(JavaSourceSlices.read(PROXY_SOURCE)),
                "public void init(FMLInitializationEvent event)", "ClientProxy.init");
    }

    /** 引用了该成员方法的生产类（相对路径，排序）。 */
    private static List<String> ownersOfMethodRef(String methodRef) throws Exception {
        List<String> owners = new ArrayList<String>();
        for (File classFile : productionClasses()) {
            if (CompiledClasses.methodRefs(classFile).contains(methodRef)) {
                owners.add(CompiledClasses.relative(classFile));
            }
        }
        Collections.sort(owners);
        return owners;
    }

    /** 直接实现该接口的生产类（相对路径，排序）。 */
    private static List<String> implementorsOf(String interfaceName) throws Exception {
        List<String> implementors = new ArrayList<String>();
        for (File classFile : productionClasses()) {
            if (CompiledClasses.interfaces(classFile).contains(interfaceName)) {
                implementors.add(CompiledClasses.relative(classFile));
            }
        }
        Collections.sort(implementors);
        return implementors;
    }

    private static boolean containsType(Set<String> refs, String internalNamePrefix) {
        for (String ref : refs) {
            if (ref.equals(internalNamePrefix) || ref.startsWith(internalNamePrefix + "/")
                    || ref.startsWith(internalNamePrefix + "$")) {
                return true;
            }
        }
        return false;
    }

    private static void collectForbiddenTypes(String classPath, Set<String> refs, String[] forbidden,
            List<String> offenders, String purpose) {
        for (String type : forbidden) {
            if (refs.contains(type)) {
                offenders.add(classPath + " -> " + purpose + ": " + type);
            }
        }
    }

    /**
     * 注册句柄跨断线/世界切换常驻：跑真实生命周期清理路径后，句柄既不得被 close，
     * 也不得被字段丢弃（原「源码里没有 X.close(」黑名单无法证伪这两点）。
     */
    private static void assertRegistrationHandlesSurviveLifecycleCleanup() throws Exception {
        HudEditService.getInstance().clear();
        ChatActionService.getInstance().clear();
        QzMinerHudWindow window = new QzMinerHudWindow(new ChainClientState(), new ClientPhaseProjection(),
                new QzMinerHudModel.PresentationHeaderSource() {
                    @Override
                    public ChainPreviewPresentationHeader current() {
                        return null;
                    }
                });
        QzMinerHudEditEntry.install(window);

        Map<String, Object> before = registrationHandles();
        Assert.assertEquals("编辑入口必须留下两个常驻注册句柄", 2, before.size());
        for (Object handle : before.values()) {
            Assert.assertNotNull("注册句柄不得为 null", handle);
            Assert.assertFalse("新装句柄不得处于已关闭状态", isClosed(handle));
        }

        new ClientConnectionListener().cleanupLifecycleResources("test-cleanup");

        Map<String, Object> after = registrationHandles();
        Assert.assertEquals("生命周期清理不得丢弃注册句柄", before, after);
        for (Object handle : after.values()) {
            Assert.assertFalse("断线/世界切换不得 close 常驻注册句柄", isClosed(handle));
        }
    }

    /** 按句柄类型读取 QzMinerHudEditEntry 的静态注册句柄（不写死字段名）。 */
    private static Map<String, Object> registrationHandles() throws Exception {
        Map<String, Object> handles = new LinkedHashMap<String, Object>();
        for (Field field : QzMinerHudEditEntry.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (HudRegistration.class.isAssignableFrom(field.getType())
                    || ChatActionRegistration.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                handles.put(field.getName(), field.get(null));
            }
        }
        return handles;
    }

    /** 经公开句柄接口读关闭态（实现类可能包内可见，不能直接反射调用）。 */
    private static boolean isClosed(Object handle) {
        if (handle instanceof HudRegistration) {
            return ((HudRegistration) handle).isClosed();
        }
        if (handle instanceof ChatActionRegistration) {
            return ((ChatActionRegistration) handle).isClosed();
        }
        Assert.fail("未知注册句柄类型: " + handle.getClass());
        return false;
    }

    private static Map<String, String> loadLang(String path) throws IOException {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        for (String line : JavaSourceSlices.read(path).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            entries.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
        }
        Assert.assertTrue("lang 文件必须解析出条目: " + path, !entries.isEmpty());
        return entries;
    }

    private static void assertLocalized(Map<String, String> entries, String key, String lang) {
        Assert.assertTrue(lang + " 缺少 " + key, entries.containsKey(key));
        Assert.assertFalse(lang + " 的 " + key + " 不得为空", entries.get(key).trim().isEmpty());
    }

    private static int assertClassFilesContainNoSectionStyle(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            Assert.assertNotNull("编译产物目录不可读: " + file.getPath(), children);
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
}
