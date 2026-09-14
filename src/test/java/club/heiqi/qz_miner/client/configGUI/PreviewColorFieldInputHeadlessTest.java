package club.heiqi.qz_miner.client.configGUI;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.field.FieldRendererRegistry;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;
import club.heiqi.uilib.ui.scene.input.InputFrameBuilder;
import club.heiqi.uilib.ui.scene.input.RawInputEvent;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyAction;
import club.heiqi.uilib.ui.scene.input.SceneMouseButton;
import club.heiqi.uilib.ui.scene.input.ScenePointerAction;
import club.heiqi.uilib.ui.scene.layout.AnchorRect;
import club.heiqi.uilib.ui.scene.layout.Constraints;
import club.heiqi.uilib.ui.scene.layout.SceneGeometry;
import club.heiqi.uilib.ui.scene.layout.SceneLayoutEngine;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;

/**
 * 颜色字段渲染器的无头行为契约：真实 scene 树 + 真实 DraftBuffer，注入点击/全选/字符/退格。
 *
 * <p><b>存在理由</b>：{@code SceneTextInput} 是完全受控控件（文本只由外部 value 派生），
 * 「编辑期原文必须由渲染器自持」这条设计只在真正敲键时才成立——本测试替代一次实机开屏，
 * 覆盖三件离线可见但真机才暴露的事：中间态不被吞、非法文本不静默改值、失焦回落到规范形态。
 * 渲染走生产装配链（控件层注册表 + tooltip 本地化代理），不直接 new 渲染器绕过接线。</p>
 */
public class PreviewColorFieldInputHeadlessTest {

    private static final String PATH = "client.clientPreviewColorPrimary";
    private static final String PLACEHOLDER = "#RRGGBB";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;

    private File tempDir;
    private ConfigManager manager;
    private DraftBuffer draft;
    private DraftSignalAdapter adapter;
    private Harness harness;
    private SceneNode card;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-color-field-").toFile();
        ConfigBootstrap.resetForTests();
        manager = ConfigBootstrap.bootstrap(tempDir, null);
        draft = manager.openDraft();
        adapter = new DraftSignalAdapter(null, draft);
        FieldSpec spec = QzMinerConfigSchema.create().field(PATH);

        // 生产装配：控件层（HEX 渲染器）+ 本地化代理层，两层 path 装饰都应生效
        FieldRendererRegistry controls = FieldRendererRegistry.defaultRegistry();
        PreviewColorFieldRenderer.install(controls);
        FieldRendererRegistry registry = new FieldRendererRegistry();
        PreviewConfigTooltips.install(registry, controls,
                key -> "本地化:" + key);

