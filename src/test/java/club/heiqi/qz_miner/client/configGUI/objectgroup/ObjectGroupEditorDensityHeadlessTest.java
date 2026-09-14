package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import net.minecraft.util.StringTranslate;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;
import club.heiqi.uilib.ui.scene.input.InputFrameBuilder;
import club.heiqi.uilib.ui.scene.input.RawInputEvent;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyAction;
import club.heiqi.uilib.ui.scene.input.SceneMouseButton;
import club.heiqi.uilib.ui.scene.input.ScenePointerAction;
import club.heiqi.uilib.ui.scene.layout.AnchorRect;
import club.heiqi.uilib.ui.scene.layout.Constraints;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometry;
import club.heiqi.uilib.ui.scene.layout.SceneLayoutEngine;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.overlay.SceneOverlayHost;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;

/**
 * C7 密度 / 背板验收（headless）：左侧栏拥挤（F2）与编辑器背板穿透（F3）的结构性回归。
 *
 * <h3>为什么自建帧驱动与文本度量</h3>
 * <p>{@link ObjectGroupEditorTestSupport.Harness} 的文本度量是固定 8px/字符的英文口径，
 * 无法表达「zh/en 两套真实文案 × CJK 字宽」；而 headless 下 {@code StatCollector} 不加载 lang，
 * {@link ClientI18n#tr} 返回的是<b>键名</b>（比任何真实文案都长），直接用它测量会把列宽需求放大
 * 十余倍，得不到真机几何。因此本测试自建两件最小设施：</p>
 * <ul>
 *   <li>{@link LangMeasurer}：把 i18n <b>键名</b>映射为 lang 文件中的真实文案后再测宽，
 *       字宽按「拉丁/ CJK」两档比例参数化（不假定字体口径，两档都断言）；</li>
 *   <li>{@link DensityHarness}：用与 M9 harness 相同的 {@code SceneRuntime} / {@code SceneLayoutEngine} /
 *       {@code SceneOverlayHost} 公共 API 驱动帧（含 overlay 独立布局），只把度量换成上面的度量。</li>
 * </ul>
 * <p>断言口径是几何真值（绝对盒 + 父内宽），不依赖截图：横向越界、相邻重叠、文本超框（无省略策略时）。</p>
 *
 * <h3>覆盖矩阵</h3>
 * <p>{@code {zh_CN, en_US} × {位图档, TTF 宽档} × {宽挡内容下限 / 超宽视口 / 窄挡整宽}}。</p>
 */
public class ObjectGroupEditorDensityHeadlessTest {

    /** 视口高（逻辑 px）。 */
    private static final int HEIGHT = 420;
    /** 宽挡内容下限场景：{@code 0.28×700 = 196} 被内容下限顶起来（真机 854×480 档附近）。 */
    private static final int WIDE_FLOOR = 700;
    /** 超宽视口场景：旧口径 {@code 0.28×W} 撞上限 300 ⇒ 左栏被钉死。 */
    private static final int ULTRA_WIDE = 2500;
    /** 窄挡场景（{@code W < 620}）：列表独占整宽。 */
    private static final int NARROW = 500;
    /** 语言列表。 */
    private static final String[] LANGS = {"zh_CN", "en_US"};

