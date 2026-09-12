package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.editor.Registry;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;
import club.heiqi.uilib.ui.scene.input.InputFrameBuilder;
import club.heiqi.uilib.ui.scene.input.RawInputEvent;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.layout.AnchorRect;
import club.heiqi.uilib.ui.scene.layout.SceneGeometry;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.overlay.SceneOverlayHost;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 左栏（组列表）交互行为回归测试（前身：lead 核验用的打印探针）。
 *
 * <p><b>为什么升级</b>：打印探针只出读数、不会失败；本类把 5 条真机缺陷固化为断言，
 * 每条都能抓住「修复前读数」：</p>
 * <ol>
 *   <li>整卡命中图：副行（counts）与行内 padding 点击必须选中该行（修复前只绑 header）；</li>
 *   <li>roving Tab：Tab 环里 ⋮ 的数量恒等于「是否选中」的 0/1，不随行数增长（修复前 = 行数）；</li>
 *   <li>行点击 → {@code ctx.rowActivate()}，且顺序为 select → focus(viewport) → rowActivate
 *       （用计数 ctx 探针抓，修复前恒 0）；</li>
 *   <li>极窄高（逻辑 200）下列表视口 ≥ 2 行先验高、可见行数 ≥ 2（修复前视口 8px、可见 1 行）；</li>
 *   <li>{@code ctx.nudgeSelection} 越界 / 空列表 no-op，正常方向移动 + 滚动跟随
 *       （修复前列表根本没注册 nudge 处理器 ⇒ false）；</li>
 *   <li>初始焦点落在搜索<b>输入控件</b>上：不点击、直接注入 TEXT_INPUT 即可过滤，且搜索焦点下
 *       ↑/↓ 仍换组、Delete 仍不删组、Enter 仍被搜索框消费（修复前焦点在承载行容器上 ⇒ 打字丢失）。</li>
 * </ol>
 *
 * <p><b>时序前提</b>：{@code Signal.set} 是帧末批量落值（{@code ReactiveScheduler.queueWrite}），
 * 因此本类对 state 的每条命令（addGroup / nudge / …）后都推一帧再断言；连续命令之间不推帧会读到
 * 同一旧值，测不到移动与累积语义。</p>
 *
 * <p><b>两条装配路径</b>：A = 生产装配（{@code ObjectGroupEditorFieldRenderer} + {@code rt.portal} 全视图，
 * 走真实 ctx）；B = 列表 pane 直挂 + 测试自持计数 {@link ObjectGroupEditorContext}（唯一能观测
 * {@code rowActivate} / {@code nudgeSelection} 的接缝）。两条都不新建真值、不绕过 pane 公开入口。</p>
 *
 * <p><b>读数落盘</b>：每个用例的关键读数在 @After 追加到 {@code -Dlistfix.evidence=} 指定的文件
 * （默认 {@code temp/listfix-evidence.txt}），断言失败也留证。</p>
 */
public class ObjectGroupListPaneBehaviorProbeTest {

    private static final int WIDE = 700;
    private static final int NARROW = 500;
    private static final int HEIGHT = 420;
    /** 极窄高复现挡位：lead 实测此高度下列表视口只剩 8px。 */
    private static final int SHORT_HEIGHT = 200;
    /** 真机最差挡位（1920×1080 @ GUI Scale 4 = 480×270 逻辑）。 */
    private static final int DENSITY_HEIGHT = 270;
    /** 与 {@code ObjectGroupListPane.rowHeight} 同口径的行高先验：{@code max(36, lineHeight + 2×PAD)}。 */
    private static final int ROW_MIN_HEIGHT_PX = 36;
    private static final int ROW_PADDING_PX = 6;
    private static final String OVERFLOW_GLYPH = "\u22EE";
    private static final String EVIDENCE_KEY = "listfix.evidence";
    private static final String EVIDENCE_DEFAULT = "D:\\Code\\MC\\Qz工作站\\temp\\listfix-evidence.txt";

    @Rule
    public final TestName testName = new TestName();

    private final List<String> readings = new ArrayList<String>();

    private File tempDir;
    private ConfigManager manager;
    private ObjectGroupEditorTestSupport.RendererFixture fixture;
    private ObjectGroupEditorTestSupport.Harness harness;
    private SceneNode card;

