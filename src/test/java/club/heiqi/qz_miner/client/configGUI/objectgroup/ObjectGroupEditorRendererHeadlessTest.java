package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.client.picker.BlockPickerProvider;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.uilib.ui.reactive.ReactiveScheduler;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.layout.AnchorRect;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometry;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.theme.SceneTheme;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * M9：对象组编辑视图（M1 + M2/M3/M4/M5）的 headless 集成测试。
 *
 * <p>Miner 侧自建 harness（{@link ObjectGroupEditorTestSupport.Harness}）驱动
 * 「字段 renderer → 摘要卡 → 浮层编辑视图 → 输入注入」。</p>
 *
 * <p><b>当前状态（2026-09-12）</b>：L1 布局缺陷（ROW grow 先验闸门被固定兄弟撞掉 ⇒ 行内文本零宽、
 * 「完成」按钮落在视口外）使「指针点行 / 点按钮」不可达；依赖选中行的断言统一用
 * {@link #assumeRowsInteractive()} 显式跳过并标注「受阻」，L1 修复后自动生效。
 * 结构性断言（浮层数、挡位派生、fillParent、初始焦点、ESC 关闭）不依赖 L1，全部实跑。</p>
 */
public class ObjectGroupEditorRendererHeadlessTest {

    private static final int WIDE = 700;
    private static final int NARROW = 500;
    private static final int HEIGHT = 420;

    private File tempDir;
    private ConfigManager manager;
    private ObjectGroupEditorTestSupport.RendererFixture fixture;
    private ObjectGroupEditorTestSupport.Harness harness;
    private SceneNode card;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-objectgroup-view-").toFile();
        manager = ObjectGroupEditorTestSupport.bootstrap(tempDir);
        fixture = new ObjectGroupEditorTestSupport.RendererFixture(manager);
        harness = new ObjectGroupEditorTestSupport.Harness(WIDE, HEIGHT);
        card = fixture.renderer.render(harness.rt, fixture.spec, fixture.adapter);
        harness.root.appendChild(card);
        harness.frame();
    }

    @After
    public void tearDown() {
        if (harness != null) {
            harness.close();
        }
        if (fixture != null) {
            fixture.dispose();
        }
        ConfigBootstrap.resetForTests();
        ReactiveScheduler.get().reset();
        delete(tempDir);
    }

    // ==================================================================
    // §9.1-1 开屏零 picker；打开编辑视图也不预建 picker
    // ==================================================================

    @Test
    public void fieldMountTouchesNoPickerAndRegistersNoOverlay() {
        Assert.assertEquals("字段挂载不得注册浮层", 0, harness.overlayCount());
        Assert.assertEquals("字段挂载不得构建 picker（版本桥探针 = 0）",
                0, fixture.probe.environmentPushes());
        Assert.assertNull("字段子树不得出现 picker 面板文案", findText(card, pickerTitle()));
        Assert.assertTrue("摘要卡必须就地显示摘要", hasText(card,
                ObjectGroupEditorFieldRenderer.summaryTextOf(
                        new ObjectGroupEditorState(fixture.spec, fixture.adapter))));
        Assert.assertNotNull("摘要卡必须有入口按钮", findText(card, manageLabel()));
    }

    @Test
    public void openingEditorDoesNotPrebuildPicker() {
        openEditor();
        Assert.assertEquals("只允许编辑视图自身一个浮层", 1, harness.overlayCount());
        Assert.assertEquals("打开编辑视图不得预建 picker", 0, fixture.probe.environmentPushes());
        Assert.assertNull("编辑视图子树不得出现 picker 面板文案",
                findText(editorRoot(), pickerTitle()));
    }

    /**
     * §9.1-1 的正向对照：显式请求「添加成员…」后 picker 面板必须被构建（证明探针有效）。
     *
     * <p>L1 布局缺陷下按钮指针不可达（文本盒落在视口外），故用 TAB + ENTER 键盘路径；
     * 键盘不可达时显式跳过（AssumptionViolated 会标 SKIPPED，不会当作通过）。</p>
     */
    @Test
    public void explicitAddRequestBuildsPickerOnlyThen() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();
        harness.click(row("vanilla_logs"));
        harness.pressKey(SceneKey.ENTER);
        Assert.assertNotNull("详情必须给出添加成员入口", findText(editorRoot(), addMemberLabel()));
        Assert.assertEquals("成员区构建不得预建 picker", 0, fixture.probe.environmentPushes());

        boolean requested = false;
        for (int tab = 0; tab < 24 && !requested; tab++) {
            harness.pressKey(SceneKey.TAB);
            SceneNode focused = harness.focused();
            if (focused != null && hasText(focused, addMemberLabel())) {
                harness.pressKey(SceneKey.ENTER);
                requested = fixture.probe.environmentPushes() >= 1;
            }
        }
        Assert.assertTrue("经 TAB+ENTER 显式请求后 picker 面板必须被构建（探针 > 0）", requested);
        Assert.assertEquals("picker 打开后应为两层浮层", 2, harness.overlayCount());
        Assert.assertNotNull("栈顶浮层必须是 picker 面板", findText(harness.topRoot(), pickerTitle()));
    }

    // ==================================================================
    // §9.1-3 开关回收：entry 归零、反复开关不增长、effect 回落基线
    // ==================================================================

    @Test
    public void escapeClosesEditorAndRepeatedCyclesDoNotGrow() {
        int baseline = ObjectGroupEditorTestSupport.effectCount();
        Assert.assertTrue("effect 计数探针必须可用（反射转发 ReactiveScheduler 计数）", baseline >= 0);

        openEditor();
        int opened = ObjectGroupEditorTestSupport.effectCount();
        Assert.assertTrue("打开视图必须新增 effect", opened > baseline);
        Assert.assertEquals(1, harness.overlayCount());

        closeByEscape();
        Assert.assertEquals("ESC 关闭后本视图 entry 归零", 0, harness.overlayCount());
        int closed = ObjectGroupEditorTestSupport.effectCount();
        Assert.assertTrue("关闭后 effect 必须回落基线（不泄漏）: baseline=" + baseline + " closed=" + closed,
                closed <= baseline);

        for (int cycle = 0; cycle < 5; cycle++) {
            openEditor();
            Assert.assertEquals("第 " + cycle + " 轮打开应恰好 1 个 entry", 1, harness.overlayCount());
            closeByEscape();
            Assert.assertEquals("第 " + cycle + " 轮关闭应归零", 0, harness.overlayCount());
        }
        Assert.assertTrue("反复开关不得泄漏 effect",
                ObjectGroupEditorTestSupport.effectCount() <= baseline);
    }

    // ==================================================================
    // §5.1 / §8.2-U2：非锚定 portal 内容根显式 fillParent 后占满视口
    // ==================================================================

    @Test
    public void editorRootFillsOverlayViewport() {
        openEditor();
        Object cached = editorRoot().getCachedLayout();
        Assert.assertTrue("编辑视图根必须有布局盒", cached instanceof LayoutBox);
        Assert.assertEquals("编辑面必须占满视口宽", WIDE, ((LayoutBox) cached).getWidth());
        Assert.assertEquals("编辑面必须占满视口高", HEIGHT, ((LayoutBox) cached).getHeight());
    }

    // ==================================================================
    // §9.1-6 挡位切换（宽度信号驱动）—— 结构性部分不依赖选中行
    // ==================================================================

    @Test
    public void widthDerivedModeSwitchIsStructural() {
        openEditor();
        Assert.assertEquals("宽挡 = 列表 + 详情两个同级滚动面", 2, countScrollable(editorRoot()));

        harness.resize(619, HEIGHT);
        harness.frame();
        Assert.assertEquals("619 必须是窄挡（列表一层滚动面）", 1, countScrollable(editorRoot()));
        Assert.assertFalse("窄挡未下钻时不得构建详情", hasText(editorRoot(), idLabel()));

        harness.resize(620, HEIGHT);
        harness.frame();
        Assert.assertEquals("620 必须是宽挡（回到并排）", 2, countScrollable(editorRoot()));
    }

    /**
     * §9.1-6 的「选中项在挡位切换后不丢」。
     *
     * <p>窄挡下列表占满整宽，行内文本有非零盒（L1 修复前的宽挡零宽问题见
     * {@link #layoutKeepsRowContentAndTopBarInsideViewport} 的回归背景），故选行在窄挡完成，
     * 再经窄→宽→窄切换，用下钻标题暴露选中项。</p>
     */
    @Test
    public void selectionSurvivesModeSwitch() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();
        harness.click(row("vanilla_hay"));

        harness.resize(WIDE, HEIGHT);
        harness.frame();
        Assert.assertEquals("切到宽挡仍为并排", 2, countScrollable(editorRoot()));

        harness.resize(NARROW, HEIGHT);
        harness.frame();
        harness.pressKey(SceneKey.ENTER);
        Assert.assertTrue("窄挡 Enter 必须下钻", hasText(editorRoot(), narrowBackLabel()));
        Assert.assertTrue("挡位切换后选中项不丢", hasText(editorRoot(), "vanilla_hay"));
    }

    // ==================================================================
    // §9.1-7 键盘：初始焦点不在删除控件 + ESC 关闭（可实跑部分）
    // ==================================================================

    @Test
    public void initialFocusIsNotOnDestructiveControlAndEscapeCloses() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();

        SceneNode focused = harness.focused();
        Assert.assertNotNull("编辑视图必须有初始焦点", focused);
        Assert.assertFalse("初始焦点绝不落在删除控件上",
                ObjectGroupEditorTestSupport.insideText(focused, removeMemberLabel()));

        closeByEscape();
        Assert.assertEquals("列表态 ESC 关闭视图", 0, harness.overlayCount());
    }

    /**
     * §9.1-7 的 ↑/↓ 选中 + Enter 下钻（窄挡，可实跑部分）。
     */
    @Test
    public void arrowNavigationChangesSelectionAndDrills() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();
        harness.click(row("vanilla_hay"));

        harness.pressKey(SceneKey.ARROW_DOWN);
        harness.pressKey(SceneKey.ENTER);
        Assert.assertTrue("窄挡 Enter 必须下钻", hasText(editorRoot(), narrowBackLabel()));
        Assert.assertTrue("↑/↓ 必须改变选中项（下钻标题显示下一行）",
                hasText(editorRoot(), "vanilla_redstone"));
    }

    /**
     * 必须由 owner 修（M2，{@link ObjectGroupEditorView} 窄挡下钻）：下钻后列表 pane 被
     * {@code rt.show} 卸载，而实现未把焦点移交详情（详情根只在构建时 {@code rt.focusable(pane)}，
     * 未 {@code requestFocus}）⇒ 键盘焦点为空，ESC 不会被派发（{@code SceneInputRouter:568,585}
     * 无焦点即丢弃）⇒ §5.7「详情 Esc 窄挡返回列表」不可达。
     *
     * <p>本用例把现状钉住（绿色）；修复后应改为断言焦点非空且 ESC 返回列表。</p>
     */
    @Test
    public void narrowDrillKeepsFocusAndEscapeReturnsToList() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();
        harness.click(row("vanilla_hay"));
        harness.pressKey(SceneKey.ENTER);
        Assert.assertTrue("下钻本身已生效", hasText(editorRoot(), narrowBackLabel()));

        // 1) 下钻后焦点必须仍在编辑视图内（列表被卸载 ⇒ 必须移交详情，否则键盘事件被 Router 丢弃）。
        SceneNode focused = harness.focused();
        Assert.assertNotNull("下钻后焦点不得为空", focused);
        Assert.assertTrue("下钻后焦点必须留在编辑视图内（focused=" + focused + "）",
                ObjectGroupEditorTestSupport.descendants(editorRoot()).contains(focused));

        // 2) 窄挡详情 ESC = 返回列表（浮层保持打开）——走 M1 portal 的 dismissRequest 单一通路。
        harness.pressKey(SceneKey.ESCAPE);
        Assert.assertFalse("窄挡详情 ESC 必须返回列表",
                hasText(editorRoot(), narrowBackLabel()));
        Assert.assertEquals("返回列表后编辑视图必须保持打开", 1, harness.overlayCount());
        Assert.assertTrue("返回列表后必须回到列表视图", hasText(editorRoot(), "vanilla_hay"));

        // 3) 返回列表后焦点必须回到列表，且键盘导航立刻可用（ARROW_DOWN + ENTER 再下钻到下一行）。
        SceneNode afterReturn = harness.focused();
        Assert.assertNotNull("返回列表后必须有焦点", afterReturn);
        Assert.assertTrue("返回列表后焦点必须留在编辑视图内（focused=" + afterReturn + "）",
                ObjectGroupEditorTestSupport.descendants(editorRoot()).contains(afterReturn));
        harness.pressKey(SceneKey.ARROW_DOWN);
        harness.pressKey(SceneKey.ENTER);
        Assert.assertTrue("返回列表后 Enter 必须可再次下钻", hasText(editorRoot(), narrowBackLabel()));
        Assert.assertTrue("返回列表后 ↑/↓ 应继续导航到下一行", hasText(editorRoot(), "vanilla_redstone"));

        // 4) 详情态 ESC 回列表；列表态 ESC 关闭视图（单一 ESC 通路的另一半）。
        harness.pressKey(SceneKey.ESCAPE);
        Assert.assertFalse("详情态 ESC 必须回列表", hasText(editorRoot(), narrowBackLabel()));
        Assert.assertEquals("回列表后编辑视图保持打开", 1, harness.overlayCount());
        harness.pressKey(SceneKey.ESCAPE);
        Assert.assertEquals("列表态 ESC 必须关闭视图", 0, harness.overlayCount());
    }

    // ==================================================================
    // §9.1-9 / §9.1-10 UI 路径：删除 + 撤销、关闭再打开草稿存活
    // ==================================================================

    @Test
    public void deleteUndoAndDraftSurvivalAcrossReopen() {
        harness.resize(NARROW, HEIGHT);
        harness.frame();
        openEditor();
        harness.click(row("vanilla_hay"));
        harness.pressKey(SceneKey.DELETE);
        Assert.assertFalse("Delete 必须删除选中组", hasText(editorRoot(), "vanilla_hay"));
        Assert.assertTrue("删除后必须给出撤销入口", hasText(editorRoot(), undoLabel()));
        Assert.assertTrue("删除必须写入草稿（dirty）",
                Boolean.TRUE.equals(fixture.adapter.dirtySignal(ObjectGroupEditorState.PATH).get()));

        closeByEscape();
        Object draftAfterDelete = ObjectGroupEditorTestSupport.deepCopy(
                fixture.adapter.draftSignal(ObjectGroupEditorState.PATH).get());
        openEditor();
        SceneNode reopened = editorRoot();
        Assert.assertFalse("重开后不得出现已删除组", hasText(reopened, "vanilla_hay"));
        Assert.assertTrue("重开后撤销入口仍在（state 与草稿同一真值）", hasText(reopened, undoLabel()));
        Assert.assertEquals("关闭/重开不得改写草稿", draftAfterDelete,
                fixture.adapter.draftSignal(ObjectGroupEditorState.PATH).get());
        Assert.assertEquals("重开摘要必须与草稿派生一致",
                ObjectGroupEditorFieldRenderer.summaryTextOf(
                        new ObjectGroupEditorState(fixture.spec, fixture.adapter)),
                topBarSummary(reopened));
    }

    // ==================================================================
    // L1 缺陷现状钉住（须由 owner 修；修复后本用例应改为断言布局健全）
    // ==================================================================

    /**
     * L1 修复后：ROW grow 分配不得被固定兄弟撞掉（行内文本非零宽、收尾按钮在视口内、滚动面有实高）。
     *
     * <p>机制回顾：{@code SceneButton} 根节点自身无文本、无 {@code preferredWidth}，作为 ROW 固定兄弟
     * 时 {@code ConstraintResolver.priorKnownChildWidth} 返回 UNCONSTRAINED，会撞掉整条
     * {@code computeRowGrowWidths} 分配（WARN + 回退 shrink）。修复：动作按钮显式测量设宽高、
     * 容器固定兄弟用 {@code setCollapsed} 声明退出布局域、文本固定兄弟加宽上限。</p>
     */
    @Test
    public void layoutKeepsRowContentAndTopBarInsideViewport() {
        openEditor();

        // 1) 行内主内容非零宽：GROW 分配必须真的落到标题槽上。
        SceneNode id = findText(editorRoot(), "vanilla_logs");
        Assert.assertNotNull("列表行必须在树里（结构已挂载）", id);
        AnchorRect idBox = SceneGeometry.absoluteBox(id, 0, 0);
        Assert.assertTrue("行内 id 文本必须非零宽（实测 " + idBox.getWidth() + "）", idBox.getWidth() > 0);

        // 2) 顶部条收尾按钮在视口内：grow spacer 拿到剩余宽，按钮不被推出画布。
        SceneNode done = findText(editorRoot(), doneLabel());
        Assert.assertNotNull(done);
        AnchorRect doneBox = SceneGeometry.absoluteBox(done, 0, 0);
        Assert.assertTrue("「完成」按钮必须落在视口内（x=" + doneBox.getX() + " w=" + doneBox.getWidth()
                + "，视口宽 " + WIDE + "）",
                doneBox.getX() >= 0 && doneBox.getX() + doneBox.getWidth() <= WIDE);

        // 3) 两个滚动面（列表 + 详情）都必须拿到实高，而不是残值。
        SceneNode listViewport = null;
        int scrollableHeight = 0;
        for (SceneNode node : ObjectGroupEditorTestSupport.descendants(editorRoot())) {
            if (node.isScrollable()) {
                scrollableHeight += SceneGeometry.absoluteBox(node, 0, 0).getHeight();
                if (listViewport == null) {
                    listViewport = node;
                }
            }
        }
        Assert.assertTrue("列表与详情滚动面必须各有实高（合计 " + scrollableHeight + "）",
                scrollableHeight > HEIGHT);

        // 4) 行内主内容必须落在列表视口内（不是被推出去/裁掉到视口外）。
        Assert.assertNotNull("列表视口必须在树里", listViewport);
        AnchorRect vpBox = SceneGeometry.absoluteBox(listViewport, 0, 0);
        Assert.assertTrue("行内 id 必须落在列表视口内（id=" + idBox + " vp=" + vpBox + "）",
                idBox.getX() >= vpBox.getX()
                        && idBox.getX() + idBox.getWidth() <= vpBox.getX() + vpBox.getWidth());

        // 5) 宽挡列表行必须指针可达：点行 → 详情内容出现 → Delete 只删掉该行（证明点中的就是这一行）。
        SceneNode row = findText(editorRoot(), "vanilla_logs");
        Assert.assertNotNull("列表行必须在树里", row);
        harness.click(row);
        Assert.assertTrue("宽挡点行必须选中并构建详情内容", hasText(editorRoot(), idLabel()));
        harness.pressKey(SceneKey.DELETE);
        Assert.assertFalse("Delete 必须删掉被点中的组", hasText(editorRoot(), "vanilla_logs"));
        Assert.assertTrue("其它组不得受牵连", hasText(editorRoot(), "vanilla_hay"));

        // 6) 宽挡「完成」按钮指针可达：L1 前在视口外（点不到），现在必须能点中并关闭视图。
        harness.click(findText(editorRoot(), doneLabel()));
        Assert.assertEquals("宽挡点击「完成」必须关闭编辑视图", 0, harness.overlayCount());
    }

    // ==================================================================
    // D6：结构错误行的列表可见性（Flag.ERROR + ListPane 回退 双保险）
    // ==================================================================

    /**
     * D6 收敛验证（渲染层）：重复 id 的行必须出现**恰好一处**错误状态词，用主题 error 色；
     * 结构错误不得冒充「冲突」状态词。
     */
    @Test
    public void structuralErrorRowShowsErrorStateInList() {
        installDraft(new ArrayList<Object>(Arrays.asList(
                group("dup", Arrays.asList("chain_base"), "minecraft:log@0"),
                group("dup", Arrays.asList("chain_ore"), "minecraft:stone@0"))));

        openEditor();
        List<SceneNode> errorStates = findAllText(editorRoot(), errorStateLabel());
        Assert.assertEquals("错误状态词只应出现在出错的那一行", 1, errorStates.size());
        Assert.assertEquals("错误状态词必须用主题 error 色",
                SceneThemes.errorText(harness.rt).get().intValue(),
                errorStates.get(0).getTextColor());
        Assert.assertNull("结构错误不得渲染出冲突状态词",
                findText(editorRoot(), conflictStateLabel()));
        Assert.assertNotNull("出错行仍必须在列表里（布局/结构未被破坏）",
                findText(editorRoot(), "dup"));
    }

    /**
     * D7 修复验证（渲染层）：行同时带「结构错误（error 级）」与「未生效（warning 级）」时，
     * 列表必须显示错误状态词 + 主题 error 色（error 级胜出），不得再显示「未生效」；
     * 结构错误不得冒充冲突。
     */
    @Test
    public void inactiveRowWithStructuralErrorShowsErrorInList() {
        installDraft(new ArrayList<Object>(Arrays.asList(
                group("bad", new ArrayList<String>(), "not-a-selector"))));

        openEditor();
        List<SceneNode> errorStates = findAllText(editorRoot(), errorStateLabel());
        Assert.assertEquals("必须恰好 1 处错误状态词（error 级胜出）", 1, errorStates.size());
        Assert.assertEquals("错误状态词必须用主题 error 色",
                SceneThemes.errorText(harness.rt).get().intValue(),
                errorStates.get(0).getTextColor());
        Assert.assertNull("warning 级「未生效」不得再屏蔽 error 级结构错误",
                findText(editorRoot(), inactiveStateLabel()));
        Assert.assertNull("结构错误不得渲染冲突状态词",
                findText(editorRoot(), conflictStateLabel()));
    }

    // ==================================================================
    // F1（适用模式 pill 可见性 / 换行 / 一次点击可达）
    // ==================================================================

    /**
     * F1 修复验证：组 {@code modes=0} 时 7 枚已知模式 pill 必须全部构建、全部落在详情裁剪框内，
     * 且每枚 pill 的实际宽 = 先验宽（文本实测宽 + 左右内边距）—— 证明 ROW 主轴不再把「整行内宽」
     * 下传给每一枚容器 pill（旧行为：pill 宽≈行宽，第 2 枚起被裁剪）。
     */
    @Test
    public void modePillsStayInsideDetailViewportAtWideLayout() {
        openEditor();
        SceneNode listRow = findText(editorRoot(), "vanilla_logs");
        Assert.assertNotNull("列表行必须在树里", listRow);
        harness.click(listRow);
        harness.frame();
        Assert.assertNotNull("点行后详情必须显示「适用模式」", findText(editorRoot(), modesLabel()));

        SceneNode viewport = detailViewport();
        Assert.assertNotNull("详情必须有滚动裁剪视口", viewport);
        AnchorRect clip = SceneGeometry.absoluteBox(viewport, 0, 0);
        List<SceneNode> pills = modePills();
        Assert.assertEquals("7 枚已知模式 pill 必须全部构建", ObjectGroupMode.ids().length, pills.size());

        int visible = 0;
        StringBuilder outside = new StringBuilder();
        for (SceneNode pill : pills) {
            String label = labelOf(pill);
            AnchorRect box = SceneGeometry.absoluteBox(pill, 0, 0);
            Assert.assertTrue("pill 必须非零尺寸: " + label + " " + box,
                    box.getWidth() > 0 && box.getHeight() > 0);
            int expected = ObjectGroupDetailPane.pillWidthPx(harness.rt, label, pill.effectiveFontSize());
            Assert.assertEquals("pill 必须先验宽（不得被 ROW 主轴拉满整行）: " + label,
                    expected, box.getWidth());
            if (box.getX() >= clip.getX() && box.getX() + box.getWidth() <= clip.getX() + clip.getWidth()
                    && box.getY() < clip.getY() + clip.getHeight()
                    && box.getY() + box.getHeight() > clip.getY()) {
                visible++;
            } else {
                outside.append(label).append("=[").append(box.getX()).append(',').append(box.getY())
                        .append(' ').append(box.getWidth()).append('x').append(box.getHeight()).append("] ");
            }
        }
        Assert.assertEquals("7 枚 pill 必须全部落在详情裁剪框内（实测 " + visible + "/7；clip=[" + clip.getX()
                        + "," + clip.getY() + " " + clip.getWidth() + "x" + clip.getHeight() + "]；越界="
                        + (outside.length() == 0 ? "无" : outside.toString()) + "）",
                ObjectGroupMode.ids().length, visible);

        // 可辨识轮廓（F1 后续）：未选中 pill 必须带主题 INDICATOR 角色的 1px 描边 + 胶囊圆角，
        // 描边色只能来自角色配方（edge），本组件不得自行拼 RGB。
        int indicatorEdge = SceneThemes.surface(harness.rt, SceneTheme.Role.INDICATOR)
                .get().getIdle().getEdge();
        Assert.assertTrue("主题 INDICATOR idle edge 必须非透明（轮廓判据前提）",
                alphaOf(indicatorEdge) > 0);
        for (SceneNode pill : pills) {
            Assert.assertEquals("每枚 pill 必须有 1px 描边: " + labelOf(pill), 1, pill.getBorderWidth());
            Assert.assertEquals("pill 描边必须取主题 INDICATOR idle edge（不得自拼色）: " + labelOf(pill),
                    indicatorEdge, pill.getBorderColor());
            Assert.assertTrue("pill 必须有胶囊圆角（取主题角色半径）: " + labelOf(pill),
                    pill.getCornerRadius() > 0);
        }
    }

    /** ARGB alpha 通道。 */
    private static int alphaOf(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /**
     * F1 修复验证：分包随可用宽变化（不是固定行数）。
     *
     * <p>本用例在 headless 原始键文案口径下运行（未注入语言包 ⇒ 模式标签更长、pill 更宽）：
     * 1600 逻辑宽的宽挡详情栏每行可放多枚 pill，收窄到 500（窄挡下钻）后每行只能放 1 枚 ⇒
     * 行数必须严格增加。这同时证明换行输入确实来自<b>外部约束宽</b>（宽度变 → 行数变），
     * 而不是固定分行或按内容自测收敛。</p>
     */
    @Test
    public void modePillsRewrapWhenViewportNarrows() {
        harness.resize(1600, HEIGHT);
        harness.frame();
        openEditor();
        harness.click(row("vanilla_logs"));
        // 两帧：首帧详情以「宽度未知」单列安全退化，外部约束宽在 layoutDone 后写入 signal，
        // 分包结果与行重建在下一帧生效（真机同语义：打开后下一帧收敛）。
        harness.frame();
        harness.frame();
        Assert.assertTrue("宽挡下点行后详情必须可见", hasText(editorRoot(), modesLabel()));
        int wideRows = modeRowCount();
        Assert.assertTrue("宽挡大视口下必须多枚同行（实测 " + wideRows + " 行 / 7 枚）",
                wideRows < ObjectGroupMode.ids().length);

        harness.resize(NARROW, HEIGHT);
        harness.frame();
        if (!hasText(editorRoot(), modesLabel())) {
            harness.pressKey(SceneKey.ENTER);
        }
        harness.frame();
        harness.frame();
        Assert.assertTrue("窄挡下钻后详情必须可见", hasText(editorRoot(), modesLabel()));
        int narrowRows = modeRowCount();
        Assert.assertTrue("收窄后行数必须增加（" + wideRows + " 行 -> " + narrowRows + " 行）",
                narrowRows > wideRows);

        AnchorRect clip = SceneGeometry.absoluteBox(detailViewport(), 0, 0);
        for (SceneNode pill : modePills()) {
            AnchorRect box = SceneGeometry.absoluteBox(pill, 0, 0);
            Assert.assertTrue("换行后每枚 pill 仍须在裁剪框内: " + labelOf(pill) + " " + box + " clip=" + clip,
                    box.getX() >= clip.getX() && box.getX() + box.getWidth() <= clip.getX() + clip.getWidth());
        }
    }

    /**
     * 「两个点击内可达」核验：打开编辑器（第 1 击）+ 点行（第 2 击）后详情就位；
     * 此后对任意一枚模式 pill <b>单击一次</b>即完成该模式的选中（modes=0 的组无需先滚动/展开）。
     */
    @Test
    public void oneClickOnAnyModePillTogglesThatMode() {
        openEditor();
        harness.click(row("vanilla_logs"));
        harness.frame();
        Assert.assertEquals("7 枚已知模式 pill 必须全部就位",
                ObjectGroupMode.ids().length, modePills().size());

        for (int click = 0; click < ObjectGroupMode.ids().length; click++) {
            SceneNode pill = modePills().get(click);
            harness.click(pill);
            harness.frame();
            ObjectGroupEditorState state = new ObjectGroupEditorState(fixture.spec, fixture.adapter);
            ObjectGroupEditorState.RowView view = state.viewOfId("vanilla_logs");
            Assert.assertNotNull("组视图必须在", view);
            Assert.assertEquals("每次单击必须恰好新增一个选中模式（第 " + (click + 1) + " 次点击）",
                    click + 1, view.modes().size());
        }
        Assert.assertNull("选中模式后「未选择模式的组不会生效」必须消失",
                findText(editorRoot(), ClientI18n.tr("config.qz_miner.object_group.modes.none")));
    }

    /** 详情滚动视口：包含「适用模式」文案的最近可滚动祖先（= 视图内唯一详情裁剪框）。 */
    private SceneNode detailViewport() {
        SceneNode cursor = findText(editorRoot(), modesLabel());
        while (cursor != null) {
            if (cursor.isScrollable()) {
                return cursor;
            }
            cursor = cursor.__getParent();
        }
        return null;
    }

    /** 7 枚已知模式 pill（按 {@code ObjectGroupMode.ids()} 顺序；缺失项直接跳过由调用方断言数量）。 */
    private List<SceneNode> modePills() {
        List<SceneNode> pills = new ArrayList<SceneNode>();
        for (String modeId : ObjectGroupMode.ids()) {
            SceneNode text = findText(editorRoot(), modeLabel(modeId));
            if (text != null && text.__getParent() != null) {
                pills.add(text.__getParent());
            }
        }
        return pills;
    }

    /** 模式 pill 的可见行数（按 pill 绝对 y 去重）。 */
    private int modeRowCount() {
        List<Integer> ys = new ArrayList<Integer>();
        for (SceneNode pill : modePills()) {
            int y = SceneGeometry.absoluteBox(pill, 0, 0).getY();
            if (!ys.contains(Integer.valueOf(y))) {
                ys.add(Integer.valueOf(y));
            }
        }
        return ys.size();
    }

    private static String labelOf(SceneNode pill) {
        for (SceneNode child : ObjectGroupEditorTestSupport.descendants(pill)) {
            String text = child.getText();
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String modesLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.modes.label");
    }

    private static String modeLabel(String modeId) {
        return ClientI18n.tr("config.qz_miner.object_group.mode." + modeId);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** 覆盖草稿（模拟外部/异常草稿）：写 draft + 帧收敛，渲染器的 state 经 draft 绑定同步。 */
    private void installDraft(List<Object> groups) {
        fixture.adapter.onFieldEdit(ObjectGroupEditorState.PATH, groups);
        harness.frame();
    }

    private static Map<String, Object> group(String id, List<String> modes, Object... members) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("modes", new ArrayList<Object>(modes));
        group.put("members", new ArrayList<Object>(Arrays.asList(members)));
        return group;
    }

    private void openEditor() {
        SceneNode manage = findText(card, manageLabel());
        Assert.assertNotNull("摘要卡入口按钮缺失", manage);
        harness.click(manage);
        Assert.assertNotNull("打开后必须有编辑视图浮层", harness.editorRoot());
    }

    private void closeByEscape() {
        SceneNode focused = harness.focused();
        Assert.assertNotNull("ESC 关闭需要焦点在编辑视图内", focused);
        harness.pressKey(SceneKey.ESCAPE);
    }

    /** TAB 前进直到焦点落在含指定文案的控件（按钮/输入框）内，返回是否到达。 */
    private boolean focusByTabToText(String text, int maxTabs) {
        for (int i = 0; i < maxTabs; i++) {
            harness.pressKey(SceneKey.TAB);
            SceneNode focused = harness.focused();
            if (focused != null && hasText(focused, text)) {
                return true;
            }
        }
        return false;
    }

    private SceneNode editorRoot() {
        SceneNode root = harness.editorRoot();
        Assert.assertNotNull("编辑视图浮层不存在", root);
        return root;
    }

    private SceneNode row(String id) {
        SceneNode node = findText(editorRoot(), id);
        Assert.assertNotNull("列表行缺失: " + id, node);
        return node;
    }

    /** 顶部条摘要文本：与摘要卡 helper 同口径的第一个匹配文本。 */
    private String topBarSummary(SceneNode editor) {
        String expected = ObjectGroupEditorFieldRenderer.summaryTextOf(
                new ObjectGroupEditorState(fixture.spec, fixture.adapter));
        for (String text : ObjectGroupEditorTestSupport.texts(editor)) {
            if (expected.equals(text)) {
                return text;
            }
        }
        return "<未找到顶部条摘要: " + expected + ">";
    }

    private static String manageLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.manage");
    }

    private static String doneLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.editor.done");
    }

    private static String undoLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.editor.undo_remove");
    }

    private static String addMemberLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.members.add");
    }

    private static String removeMemberLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.members.remove");
    }

    private static String idLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.id.label");
    }

    private static String narrowBackLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.detail.narrow_back");
    }

    private static String pickerTitle() {
        return new BlockPickerProvider().panelPresentation().panelTitle();
    }

    private static String errorStateLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.state.error");
    }

    private static String conflictStateLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.state.conflict");
    }

    private static String inactiveStateLabel() {
        return ClientI18n.tr("config.qz_miner.object_group.state.inactive");
    }

    private static SceneNode findText(SceneNode node, String text) {
        return ObjectGroupEditorTestSupport.findText(node, text);
    }

    private static List<SceneNode> findAllText(SceneNode node, String text) {
        return ObjectGroupEditorTestSupport.findAllText(node, text);
    }

    private static boolean hasText(SceneNode node, String text) {
        return ObjectGroupEditorTestSupport.hasText(node, text);
    }

    private static int countScrollable(SceneNode node) {
        return ObjectGroupEditorTestSupport.countScrollable(node);
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