        harness = new Harness(WIDTH, HEIGHT);
        card = registry.resolve(spec).render(harness.rt, spec, adapter);
        harness.root.appendChild(card);
        harness.frame();
    }

    @After
    public void tearDown() {
        if (harness != null) {
            harness.close();
        }
        if (adapter != null) {
            adapter.dispose();
        }
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        delete(tempDir);
    }

    /** 开屏显示的是当前值的 HEX 形态（不再是 4253439），且 tooltip 本地化代理仍作用于同一字段。 */
    @Test
    public void rendersHexTextOfCurrentValueAndKeepsLocalizedHelper() {
        Assert.assertTrue("显示必须是 #RRGGBB 形态: " + texts(card),
                hasText(card, "#40E6FF"));
        Assert.assertFalse("不得再显示十进制数字框形态", hasText(card, "4253439"));
        Assert.assertTrue("本地化代理必须仍生效（helper 走语言键）",
                hasText(card, "本地化:" + PreviewConfigTooltips.tooltipKey(PATH)));

        // 显示从草稿值派生（单真源）：外部改草稿，文本跟着走
        adapter.onFieldEdit(PATH, Double.valueOf(0x000000));
        harness.frame();
        Assert.assertTrue("草稿变化必须驱动显示重派生", hasText(card, "#000000"));
        Assert.assertFalse(hasText(card, "#40E6FF"));
    }

    /** 逐位敲十六进制：六位之前全是非法中间态，必须原样显示、不写坏值，第六位才提交为颜色。 */
    @Test
    public void typingKeepsIntermediateStatesAndCommitsOnlyLegalColors() {
        SceneNode input = focusInput();
        harness.selectAll(input);

        StringBuilder typed = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            harness.type("F");
            typed.append('F');
            String expected = typed.toString();
            Assert.assertEquals("中间态必须原样显示（不得被解析吃掉）", expected, inputText(input));
            if (i < 5) {
                Assert.assertTrue("非法中间态必须经 DraftBuffer 报错", hasError());
                Assert.assertEquals("非法中间态不得被静默改值", expected, draft.getDraft(PATH));
            }
        }
        Assert.assertEquals("六位十六进制必须落成颜色值", Double.valueOf(0xFFFFFF), draft.getDraft(PATH));
        Assert.assertFalse("合法颜色不得留错误", hasError());
        Assert.assertEquals("未失焦时保留用户原文（不中途规范化）", "FFFFFF", inputText(input));

        harness.blur(card);
        Assert.assertEquals("失焦后回落为规范 #RRGGBB 形态", "#FFFFFF", inputText(input));
    }

    /**
     * 兼容与退化：纯十进制（改造前的唯一形态）逐位仍可用。
     *
     * <p>逐位断言的判别力：{@code 4}、{@code 42}… 每一步本身都是合法颜色，若显示不经过
     * 编辑期原文而是直接派生自值，首字符就会被规范成 {@code #000004}，后续输入全废。</p>
     */
    @Test
    public void plainDecimalInputStillWorksAndNormalizesOnBlur() {
        SceneNode input = focusInput();
        harness.selectAll(input);

        String typed = "";
        for (char c : "4253439".toCharArray()) {
            harness.type(String.valueOf(c));
            typed += c;
            Assert.assertEquals("逐位输入十进制不得被中途规范化", typed, inputText(input));
        }
        Assert.assertEquals("十进制必须落成同一个颜色值", Double.valueOf(0x40E6FF), draft.getDraft(PATH));
        Assert.assertFalse(hasError());

        harness.blur(card);
        Assert.assertEquals("失焦后规范化为 HEX", "#40E6FF", inputText(input));
    }

    /** 非法文本必须报错并原样留在草稿里（不静默改值、不产生半截颜色值）；HEX 前缀 '#' 也不被吃掉。 */
    @Test
    public void illegalTextIsReportedNotSilentlyRewritten() {
        SceneNode input = focusInput();
        harness.selectAll(input);

        harness.type("zz");
        Assert.assertEquals("非法原文必须原样显示", "zz", inputText(input));
        Assert.assertEquals("非法原文原样留在草稿（走 DraftBuffer 校验）", "zz", draft.getDraft(PATH));
        Assert.assertTrue("必须报错并锁保存", hasError());

        // 删空再补合法十六进制：错误消失，值落成 Double
        harness.pressKey(SceneKey.BACKSPACE, false);
        harness.pressKey(SceneKey.BACKSPACE, false);
        harness.type("40E6FF");
        Assert.assertEquals(Double.valueOf(0x40E6FF), draft.getDraft(PATH));
        Assert.assertFalse(hasError());

        // '#' 是 HEX 语法的一部分：文本型输入不得把它过滤掉
        harness.selectAll(input);
        harness.type("#");
        Assert.assertEquals("'#' 必须能敲进去", "#", inputText(input));
        Assert.assertTrue("残缺 HEX 必须报错", hasError());
        harness.type("FFFFFF");
        Assert.assertEquals("#FFFFFF", inputText(input));
        Assert.assertEquals(Double.valueOf(0xFFFFFF), draft.getDraft(PATH));
        Assert.assertFalse(hasError());
    }

    // ==================================================================
    // 定位与探针
    // ==================================================================

    /** 点击输入框文本所在位置取得焦点（命中链自会落到唯一的可聚焦控件根），返回该控件根。 */
    private SceneNode focusInput() {
        SceneNode text = findText(card, "#40E6FF");
        Assert.assertNotNull("未找到输入框文本节点: " + texts(card), text);
        harness.click(text);
        SceneNode focused = harness.rt.getFocusedNode();
        Assert.assertNotNull("点击后必须有焦点", focused);
        return focused;
    }

    /** 输入框当前文本 = 控件子树文本按文档序拼接（caret/占位层不计），不依赖 caret 位置。 */
    private static String inputText(SceneNode inputRoot) {
        StringBuilder out = new StringBuilder();
        for (SceneNode node : descendants(inputRoot)) {
            String text = node.getText();
            if (text == null || text.isEmpty() || PLACEHOLDER.equals(text)) {
                continue;
            }
            out.append(text);
        }
        return out.toString();
    }

    private boolean hasError() {
        String error = adapter.errorSignal(PATH).get();
        return error != null && !error.isEmpty();
    }

    private static boolean hasText(SceneNode node, String text) {
        return findText(node, text) != null;
    }

    private static SceneNode findText(SceneNode node, String text) {
        for (SceneNode candidate : descendants(node)) {
            if (text.equals(candidate.getText())) {
                return candidate;
            }
        }
        return null;
    }

    private static List<String> texts(SceneNode node) {
        List<String> out = new ArrayList<String>();
        for (SceneNode candidate : descendants(node)) {
            String text = candidate.getText();
            if (text != null && !text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    private static List<SceneNode> descendants(SceneNode node) {
        List<SceneNode> out = new ArrayList<SceneNode>();
        collect(node, out);
        return out;
    }

    private static void collect(SceneNode node, List<SceneNode> out) {
        out.add(node);
        for (SceneNode child : node.__getChildren()) {
            collect(child, out);
        }
    }

    private static void delete(File dir) {
        if (dir == null) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        dir.delete();
    }

    // ==================================================================
    // 无头搭台（Miner 侧自建：UILib 的测试度量桩不随制品发布）
    // ==================================================================

    /** 确定性文本度量（口径对齐 UILib FixedTextMeasurer 8/16）。 */
    private static final class MiniMeasurer implements SceneTextMeasurer {
        @Override
        public int measureWidth(String text, int fontSizePx) {
            return (text == null ? 0 : text.length()) * 8;
        }

        @Override
        public int lineHeight(int fontSizePx) {
            return 16;
        }

        @Override
        public int ascent(int fontSizePx) {
            return 12;
        }

        @Override
        public int descent(int fontSizePx) {
            return 4;
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

    /** 单屏 headless harness：布局 + 输入注入 + 帧推进（无 overlay 需求）。 */
    private static final class Harness implements AutoCloseable {
        final SceneRuntime rt = new SceneRuntime(new MiniMeasurer());
        final SceneNode root;
        private final SceneLayoutEngine engine = new SceneLayoutEngine(new MiniMeasurer());
        private long frameNanos = 1_000_000L;

        Harness(int width, int height) {
            root = SceneNode.column();
            root.setFillParentWidth(true);
            root.setFillParentHeight(true);
            rt.__setViewportLogicalBox(width, height);
            this.width = width;
            this.height = height;
        }

        private final int width;
        private final int height;

        /** 一遍帧：推进帧时间 →（布局 + flush）× N → 末次布局（收敛挂载期新节点）。 */
        void frame() {
            rt.__tickFrame(frameNanos);
            frameNanos += 16_000_000L;
            for (int pass = 0; pass < 8; pass++) {
                layout();
                rt.flush();
            }
            layout();
        }

        private void layout() {
            engine.layout(root, new Constraints(width, height));
            rt.__setLayoutDoneEpoch((int) engine.layoutChangeEpoch());
        }

        void click(SceneNode node) {
            AnchorRect box = null;
            for (int retry = 0; retry < 3 && box == null; retry++) {
                frame();
                AnchorRect candidate = SceneGeometry.absoluteBox(node, 0, 0);
                if (candidate.getWidth() > 0 && candidate.getHeight() > 0) {
                    box = candidate;
                }
            }
            Assert.assertNotNull("节点未布局，无法点击", box);
            int x = box.getX() + box.getWidth() / 2;
            int y = box.getY() + box.getHeight() / 2;
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

        /** 注入平台文本（真机中文输入法/键盘字符通道同一条路）。 */
        void type(String text) {
            InputFrameBuilder builder = new InputFrameBuilder(0, 0);
            builder.push(RawInputEvent.ofText(text, frameNanos));
            rt.route(root, builder.drainFrame(), 0, 0);
            rt.flush();
            frame();
        }

        void pressKey(SceneKey key, boolean control) {
            InputFrameBuilder builder = new InputFrameBuilder(0, 0);
            builder.push(RawInputEvent.ofKey(key, SceneKeyAction.PRESSED, control, false, false, false,
                    0, 0, frameNanos));
            rt.route(root, builder.drainFrame(), 0, 0);
            rt.flush();
            frame();
        }

        void selectAll(SceneNode input) {
            pressKey(SceneKey.KEY_A, true);
            Assert.assertSame("全选前置条件：焦点仍在输入框", input, rt.getFocusedNode());
        }

        /** 焦点移出输入框（失焦清编辑期原文）。 */
        void blur(SceneNode other) {
            rt.requestFocus(other);
            frame();
        }

        @Override
        public void close() {
            rt.dispose();
        }
    }
}
