package club.heiqi.qz_miner.client.configGUI;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import net.minecraft.util.StringTranslate;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.schema.ColorSpec;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.HexColorCodec;
import club.heiqi.config.schema.SectionSpec;
import club.heiqi.config.ui.ConfigScreen;
import club.heiqi.config.ui.ConfigUI;
import club.heiqi.config.ui.editor.Registry;
import club.heiqi.qz_miner.client.configGUI.objectgroup.ObjectGroupEditorFieldRenderer;
import club.heiqi.qz_miner.client.configGUI.objectgroup.ObjectGroupEditorState;
import club.heiqi.qz_miner.client.picker.ObjectGroupPickerRegistration;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.testsupport.LanguageFiles;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 「本地化代理与颜色控件在同一字段上叠加生效」的开屏端到端契约（headless，不开 GL）。
 *
 * <p><b>存在理由</b>：颜色键从私有 HEX 渲染器迁到 UILib 通用颜色字段（schema {@code .color(...)}
 * → {@link club.heiqi.config.ui.field.ColorFieldRenderer}）后，{@link PreviewConfigTooltips} 的代理渲染器
 * 不再持有 delegates 重载，改为「换 helper + 按类型取 {@code FieldRendererRegistry.defaultRegistry()} 分发」。
 * 于是同一字段上叠了两层装饰（本地化换 helper、类型默认选控件），两层各自的行为由 UILib 组件级用例覆盖，
 * 唯独「叠在同一字段上仍然同时成立」只有开屏才看得到：代理若把控件分发吃掉，用户看到的是十进制
 * {@code 4253439}；代理若没接上，helper 退回 schema 文案而不是语言表 tooltip。</p>
 *
 * <p><b>为什么这样开屏</b>：{@code QzMinerConfigGUI} 无法在测试 JVM 里加载——它的父类链
 * （{@code McScreenBridge} → {@code GuiScreen} → {@code Minecraft}）在类初始化时就要求 LWJGL 显示 API，
 * headless 下 {@code NoSuchMethodError: DisplayMode.<init>} 必炸。故按本仓既有口径
 * （{@code ConfigRestoreDefaultsEndToEndTest}）用与 {@code QzMinerConfigGUI.buildSurface()} <b>完全同形的
 * 5 参 {@link ConfigUI#buildScreen}</b> 装配真实 {@link ConfigScreen}（同样的 editor registry 装配、
 * 同样的 {@link PreviewConfigTooltips#install} 调用、空恢复策略），只把平台输入源换成 headless 的 null；
 * 帧驱动与树观察走 UILib 自己的无头入口（{@link ConfigScreen#__doFrameForTest} + 包内探针反射），
 * 不新造 scene 脚手架、不 new 真实 Minecraft 客户端。</p>
 *
 * <p><b>语言表</b>：headless 下 {@code StatCollector} 语言表是空的，{@link club.heiqi.qz_miner.client.ClientI18n}
 * 会把键名原样返回、代理随即回退 schema helper——本用例经 Forge 生产入口 {@link StringTranslate#inject}
 * 把仓内 zh_CN.lang 合并进 MC 语言表（不清表、不动其它键），让生产解析器读到真机同一份文案。</p>
 */
public class PreviewConfigTooltipsColorFieldHeadlessTest {

    /** 语言资源（与 {@code PreviewConfigTooltipsTest} 同一定位口径）。 */
    private static final String LANG_PATH = "assets/qz_miner/lang/zh_CN.lang";

    /** 原版语言资源：headless 语言表的原始口径（{@code StringTranslate} 构造器只加载这一份）。 */
    private static final String VANILLA_LANG_PATH = "/assets/minecraft/lang/en_US.lang";

    /** section 标识名：颜色键所在分类。 */
    private static final String CLIENT_SECTION = "client";

    private static final int CANVAS_WIDTH = 1600;
    private static final int CANVAS_HEIGHT = 900;

    private File tempDir;
    private ConfigScreen screen;

    /** 隔离临时配置目录、语言表与静态态后开屏（语言表必须在装配前注入：helper 在构建期解析）。 */
    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-color-field-").toFile();
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        injectZhLang();
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);
        screen = buildSameShapeSurface(manager);
    }

    /** 回收 screen / 静态态 / 临时目录（语言表由 {@link #restoreVanillaLanguageTable()} 统一还原）。 */
    @After
    public void tearDown() {
        if (screen != null) {
            screen.dispose();
        }
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        delete(tempDir);
    }

    /**
     * 每个颜色字段上两件事同时成立：显示文本是 HEX 规范形态、helper 是 zh_CN.lang 的 tooltip。
     *
     * <p>证伪：① 本地化代理不再把控件交给类型默认渲染器（或颜色渲染器没被选中）⇒ 显示退回十进制
     * {@code 4253439}，断言 1 红；② 代理没接上/被摘掉 ⇒ helper 是 schema 文案，断言 2 红。</p>
     *
     * <p>被测字段集合是动态的（{@link PreviewConfigTooltips#paths()} ∩ schema 的 {@code .color(...)}
     * 声明），不钉死键名：颜色键增删改由该集合自动跟随。</p>
     */
    @Test
    public void colorFieldsShowHexWhileTheirHelpersStayLocalized() throws Exception {
        openClientSection();
        ConfigSchema schema = QzMinerConfigSchema.create();
        Map<String, String> zhLang = LanguageFiles.asMap(LanguageFiles.read(LANG_PATH));
        List<String> colorPaths = colorPathsOf(schema);
        Assert.assertFalse("schema 必须至少有一个受本地化覆盖的颜色键（widget = ColorSpec）", colorPaths.isEmpty());

        boolean tooltipDiffersFromSchemaHelper = false;
        for (String path : colorPaths) {
            FieldSpec spec = schema.field(path);
            int rgb = ((Number) spec.defaultValue()).intValue();
            String canonicalHex = HexColorCodec.format(rgb);
            String decimalForm = String.valueOf(rgb);
            Assert.assertNotEquals("前提：" + path + " 的十进制形态必须与 HEX 规范形态不同，否则本用例无判别力",
                    decimalForm, canonicalHex);

            String tooltipKey = PreviewConfigTooltips.tooltipKey(path);
            String zhTooltip = zhLang.get(tooltipKey);
            Assert.assertNotNull(LANG_PATH + " 缺少 " + tooltipKey, zhTooltip);
            tooltipDiffersFromSchemaHelper |= !zhTooltip.equals(spec.helper());

            // 定位该字段卡片：标题文本 = 字段**生效** label（唯一），卡片 = 标题 → header → card。
            // 生效 label = 代理渲染器 label 语言键命中时的语言表文本，否则 schema 原文（同代理的回退语义）。
            SceneNode title = soleText(root(), effectiveLabel(spec, zhLang));
            SceneNode card = title.__getParent().__getParent();

            // 断言 1：颜色字段的显示文本是 HEX 规范形态，而不是十进制。
            Assert.assertEquals(path + " 显示文本必须是 #RRGGBB 规范形态（十进制 " + decimalForm
                            + " 说明控件没走颜色渲染器）",
                    canonicalHex, shownText(textInputRootOf(card)));

            // 断言 2：同一字段的 helper 已被本地化代理替换为 zh_CN.lang 的 tooltip 文本。
            Assert.assertEquals(path + " helper 必须是本地化代理解析出的 zh_CN.lang tooltip"
                            + "（而不是 schema 文案或裸键名）",
                    zhTooltip, helperTextOf(card));
        }
        Assert.assertTrue("至少一个颜色键的语言表 tooltip 必须不同于 schema helper，否则 helper 断言无判别力",
                tooltipDiffersFromSchemaHelper);
    }

    /**
     * 字段标题的生效文本：{@link PreviewConfigTooltips#labelKey(String)} 在语言表命中时取语言文本，
     * 否则回退 schema 的 {@code label()}（与代理渲染器的回退语义同源，避免本用例把「标题 == 原始键名」
     * 当成契约——label 语言键一旦补齐，原始键名就不再是屏幕上的标题）。
     *
     * @param spec   字段元数据
     * @param zhLang zh_CN.lang 键值表
     * @return 屏幕上的标题文本
     */
    private static String effectiveLabel(FieldSpec spec, Map<String, String> zhLang) {
        String localized = zhLang.get(PreviewConfigTooltips.labelKey(spec.path()));
        return localized == null || localized.isEmpty() ? spec.label() : localized;
    }

    /**
     * 受本地化代理覆盖、且 schema 以 {@code .color(...)} 声明为颜色控件的字段。
     *
     * @param schema 生产 schema
     * @return 颜色键完整 path（{@link PreviewConfigTooltips#paths()} 保序）
     */
    private static List<String> colorPathsOf(ConfigSchema schema) {
        List<String> paths = new ArrayList<String>();
        for (String path : PreviewConfigTooltips.paths()) {
            FieldSpec spec = schema.field(path);
            if (spec != null && spec.widget() instanceof ColorSpec) {
                paths.add(path);
            }
        }
        return paths;
    }

    // ==================================================================
    // 开屏装配（与 QzMinerConfigGUI.buildSurface() 同形）
    // ==================================================================

    /**
     * 与生产 {@code QzMinerConfigGUI.buildSurface()} 同形的配置页装配（见类头说明）。
     *
     * @param manager 生产 ConfigBootstrap 管理器
     * @return 真实 ConfigScreen
     */
    private static ConfigScreen buildSameShapeSurface(ConfigManager manager) {
        // 对象组编辑视图要用本 screen 已冻结的 editor registry（成员 picker 候选源来自它）；
        // buildScreen 先跑 editorRegistryCustomizer、再跑字段 renderer customizer，故用惰性持有者。
        final Registry[] editorRegistry = new Registry[1];
        return ConfigUI.buildScreen(manager, null,
                registry -> {
                    registry.registerPath(ObjectGroupEditorState.PATH,
                            new ObjectGroupEditorFieldRenderer(() -> editorRegistry[0]));
                    PreviewConfigTooltips.install(registry);
                },
                policy -> { },
                editors -> {
                    editorRegistry[0] = editors;
                    ObjectGroupPickerRegistration.register(editors);
                });
    }

    /** 切到颜色键所在分类并跑一帧（帧内完成单槽 panel 切换 + 布局/绑定物化）。 */
    private void openClientSection() throws Exception {
        ConfigSchema schema = QzMinerConfigSchema.create();
        int index = clientSectionIndex(schema);
        SceneRuntime rt = (SceneRuntime) probe(ConfigScreen.class, screen, "__getRuntime");
        @SuppressWarnings("unchecked")
        Signal<Integer> activeSection =
                (Signal<Integer>) probe(ConfigScreen.class, screen, "__getActiveSectionSignal");
        activeSection.set(Integer.valueOf(index));
        rt.flush();
        screen.__doFrameForTest(CANVAS_WIDTH, CANVAS_HEIGHT);
        rt.flush();
        Assert.assertEquals("开屏后必须已挂载 " + CLIENT_SECTION + " 分类面板", index,
                probe(ConfigScreen.class, screen, "__getDisplayedSectionIndex"));
    }

    private static int clientSectionIndex(ConfigSchema schema) {
        List<SectionSpec> sections = schema.sections();
        for (int i = 0; i < sections.size(); i++) {
            if (CLIENT_SECTION.equals(sections.get(i).name())) {
                return i;
            }
        }
        throw new AssertionError("schema 缺少 " + CLIENT_SECTION + " 分类");
    }

    private SceneNode root() throws Exception {
        return (SceneNode) probe(ConfigScreen.class, screen, "__getRoot");
    }

    /** 反射读取 UILib 包内测试探针（与 ConfigRestoreDefaultsEndToEndTest 同一手法）。 */
    private static Object probe(Class<?> type, Object target, String name) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    // ==================================================================
    // 树观察：卡片 / 控件 / helper
    // ==================================================================

    /** 全树中文本恰好等于给定值的唯一节点（多处命中即判失败，避免定位到别的字段）。 */
    private static SceneNode soleText(SceneNode node, String text) {
        List<SceneNode> matches = new ArrayList<SceneNode>();
        collect(node, text, matches);
        Assert.assertEquals("文本 '" + text + "' 在树里必须唯一（否则卡片定位有歧义）", 1, matches.size());
        return matches.get(0);
    }

    private static void collect(SceneNode node, String text, List<SceneNode> out) {
        if (text.equals(node.getText())) {
            out.add(node);
        }
        for (SceneNode child : node.__getChildren()) {
            collect(child, text, out);
        }
    }

    /**
     * 字段卡片直属的 helper 文本节点。
     *
     * <p>卡片结构（UILib {@code FormFieldShell}）：header（状态点 + 标题）→ helper 文本 → 控件 mount 槽
     * → error 占位；标题在 header 内、控件自带子树，故「直属子节点里自有文本非空」的那个就是 helper。</p>
     */
    private static SceneNode helperNodeOf(SceneNode card) {
        List<SceneNode> leaves = new ArrayList<SceneNode>();
        for (SceneNode child : card.__getChildren()) {
            String text = child.getText();
            if (text != null && !text.isEmpty()) {
                leaves.add(child);
            }
        }
        Assert.assertEquals("字段卡片直属叶子文本节点必须恰好是 helper 一个", 1, leaves.size());
        return leaves.get(0);
    }

    private static String helperTextOf(SceneNode card) {
        String text = helperNodeOf(card).getText();
        return text == null ? "" : text;
    }

    /**
     * 卡片内的文本输入框根：结构为 B2 五槽 + 独立占位层（6 子），与
     * {@code ColorFieldRendererEditTextTest.findTextInputRoot} 同一口径（跳过 header）。
     */
    private static SceneNode textInputRootOf(SceneNode card) {
        SceneNode found = null;
        for (int i = 1; i < card.__getChildren().size(); i++) {
            SceneNode child = card.__getChildren().get(i);
            if (child.__getChildren().size() == 6) {
                Assert.assertNull("字段卡片内不得有两个文本输入框根", found);
                found = child;
            }
        }
        Assert.assertNotNull("颜色字段必须挂载文本输入框（五槽 + 占位层 = 6 子）", found);
        return found;
    }

    /** 输入框当前显示文本 = prefix + highlight + suffix（无选区时即全文）。 */
    private static String shownText(SceneNode inputRoot) {
        return text(inputRoot.__getChildren().get(0))
                + text(inputRoot.__getChildren().get(2))
                + text(inputRoot.__getChildren().get(4));
    }

    private static String text(SceneNode node) {
        String value = node.getText();
        return value == null ? "" : value;
    }

    // ==================================================================
    // 语言表注入 / 清理
    // ==================================================================

    /**
     * 还原 MC 语言表：{@link StringTranslate} 是进程级单例，注入会跨用例类生效。
     *
     * <p>headless 的原始口径 = 只含原版 en_US，故按同源资源重建后 {@code replaceWith} 精确还原
     * （与 {@code ObjectGroupEditorDensityHeadlessTest} 的注入/还原同法）。</p>
     */
    @AfterClass
    public static void restoreVanillaLanguageTable() throws Exception {
        InputStream stream = StringTranslate.class.getResourceAsStream(VANILLA_LANG_PATH);
        Assert.assertNotNull("原版语言资源必须存在: " + VANILLA_LANG_PATH, stream);
        try {
            StringTranslate.replaceWith(StringTranslate.parseLangFile(stream));
        } finally {
            stream.close();
        }
    }

    /** 把仓内 zh_CN.lang 合并进 MC 语言表（Forge 的模组语言文件入口，不清表）。 */
    private static void injectZhLang() throws Exception {
        InputStream stream = PreviewConfigTooltipsColorFieldHeadlessTest.class.getClassLoader()
                .getResourceAsStream(LANG_PATH);
        Assert.assertNotNull("语言资源必须存在: " + LANG_PATH, stream);
        try {
            StringTranslate.inject(stream);
        } finally {
            stream.close();
        }
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