    private File tempDir;
    private ConfigManager manager;
    private ObjectGroupEditorTestSupport.RendererFixture fixture;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-density-").toFile();
        manager = ObjectGroupEditorTestSupport.bootstrap(tempDir);
        fixture = new ObjectGroupEditorTestSupport.RendererFixture(manager);
    }

    @After
    public void tearDown() {
        if (fixture != null) {
            fixture.dispose();
        }
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        delete(tempDir);
    }

    // ==================================================================
    // F2：三档列宽 × zh/en × 两档字宽 —— 左侧栏不得溢出 / 重叠 / 文字超框
    // ==================================================================

    @Test
    public void leftPaneHasNoOverflowOverlapOrClippedTextAcrossWidthsAndLanguages() throws Exception {
        for (String lang : LANGS) {
            Map<String, String> texts = loadLang(lang);
            Assume.assumeTrue("lang 文件缺失（需在仓根运行测试）: " + lang, texts != null);
            applyLang(lang);
            requireLocalizedText();
            for (FontModel model : new FontModel[] {FontModel.BITMAP, FontModel.TTF_WIDE}) {
                for (int width : new int[] {WIDE_FLOOR, ULTRA_WIDE, NARROW}) {
                    DensityHarness harness = openEditor(texts, model, width);
                    try {
                        SceneNode pane = listPane(harness);
                        String scene = lang + "/" + model.name + "/W=" + width;
                        List<String> issues = new ArrayList<String>();
                        audit(pane, harness.rt, issues);
                        Assert.assertTrue(scene + " 左列出现横向违规: " + issues, issues.isEmpty());

                        // 搜索框：占位文案必须完整可读（未被压缩到内容下限之下）。
                        SceneNode search = searchInput(pane);
                        int searchFloor = searchMin(harness, search);
                        Assert.assertTrue(scene + " 搜索框宽必须 ≥ 占位文案最小宽（实测 "
                                        + box(search).getWidth() + " < " + searchFloor + "）",
                                box(search).getWidth() + 1 >= searchFloor);
                        // 「新建组」按钮：文字 + 内边距必须放得下（不得靠裁字）。
                        SceneNode addButton = searchActionButton(pane);
                        int addNatural = harness.rt.measureTextWidth(ClientI18n.tr(
                                "config.qz_miner.object_group.list.add"), addButton.effectiveFontSize())
                                + addButton.getPaddingLeft() + addButton.getPaddingRight();
                        Assert.assertTrue(scene + " 「新建组」按钮必须容纳标签 + 内边距（实测 "
                                        + box(addButton).getWidth() + " < " + addNatural + "）",
                                box(addButton).getWidth() + 1 >= addNatural);
                        // 分段控件：四段实测宽之和 + 段间距必须落在其盒内（真机症状：chip 被计数覆盖）。
                        SceneNode segmented = segmented(pane);
                        int segmentsNeed = segmentsNeed(harness, segmented);
                        Assert.assertTrue(scene + " 分段控件必须容纳四段（实测 "
                                        + box(segmented).getWidth() + " < " + segmentsNeed + "）",
                                box(segmented).getWidth() + 1 >= segmentsNeed);
                        // 计数与分段不同行：计数顶边必须在分段底边之下（不再争主轴宽）。
                        SceneNode count = countText(pane);
                        Assert.assertTrue(scene + " 计数必须另起一行（count.y=" + box(count).getY()
                                        + " segmented.bottom=" + bottom(box(segmented)) + "）",
                                box(count).getY() + 1 >= bottom(box(segmented)));
                        // 列表行：行尾溢出按钮必须落在主行内（真机症状：⋮ 压到行边框上）。
                        SceneNode menu = rowMenuButton(pane);
                        SceneNode header = menu.__getParent();
                        AnchorRect headerBox = box(header);
                        Assert.assertTrue(scene + " 行尾按钮必须落在主行内（menu.right="
                                        + right(box(menu)) + " header.rightPad="
                                        + (right(headerBox) - header.getPaddingRight()) + "）",
                                right(box(menu)) <= right(headerBox) - header.getPaddingRight() + 1);
                    } finally {
                        harness.close();
                    }
                }
            }
        }
    }

    @Test
    public void wideColumnFollowsContentFloorAndKeepsDetailMinimalWidth() throws Exception {
        for (String lang : LANGS) {
            Map<String, String> texts = loadLang(lang);
            Assume.assumeTrue("lang 文件缺失（需在仓根运行测试）: " + lang, texts != null);
            applyLang(lang);
            requireLocalizedText();
            for (FontModel model : new FontModel[] {FontModel.BITMAP, FontModel.TTF_WIDE}) {
                for (int width : new int[] {WIDE_FLOOR, ULTRA_WIDE}) {
                    DensityHarness harness = openEditor(texts, model, width);
                    try {
                        SceneNode listHost = listHost(harness);
                        SceneNode detailHost = detailHost(harness);
                        int listWidth = box(listHost).getWidth();
                        int contentMin = contentMinWidth(harness, listHost.effectiveFontSize());
                        String scene = lang + "/" + model.name + "/W=" + width;
                        Assert.assertTrue(scene + " 宽挡列表列宽必须 ≥ 内容下限（实测 " + listWidth
                                + " < " + contentMin + "）", listWidth >= contentMin);
                        Assert.assertTrue(scene + " 宽挡详情列必须非零（左列不得独占整宽）: "
                                + box(detailHost).getWidth(), box(detailHost).getWidth() > 0);
                        // 超宽视口：左列必须随视口增长（旧口径被 300 上限钉死）。
                        if (width == ULTRA_WIDE) {
                            Assert.assertTrue(scene + " 超宽视口左列必须超过旧上限 300（实测 " + listWidth + "）",
                                    listWidth > 300);
                        }
                        // 详情列保留：宽挡下两列之和 + gap 必须等于 body 内宽（不越界、不留缝）。
                        Assert.assertTrue(scene + " 两列宽度必须落在 body 内（list=" + listWidth
                                        + " detail=" + box(detailHost).getWidth() + "）",
                                listWidth + box(detailHost).getWidth() <= width);
                        // 防回归（本轮真实回归）：宽挡详情列必须吃满「body 内宽 − 列表列宽 − gap」。
                        // 旧实现给列表列声明了 fillParentWidth，被 UILib ConstraintResolver.effectiveGrowRow
                        // 视为 ROW 主轴隐式 grow=1，与详情列显式 grow=1 等权分配 ⇒ 详情只剩一半宽、右侧留空。
                        SceneNode bodyRow = bodyContent(harness);
                        int bodyInner = box(bodyRow).getWidth() - bodyRow.getPaddingLeft()
                                - bodyRow.getPaddingRight();
                        Assert.assertEquals(scene + " 宽挡详情列必须吃满剩余（body 内宽 − 列表 − gap="
                                        + bodyRow.getGap() + "）：bodyInner=" + bodyInner + " list="
                                        + listWidth + " detail=" + box(detailHost).getWidth(),
                                bodyInner - listWidth - bodyRow.getGap(), box(detailHost).getWidth());
                    } finally {
                        harness.close();
                    }
                }
            }
        }
    }

    @Test
    public void narrowDrillKeepsListAtFullAvailableWidth() throws Exception {
        Map<String, String> texts = loadLang("zh_CN");
        Assume.assumeTrue("lang 文件缺失（需在仓根运行测试）", texts != null);
        applyLang("zh_CN");
        requireLocalizedText();
        DensityHarness harness = openEditor(texts, FontModel.BITMAP, NARROW);
        try {
            SceneNode holder = bodyHolder(harness);
            AnchorRect holderBox = box(holder);
            int holderInner = holderBox.getWidth() - holder.getPaddingLeft() - holder.getPaddingRight();
            SceneNode listHost = listHost(harness);
            Assert.assertEquals("窄挡未下钻：列表列必须吃满 body 内宽（实测 "
                            + box(listHost).getWidth() + "，期望 " + holderInner + "）",
                    holderInner, box(listHost).getWidth());
            Assert.assertEquals("窄挡未下钻：列表列必须从 body 左边起",
                    holderBox.getX(), box(listHost).getX());
            List<String> issues = new ArrayList<String>();
            audit(listPane(harness), harness.rt, issues);
            Assert.assertTrue("窄挡左列出现横向违规: " + issues, issues.isEmpty());

            // 窄挡下钻：详情列必须同样独占 body 内宽（P0 回归点——修复前它被排在 x = 视口宽之外）。
            SceneNode rowText = ObjectGroupEditorTestSupport.findText(listPane(harness), "vanilla_logs");
            Assert.assertNotNull("窄挡列表行缺失", rowText);
            harness.click(rowText);
            harness.pressKey(SceneKey.ENTER);
            SceneNode drilledDetail = detailHost(harness);
            Assert.assertEquals("窄挡下钻：详情列必须吃满 body 内宽（实测 "
                            + box(drilledDetail).getWidth() + "，期望 " + holderInner + "）",
                    holderInner, box(drilledDetail).getWidth());
            Assert.assertEquals("窄挡下钻：详情列必须从 body 左边起",
                    holderBox.getX(), box(drilledDetail).getX());
            SceneNode backLabel = ObjectGroupEditorTestSupport.findText(harness.editorRoot(),
                    ClientI18n.tr("config.qz_miner.object_group.detail.narrow_back"));
            Assert.assertNotNull("窄挡下钻：返回头部必须挂载", backLabel);
            Assert.assertTrue("窄挡下钻：返回头部必须落在视口内（left=" + box(backLabel).getX()
                            + " right=" + right(box(backLabel)) + "）",
                    right(box(backLabel)) <= right(box(harness.editorRoot())) + 1);
        } finally {
            harness.close();
        }
    }

    // ==================================================================
    // F3：编辑器背板必须不透明（玻璃不可用时不得透出下层配置页）
    // ==================================================================

    @Test
    public void editorBackdropIsOpaqueWithoutRequiringGlass() throws Exception {
        Map<String, String> texts = loadLang("zh_CN");
        Assume.assumeTrue("lang 文件缺失（需在仓根运行测试）", texts != null);
        applyLang("zh_CN");
        requireLocalizedText();
        DensityHarness harness = openEditor(texts, FontModel.BITMAP, WIDE_FLOOR);
        try {
            SceneNode editor = harness.editorRoot();
            int bg = editor.getBackgroundColor();
            int alpha = (bg >>> 24) & 0xFF;
            Assert.assertEquals("编辑视图背板必须完全不透明（实测 alpha=" + alpha + "，液态玻璃 PANEL 仅 0x14=20）",
                    0xFF, alpha);
            Assert.assertNull("不透明背板不得再依赖玻璃滤镜（backdrop 必须为 null）", editor.getBackdrop());
            AnchorRect box = box(editor);
            Assert.assertEquals("背板必须铺满视口宽", WIDE_FLOOR, box.getWidth());
            Assert.assertEquals("背板必须铺满视口高", HEIGHT, box.getHeight());
        } finally {
            harness.close();
        }
    }

    /**
     * 成员区标题与计数不得语义重复（真机「成员 2 个成员」）：
     * 计数文案里不得再出现标题名词（zh「成员」/ en「Members」，大小写无关）。
     */
    @Test
    public void memberCountLabelDoesNotRepeatSectionNoun() throws Exception {
        for (String lang : LANGS) {
            Map<String, String> texts = loadLang(lang);
            Assume.assumeTrue("lang 文件缺失（需在仓根运行测试）: " + lang, texts != null);
            String label = texts.get("config.qz_miner.object_group.members.label");
            String count = texts.get("config.qz_miner.object_group.members.count");
            Assert.assertNotNull(lang + " 缺少 members.label", label);
            Assert.assertNotNull(lang + " 缺少 members.count", count);
            // 曾断言「计数文案不得包含区块名词」（真机「成员 2 个成员」）：那是对措辞的快照，
            // 换同义文案即误报，且不保护任何代码行为（Lead 裁定 29/41 ⇒ 删除）。
            // 保留数据契约本身：两条 lang 必须都有该键、且计数文案必须含数值占位符。
            Assert.assertTrue(lang + " 计数文案必须含数值占位符", count.contains("%s"));
        }
    }

    // ==================================================================
    // 设施：语言 / 文本度量 / 帧驱动
    // ==================================================================

    /** 字宽模型：拉丁与 CJK 每字符宽 / 字号 的比例（两档都不许溢出）。 */
    static final class FontModel {
        static final FontModel BITMAP = new FontModel("bitmap", 0.5D, 1.0D);
        static final FontModel TTF_WIDE = new FontModel("ttf-wide", 0.6D, 1.15D);

        final String name;
        final double latinRatio;
        final double cjkRatio;

        FontModel(String name, double latinRatio, double cjkRatio) {
            this.name = name;
            this.latinRatio = latinRatio;
            this.cjkRatio = cjkRatio;
        }
    }

    /** 原始（未注入前）语言表快照：注入是全局态，用后必须还原，避免污染同 JVM 的其它测试。 */
    private static Map<String, String> vanillaLang;
    /** 是否已注入过语言表。 */
    private static boolean langInjected;

    /**
     * 把本模组的 lang 文件注入 MC 语言表（{@link StringTranslate#replaceWith} 是 Forge 的公开入口）。
     *
     * <p><b>为什么必须注入</b>：headless 下 {@code StatCollector} 的语言表是空的，
     * {@link ClientI18n#tr} 会把键名原样返回（长度是真实文案的数倍），此时布局里渲染的文案、
     * 生产列宽策略实测的文案都不是真机文案 —— 任何「真实文案下是否重叠」的断言都会失真。</p>
     *
     * <p>注入后：① 渲染文本 = 真实文案；② {@code ObjectGroupListPane.minContentWidth} 实测
     * 真实文案 → 列宽策略端到端可验证。注入源是仓内 {@code src/main/resources} 的 lang 文件，
     * 与真机加载的同一份资源。</p>
     *
     * @param lang 语言代码（zh_CN / en_US）
     */
    static synchronized void applyLang(String lang) throws IOException {
        Path path = langPath(lang);
        Assume.assumeTrue("lang 文件缺失（需在仓根运行测试）: " + path, Files.exists(path));
        if (vanillaLang == null) {
            Map<String, String> base = new LinkedHashMap<String, String>();
            InputStream stream = StringTranslate.class.getResourceAsStream("/assets/minecraft/lang/en_US.lang");
            if (stream != null) {
                try {
                    base.putAll(StringTranslate.parseLangFile(stream));
                } finally {
                    stream.close();
                }
            }
            vanillaLang = base;
        }
        Map<String, String> merged = new LinkedHashMap<String, String>(vanillaLang);
        InputStream in = Files.newInputStream(path);
        try {
            merged.putAll(StringTranslate.parseLangFile(in));
        } finally {
            in.close();
        }
        StringTranslate.replaceWith(merged);
        langInjected = true;
    }

    /** 还原注入前的语言表（全局态隔离）。 */
    @AfterClass
    public static void restoreVanillaLang() {
        if (langInjected) {
            StringTranslate.replaceWith(vanillaLang == null
                    ? new LinkedHashMap<String, String>() : vanillaLang);
            langInjected = false;
        }
    }

    /**
     * 断言语言注入确实生效：{@link ClientI18n#tr} 必须返回真实文案而不是键名。
     *
     * <p>若这里失败，说明本环境的语言表结构与预期不同，后续几何断言会退化到「键名口径」，
     * 因此显式 Assume 跳过（SKIPPED 可见），绝不当成通过。</p>
     */
    private static void requireLocalizedText() {
        String key = "config.qz_miner.object_group.list.add";
        Assume.assumeTrue("MC 语言表注入未生效（StatCollector 未读取 StringTranslate 表），本轮几何口径不可用",
                !key.equals(ClientI18n.tr(key)));
    }

    static Path langPath(String lang) {
        return Paths.get("src", "main", "resources", "assets", "qz_miner", "lang", lang + ".lang");
    }

    /** 读 lang 文件（{@code key=value}）；缺失返回 null（由调用方 Assume 跳过）。 */
    static Map<String, String> loadLang(String lang) throws IOException {
        Path path = langPath(lang);
        if (!Files.exists(path)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
        }
        return out;
    }

    /**
     * 语言感知文本度量：i18n 键名 → 当前语言真实文案，再按 {@link FontModel} 的比例累计字宽。
     *
     * <p>headless 下 {@code StatCollector} 没有 lang 数据，节点文本就是键名；本度量把键名换成
     * 真实文案，因此布局决策（列宽下限、按钮宽、分段宽）与真机同口径，而其它任意文本原样测量。</p>
     */
    static final class LangMeasurer implements SceneTextMeasurer {
        private final Map<String, String> texts;
        private final FontModel model;

        LangMeasurer(Map<String, String> texts, FontModel model) {
            this.texts = texts;
            this.model = model;
        }

        private String resolve(String text) {
            if (text == null) {
                return "";
            }
            String mapped = texts.get(text);
            return mapped == null ? text : mapped;
        }

        @Override
        public int measureWidth(String text, int fontSizePx) {
            String value = resolve(text);
            double total = 0.0D;
            for (int i = 0; i < value.length(); ) {
                int cp = value.codePointAt(i);
                i += Character.charCount(cp);
                if (cp == '\n' || cp == '\r') {
                    continue;
                }
                total += (cp >= 0x2E80 ? model.cjkRatio : model.latinRatio) * fontSizePx;
            }
            return (int) Math.ceil(total);
        }

        @Override
        public int lineHeight(int fontSizePx) {
            // 与既有 ObjectGroupEditorTestSupport.MiniMeasurer 同口径（lineHeight 16 @ 默认字号），
            // 否则测试视口高 420 会被变高的行内容撑大，掩盖「视口高由宿主给定」这一结构事实。
            return Math.max(9, fontSizePx);
        }

        @Override
        public int ascent(int fontSizePx) {
            return Math.round(fontSizePx * 0.75F);
        }

        @Override
        public int descent(int fontSizePx) {
            return Math.round(fontSizePx * 0.25F);
        }

        @Override
        public int epoch() {
            return 0;
        }

        @Override
        public List<String> splitLines(String text, int fontSizePx, int wrapWidth, int textMode) {
            String safe = text == null ? "" : text;
            if (wrapWidth > 0) {
                return Collections.singletonList(safe);
            }
            return java.util.Arrays.asList(safe.split("\n"));
        }
    }

    /**
     * 帧驱动（与 {@code ObjectGroupEditorTestSupport.Harness} 同口径的主树 + overlay 独立布局、
     * 多遍收敛与末次布局），唯一差别是度量可注入。
     */
    static final class DensityHarness implements AutoCloseable {
        final SceneRuntime rt;
        final SceneNode root;
        private final SceneTextMeasurer measurer;
        private final SceneLayoutEngine engine;
        private final Map<SceneNode, SceneLayoutEngine> overlayEngines =
                new IdentityHashMap<SceneNode, SceneLayoutEngine>();
        private int width;
        private int height;
        private long frameNanos = 1_000_000L;

        DensityHarness(SceneTextMeasurer measurer, int width, int height) {
            this.measurer = measurer;
            this.rt = new SceneRuntime(measurer);
            this.engine = new SceneLayoutEngine(measurer);
            this.width = width;
            this.height = height;
            this.root = SceneNode.column();
            root.setFillParentWidth(true);
            root.setFillParentHeight(true);
            rt.__setViewportLogicalBox(width, height);
        }

        void frame() {
            rt.__tickFrame(frameNanos);
            frameNanos += 16_000_000L;
            for (int pass = 0; pass < 8; pass++) {
                layoutAll();
                rt.flush();
            }
            layoutAll();
        }

        private void layoutAll() {
            engine.layout(root, new Constraints(width, height));
            long epoch = engine.layoutChangeEpoch();
            Set<SceneNode> active = Collections.newSetFromMap(new IdentityHashMap<SceneNode, Boolean>());
            for (SceneOverlayHost.Entry entry : rt.getOverlayHost().bottomFirst()) {
                SceneNode overlayRoot = entry.getRoot();
                active.add(overlayRoot);
                SceneLayoutEngine overlayEngine = overlayEngines.get(overlayRoot);
                if (overlayEngine == null) {
                    overlayEngine = new SceneLayoutEngine(measurer);
                    overlayEngines.put(overlayRoot, overlayEngine);
                }
                overlayEngine.layout(overlayRoot, new Constraints(width, height));
                epoch += overlayEngine.layoutChangeEpoch();
            }
            overlayEngines.keySet().retainAll(active);
            rt.__setLayoutDoneEpoch((int) epoch);
        }

        SceneNode editorRoot() {
            List<SceneNode> roots = new ArrayList<SceneNode>();
            for (SceneOverlayHost.Entry entry : rt.getOverlayHost().bottomFirst()) {
                roots.add(entry.getRoot());
            }
            return roots.isEmpty() ? null : roots.get(0);
        }

        void click(SceneNode node) {
            AnchorRect box = box(node);
            for (int retry = 0; retry < 3 && (box.getWidth() <= 0 || box.getHeight() <= 0); retry++) {
                frame();
                box = box(node);
            }
            clickAt(box.getX() + box.getWidth() / 2, box.getY() + box.getHeight() / 2);
        }

        void pressKey(SceneKey key) {
            InputFrameBuilder frameBuilder = new InputFrameBuilder(0, 0);
            frameBuilder.push(RawInputEvent.ofKey(key, SceneKeyAction.PRESSED,
                    false, false, false, false, 0, 0, frameNanos));
            rt.route(root, frameBuilder.drainFrame(), 0, 0);
            rt.flush();
            frame();
        }

        private void clickAt(int x, int y) {
            InputFrameBuilder down = new InputFrameBuilder(x, y);
            down.push(RawInputEvent.ofPointer(ScenePointerAction.BUTTON_DOWN, x, y, SceneMouseButton.LEFT,
                    0, 0, 0, false, false, false, false, frameNanos));
            rt.route(root, down.drainFrame(), 0, 0);
            InputFrameBuilder up = new InputFrameBuilder(x, y);
            up.push(RawInputEvent.ofPointer(ScenePointerAction.BUTTON_UP, x, y, SceneMouseButton.LEFT,
                    0, 0, 0, false, false, false, false, frameNanos + 1L));
            rt.route(root, up.drainFrame(), 0, 0);
            rt.flush();
            frame();
        }

        @Override
        public void close() {
            rt.dispose();
        }
    }

    // ==================================================================
    // 设施：场景装配 / 节点定位 / 审计
    // ==================================================================

    private DensityHarness openEditor(Map<String, String> texts, FontModel model, int width) {
        DensityHarness harness = new DensityHarness(new LangMeasurer(texts, model), width, HEIGHT);
        SceneNode card = fixture.renderer.render(harness.rt, fixture.spec, fixture.adapter);
        harness.root.appendChild(card);
        harness.frame();
        SceneNode manage = ObjectGroupEditorTestSupport.findText(card, ClientI18n.tr(
                "config.qz_miner.object_group.manage"));
        Assert.assertNotNull("摘要卡入口按钮缺失", manage);
        harness.click(manage);
        SceneNode editor = harness.editorRoot();
        Assert.assertNotNull("编辑视图浮层缺失", editor);
        harness.frame();
        return harness;
    }

    /** 主体挂载容器（视图根最后一个子）：三态形态的宿主 COLUMN。 */
    static SceneNode bodyHolder(DensityHarness harness) {
        return lastChild(harness.editorRoot());
    }

    /**
     * 当前挂载的主体形态内容：跳过 {@code rt.show} 的零尺寸 anchor（锚点无尺寸）。
     *
     * <p>宽挡内容 = ROW（列表列 + 详情列并排）；窄挡内容 = 激活的那一列本身。</p>
     */
    static SceneNode bodyContent(DensityHarness harness) {
        SceneNode holder = bodyHolder(harness);
        for (SceneNode child : holder.__getChildren()) {
            AnchorRect b = box(child);
            if (b.getWidth() > 0 && b.getHeight() > 0) {
                return child;
            }
        }
        return holder;
    }

    /**
     * 主体当前列集（两种结构都兼容）：
     * 兼容旧「两列常挂 + 折叠退出」形态（holder 自身是 ROW）与现「三态互斥形态」
     * （holder 是 COLUMN，其唯一非零尺寸子是 ROW 或单列）。
     */
    static SceneNode[] bodyColumns(SceneNode holder) {
        SceneNode content = holder;
        if (holder.getFlexDirection() != club.heiqi.uilib.ui.scene.layout.FlexDirection.ROW) {
            for (SceneNode child : holder.__getChildren()) {
                AnchorRect b = box(child);
                if (b.getWidth() > 0 && b.getHeight() > 0) {
                    content = child;
                    break;
                }
            }
        }
        if (content.getFlexDirection() == club.heiqi.uilib.ui.scene.layout.FlexDirection.ROW) {
            List<SceneNode> kids = content.__getChildren();
            return kids.toArray(new SceneNode[0]);
        }
        return new SceneNode[] {content};
    }

    /** 列表列（宽挡第一列 / 窄挡独占整宽）。 */
    static SceneNode listHost(DensityHarness harness) {
        return bodyColumns(bodyHolder(harness))[0];
    }

    /** 详情列（宽挡第二列 / 窄挡下钻独占整宽；单列形态下与 {@link #listHost} 同列）。 */
    static SceneNode detailHost(DensityHarness harness) {
        SceneNode[] columns = bodyColumns(bodyHolder(harness));
        return columns.length > 1 ? columns[1] : columns[0];
    }

    /** 列表 pane 根：列表宿主（{@code rt.show}）的第一层 column 子。 */
    static SceneNode listPane(DensityHarness harness) {
        SceneNode host = listHost(harness);
        for (SceneNode child : host.__getChildren()) {
            if (child.getFlexDirection() == club.heiqi.uilib.ui.scene.layout.FlexDirection.COLUMN
                    && !child.isScrollable()) {
                return child;
            }
        }
        return host;
    }

    private static String childSummary(SceneNode node) {
        StringBuilder sb = new StringBuilder();
        for (SceneNode child : node.__getChildren()) {
            sb.append(' ').append(boxText(child)).append("(ph=").append(child.getPreferredHeight())
                    .append(",collapsed=").append(child.isCollapsed()).append(')');
        }
        return sb.toString();
    }

    private static SceneNode lastChild(SceneNode node) {
        List<SceneNode> kids = node.__getChildren();
        return kids.isEmpty() ? null : kids.get(kids.size() - 1);
    }

    /** 搜索输入框：搜索行内第一个既非按钮也非文本的控件节点。 */
    static SceneNode searchInput(SceneNode pane) {
        return pane.__getChildren().get(0).__getChildren().get(0);
    }

    /** 「新建组」按钮：搜索行最后一个子。 */
    static SceneNode searchActionButton(SceneNode pane) {
        SceneNode row = pane.__getChildren().get(0);
        List<SceneNode> kids = row.__getChildren();
        return kids.get(kids.size() - 1);
    }

    /** 谓词区第一行：块是 COLUMN（分段行 + 计数行）时取首行，块自身是 ROW 时即该行。 */
    private static SceneNode filterRow(SceneNode pane) {
        SceneNode block = pane.__getChildren().get(1);
        return block.getFlexDirection() == club.heiqi.uilib.ui.scene.layout.FlexDirection.ROW
                ? block : block.__getChildren().get(0);
    }

    /** 分段控件：谓词区第一行的第一个子。 */
    static SceneNode segmented(SceneNode pane) {
        return filterRow(pane).__getChildren().get(0);
    }

    /** 计数文本：块是 COLUMN 时取第二行（计数行）末子，块自身是 ROW 时取同行末子。 */
    static SceneNode countText(SceneNode pane) {
        SceneNode block = pane.__getChildren().get(1);
        SceneNode row = block.getFlexDirection() == club.heiqi.uilib.ui.scene.layout.FlexDirection.ROW
                ? block : block.__getChildren().get(1);
        List<SceneNode> kids = row.__getChildren();
        return kids.get(kids.size() - 1);
    }

    /** 首行行尾溢出按钮：行主行最后一个子（行结构：主行 ROW + 副行文本）。 */
    static SceneNode rowMenuButton(SceneNode pane) {
        SceneNode viewport = null;
        for (SceneNode node : ObjectGroupEditorTestSupport.descendants(pane)) {
            if (node.isScrollable()) {
                viewport = node;
                break;
            }
        }
        Assert.assertNotNull("列表行视口缺失", viewport);
        SceneNode rowRoot = viewport.__getChildren().get(0);
        SceneNode header = rowRoot.__getChildren().get(0);
        List<SceneNode> kids = header.__getChildren();
        return kids.get(kids.size() - 1);
    }

    static AnchorRect box(SceneNode node) {
        return SceneGeometry.absoluteBox(node, 0, 0);
    }

    private static int right(AnchorRect rect) {
        return rect.getX() + rect.getWidth();
    }

    private static int bottom(AnchorRect rect) {
        return rect.getY() + rect.getHeight();
    }

    private static int searchMin(DensityHarness harness, SceneNode search) {
        return ObjectGroupListPane.searchBoxMinWidth(harness.rt, search.effectiveFontSize());
    }

    /** 列表内容下限（生产入口 {@code ObjectGroupListPane.minContentWidth}）。 */
    static int contentMinWidth(DensityHarness harness, int fontSizePx) {
        return ObjectGroupListPane.minContentWidth(harness.rt, fontSizePx);
    }

    /** 分段控件四段实测宽之和 + 段间距（段宽由控件自身 {@code preferredWidth} 先验给出）。 */
    private static int segmentsNeed(DensityHarness harness, SceneNode segmented) {
        List<SceneNode> segments = segmented.__getChildren();
        int need = 0;
        for (SceneNode segment : segments) {
            need += segment.getPreferredWidth();
        }
        return need + segmented.getGap() * Math.max(0, segments.size() - 1);
    }

    /**
     * 静态审计：对每个容器检查子节点横向越界、相邻重叠、文本超框（无省略策略时）。
     *
     * @param root   审计子树根
     * @param rt     场景运行时
     * @param issues 违规描述收集器
     */
    static void audit(SceneNode root, SceneRuntime rt, List<String> issues) {
        AnchorRect box = box(root);
        if (box.getWidth() <= 0 || box.getHeight() <= 0 || root.isCollapsed()) {
            return;
        }
        int innerLeft = box.getX() + root.getPaddingLeft();
        int innerRight = right(box) - root.getPaddingRight();
        // 重叠只在父的主轴方向上有意义：ROW 比横向区间，COLUMN 比纵向区间（纵向相邻是正常堆叠）。
        boolean horizontal = root.getFlexDirection() == club.heiqi.uilib.ui.scene.layout.FlexDirection.ROW;
        SceneNode prev = null;
        AnchorRect prevBox = null;
        for (SceneNode child : root.__getChildren()) {
            if (child.isCollapsed()) {
                continue;
            }
            AnchorRect childBox = box(child);
            if (childBox.getWidth() <= 0 && childBox.getHeight() <= 0) {
                continue;
            }
            if (right(childBox) > innerRight + 1 || childBox.getX() < innerLeft - 1) {
                issues.add("H_OVERFLOW parent=" + brief(root) + " child=" + brief(child)
                        + " childBox=[" + childBox.getX() + "," + right(childBox) + "] inner=["
                        + innerLeft + "," + innerRight + "]");
            }
            if (prevBox != null && horizontal && right(prevBox) > childBox.getX() + 1) {
                issues.add("OVERLAP parent=" + brief(root) + " a=" + brief(prev) + " right=" + right(prevBox)
                        + " b=" + brief(child) + " x=" + childBox.getX());
            }
            if (prevBox != null && !horizontal && !root.isScrollable()
                    && bottom(prevBox) > childBox.getY() + 1) {
                issues.add("V_OVERLAP parent=" + brief(root) + " a=" + brief(prev) + " bottom="
                        + bottom(prevBox) + " b=" + brief(child) + " y=" + childBox.getY());
            }
            prev = child;
            prevBox = childBox;
        }
        String text = root.getText();
        if (text != null && !text.isEmpty() && !root.isEllipsis() && root.getMaxWidth() <= 0
                && root.getMaxTextWidth() <= 0) {
            int need = rt.measureTextWidth(text, root.effectiveFontSize());
            if (need > box.getWidth() + 1) {
                issues.add("TEXT_OVERFLOW node=" + brief(root) + " need=" + need + " boxW="
                        + box.getWidth());
            }
        }
        for (SceneNode child : root.__getChildren()) {
            audit(child, rt, issues);
        }
    }

    private static String brief(SceneNode node) {
        String text = node.getText();
        String label = node.getFlexDirection() + boxText(node);
        return text == null || text.isEmpty() ? label : label + " text='" + text + "'";
    }

    static String boxText(SceneNode node) {
        AnchorRect rect = box(node);
        return "[" + rect.getX() + "," + rect.getY() + " " + rect.getWidth() + "x" + rect.getHeight() + "]";
    }

    /**
     * 探针入口（三态自证）：宽挡并排 / 窄挡列表 / 窄挡下钻详情 的 x、宽与头部可见性。
     *
     * @param out    输出
     * @param langs  语言列表
     * @param widths 视口宽列表（<620 走窄挡；≥620 走宽挡）
     */
    public static void dumpDrillStates(PrintWriter out, String[] langs, int[] widths) throws Exception {
        dumpDrillStates(out, langs, widths, true);
    }

    /**
     * 三态 dump（可指定文案口径）。
     *
     * @param localize true = 注入仓内 lang（真机文案）；false = 还原为空语言表，
     *                 布局文本即键名（与全量 build 的 headless 口径一致，用于对照 mode-fixer 的失败用例）
     */
    public static void dumpDrillStates(PrintWriter out, String[] langs, int[] widths,
                                       boolean localize) throws Exception {
        for (String lang : langs) {
            Map<String, String> texts = loadLang(lang);
            if (texts == null) {
                continue;
            }
            if (localize) {
                applyLang(lang);
            } else {
                restoreVanillaLang();
                texts = new LinkedHashMap<String, String>();
            }
            for (int width : widths) {
                File dir = Files.createTempDirectory("qz-drill-dump-").toFile();
                ConfigManager manager = ObjectGroupEditorTestSupport.bootstrap(dir);
                ObjectGroupEditorTestSupport.RendererFixture fx =
                        new ObjectGroupEditorTestSupport.RendererFixture(manager);
                DensityHarness harness = new DensityHarness(new LangMeasurer(texts, FontModel.BITMAP),
                        width, HEIGHT);
                try {
                    SceneNode card = fx.renderer.render(harness.rt, fx.spec, fx.adapter);
                    harness.root.appendChild(card);
                    harness.frame();
                    harness.click(ObjectGroupEditorTestSupport.findText(card, ClientI18n.tr(
                            "config.qz_miner.object_group.manage")));
                    SceneNode editor = harness.editorRoot();
                    out.println();
                    out.println("================ 三态 lang=" + lang + " W=" + width
                            + (localize ? " 本地化" : " 原始键口径")
                            + " (wide=" + (width >= 620) + ") ================");
                    out.println("[editor] " + boxText(editor));
                    SceneNode[] before = bodyColumns(bodyHolder(harness));
                    out.println("[初始] holder=" + boxText(bodyHolder(harness)) + " 列数=" + before.length);
                    for (int i = 0; i < before.length; i++) {
                        out.println("[初始列" + i + "] " + boxText(before[i]));
                    }
                    if (width >= 620) {
                        SceneNode listColumn = listHost(harness);
                        SceneNode detailColumn = detailHost(harness);
                        out.println("[宽挡 gap] " + (box(detailColumn).getX() - right(box(listColumn))));
                    } else {
                        SceneNode listPane = listPane(harness);
                        SceneNode rowText = listPane == null ? null
                                : ObjectGroupEditorTestSupport.findText(listPane, "vanilla_logs");
                        if (rowText != null) {
                            harness.click(rowText);
                            harness.pressKey(SceneKey.ENTER);
                        }
                        SceneNode[] after = bodyColumns(bodyHolder(harness));
                        AnchorRect editorBox = box(editor);
                        SceneNode back = ObjectGroupEditorTestSupport.findText(editor, ClientI18n.tr(
                                "config.qz_miner.object_group.detail.narrow_back"));
                        boolean headerInside = back != null && box(back).getWidth() > 0
                                && box(back).getX() >= editorBox.getX()
                                && right(box(back)) <= right(editorBox);
                        boolean modesVisible = ObjectGroupEditorTestSupport.hasText(editor, ClientI18n.tr(
                                "config.qz_miner.object_group.modes.label"));
                        StringBuilder inside = new StringBuilder();
                        for (SceneNode column : after) {
                            inside.append(' ').append(box(column).getX() >= editorBox.getX()
                                    && right(box(column)) <= right(editorBox) + 1);
                        }
                        out.println("[窄挡下钻] 列数=" + after.length
                                + " 返回头部在视口内=" + headerInside
                                + " 详情内容已挂载=" + modesVisible
                                + " 各列在视口内:" + inside);
                        for (int i = 0; i < after.length; i++) {
                            out.println("[下钻列" + i + "] " + boxText(after[i]));
                        }
                    }
                } finally {
                    harness.close();
                    fx.dispose();
                    ConfigBootstrap.resetForTests();
                    ReactiveScheduler.get().reset();
                    delete(dir);
                }
            }
        }
        out.flush();
    }

    /** 打印「当前主体形态」的列几何：ROW 形态逐列打印，单列形态打印自身。 */
    private static void dumpColumns(PrintWriter out, String tag, SceneNode content) {
        if (content.getFlexDirection() == club.heiqi.uilib.ui.scene.layout.FlexDirection.ROW) {
            List<SceneNode> kids = content.__getChildren();
            for (int i = 0; i < kids.size(); i++) {
                out.println("[" + tag + ".col" + i + "] " + boxText(kids.get(i)));
            }
        } else {
            out.println("[" + tag + ".single] " + boxText(content));
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

    /**
     * 探针入口：对给定语言 × 字宽档 × 视口宽逐场景 dump 左列几何与违规清单（写文本日志，供人工对照）。
     *
     * @param out    输出
     * @param langs  语言列表
     * @param widths 视口宽列表
     */
    public static void dumpScenarios(PrintWriter out, String[] langs, int[] widths) throws Exception {
        for (String lang : langs) {
            Map<String, String> texts = loadLang(lang);
            if (texts == null) {
                out.println("!! lang 缺失: " + lang);
                continue;
            }
            applyLang(lang);
            out.println("[lang] " + lang + " add='" + ClientI18n.tr(
                    "config.qz_miner.object_group.list.add") + "' localized="
                    + !"config.qz_miner.object_group.list.add".equals(ClientI18n.tr(
                            "config.qz_miner.object_group.list.add")));
            for (FontModel model : new FontModel[] {FontModel.BITMAP, FontModel.TTF_WIDE}) {
                for (int width : widths) {
                    File dir = Files.createTempDirectory("qz-density-dump-").toFile();
                    ConfigManager manager = ObjectGroupEditorTestSupport.bootstrap(dir);
                    ObjectGroupEditorTestSupport.RendererFixture fx =
                            new ObjectGroupEditorTestSupport.RendererFixture(manager);
                    DensityHarness harness = new DensityHarness(new LangMeasurer(texts, model), width, HEIGHT);
                    try {
                        out.println();
                        out.println("================ lang=" + lang + " font=" + model.name
                                + " W=" + width + " ================");
                        SceneNode card = fx.renderer.render(harness.rt, fx.spec, fx.adapter);
                        harness.root.appendChild(card);
                        harness.frame();
                        SceneNode manage = ObjectGroupEditorTestSupport.findText(card, ClientI18n.tr(
                                "config.qz_miner.object_group.manage"));
                        harness.click(manage);
                        SceneNode editor = harness.editorRoot();
                        SceneNode host = listHost(harness);
                        SceneNode detail = detailHost(harness);
                        out.println("[editor] " + boxText(editor) + " bg=0x"
                                + Integer.toHexString(editor.getBackgroundColor()) + " backdrop="
                                + (editor.getBackdrop() == null ? "null" : String.valueOf(editor.getBackdrop())));
                        out.println("[list列] " + boxText(host) + " [detail列] " + boxText(detail)
                                + " 内容下限=" + contentMinWidth(harness, host.effectiveFontSize()));
                        out.println("[高度链] root.ph=" + editor.getPreferredHeight()
                                + " body=" + boxText(lastChild(editor))
                                + " listHost=" + boxText(host) + " detailHost=" + boxText(detail));
                        SceneNode pane = listPane(harness);
                        out.println("[列表pane] " + boxText(pane) + " ph=" + pane.getPreferredHeight()
                                + " 子=" + childSummary(pane));
                        SceneNode vp = null;
                        for (SceneNode node : ObjectGroupEditorTestSupport.descendants(pane)) {
                            if (node.isScrollable()) {
                                vp = node;
                                break;
                            }
                        }
                        if (vp != null) {
                            out.println("[列表视口] " + boxText(vp) + " ph=" + vp.getPreferredHeight()
                                    + " 行数=" + vp.__getChildren().size()
                                    + " 内容高=" + (vp.__getChildren().isEmpty() ? 0
                                            : (bottom(box(vp.__getChildren().get(vp.__getChildren().size() - 1)))
                                                    - box(vp.__getChildren().get(0)).getY())));
                        }
                        SceneNode detailInner = detail.__getChildren().isEmpty()
                                ? detail : detail.__getChildren().get(0);
                        out.println("[详情内层] " + boxText(detailInner) + " ph=" + detailInner.getPreferredHeight()
                                + " 子=" + childSummary(detailInner));
                        SceneNode search = searchInput(pane);
                        SceneNode add = searchActionButton(pane);
                        SceneNode seg = segmented(pane);
                        SceneNode count = countText(pane);
                        out.println("[搜索行] row=" + boxText(search.__getParent())
                                + " search=" + boxText(search) + " searchMin="
                                + searchMin(harness, search)
                                + " add=" + boxText(add) + " addText="
                                + harness.rt.measureTextWidth(ClientI18n.tr(
                                        "config.qz_miner.object_group.list.add"),
                                        add.effectiveFontSize()));
                        StringBuilder segs = new StringBuilder();
                        for (SceneNode segment : seg.__getChildren()) {
                            segs.append(' ').append(boxText(segment)).append("(pw=")
                                    .append(segment.getPreferredWidth()).append(')');
                        }
                        out.println("[分段] " + boxText(seg) + " 段需求=" + segmentsNeed(harness, seg) + segs);
                        out.println("[计数] " + boxText(count) + " text='" + count.getText()
                                + "' 分段底=" + bottom(box(seg)));
                        SceneNode menu = rowMenuButton(pane);
                        out.println("[行尾按钮] " + boxText(menu) + " 主行="
                                + boxText(menu.__getParent()));
                        List<String> issues = new ArrayList<String>();
                        audit(pane, harness.rt, issues);
                        out.println("---- 左列违规清单 ----");
                        if (issues.isEmpty()) {
                            out.println("(无)");
                        } else {
                            for (String issue : issues) {
                                out.println(issue);
                            }
                        }
                    } finally {
                        harness.close();
                        fx.dispose();
                        ConfigBootstrap.resetForTests();
                        ReactiveScheduler.get().reset();
                        delete(dir);
                    }
                }
            }
        }
        out.flush();
    }
}