    /** 路径 B：独立 harness（不带摘要卡，保证 pane 拿到确定高约束）+ 计数上下文。 */
    private ObjectGroupEditorTestSupport.Harness paneHarness;
    private ObjectGroupEditorState listState;
    private CountingContext listContext;
    private SceneNode listPane;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-leftpane-regression-").toFile();
        manager = ObjectGroupEditorTestSupport.bootstrap(tempDir);
        fixture = new ObjectGroupEditorTestSupport.RendererFixture(manager);
        harness = new ObjectGroupEditorTestSupport.Harness(WIDE, HEIGHT);
        card = fixture.renderer.render(harness.rt, fixture.spec, fixture.adapter);
        harness.root.appendChild(card);
        harness.frame();
    }

    @After
    public void tearDown() {
        try {
            writeEvidence();
        } finally {
            if (paneHarness != null) {
                paneHarness.close();
            }
            if (harness != null) {
                harness.close();
            }
            if (fixture != null) {
                fixture.dispose();
            }
            ConfigBootstrap.resetForTests();
            ReactiveScheduler.get().reset();
            if (tempDir != null) {
                delete(tempDir);
            }
        }
    }

    // ==================================================================
    // ① 整卡命中图：副行 / padding / 行内间隙都必须选中该行
    // ==================================================================

    @Test
    public void wholeCardIsClickable_countsAndPaddingIncluded() {
        openEditor();
        List<String> ids = visibleIds();
        Assert.assertTrue("夹具行数（至少 3）: " + ids, ids.size() >= 3);
        List<SceneNode> rows = rowsOf(harness.editorRoot(), ids);
        for (SceneNode row : rows) {
            Assert.assertNotNull("行根缺失", row);
        }
        AnchorRect box = SceneGeometry.absoluteBox(rows.get(1), 0, 0);
        SceneNode counts = rows.get(1).__getChildren().get(1);
        AnchorRect countsBox = SceneGeometry.absoluteBox(counts, 0, 0);
        SceneNode title = ObjectGroupEditorTestSupport.findText(rows.get(1), ids.get(1));
        AnchorRect titleBox = SceneGeometry.absoluteBox(title, 0, 0);

        List<String> labels = new ArrayList<String>();
        List<int[]> points = new ArrayList<int[]>();
        labels.add("header标题");
        points.add(new int[] {titleBox.getX() + titleBox.getWidth() / 2,
                titleBox.getY() + titleBox.getHeight() / 2});
        labels.add("副行counts");
        points.add(new int[] {countsBox.getX() + countsBox.getWidth() / 2,
                countsBox.getY() + countsBox.getHeight() / 2});
        labels.add("行内间隙");
        points.add(new int[] {box.getX() + box.getWidth() / 2, countsBox.getY() - 1});
        labels.add("padding左上");
        points.add(new int[] {box.getX() + 2, box.getY() + 2});
        labels.add("padding右上");
        points.add(new int[] {box.getX() + box.getWidth() - 2, box.getY() + 2});
        labels.add("padding左下");
        points.add(new int[] {box.getX() + 2, box.getBottom() - 2});
        labels.add("padding右下");
        points.add(new int[] {box.getX() + box.getWidth() - 2, box.getBottom() - 2});

        StringBuilder results = new StringBuilder();
        boolean allHit = true;
        for (int i = 0; i < points.size(); i++) {
            // 每个采样点前把选中复位到第 1 行（点其 id 文本）
            harness.click(ObjectGroupEditorTestSupport.findText(rows.get(0), ids.get(0)));
            harness.frame();
            int reset = selectedIndex(rows);
            harness.clickAt(points.get(i)[0], points.get(i)[1]);
            harness.frame();
            int selected = selectedIndex(rows);
            boolean ok = reset == 0 && selected == 1;
            allHit = allHit && ok;
            results.append(labels.get(i)).append('=').append(selected);
            if (reset != 0) {
                results.append("(复位失败=").append(reset).append(')');
            }
            results.append(' ');
        }
        record("① 整卡命中图（期望 1 = 第 2 行选中，行盒 " + box.getWidth() + "x" + box.getHeight() + "）: "
                + results);
        Assert.assertTrue("整卡必须可点（副行 / padding / 间隙都算行盒内）: " + results, allHit);
    }

    // ==================================================================
    // A 搜索输入框初始焦点：直接注入 TEXT_INPUT 即可过滤（无需先点击输入框）
    // ==================================================================

    @Test
    public void searchInputHoldsInitialFocusAndAcceptsTextDirectly() {
        openEditor();
        String placeholder = ClientI18n.tr("config.qz_miner.object_group.list.search");
        SceneNode placeholderNode = ObjectGroupEditorTestSupport.findText(harness.editorRoot(), placeholder);
        Assert.assertNotNull("搜索占位文案缺失", placeholderNode);
        SceneNode input = placeholderNode.__getParent();
        SceneNode inputRow = input.__getParent();
        record("A 开屏: 焦点=" + describe(harness.focused()) + " 输入控件=" + describe(input)
                + " 承载行=" + describe(inputRow));
        Assert.assertNotNull("开屏必须有初始焦点", harness.focused());
        Assert.assertSame("初始焦点必须落在搜索输入控件上（不是承载它的行容器）", input, harness.focused());

        // 不点击、不 TAB：直接注入文本
        typeText("hay");
        SceneNode hay = rowRoot(harness.editorRoot(), "vanilla_hay");
        boolean logsRow = rowRoot(harness.editorRoot(), "vanilla_logs") != null;
        record("A 直接 TEXT_INPUT(hay): hay行=" + (hay != null) + " logs行=" + logsRow
                + " 焦点=" + describe(harness.focused()) + " overlay=" + harness.overlayCount());
        Assert.assertNotNull("直接打字必须命中搜索框（hay 行保留）", hay);
        Assert.assertFalse("直接打字必须过滤掉非命中行（logs 行消失）", logsRow);
        Assert.assertSame("打字后焦点仍在输入控件", input, harness.focused());

        // 搜索焦点下 ↑/↓ 仍放行给列表（导航非破坏）
        harness.pressKey(SceneKey.ARROW_DOWN);
        harness.frame();
        hay = rowRoot(harness.editorRoot(), "vanilla_hay");
        record("A 搜索焦点 ARROW_DOWN: 行选中=" + (hay != null && hay.getBackgroundColor() != 0));
        Assert.assertTrue("搜索框内 ↑/↓ 仍必须换组", hay != null && hay.getBackgroundColor() != 0);

        // 搜索焦点下 Delete 不得删组（属于文本编辑）
        harness.pressKey(SceneKey.DELETE);
        harness.frame();
        record("A 搜索焦点 DELETE: hay行仍在=" + (rowRoot(harness.editorRoot(), "vanilla_hay") != null)
                + " overlay=" + harness.overlayCount());
        Assert.assertNotNull("搜索框里的 Delete 不得删除组", rowRoot(harness.editorRoot(), "vanilla_hay"));
        Assert.assertEquals("编辑器保持打开", 1, harness.overlayCount());

        // 搜索框内 Enter 必须被搜索框消费（不得触发视图级下钻 / 焦点进详情）
        harness.pressKey(SceneKey.ENTER);
        harness.frame();
        record("A 搜索焦点 ENTER: 焦点=" + describe(harness.focused()) + " overlay=" + harness.overlayCount());
        Assert.assertSame("搜索框内 Enter 被消费（焦点不转移）", input, harness.focused());
        Assert.assertEquals("编辑器保持打开", 1, harness.overlayCount());
    }

    /** 直接注入文本事件（不经过鼠标点击）：验证初始焦点真的落在搜索输入控件上。 */
    private void typeText(String text) {
        InputFrameBuilder builder = new InputFrameBuilder(0, 0);
        builder.push(RawInputEvent.ofText(text, 2_000_000L));
        harness.rt.route(harness.root, builder.drainFrame(), 0, 0);
        harness.frame();
    }

    // ==================================================================
    // ② roving Tab：⋮ 的 Tab 环成员数恒为 0 / 1，不随行数增长
    // ==================================================================

    @Test
    public void tabRingContainsOnlySelectedRowMenu() {
        openEditor();
        List<String> ids = visibleIds();
        List<SceneNode> rows = rowsOf(harness.editorRoot(), ids);
        int ringNoSelection = ringMenuCount(rows);
        record("② 开屏 selectedIndex=" + selectedIndex(rows) + " 行数=" + rows.size()
                + " ringMenu=" + ringNoSelection);

        // 点击第 2 行 → 只有它的 ⋮ 进 Tab 环
        harness.click(ObjectGroupEditorTestSupport.findText(rows.get(1), ids.get(1)));
        harness.frame();
        SceneNode menu0 = menuButtonOf(rows.get(0));
        SceneNode menu1 = menuButtonOf(rows.get(1));
        int ringSelected = ringMenuCount(rows);
        record("② 选中第 2 行 selectedIndex=" + selectedIndex(rows) + " ringMenu=" + ringSelected
                + " row0⋮focusable=" + isFocusable(harness.rt, menu0)
                + " row1⋮focusable=" + isFocusable(harness.rt, menu1));
        Assert.assertEquals("未选中行时 ⋮ 不得进入 Tab 环", 0, ringNoSelection);
        Assert.assertEquals("只有当前选中行的 ⋮ 在 Tab 环内", 1, ringSelected);
        Assert.assertFalse("非选中行 ⋮ 仍可鼠标点击，但不进 Tab 环", isFocusable(harness.rt, menu0));
        Assert.assertTrue("选中行 ⋮ 必须在 Tab 环内（可 Tab 到达 + F10/Menu）",
                isFocusable(harness.rt, menu1));

        // Tab 行为：视口 TAB 之后必须落到「选中行」的 ⋮（修复前落到第 1 行的）
        SceneNode viewport = rows.get(0).__getParent();
        Assert.assertSame("点击行后焦点回到列表视口", viewport, harness.focused());
        harness.pressKey(SceneKey.TAB);
        harness.frame();
        record("② TAB from viewport -> " + describe(harness.focused()));
        Assert.assertSame("Tab 下一站 = 选中行的 ⋮", menu1, harness.focused());

        // 行数增长（+2 行）后环成员数不变
        int before = visibleIds().size();
        ObjectGroupEditorState live = stateSnapshot();
        Assert.assertTrue("复制组必须成功", live.duplicateGroup(live.visibleViews().get(0).key()).accepted());
        harness.frame(); // 信号写入按帧落值（Signal.set 排队到 flush）：每次命令后必须推一帧
        Assert.assertTrue("复制组必须成功", live.duplicateGroup(live.visibleViews().get(0).key()).accepted());
        harness.frame();
        List<String> grownIds = visibleIds();
        List<SceneNode> grownRows = rowsOf(harness.editorRoot(), grownIds);
        record("② 行数 " + before + " -> " + grownIds.size() + " ringMenu=" + ringMenuCount(grownRows));
        Assert.assertEquals("行数确实增长", before + 2, grownIds.size());
        harness.click(ObjectGroupEditorTestSupport.findText(grownRows.get(0), grownIds.get(0)));
        harness.frame();
        Assert.assertEquals("Tab 环成员不随行数增长", 1, ringMenuCount(grownRows));
    }

    // ==================================================================
    // ③ rowActivate 计数探针 + 顺序（select → focus → rowActivate）
    // ==================================================================

    @Test
    public void rowClick_selectsFocusesThenActivates() {
        buildListPane();
        List<String> ids = listVisibleIds();
        List<SceneNode> rows = rowsOf(listPane, ids);
        SceneNode viewport = rows.get(0).__getParent();
        long secondKey = listState.visibleViews().get(1).key();

        listContext.resetProbe();
        paneHarness.click(rows.get(1).__getChildren().get(1));
        paneHarness.frame();
        record("③ 点击第 2 行副行: rowActivate=" + listContext.rowActivateCount
                + " selectedAtActivate=" + listContext.selectedKeyAtActivate
                + " focusAtActivate=" + describe(listContext.focusedAtActivate));
        Assert.assertEquals("行点击必须调用 ctx.rowActivate()", 1, listContext.rowActivateCount);
        Assert.assertEquals("rowActivate 前已完成 select", secondKey, listContext.selectedKeyAtActivate);
        Assert.assertSame("rowActivate 前焦点已交回列表视口", viewport, listContext.focusedAtActivate);
        Assert.assertEquals("行选中生效", 1, selectedIndex(rows));
        Assert.assertSame("点击后焦点在列表视口", viewport, paneHarness.focused());
    }

    @Test
    public void overflowMenuClick_opensMenuWithoutActivatingRow() {
        buildListPane();
        List<String> ids = listVisibleIds();
        List<SceneNode> rows = rowsOf(listPane, ids);
        listContext.resetProbe();

        // 非选中行的 ⋮：鼠标点击仍能打开菜单（且只选中、不触发行激活）
        SceneNode menu1 = menuButtonOf(rows.get(1));
        Assert.assertFalse("未选中行的 ⋮ 不在 Tab 环内", isFocusable(paneHarness.rt, menu1));
        // 路径 B 没有编辑器浮层 ⇒ 基线 0，菜单打开即 +1
        int baseOverlays = paneHarness.overlayCount();
        paneHarness.click(menu1);
        paneHarness.frame();
        record("③ 非选中行 ⋮ 点击: overlays=" + paneHarness.overlayCount()
                + " rowActivate=" + listContext.rowActivateCount + " selected=" + selectedIndex(rows));
        Assert.assertEquals("非选中行 ⋮ 仍可鼠标点击打开菜单", baseOverlays + 1, paneHarness.overlayCount());
        Assert.assertEquals("⋮ 点击不得触发行激活", 0, listContext.rowActivateCount);
        Assert.assertEquals("⋮ 点击只选中该行", 1, selectedIndex(rows));
        paneHarness.pressKey(SceneKey.ESCAPE);
        paneHarness.frame();
        Assert.assertEquals("ESC 关菜单", baseOverlays, paneHarness.overlayCount());

        // 选中行的 ⋮：仍可打开（且不双重触发）
        paneHarness.click(ObjectGroupEditorTestSupport.findText(rows.get(1), ids.get(1)));
        paneHarness.frame();
        int afterRowClick = listContext.rowActivateCount;
        paneHarness.click(menuButtonOf(rows.get(1)));
        paneHarness.frame();
        record("③ 选中行 ⋮ 点击: overlays=" + paneHarness.overlayCount()
                + " rowActivate=" + listContext.rowActivateCount + " (行点击后 " + afterRowClick + ")");
        Assert.assertEquals("选中行 ⋮ 仍可打开菜单", baseOverlays + 1, paneHarness.overlayCount());
        Assert.assertEquals("⋮ 点击不叠加行激活（不得双重触发）", afterRowClick, listContext.rowActivateCount);
    }

    // ==================================================================
    // ④ nudge：越界 / 空列表 no-op，正常移动 + 滚动跟随
    // ==================================================================

    @Test
    public void nudgeSelection_movesAndNoOpsAtBounds() {
        buildListPane();
        List<SceneNode> rows = rowsOf(listPane, listVisibleIds());
        int lastIndex = listState.visibleViews().size() - 1;
        long firstKey = listState.visibleViews().get(0).key();
        long lastKey = listState.visibleViews().get(lastIndex).key();

        // 每次 nudge 与每帧一致（真实 ↑/↓ 也是一次按键一帧）：Signal 写入按帧落值，
        // 连续 nudge 之间不推帧会读到同一个旧选中键，无法验证移动语义。
        boolean handled = listContext.nudgeSelection(1);
        paneHarness.frame();
        record("④ nudge(+1) 自未选中: handled=" + handled + " selected=" + selectedIndex(rows));
        Assert.assertTrue("列表构建期必须注册 selection nudge 处理器", handled);
        Assert.assertEquals("未选中时 +1 落到首行", 0, selectedIndex(rows));

        listContext.nudgeSelection(-1);
        paneHarness.frame();
        record("④ 首行 nudge(-1): selectedKey=" + listState.selectedKey().get());
        Assert.assertEquals("首行上移越界 no-op", firstKey, listState.selectedKey().get().longValue());

        for (int i = 1; i <= lastIndex; i++) {
            listContext.nudgeSelection(1);
            paneHarness.frame();
            Assert.assertEquals("逐级下移", i, selectedIndex(rows));
        }
        listContext.nudgeSelection(1);
        paneHarness.frame();
        record("④ 末行 nudge(+1): selectedKey=" + listState.selectedKey().get());
        Assert.assertEquals("末行下移越界 no-op", lastKey, listState.selectedKey().get().longValue());

        listState.search().set("zzz-no-such-group");
        paneHarness.frame();
        record("④ 过滤无命中: rows=" + listState.visibleViews().size()
                + " selectedKey=" + listState.selectedKey().get());
        Assert.assertTrue("过滤后列表应为空", listState.visibleViews().isEmpty());
        listContext.nudgeSelection(1);
        paneHarness.frame();
        Assert.assertEquals("空列表 nudge no-op", lastKey, listState.selectedKey().get().longValue());
    }

    @Test
    public void nudgeSelection_scrollsIntoView() {
        buildListPane();
        // 追加到 8 行：内容必然超出视口，滚动跟随才可判定（不依赖具体挡位高度假设）
        for (int i = 0; i < 5; i++) {
            Assert.assertTrue("追加组必须成功", listState.addGroup().accepted());
            paneHarness.frame(); // 组写命令按帧落值（Signal.set 排队到 flush），逐条推帧才能累积
        }
        List<String> ids = listVisibleIds();
        List<SceneNode> rows = rowsOf(listPane, ids);
        SceneNode viewport = rows.get(0).__getParent();
        paneHarness.resize(WIDE, SHORT_HEIGHT);
        paneHarness.frame();
        paneHarness.click(ObjectGroupEditorTestSupport.findText(rows.get(0), ids.get(0)));
        paneHarness.frame();
        record("④ 矮视口 行数=" + rows.size() + " viewport=" + box(viewport)
                + " scroll=" + viewport.getScrollOffsetY());

        Assert.assertTrue("行数需超出视口才能验证滚动跟随", rows.size() >= 8);
        for (int i = 0; i < rows.size(); i++) {
            listContext.nudgeSelection(1);
            paneHarness.frame();
            int selected = selectedIndex(rows);
            AnchorRect view = rect(viewport);
            AnchorRect rowBox = rect(rows.get(selected));
            boolean fullyVisible = rowBox.getY() >= view.getY() && rowBox.getBottom() <= view.getBottom();
            record("④ step" + i + " selected=" + selected + " row=" + box(rows.get(selected))
                    + " viewport=" + box(viewport) + " scroll=" + viewport.getScrollOffsetY()
                    + " fullyVisible=" + fullyVisible);
            Assert.assertEquals("逐级下移", Math.min(i + 1, rows.size() - 1), selected);
            Assert.assertTrue("第 " + i + " 步行必须完整落在视口内（滚动步距=实测行盒高+行间距）",
                    fullyVisible);
        }
        Assert.assertEquals("末行可达", rows.size() - 1, selectedIndex(rows));
        Assert.assertTrue("选中行越出视口时必须滚动跟随（scroll>0）", viewport.getScrollOffsetY() > 0);
    }

    // ==================================================================
    // 窄挡点击行 → 下钻（③ 与 task-2 视图注入的联调口）
    // ==================================================================

    @Test
    public void narrowRowClickDrillsDownThroughRowActivate() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();
        SceneNode row = ObjectGroupEditorTestSupport.findText(harness.editorRoot(), "vanilla_hay");
        Assert.assertNotNull("窄挡列表行缺失", row);
        harness.click(row);
        harness.frame();
        boolean drilled = ObjectGroupEditorTestSupport.hasText(harness.editorRoot(),
                ClientI18n.tr("config.qz_miner.object_group.detail.narrow_back"));
        record("⑤ 窄挡点击行: drilled=" + drilled + " overlays=" + harness.overlayCount());
        Assert.assertTrue("窄挡点击行必须下钻（ctx.rowActivate → 视图 drilled）", drilled);
        Assert.assertEquals("下钻后编辑器保持打开", 1, harness.overlayCount());
    }

    // ==================================================================
    // 键盘 MENU 锚点必须跟随「实测行盒」（滚动后不漂移）
    // ==================================================================

    @Test
    public void keyboardMenuAnchorTracksMeasuredRow() {
        buildListPane();
        // 宿主加高 + 行数加多：保证 ① 内容必须滚动；② 选中行放到视口顶部后，菜单向下展开有充足空间
        // （空间不足时锚定浮层会向上翻转并按安全边距夹紧，观测值就不再等于锚点，测不到步距缺陷）。
        for (int i = 0; i < 17; i++) {
            Assert.assertTrue("追加组必须成功", listState.addGroup().accepted());
            paneHarness.frame();
        }
        paneHarness.resize(WIDE, 720);
        paneHarness.frame();
        List<String> ids = listVisibleIds();
        List<SceneNode> rows = rowsOf(listPane, ids);
        SceneNode viewport = rows.get(0).__getParent();
        // 建立列表焦点（点击行 → requestFocus(viewport)）
        paneHarness.click(ObjectGroupEditorTestSupport.findText(rows.get(0), ids.get(0)));
        paneHarness.frame();
        // 一路 ↓ 到末行：视口滚到底（锚点缺陷在滚动后才会暴露）
        for (int i = 0; i < rows.size(); i++) {
            listContext.nudgeSelection(1);
            paneHarness.frame();
        }
        // 取「视口内最上面的完整可见行」为选中行：它下方空间充足，菜单向下展开不会翻转/夹紧，
        // 菜单内容顶边即可直接与锚点比对（翻转路径观测不到锚点）。
        AnchorRect view = rect(viewport);
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            AnchorRect rowBox = rect(rows.get(i));
            if (rowBox.getY() >= view.getY() && rowBox.getBottom() <= view.getBottom()) {
                index = i;
                break;
            }
        }
        Assert.assertTrue("视口内应有完整可见行", index >= 0);
        paneHarness.click(ObjectGroupEditorTestSupport.findText(rows.get(index), ids.get(index)));
        paneHarness.frame();
        Assert.assertEquals("点击后选中该行", index, selectedIndex(rows));
        int expectedY = rect(rows.get(index)).getBottom();
        paneHarness.pressKey(SceneKey.MENU);
        paneHarness.frame();
        SceneOverlayHost.Entry menu = topOverlayEntry(paneHarness);
        Assert.assertNotNull("键盘 MENU 必须打开浮层", menu);
        Assert.assertNotNull("MENU 浮层必须带锚点", menu.getAnchorProvider());
        int anchorY = menu.getAnchorProvider().get().getY();
        record("④b 键盘 MENU 锚点: 行数=" + rows.size() + " selected=" + index + " row=" + box(rows.get(index))
                + " rowBottom=" + expectedY + " viewport=" + box(viewport)
                + " scroll=" + viewport.getScrollOffsetY() + " anchorY=" + anchorY
                + " overlays=" + paneHarness.overlayCount());
        // 无障碍环境的 harness 不跑 SceneFramePipeline 的锚点回放，故直接读浮层条目登记的
        // AnchorProvider：它返回的 (x,y) 正是键盘 MENU 路径交给浮层系统的锚点。
        Assert.assertEquals("菜单锚点 Y 必须等于实测选中行盒底边（含滚动偏移）", expectedY, anchorY);
    }

    /** 栈顶浮层条目（锚点断言用）。 */
    private static SceneOverlayHost.Entry topOverlayEntry(ObjectGroupEditorTestSupport.Harness target) {
        List<SceneOverlayHost.Entry> entries = target.rt.getOverlayHost().bottomFirst();
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    // ==================================================================
    // 空搜索词 Enter 放行 / 有词 Enter 消费（C3 §5.7 偏离口径）
    // ==================================================================

    @Test
    public void emptySearchEnterPassesThroughToDrill() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();

        // 基线键盘路径：不动鼠标、空搜索词 —— ↓ 选组 + Enter 必须下钻
        harness.pressKey(SceneKey.ARROW_DOWN);
        harness.frame();
        harness.pressKey(SceneKey.ENTER);
        harness.frame();
        boolean drilled = ObjectGroupEditorTestSupport.hasText(harness.editorRoot(),
                ClientI18n.tr("config.qz_miner.object_group.detail.narrow_back"));
        record("A2 空词 ↓+Enter: drilled=" + drilled + " overlays=" + harness.overlayCount());
        Assert.assertTrue("空搜索词 Enter 必须放行给视图下钻（恢复基线键盘路径）", drilled);
        Assert.assertEquals("下钻后编辑视图保持打开", 1, harness.overlayCount());

        // ESC 返回列表（无搜索词 → 下钻态返回，浮层保持打开）
        harness.pressKey(SceneKey.ESCAPE);
        harness.frame();
        Assert.assertFalse("ESC 必须回到列表", ObjectGroupEditorTestSupport.hasText(harness.editorRoot(),
                ClientI18n.tr("config.qz_miner.object_group.detail.narrow_back")));
        Assert.assertEquals("返回列表后保持打开", 1, harness.overlayCount());

        // 有搜索词：Enter 仍必须被搜索框消费（防误触）
        typeText("hay");
        harness.frame();
        harness.pressKey(SceneKey.ENTER);
        harness.frame();
        boolean drilledWithQuery = ObjectGroupEditorTestSupport.hasText(harness.editorRoot(),
                ClientI18n.tr("config.qz_miner.object_group.detail.narrow_back"));
        record("A2 有词 Enter: drilled=" + drilledWithQuery + " overlays=" + harness.overlayCount());
        Assert.assertFalse("有搜索词时 Enter 不得下钻（防误触）", drilledWithQuery);
        Assert.assertEquals("有词 Enter 后编辑视图保持打开", 1, harness.overlayCount());
    }

    // ==================================================================
    // ⑤ 极窄高保护：按序折叠「计数行」→「筛选行」，视口 ≥ 2 行先验高
    // ==================================================================

    @Test
    public void extremeShortHeight_foldsCountThenFilterRow() {
        openEditor();
        List<String> ids = visibleIds();
        List<SceneNode> rows = rowsOf(harness.editorRoot(), ids);
        SceneNode viewport = rows.get(0).__getParent();
        SceneNode pane = viewport.__getParent();
        List<SceneNode> paneChildren = pane.__getChildren();
        SceneNode filterBlock = paneChildren.get(1);
        SceneNode countRow = filterBlock.__getChildren().get(1);
        int minViewport = minViewportHeight(viewport);

        int normalHeight = rect(viewport).getHeight();
        record("⑤ 正常挡 " + HEIGHT + ": pane=" + box(pane) + " 视口=" + box(viewport)
                + " 可见行=" + visibleRowCount(rows, viewport)
                + " count折叠=" + countRow.isCollapsed() + " 筛选块折叠=" + filterBlock.isCollapsed()
                + " 需求视口>=" + minViewport);
        Assert.assertFalse("正常挡不得折叠计数行", countRow.isCollapsed());
        Assert.assertFalse("正常挡不得折叠筛选行", filterBlock.isCollapsed());
        Assert.assertTrue("正常挡视口 ≥ 2 行先验高", normalHeight >= minViewport);

        // 真机最差挡位（480×270 逻辑）：筛选行必须仍在（不得牺牲正常挡位布局）
        harness.resize(WIDE, DENSITY_HEIGHT);
        harness.frame();
        record("⑤ 真机最差挡 " + DENSITY_HEIGHT + ": 视口=" + box(viewport)
                + " 可见行=" + visibleRowCount(rows, viewport)
                + " count折叠=" + countRow.isCollapsed() + " 筛选块折叠=" + filterBlock.isCollapsed());
        Assert.assertFalse("真机最差挡不得折叠筛选行", filterBlock.isCollapsed());
        Assert.assertTrue("真机最差挡视口 ≥ 2 行先验高", rect(viewport).getHeight() >= minViewport);
        harness.resize(WIDE, HEIGHT);
        harness.frame();

        // 极窄高 200：按序折叠到筛选行，视口拿回 ≥ 2 行先验高
        harness.resize(WIDE, SHORT_HEIGHT);
        harness.frame();
        int shortHeight = rect(viewport).getHeight();
        int shortVisible = visibleRowCount(rows, viewport);
        record("⑤ 极窄高 " + SHORT_HEIGHT + ": pane=" + box(pane) + " 视口=" + box(viewport)
                + " 可见行=" + shortVisible + " count折叠=" + countRow.isCollapsed()
                + " 筛选块折叠=" + filterBlock.isCollapsed() + " 需求视口>=" + minViewport);
        Assert.assertTrue("极窄高必须折叠计数行", countRow.isCollapsed());
        Assert.assertTrue("计数行不够时必须继续折叠筛选行", filterBlock.isCollapsed());
        Assert.assertTrue("极窄高视口 ≥ 2 行先验高（实测 " + shortHeight + " < " + minViewport + "）",
                shortHeight >= minViewport);
        Assert.assertTrue("极窄高可见行数 ≥ 2（实测 " + shortVisible + "）", shortVisible >= 2);

        // 恢复正常挡：解折叠、视口还原
        harness.resize(WIDE, HEIGHT);
        harness.frame();
        record("⑤ 还原后: 视口=" + box(viewport) + " count折叠=" + countRow.isCollapsed()
                + " 筛选块折叠=" + filterBlock.isCollapsed());
        Assert.assertFalse("恢复挡位后计数行必须解折叠", countRow.isCollapsed());
        Assert.assertFalse("恢复挡位后筛选行必须解折叠", filterBlock.isCollapsed());
        Assert.assertEquals("恢复挡位后视口还原", normalHeight, rect(viewport).getHeight());
    }

    // ==================================================================
    // 装配 B：列表 pane 直挂 + 计数 ctx
    // ==================================================================

    /** 路径 B 的可见行 id（真值取列表自持 state，不经第二实例）。 */
    private List<String> listVisibleIds() {
        List<String> ids = new ArrayList<String>();
        for (ObjectGroupEditorState.RowView view : listState.visibleViews()) {
            ids.add(view.id());
        }
        return ids;
    }

    private void buildListPane() {
        paneHarness = new ObjectGroupEditorTestSupport.Harness(WIDE, HEIGHT);
        listState = new ObjectGroupEditorState(fixture.spec, fixture.adapter);
        listContext = new CountingContext(paneHarness.rt, fixture.adapter, fixture.registry, listState);
        listPane = ObjectGroupListPane.build(listContext);
        paneHarness.root.appendChild(listPane);
        paneHarness.frame();
    }

    /** 测试自持的宿主上下文：只做计数/转发，真值仍来自 state 与 runtime。 */
    private static final class CountingContext implements ObjectGroupEditorContext {
        private final SceneRuntime rt;
        private final DraftSignalAdapter adapter;
        private final Registry registry;
        private final ObjectGroupEditorState state;

        private int rowActivateCount;
        private long selectedKeyAtActivate = Long.MIN_VALUE;
        private SceneNode focusedAtActivate;
        private int closeRequests;
        private IntConsumer nudgeHandler;
        private Runnable rowActivateHandler;
        private BooleanSupplier dismissHandler;

        CountingContext(SceneRuntime rt, DraftSignalAdapter adapter, Registry registry,
                        ObjectGroupEditorState state) {
            this.rt = rt;
            this.adapter = adapter;
            this.registry = registry;
            this.state = state;
        }

        void resetProbe() {
            rowActivateCount = 0;
            selectedKeyAtActivate = Long.MIN_VALUE;
            focusedAtActivate = null;
        }

        @Override
        public SceneRuntime rt() {
            return rt;
        }

        @Override
        public DraftSignalAdapter adapter() {
            return adapter;
        }

        @Override
        public Registry editorRegistry() {
            return registry;
        }

        @Override
        public ObjectGroupEditorState state() {
            return state;
        }

        @Override
        public void requestClose() {
            closeRequests++;
        }

        @Override
        public void setDismissHandler(BooleanSupplier handler) {
            if (handler == null) {
                throw new IllegalArgumentException("handler must not be null");
            }
            dismissHandler = handler;
        }

        @Override
        public void setRowActivateHandler(Runnable handler) {
            if (handler == null) {
                throw new IllegalArgumentException("handler must not be null");
            }
            rowActivateHandler = handler;
        }

        @Override
        public void rowActivate() {
            rowActivateCount++;
            // 顺序证据：rowActivate 执行时焦点必须已在列表视口（requestFocus 同步生效）
            focusedAtActivate = rt.getFocusedNode();
            // 选中是 Signal 写入（排队到 flush 才落值）：先推一次 flush 才能读到 handler 内已执行的
            // state.select(key)，用于验证「select 在 rowActivate 之前」。
            rt.flush();
            selectedKeyAtActivate = state.selectedKey().get().longValue();
            if (rowActivateHandler != null) {
                rowActivateHandler.run();
            }
        }

        @Override
        public void setSelectionNudgeHandler(IntConsumer handler) {
            if (handler == null) {
                throw new IllegalArgumentException("handler must not be null");
            }
            nudgeHandler = handler;
        }

        @Override
        public boolean nudgeSelection(int delta) {
            if (nudgeHandler == null) {
                return false;
            }
            nudgeHandler.accept(delta);
            return true;
        }

        @Override
        public void handleDismissRequest() {
            BooleanSupplier handler = dismissHandler;
            if (handler != null && handler.getAsBoolean()) {
                return;
            }
            closeRequests++;
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void openEditor() {
        SceneNode manage = ObjectGroupEditorTestSupport.findText(card,
                ClientI18n.tr("config.qz_miner.object_group.manage"));
        Assert.assertNotNull("摘要卡入口按钮缺失", manage);
        harness.click(manage);
        Assert.assertNotNull(harness.editorRoot());
        harness.frame();
    }

    /** 生产装配下当前可见行 id（列表真值取自 state，不缓存）。 */
    private List<String> visibleIds() {
        List<String> ids = new ArrayList<String>();
        for (ObjectGroupEditorState.RowView view : stateSnapshot().visibleViews()) {
            ids.add(view.id());
        }
        return ids;
    }

    private ObjectGroupEditorState stateSnapshot() {
        return new ObjectGroupEditorState(fixture.spec, fixture.adapter);
    }

    private static List<SceneNode> rowsOf(SceneNode scope, List<String> ids) {
        List<SceneNode> rows = new ArrayList<SceneNode>();
        for (String id : ids) {
            rows.add(rowRoot(scope, id));
        }
        return rows;
    }

    /** 行根 = 承载 1px 边框的整卡节点（id 文本向上找首个 borderWidth==1）。 */
    private static SceneNode rowRoot(SceneNode scope, String id) {
        SceneNode cursor = ObjectGroupEditorTestSupport.findText(scope, id);
        for (int i = 0; i < 6 && cursor != null; i++) {
            if (cursor.getBorderWidth() == 1) {
                return cursor;
            }
            cursor = cursor.__getParent();
        }
        return null;
    }

    private static SceneNode menuButtonOf(SceneNode row) {
        if (row == null) {
            return null;
        }
        SceneNode glyph = ObjectGroupEditorTestSupport.findText(row, OVERFLOW_GLYPH);
        return glyph == null ? null : glyph.__getParent();
    }

    /** UILib FocusManager 注册表探针（Tab 环成员真值）。 */
    private Set<SceneNode> focusables(SceneRuntime rt) {
        try {
            Object router = rt.getInputRouter();
            Method manager = router.getClass().getDeclaredMethod("__getFocusManager");
            manager.setAccessible(true);
            Object focusManager = manager.invoke(router);
            Method snapshot = focusManager.getClass().getDeclaredMethod("__getFocusables");
            snapshot.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<SceneNode> result = (Set<SceneNode>) snapshot.invoke(focusManager);
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("UILib focusables 探针不可用: " + failure, failure);
        }
    }

    private boolean isFocusable(SceneRuntime rt, SceneNode node) {
        return node != null && focusables(rt).contains(node);
    }

    private int ringMenuCount(List<SceneNode> rows) {
        Set<SceneNode> ring = focusables(harness.rt);
        int count = 0;
        for (SceneNode row : rows) {
            SceneNode menu = menuButtonOf(row);
            if (menu != null && ring.contains(menu)) {
                count++;
            }
        }
        return count;
    }

    private static int selectedIndex(List<SceneNode> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getBackgroundColor() != 0) {
                return i;
            }
        }
        return -1;
    }

    private static int visibleRowCount(List<SceneNode> rows, SceneNode viewport) {
        AnchorRect view = SceneGeometry.absoluteBox(viewport, 0, 0);
        int count = 0;
        for (SceneNode row : rows) {
            AnchorRect box = SceneGeometry.absoluteBox(row, 0, 0);
            int top = Math.max(box.getY(), view.getY());
            int bottom = Math.min(box.getBottom(), view.getBottom());
            if (bottom - top > 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 视口需求高：2 × 行高先验（与 {@code ObjectGroupListPane.rowHeight} 同口径派生，生效字号驱动）。
     *
     * <p>不计行间距：极窄高下「2 行高」已是最低可读口径，行间距另由可见行数断言兜底
     * （真实行盒 62px 远高于 36px 先验，只靠视口高反推可见行数会失真）。</p>
     */
    private int minViewportHeight(SceneNode node) {
        int lineHeight = harness.rt.lineHeight(node.effectiveFontSize());
        int rowHeight = Math.max(ROW_MIN_HEIGHT_PX, lineHeight + 2 * ROW_PADDING_PX);
        return 2 * rowHeight;
    }

    private static AnchorRect rect(SceneNode node) {
        return SceneGeometry.absoluteBox(node, 0, 0);
    }

    private static String box(SceneNode node) {
        if (node == null) {
            return "null";
        }
        AnchorRect rect = rect(node);
        return "[" + rect.getX() + "," + rect.getY() + " " + rect.getWidth() + "x" + rect.getHeight() + "]";
    }

    private static String describe(SceneNode node) {
        if (node == null) {
            return "null";
        }
        String text = node.getText();
        return (text == null || text.isEmpty() ? "<no-text>" : "'" + text + "'") + box(node);
    }

    private void record(String line) {
        readings.add(line);
        System.out.println("[listfix] " + line);
    }

    private void writeEvidence() {
        if (readings.isEmpty()) {
            return;
        }
        String path = System.getProperty(EVIDENCE_KEY, EVIDENCE_DEFAULT);
        StringBuilder out = new StringBuilder();
        out.append("==== ").append(testName.getMethodName()).append(" ====\n");
        for (String line : readings) {
            out.append("  ").append(line).append('\n');
        }
        try {
            Files.createDirectories(Paths.get(path).getParent());
            Files.write(Paths.get(path), out.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception failure) {
            System.out.println("[listfix] evidence 写入失败: " + failure);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
