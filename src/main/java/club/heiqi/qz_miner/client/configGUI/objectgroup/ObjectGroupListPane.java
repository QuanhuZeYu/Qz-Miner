package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.uilib.ui.reactive.Computed;
import club.heiqi.uilib.ui.reactive.Effect;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneContextMenu;
import club.heiqi.uilib.ui.scene.control.SceneInputType;
import club.heiqi.uilib.ui.scene.control.SceneSegmented;
import club.heiqi.uilib.ui.scene.control.SceneTextInput;
import club.heiqi.uilib.ui.scene.control.search.PickerChrome;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.input.SceneInteractionState;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyAction;
import club.heiqi.uilib.ui.scene.layout.AnchorRect;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.FlexDirection;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneGeometry;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.node.TextHorizontalAlign;
import club.heiqi.uilib.ui.scene.paint.SceneChromeTokens;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.runtime.SceneScrolls;
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceBinder;
import club.heiqi.uilib.ui.scene.theme.SceneTheme;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * 对象组列表 pane（M3）：搜索 + 固定谓词 + 行（状态点/顺序号/id/副行/溢出菜单）+ 双分支空状态。
 *
 * <p><b>唯一真值</b>：本类不自建任何配置真值或校验真值——行、可见行、摘要、冲突、选中全部取自
 * {@link ObjectGroupEditorState}（{@code visibleViews() / viewOf(key) / selectedKey() / summary()}），
 * 编辑全部经 state 命令回到 {@code DraftSignalAdapter}。行内所有动态文本与颜色都按 key 现读
 * {@code state.viewOf(key)}，不缓存 {@link ObjectGroupEditorState.RowView} 快照（快照会随模式、
 * 成员与解析器校验变化而陈旧）。</p>
 *
 * <p><b>键盘（C3 §5.7）</b>：列表键盘焦点 = 行视口（{@code rt.focusable}），初始焦点在搜索框。
 * ↑/↓ 移动唯一选中项并滚动跟随、Menu / Shift+F10 打开当前选中行的溢出菜单、Delete 删除当前选中组
 * （进草稿 + 视图「撤销删除」条，不做二次确认）；这三组键在「列表焦点」与「搜索框焦点」下都生效
 * （导航非破坏，§5.7 的「列表 ↑/↓」不应要求先 TAB/点行），但 Delete 只在列表焦点生效——搜索框里
 * 的 Delete 属于文本编辑。Enter 不在本类消费：搜索框自己吞掉 Enter，其余场景冒泡到 pane 根后由
 * {@link ObjectGroupEditorView} 承担「窄挡下钻 / 宽挡焦点进详情」。内层按钮经 {@link #claimKeyboard}
 * 独占非 TAB、非 ESC 键，既不顺带触发视图级语义，也不吞掉 TAB（框架焦点遍历）与 ESC（视图关闭）。</p>
 *
 * <p><b>先验尺寸（布局闸门纪律）</b>：行主行是 ROW（标题槽 grow + 状态词/顺序号/状态点/行尾按钮
 * 固定），按 {@code ConstraintResolver} 的闸门要求，行尾按钮显式测量设宽高、标题槽设「行宽 1/3 宽
 * 下限」、状态词与副行设宽上限；工具栏两区（搜索行 / 谓词区 = 分段行 + 计数行）作为 pane COLUMN 的
 * 固定兄弟显式设先验高（COLUMN 的主轴先验高按<b>求和</b>，见 {@code contentHeight}）。列宽下限本身由
 * {@link #minContentWidth} 用当前语言文案实测给出，编辑视图据此派生列宽，保证工具栏永不重叠。
 * 这样首帧布局就不会因「固定兄弟不可先验」而整条放弃（回退 shrink ⇒ 零宽/溢出）。</p>
 *
 * <p><b>菜单锚点口径</b>：编辑视图由 M1 以非锚定 portal 注册（宿主左上角全尺寸），其浮层树根即宿主
 * 原点，故 {@code SceneGeometry.absoluteBox(node, 0, 0)} 与指针事件的 site 逻辑坐标同空间；
 * {@link SceneContextMenu#open} 需要的正是该空间坐标。</p>
 *
 * <p><b>动态化（C3 §6.2）</b>：行高、状态点边长、行尾按钮命中区全部由生效字号派生，颜色全部取自
 * {@link SceneThemes} 主题派生，文案全部经 {@link ClientI18n}；本类不含分辨率、缩放、语言与颜色常量。</p>
 *
 * <p><b>挂载量（C3 §5.4）</b>：行轻量（本类实测约 8-10 节点 + 颜色 binding / 行，≤64 行有界），
 * 首版全量挂载。若真机 64 组成帧热点，按 §5.4 退路换窗口化载体（{@code SceneVirtualGrid} 的
 * spacer 数学），行结构可直接替换，不留长期妥协。</p>
 */
public final class ObjectGroupListPane {

    /** 行高下限（C3 §5.4：{@code max(36, 文本行高 + 2×PAD)}，逻辑 px）。 */
    private static final int ROW_MIN_HEIGHT_PX = 36;
    /** 行内边距（逻辑 px）。 */
    private static final int ROW_PADDING_PX = 6;
    /** 行内主行元素间距（逻辑 px）。 */
    private static final int ROW_GAP_PX = 6;
    /** 行内主行与副行间距（逻辑 px）。 */
    private static final int ROW_LINE_GAP_PX = 2;
    /** 行圆角（逻辑 px）。 */
    private static final int ROW_RADIUS_PX = 4;
    /** pane 内区块间距（逻辑 px）。 */
    private static final int PANE_GAP_PX = 6;
    /** pane 内边距（逻辑 px）。 */
    private static final int PANE_PADDING_PX = 6;
    /** 状态点边长下限（逻辑 px）。 */
    private static final int DOT_MIN_SIDE_PX = 6;
    /** 状态点边长比例（生效字号 × 0.7）。 */
    private static final float DOT_SIDE_RATIO = 0.7F;
    /** 命中区下界（WCAG 2.5.7 目标尺寸，逻辑 px）：行尾溢出按钮不小于该值。 */
    private static final int MIN_HIT_SIDE_PX = 24;
    /** 标题槽（id）宽下限：占行主行宽百分比——行内主内容不得被状态词/按钮挤成零宽。 */
    private static final int TITLE_MIN_WIDTH_PERCENT = 33;
    /** 行状态词宽上限：占行主行宽百分比。 */
    private static final int STATUS_WIDTH_PERCENT = 40;
    /** 谓词区「分段行 / 计数行」两行之间的间距（逻辑 px）：计数不再与分段争主轴宽。 */
    private static final int FILTER_LINE_GAP_PX = 4;
    /** 行尾溢出按钮字形（与语言无关的符号，不参与本地化）。 */
    private static final String OVERFLOW_GLYPH = "\u22EE";

    /** 固定谓词顺序（C3 §5.4 {@code [全部][冲突][未生效][含通配]}）；谓词集合本身由 state 冻结。 */
    private static final List<ObjectGroupEditorState.Filter> FILTER_ORDER =
            Collections.unmodifiableList(Arrays.asList(
                    ObjectGroupEditorState.Filter.ALL,
                    ObjectGroupEditorState.Filter.CONFLICT,
                    ObjectGroupEditorState.Filter.INACTIVE,
                    ObjectGroupEditorState.Filter.WILDCARD));

    private ObjectGroupListPane() {
    }

    /**
     * 构建组列表 pane（M2/M3 冻结签名）。
     *
     * @param ctx 编辑视图宿主上下文
     * @return 可直接 appendChild 的 pane 根节点
     */
    public static SceneNode build(ObjectGroupEditorContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        final SceneRuntime rt = ctx.rt();
        final ObjectGroupEditorState state = ctx.state();
        final Palette palette = new Palette(rt);
        final ReadableSignal<Boolean> enabled = Signal.create(Boolean.TRUE);
        final ReadableSignal<Boolean> readOnly = Signal.create(Boolean.FALSE);
        // 滚动真值唯一持有者：SceneScrolls 的滚轮 handler 与键盘滚动跟随写同一 signal，
        // 跨行集挂载保留位置（不会出现「直接用 setScrollOffsetY 造成 signal 落后」的分叉）。
        final Signal<Integer> scroll = Signal.create(Integer.valueOf(0));
        // 行视口引用：行集挂载期写入，键盘几何（滚动跟随 / 菜单锚点）读它。
        final SceneNode[] viewportRef = new SceneNode[1];

        final SceneNode pane = SceneNode.column();
        pane.setGap(PANE_GAP_PX);
        pane.setPadding(PANE_PADDING_PX);
        pane.setFillParentHeight(true);
        pane.setFillParentWidth(true);
        // 极限文案（未本地化键名回退 / 极窄列）下工具栏可能仍超出列宽：裁剪在 pane 内，
        // 绝不绘制到相邻的详情列上（重叠是可见缺陷，裁剪只是降级）。
        pane.setClipChildren(true);
        bindPaneSurface(rt, pane);

        SceneNode search = buildSearch(rt, state, enabled, readOnly);
        pane.appendChild(search);
        pane.appendChild(buildFilterRow(rt, state, palette, enabled));

        ReadableSignal<Boolean> hasRows = Computed.create(Boolean.FALSE,
                () -> Boolean.valueOf(!state.visibleViews().isEmpty()));
        ReadableSignal<Boolean> empty = Computed.create(Boolean.TRUE,
                () -> Boolean.valueOf(!Boolean.TRUE.equals(hasRows.get())));
        rt.show(pane, empty, () -> buildEmptyState(rt, state, palette));
        rt.show(pane, hasRows, () -> buildRowViewport(rt, state, palette, scroll, viewportRef));

        // 列表键盘：目标为 pane 根 / 行视口（列表焦点），或搜索框（仅导航键）。
        // ENTER 不在此消费 —— 留给 ObjectGroupEditorView 在 pane 根上的「下钻 / 焦点进详情」语义。
        final SceneNode searchBox = search;
        rt.on(pane, SceneEventType.KEY_DOWN, (ev, ectx) -> {
            if (ev.getKeyAction() != SceneKeyAction.PRESSED) {
                return;
            }
            final SceneNode viewport = viewportRef[0];
            SceneNode target = ev.getTarget();
            boolean listFocused = target == pane || (viewport != null && target == viewport);
            boolean searchFocused = target != null && target == searchBox;
            if (!listFocused && !searchFocused) {
                return;
            }
            SceneKey key = ev.getKey();
            if (key == SceneKey.ARROW_DOWN) {
                // 搜索框内也放行：C3 §5.7「列表 ↑/↓」不应要求用户先 TAB/点行（导航非破坏性）。
                moveSelection(rt, state, viewport, scroll, 1);
                ectx.stopPropagation();
            } else if (key == SceneKey.ARROW_UP) {
                moveSelection(rt, state, viewport, scroll, -1);
                ectx.stopPropagation();
            } else if (key == SceneKey.MENU || (key == SceneKey.F10 && ev.isShiftDown())) {
                openSelectedRowMenu(rt, state, viewport, ectx.getTreeRootAbsX(), ectx.getTreeRootAbsY());
                ectx.stopPropagation();
            } else if (key == SceneKey.DELETE && listFocused) {
                // 破坏性键只在列表焦点生效：搜索框里的 Delete 属于文本编辑。
                ObjectGroupEditorState.RowView selected = state.selection();
                if (selected != null) {
                    state.removeGroup(selected.key());
                }
                ectx.stopPropagation();
            }
        });

        // 初始焦点落在搜索框：绝不在删除控件上，同时给视图根的 ESC / Enter 语义一个事件 target。
        rt.requestFocus(search);
        return pane;
    }

    // ------------------------------------------------------------------ 工具条 / 谓词

    /** 搜索行：搜索框独占剩余宽 + 「新建组」（达组数上限时改为结构化提示并禁用）。 */
    private static SceneNode buildSearch(SceneRuntime rt, ObjectGroupEditorState state,
                                         ReadableSignal<Boolean> enabled, ReadableSignal<Boolean> readOnly) {
        SceneNode row = SceneNode.row();
        row.setGap(PANE_GAP_PX);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);

        SceneTextInput.Props props = new SceneTextInput.Props(state.search(), enabled, readOnly,
                ClientI18n.tr("config.qz_miner.object_group.list.search"), Integer.MAX_VALUE,
                SceneInputType.TEXT, next -> state.search().set(next));
        SceneNode search = SceneTextInput.create(rt, props).get();
        search.setFlexGrow(1);
        // 搜索框最小宽 = 占位文案实测 + 控件内边距：列宽策略已保证整行放得下，这里是压缩兜底下界。
        bindSearchMinWidth(rt, search);
        // 搜索框只独占 Enter（避免 Enter 顺带触发视图级下钻）：↑/↓/Menu 放行给列表键盘语义，
        // 其余键由 pane 级守卫按事件 target 区分（Delete 只在列表焦点生效）。
        rt.on(search, SceneEventType.KEY_DOWN, (ev, ectx) -> {
            if (ev.getKey() == SceneKey.ENTER) {
                ectx.stopPropagation();
            }
        });
        row.appendChild(search);

        ReadableSignal<Boolean> canAddGroup = Computed.create(Boolean.TRUE, () -> Boolean.valueOf(
                state.summary().groupCount() < ObjectGroupRuleSet.MAX_GROUPS));
        ReadableSignal<String> addLabel = Computed.create(
                ClientI18n.tr("config.qz_miner.object_group.list.add"),
                () -> Boolean.TRUE.equals(canAddGroup.get())
                        ? ClientI18n.tr("config.qz_miner.object_group.list.add")
                        : ClientI18n.tr("config.qz_miner.object_group.limit.groups"));
        SceneNode add = searchActionButton(rt, row, search, addLabel, canAddGroup,
                () -> state.addGroup());
        row.appendChild(add);
        // 搜索行是 pane COLUMN 的固定兄弟（同排还有行视口 grow 子）：高度必须先验可算。
        bindPriorHeight(rt, row);
        return row;
    }

    /**
     * 谓词区（两行）：分段行 = 固定谓词四选一（{@code SceneSegmented}）；计数行 = 可见条数。
     *
     * <p><b>为什么拆两行</b>：分段控件是 {@code SHRINK} 段 + 段间距的水平底座，它的最小宽由
     * 「四段文案实测 + 段内边距 + 段间距」决定、不能压缩；计数文本是行内固定兄弟，其先验宽会先被
     * 扣掉，剩余宽再给分段控件 —— 二者同行时若列宽不足，分段控件内部各段会横向溢出自身盒并
     * <b>直接绘制在计数文本之上</b>（真机症状：「含通配」chip 被「3 组」压住、chip 右边框被盖掉）。
     * 拆行后两者各自占满整行宽，任何列宽下都不重叠。</p>
     *
     * <p>两行都是 pane COLUMN 的固定兄弟：整块高必须先验可算（{@link #bindPriorHeight}）。</p>
     */
    private static SceneNode buildFilterRow(SceneRuntime rt, ObjectGroupEditorState state,
                                            Palette palette, ReadableSignal<Boolean> enabled) {
        final SceneNode block = SceneNode.column();
        block.setGap(FILTER_LINE_GAP_PX);

        SceneNode row = SceneNode.row();
        row.setGap(PANE_GAP_PX);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);

        final Signal<Integer> index = Signal.create(Integer.valueOf(0));
        rt.bind(state.filter(), current -> index.set(Integer.valueOf(filterIndexOf(current))));
        List<String> labels = new ArrayList<String>(FILTER_ORDER.size());
        for (ObjectGroupEditorState.Filter filter : FILTER_ORDER) {
            labels.add(ClientI18n.tr(filterKey(filter)));
        }
        SceneSegmented.Props props = new SceneSegmented.Props(index, labels, enabled, selected -> {
            int value = selected.intValue();
            if (value >= 0 && value < FILTER_ORDER.size()) {
                state.filter().set(FILTER_ORDER.get(value));
            }
        });
        SceneNode segmented = SceneSegmented.create(rt, props).get();
        claimKeyboard(rt, segmented);
        // 分段控件作 grow 子而非固定兄弟：既不必先验宽（闸门不适用），宽文案也不会把同排文本挤出画布。
        segmented.setFlexGrow(1);
        row.appendChild(segmented);
        block.appendChild(row);

        // 计数独立一行、贴右：与分段行共享整行宽，不再互抢主轴空间。
        final SceneNode countRow = SceneNode.row();
        countRow.setCrossAxisAlign(CrossAxisAlign.CENTER);
        SceneNode spacer = new SceneNode();
        spacer.setHitTestable(false);
        spacer.setFlexGrow(1);
        countRow.appendChild(spacer);
        final SceneNode count = label("");
        count.setMaxLines(1);
        count.setEllipsis(true);
        count.setTextHorizontalAlign(TextHorizontalAlign.RIGHT);
        rt.bindComputed(() -> ClientI18n.tr("config.qz_miner.object_group.list.count",
                Integer.valueOf(state.visibleViews().size())), count::setText);
        rt.bind(palette.muted, count::setTextColor);
        countRow.appendChild(count);
        block.appendChild(countRow);

        // 计数文本宽上限 = 承载行宽：超长文案（未本地化键名 / 极窄列）只省略、不越界。
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            Object cached = countRow.getCachedLayout();
            if (cached instanceof LayoutBox) {
                int width = ((LayoutBox) cached).getWidth();
                if (width > 0) {
                    count.setMaxWidth(Math.max(1, width - countRow.getPaddingLeft()
                            - countRow.getPaddingRight()));
                }
            }
        }));
        // 谓词区是 pane COLUMN 的固定兄弟：整块高必须先验可算。
        bindPriorHeight(rt, block);
        return block;
    }

    // ------------------------------------------------------------------ 行

    /** 行视口：独立滚动容器（{@code SceneScrolls} 为本类唯一滚轮汇点），行按 keyed 渲染。 */
    private static SceneNode buildRowViewport(SceneRuntime rt, ObjectGroupEditorState state,
                                              Palette palette, Signal<Integer> scroll, SceneNode[] viewportRef) {
        final SceneNode viewport = SceneNode.column();
        viewport.setGap(PANE_GAP_PX);
        viewport.setScrollable(true);
        viewport.setClipChildren(true);
        viewport.setFillParentHeight(true);
        viewport.setFillParentWidth(true);
        // COLUMN 主轴显式 grow：视口是 pane 里唯一吃剩余高的子，声明 grow 后 pane 的主轴先验高
        // 不再被「视口内容高」估算放大（内容超高时 pane 只滚动，不撑高宿主）。
        viewport.setFlexGrow(1);
        viewportRef[0] = viewport;
        // 调用方自管 scroll state 形态：同一 signal 同时服务滚轮、键盘滚动跟随与跨挂载位置保持。
        SceneScrolls.attach(rt, viewport, scroll, scroll::set);

        // 每次布局后把偏移夹回有界范围：行集在空↔非空之间切换后旧偏移可能超过新内容高。
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            int max = Math.max(0, SceneGeometry.maxScrollY(viewport));
            if (scroll.get().intValue() > max) {
                scroll.set(Integer.valueOf(max));
            }
        }));

        rt.focusable(viewport);
        rt.forEach(viewport, Computed.create(
                        Collections.<ObjectGroupEditorState.RowView>emptyList(), state::visibleViews),
                row -> Long.valueOf(row.key()),
                row -> buildRow(rt, state, viewport, palette, row));
        return viewport;
    }

    /** 单行：主行（状态点 / 顺序号 / id / 状态词 / 溢出菜单）+ 副行（模式数 · 成员数）。 */
    private static SceneNode buildRow(SceneRuntime rt, final ObjectGroupEditorState state,
                                      final SceneNode viewport, final Palette palette,
                                      final ObjectGroupEditorState.RowView row) {
        final long key = row.key();

        final SceneNode root = SceneNode.column();
        root.setGap(ROW_LINE_GAP_PX);
        root.setPadding(ROW_PADDING_PX);
        root.setBorderWidth(1);
        root.setCornerRadius(ROW_RADIUS_PX);

        ReadableSignal<Boolean> selected = Computed.create(Boolean.FALSE,
                () -> Boolean.valueOf(state.selectedKey().get().longValue() == key));
        rt.bindComputed(() -> Integer.valueOf(Boolean.TRUE.equals(selected.get())
                        ? palette.selectionBackground.get().intValue() : 0),
                root::setBackgroundColor);
        rt.bindComputed(() -> Integer.valueOf(Boolean.TRUE.equals(selected.get())
                        ? palette.borderFocus.get().intValue() : palette.borderDefault.get().intValue()),
                root::setBorderColor);

        final SceneNode header = SceneNode.row();
        header.setGap(ROW_GAP_PX);
        header.setCrossAxisAlign(CrossAxisAlign.CENTER);
        // 点行主区即选中；焦点交给行视口，使点击后 ↑/↓/Menu/Delete 立即可用。
        rt.on(header, SceneEventType.CLICK, (ev, ectx) -> {
            state.select(key);
            rt.requestFocus(viewport);
        });

        final SceneNode dot = new SceneNode();
        dot.setHitTestable(false);
        header.appendChild(dot);

        final SceneNode order = label("");
        header.appendChild(order);

        final SceneNode titleSlot = SceneNode.row();
        titleSlot.setFlexGrow(1);
        // FILL 而非 SHRINK：SHRINK 槽宽由「子节点上一轮 cache」推导，首帧 id 文本尚未落值时
        // 槽宽塌成 0 并自我稳定（文本随之恒零宽）；FILL 直接用 ROW 分配到的份额，且让行尾菜单贴右。
        titleSlot.setWidthSizing(SceneNode.WidthSizing.FILL);
        titleSlot.setClipChildren(true);
        final SceneNode id = label(idText(state, key));
        id.setMaxLines(1);
        id.setEllipsis(true);
        titleSlot.appendChild(id);
        header.appendChild(titleSlot);

        final SceneNode status = label("");
        status.setMaxLines(1);
        status.setEllipsis(true);
        header.appendChild(status);

        final SceneNode menu = buildRowMenu(rt, state, key);
        header.appendChild(menu);
        root.appendChild(header);

        final SceneNode counts = label("");
        counts.setMaxLines(1);
        counts.setEllipsis(true);
        root.appendChild(counts);

        rt.bindComputed(() -> orderText(state, key), order::setText);
        rt.bindComputed(() -> idText(state, key), id::setText);
        rt.bindComputed(() -> countsText(state, key), counts::setText);
        rt.bindComputed(() -> statusText(state, key), status::setText);
        rt.bindComputed(() -> Integer.valueOf(statusColor(state, key, palette)), status::setTextColor);
        rt.bindComputed(() -> Integer.valueOf(statusColor(state, key, palette)), dot::setBackgroundColor);
        rt.bind(palette.foreground, id::setTextColor);
        rt.bind(palette.muted, order::setTextColor);
        rt.bind(palette.muted, counts::setTextColor);

        // 字号派生几何：行高、状态点边长、命中区、id 单行省略宽；字号/DPI/主题变化经 layoutDone 重派生。
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            int fontSizePx = root.effectiveFontSize();
            root.setPreferredHeight(rowHeight(rt, fontSizePx));
            int dotSide = dotSide(fontSizePx);
            dot.setPreferredWidth(dotSide);
            dot.setPreferredHeight(dotSide);
            dot.setCornerRadius(dotSide / 2);
            int hitSide = hitSide(rt, fontSizePx);
            menu.setPreferredWidth(hitSide);
            menu.setPreferredHeight(hitSide);
            // 行内横向预算：固定兄弟（状态点 / 顺序号 / 行尾按钮）实测先验宽 + 标题槽下限 +
            // 状态词上限必须落在主行内宽之内。若各写各的（33% 下限 + 40% 上限 + 固定项），
            // 合计会超过 100% ⇒ 状态词与行尾按钮被推出行盒（真机症状：⋮ 压在行边框上）。
            Object headerCached = header.getCachedLayout();
            if (headerCached instanceof LayoutBox) {
                int headerWidth = ((LayoutBox) headerCached).getWidth();
                if (headerWidth > 0) {
                    int inner = Math.max(1, headerWidth - header.getPaddingLeft()
                            - header.getPaddingRight());
                    int fixed = dotSide + rt.measureTextWidth(orderText(state, key), header.effectiveFontSize())
                            + hitSide;
                    int gaps = ROW_GAP_PX * Math.max(0, header.__getChildren().size() - 1);
                    int titleMin = Math.max(1, inner * TITLE_MIN_WIDTH_PERCENT / 100);
                    int statusCap = Math.max(1, inner * STATUS_WIDTH_PERCENT / 100);
                    int statusBudget = Math.max(1, inner - gaps - fixed - titleMin);
                    titleSlot.setPreferredWidth(titleMin);
                    // 上限 0 在布局语义里表示「无上限」，故下界取 1（省略号级别），不得写 0。
                    status.setMaxWidth(Math.min(statusCap, statusBudget));
                }
            }
            Object cached = titleSlot.getCachedLayout();
            if (cached instanceof LayoutBox) {
                int slotWidth = ((LayoutBox) cached).getWidth();
                if (slotWidth > 0) {
                    id.setMaxWidth(slotWidth);
                }
            }
            Object rowCached = root.getCachedLayout();
            if (rowCached instanceof LayoutBox) {
                int rowWidth = ((LayoutBox) rowCached).getWidth();
                if (rowWidth > 0) {
                    counts.setMaxWidth(Math.max(1, rowWidth - 2 * ROW_PADDING_PX));
                }
            }
        }));
        return root;
    }

    /** 行尾溢出菜单按钮：以按钮自身行盒为锚（点与 Enter/Space 走同一路径）。 */
    private static SceneNode buildRowMenu(SceneRuntime rt, final ObjectGroupEditorState state, final long key) {
        final SceneNode[] buttonRef = new SceneNode[1];
        final ReadableSignal<String> glyph = Signal.create(OVERFLOW_GLYPH);
        SceneButton.Props props = SceneButton.Props.builder(glyph)
                // 行尾溢出按钮在行底上几乎同色（真机不可辨）：改走 INDICATOR 角色配方，
                // 由主题给出可见底盘/描边与 hover 反馈，不在业务侧写死颜色。
                .surface(SceneThemes.surface(rt, SceneTheme.Role.INDICATOR))
                .onClick(() -> {
                    SceneNode button = buttonRef[0];
                    if (button == null) {
                        return;
                    }
                    AnchorRect box = SceneGeometry.absoluteBox(button, 0, 0);
                    state.select(key);
                    SceneContextMenu.open(rt, box.getX(), box.getBottom(), rowMenuItems(state, key));
                })
                .build();
        SceneNode button = SceneButton.create(rt, props).get();
        buttonRef[0] = button;
        claimKeyboard(rt, button);
        // 行 header 是 ROW（同排有 titleSlot grow 子）：固定兄弟宽必须先验可算（L1 闸门）。
        bindActionWidth(rt, button, glyph, null, 0);
        // 按钮自身的激活已完成（primitive 先注册先执行），行点击语义不再叠加到同一次点击上。
        rt.on(button, SceneEventType.CLICK, (ev, ectx) -> ectx.stopPropagation());
        return button;
    }

    /** 行溢出菜单项（§5.4：移动 → 复制 → 删除；命令全部来自 state，无本地改写）。 */
    private static List<SceneContextMenu.MenuItem> rowMenuItems(final ObjectGroupEditorState state,
                                                                final long key) {
        List<SceneContextMenu.MenuItem> items = new ArrayList<SceneContextMenu.MenuItem>();
        items.add(SceneContextMenu.MenuItem.of(ClientI18n.tr("config.qz_miner.object_group.list.move_up"),
                () -> state.moveGroupUp(key)));
        items.add(SceneContextMenu.MenuItem.of(ClientI18n.tr("config.qz_miner.object_group.list.move_down"),
                () -> state.moveGroupDown(key)));
        items.add(SceneContextMenu.MenuItem.of(ClientI18n.tr("config.qz_miner.object_group.list.move_top"),
                () -> state.moveGroupTop(key)));
        items.add(SceneContextMenu.MenuItem.of(ClientI18n.tr("config.qz_miner.object_group.list.move_bottom"),
                () -> state.moveGroupBottom(key)));
        items.add(SceneContextMenu.MenuItem.divider());
        items.add(SceneContextMenu.MenuItem.of(ClientI18n.tr("config.qz_miner.object_group.list.duplicate"),
                () -> state.duplicateGroup(key)));
        items.add(SceneContextMenu.MenuItem.divider());
        items.add(SceneContextMenu.MenuItem.of(ClientI18n.tr("config.qz_miner.object_group.list.remove"),
                () -> state.removeGroup(key)));
        return items;
    }

    // ------------------------------------------------------------------ 空状态（双分支）

    /** 空状态双分支（C3 §5.4 D7）：0 组 vs 过滤无命中。 */
    private static SceneNode buildEmptyState(SceneRuntime rt, ObjectGroupEditorState state, Palette palette) {
        SceneNode block = SceneNode.column();
        block.setGap(PANE_GAP_PX);
        block.setPadding(ROW_PADDING_PX);

        ReadableSignal<Boolean> noGroup = Computed.create(Boolean.FALSE,
                () -> Boolean.valueOf(state.summary().isEmpty()));
        rt.show(block, noGroup, () -> message(rt, palette,
                ClientI18n.tr("config.qz_miner.object_group.empty.none")));
        rt.show(block, Computed.create(Boolean.FALSE,
                        () -> Boolean.valueOf(!Boolean.TRUE.equals(noGroup.get()))),
                () -> filteredEmpty(rt, state, palette));
        return block;
    }

    private static SceneNode filteredEmpty(SceneRuntime rt, final ObjectGroupEditorState state, Palette palette) {
        SceneNode block = SceneNode.column();
        block.setGap(PANE_GAP_PX);
        block.appendChild(message(rt, palette, ClientI18n.tr("config.qz_miner.object_group.empty.filtered")));
        SceneNode button = actionButton(rt, null, 0,
                Signal.create(ClientI18n.tr("config.qz_miner.object_group.empty.clear_filter")),
                Signal.create(Boolean.TRUE), () -> {
                    state.search().set("");
                    state.filter().set(ObjectGroupEditorState.Filter.ALL);
                });
        block.appendChild(button);
        return block;
    }

    private static SceneNode message(SceneRuntime rt, Palette palette, String text) {
        SceneNode node = label(text);
        rt.bind(palette.muted, node::setTextColor);
        return node;
    }

    // ------------------------------------------------------------------ 键盘

    /** ↑/↓：移动唯一选中项并让该行滚入视口（选中项是详情真源，挡位切换不丢）。 */
    private static void moveSelection(SceneRuntime rt, ObjectGroupEditorState state,
                                      SceneNode viewport, Signal<Integer> scroll, int delta) {
        if (viewport == null) {
            return;
        }
        List<ObjectGroupEditorState.RowView> visible = state.visibleViews();
        if (visible.isEmpty()) {
            return;
        }
        long selected = state.selectedKey().get().longValue();
        int current = -1;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).key() == selected) {
                current = i;
                break;
            }
        }
        int next = current < 0
                ? (delta > 0 ? 0 : visible.size() - 1)
                : Math.max(0, Math.min(visible.size() - 1, current + delta));
        if (next == current) {
            return;
        }
        state.select(visible.get(next).key());
        scrollIntoView(rt, viewport, scroll, next);
    }

    /** 滚动跟随：行高与视口高都取运行期生效值，不做静态假定。 */
    private static void scrollIntoView(SceneRuntime rt, SceneNode viewport, Signal<Integer> scroll, int index) {
        Object cached = viewport.getCachedLayout();
        if (!(cached instanceof LayoutBox)) {
            return;
        }
        int rowHeight = rowHeight(rt, viewport.effectiveFontSize());
        int top = index * (rowHeight + PANE_GAP_PX);
        int viewportHeight = Math.max(0, ((LayoutBox) cached).getHeight());
        int current = scroll.get().intValue();
        int next = current;
        if (top < current) {
            next = top;
        } else if (top + rowHeight > current + viewportHeight) {
            next = top + rowHeight - viewportHeight;
        }
        next = Math.max(0, Math.min(Math.max(0, SceneGeometry.maxScrollY(viewport)), next));
        if (next != current) {
            scroll.set(Integer.valueOf(next));
        }
    }

    /** Menu / Shift+F10：以选中行的行盒为锚打开溢出菜单（键盘路径没有指针坐标）。 */
    private static void openSelectedRowMenu(SceneRuntime rt, ObjectGroupEditorState state,
                                            SceneNode viewport, int treeRootAbsX, int treeRootAbsY) {
        ObjectGroupEditorState.RowView selected = state.selection();
        if (selected == null || viewport == null) {
            return;
        }
        AnchorRect box = SceneGeometry.absoluteBox(viewport, treeRootAbsX, treeRootAbsY);
        int index = indexOf(state.visibleViews(), selected.key());
        int rowHeight = rowHeight(rt, viewport.effectiveFontSize());
        int y = box.getY() + Math.max(0, index) * (rowHeight + PANE_GAP_PX) + rowHeight
                - viewport.getScrollOffsetY();
        SceneContextMenu.open(rt, box.getX() + ROW_PADDING_PX, y, rowMenuItems(state, selected.key()));
    }

    /**
     * 内层控件独占键盘：Enter/Space 只做控件自身动作，不再冒泡成视图级语义（下钻 / 删除）；
     * TAB 放行给框架焦点遍历，ESC 放行给视图根关闭语义（两者都不得被吞）。
     *
     * @param rt      场景运行时
     * @param control 内层可聚焦控件节点
     */
    private static void claimKeyboard(SceneRuntime rt, SceneNode control) {
        rt.on(control, SceneEventType.KEY_DOWN, (ev, ectx) -> {
            SceneKey key = ev.getKey();
            if (key != SceneKey.TAB && key != SceneKey.ESCAPE) {
                ectx.stopPropagation();
            }
        });
    }

    // ------------------------------------------------------------------ 只读派生

    private static String orderText(ObjectGroupEditorState state, long key) {
        ObjectGroupEditorState.RowView view = state.viewOf(key);
        return view == null ? "" : Integer.toString(view.index() + 1);
    }

    private static String idText(ObjectGroupEditorState state, long key) {
        ObjectGroupEditorState.RowView view = state.viewOf(key);
        return view == null ? "" : view.id();
    }

    private static String countsText(ObjectGroupEditorState state, long key) {
        ObjectGroupEditorState.RowView view = state.viewOf(key);
        if (view == null) {
            return "";
        }
        return ClientI18n.tr("config.qz_miner.object_group.list.row",
                Integer.valueOf(view.modes().size()), Integer.valueOf(view.members().size()));
    }

    /**
     * 状态词：只在真正需要注意时显示（干净行只留状态点，避免噪声）。
     *
     * <p>结构错误通道：D3 之后解析器按类分类行错误——冲突类落
     * {@link ObjectGroupEditorState.Flag#CONFLICT}，其余结构错误（重复 id、id 为空、selector 非法等）
     * 落 {@link ObjectGroupEditorState.Flag#ERROR} 并带 {@link ObjectGroupEditorState.RowView#error()}
     * 文案；只看前三个 Flag 时这类行在列表里既无状态词也无错误色，错误只在选中后的详情 banner 可见。
     * （{@code error() != null} 作同条件兜底，覆盖「有错误文案但未打 ERROR」的将来形态。）</p>
     *
     * <p>语义优先级：<b>冲突 &gt; 未完成 &gt; 结构错误 &gt; 未生效</b>——error 级必须先于 warning 级，
     * 否则「modes 空 + selector 非法」这类同时带 ERROR 与 INACTIVE 的行会被「未生效」屏蔽掉
     * 阻断保存的错误提示。</p>
     */
    private static String statusText(ObjectGroupEditorState state, long key) {
        ObjectGroupEditorState.RowView view = state.viewOf(key);
        if (view == null) {
            return "";
        }
        if (view.hasFlag(ObjectGroupEditorState.Flag.CONFLICT)) {
            return ClientI18n.tr("config.qz_miner.object_group.state.conflict");
        }
        if (view.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE)) {
            return ClientI18n.tr("config.qz_miner.object_group.state.incomplete");
        }
        if (view.hasFlag(ObjectGroupEditorState.Flag.ERROR) || view.error() != null) {
            return ClientI18n.tr("config.qz_miner.object_group.state.error");
        }
        if (view.hasFlag(ObjectGroupEditorState.Flag.INACTIVE)) {
            return ClientI18n.tr("config.qz_miner.object_group.state.inactive");
        }
        return "";
    }

    /**
     * 状态点/状态词语义色：冲突 / 未完成 / 结构错误 = error，未生效 = warning，其余 = success（全走主题）。
     * 判定顺序与 {@link #statusText} 一致：error 级先于 warning 级。
     */
    private static int statusColor(ObjectGroupEditorState state, long key, Palette palette) {
        ObjectGroupEditorState.RowView view = state.viewOf(key);
        if (view == null) {
            return palette.success.get().intValue();
        }
        if (view.hasFlag(ObjectGroupEditorState.Flag.CONFLICT)
                || view.hasFlag(ObjectGroupEditorState.Flag.INCOMPLETE)
                || view.hasFlag(ObjectGroupEditorState.Flag.ERROR)
                || view.error() != null) {
            return palette.error.get().intValue();
        }
        if (view.hasFlag(ObjectGroupEditorState.Flag.INACTIVE)) {
            return palette.warning.get().intValue();
        }
        return palette.success.get().intValue();
    }

    private static int indexOf(List<ObjectGroupEditorState.RowView> views, long key) {
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).key() == key) {
                return i;
            }
        }
        return -1;
    }

    private static int filterIndexOf(ObjectGroupEditorState.Filter filter) {
        int index = FILTER_ORDER.indexOf(filter);
        return index < 0 ? 0 : index;
    }

    private static String filterKey(ObjectGroupEditorState.Filter filter) {
        switch (filter) {
            case CONFLICT:
                return "config.qz_miner.object_group.filter.conflict";
            case INACTIVE:
                return "config.qz_miner.object_group.filter.inactive";
            case WILDCARD:
                return "config.qz_miner.object_group.filter.wildcard";
            default:
                return "config.qz_miner.object_group.filter.all";
        }
    }

    // ------------------------------------------------------------------ 内容下限（列宽自适应输入）

    /**
     * 列表 pane 的<b>内容下限宽</b>（逻辑 px）：工具栏任一行在此宽内都不得重叠或裁掉文案。
     *
     * <p><b>为什么必须由内容实测</b>：搜索行与谓词行都是「固定文案 + 固定内边距 + 固定间距」，
     * 它们的自然需求随语言（zh/en）、字号与主题内边距变化；把列宽写死为常量族（如 220~300）
     * 在长语言文案下必然溢出——真机症状是谓词分段 chip 被计数文本压住。这里用
     * {@code rt.measureTextWidth} 实测当前生效文案，与控件同口径的先验内边距（
     * {@link SceneChromeTokens#PAD_MD} = 按钮内边距、{@link SceneChromeTokens#PAD_LG} =
     * {@link SceneSegmented} 段内边距、{@link SceneChromeTokens#GAP_SM} = 段间距，三者都是
     * UILib 公开常量，与控件实现同源）相加，得到「不裁字的最小列宽」。</p>
     *
     * <p>调用方（编辑视图）只在宽挡用它做列宽下限；窄挡下列表独占整宽，由视口宽保证。</p>
     *
     * @param rt         场景运行时（提供文本实测）
     * @param fontSizePx 生效字号（逻辑 px）
     * @return 最小内容宽（含 pane 左右内边距）
     */
    public static int minContentWidth(SceneRuntime rt, int fontSizePx) {
        return 2 * PANE_PADDING_PX + Math.max(searchRowNeed(rt, fontSizePx), filterRowNeed(rt, fontSizePx));
    }

    /** 搜索框最小宽：占位文案完整可见（控件内边距同 {@code SceneTextInput.PADDING}）。 */
    static int searchBoxMinWidth(SceneRuntime rt, int fontSizePx) {
        return labelWidth(rt, "config.qz_miner.object_group.list.search", fontSizePx)
                + 2 * SceneChromeTokens.PAD_MD;
    }

    /** 搜索行自然需求宽（搜索框最小宽 + 间距 + 「新建组」自然宽）。 */
    private static int searchRowNeed(SceneRuntime rt, int fontSizePx) {
        return searchBoxMinWidth(rt, fontSizePx) + PANE_GAP_PX + addButtonNaturalWidth(rt, fontSizePx);
    }

    /** 「新建组」按钮自然宽（标签实测 + 按钮内边距同 {@code SceneButton.PADDING}）。 */
    private static int addButtonNaturalWidth(SceneRuntime rt, int fontSizePx) {
        return labelWidth(rt, "config.qz_miner.object_group.list.add", fontSizePx)
                + 2 * SceneChromeTokens.PAD_MD;
    }

    /** 谓词行自然需求宽：四段文案实测 + 段内边距 + 段间距（与 {@code SceneSegmented} 同口径常量）。 */
    private static int filterRowNeed(SceneRuntime rt, int fontSizePx) {
        int need = 0;
        for (int i = 0; i < FILTER_ORDER.size(); i++) {
            need += labelWidth(rt, filterKey(FILTER_ORDER.get(i)), fontSizePx)
                    + 2 * SceneChromeTokens.PAD_LG;
        }
        return need + SceneChromeTokens.GAP_SM * (FILTER_ORDER.size() - 1);
    }

    /**
     * 文案实测宽（供内容下限使用）：<b>未本地化的回退键名不参与测量</b>。
     *
     * <p>语言包缺失时 {@link ClientI18n#tr} 会把键名原样返回（例如
     * {@code config.qz_miner.object_group.filter.wildcard}，长度是真实文案的数倍）。这种回退键名
     * 是异常态，若参与内容下限会把列宽需求放大数倍、把详情列挤到零宽；此时更稳妥的降级是
     * 「不做内容下限抬升，由 pane 裁剪兜底」。真实语言文案存在时本规则不生效。</p>
     *
     * @param rt         场景运行时
     * @param key        语言键
     * @param fontSizePx 生效字号
     * @return 文案宽（未本地化为 0）
     */
    private static int labelWidth(SceneRuntime rt, String key, int fontSizePx) {
        String text = ClientI18n.tr(key);
        if (text == null || text.equals(key)) {
            return 0;
        }
        return rt.measureTextWidth(text, fontSizePx);
    }

    // ------------------------------------------------------------------ 尺寸先验（布局闸门纪律）

    /**
     * 动作按钮：显式先验宽 + 键盘独占。
     *
     * <p>{@code SceneButton} 根节点自身无文本（文本在子节点）⇒ 作为 ROW 固定兄弟
     * {@code priorKnownChildWidth} 恒为 UNCONSTRAINED，会撞掉整条 ROW 的 grow 分配
     * （{@code ConstraintResolver.computeRowGrowWidths} 早退 + WARN）。此处照 UILib 既有范式
     * （{@code StructuredListFieldRenderer.actionButton}「供父 ROW 先验扣除」）显式测量设宽。</p>
     */
    private static SceneNode actionButton(SceneRuntime rt, SceneNode capParent, int capPercent,
                                          ReadableSignal<String> label,
                                          ReadableSignal<Boolean> enabled, Runnable onClick) {
        SceneNode button = SceneButton.create(rt, SceneButton.Props
                .builder(label)
                .enabled(enabled)
                .onClick(onClick)
                .build()).get();
        bindActionWidth(rt, button, label, capParent, capPercent);
        claimKeyboard(rt, button);
        return button;
    }

    /** 按钮先验宽：{@code measureTextWidth(标签) + 左右内边距}；文本源用标签信号避免首帧时序问题。 */
    private static void bindActionWidth(SceneRuntime rt, final SceneNode button,
                                        final ReadableSignal<String> label,
                                        final SceneNode capParent, final int capPercent) {
        Runnable apply = () -> {
            String text = label.get();
            int natural = rt.measureTextWidth(text == null ? "" : text, button.effectiveFontSize())
                    + button.getPaddingLeft() + button.getPaddingRight();
            int cap = capWidth(capParent, capPercent);
            button.setPreferredWidth(cap > 0 ? Math.min(natural, cap) : natural);
            // 先验高同样显式声明：按钮内部标签经 bindText 落值，构建期按子节点测不到文本。
            button.setPreferredHeight(rt.lineHeight(button.effectiveFontSize())
                    + button.getPaddingTop() + button.getPaddingBottom());
        };
        apply.run();
        rt.bind(label, next -> apply.run());
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
    }

    /**
     * 搜索行「新建组」按钮宽：自然宽（标签实测 + 按钮内边距）优先；
     * 上限 = 行内宽 − 搜索框最小宽 − 间距，保证长语言文案下搜索框仍完整可读。
     */
    private static SceneNode searchActionButton(SceneRuntime rt, final SceneNode row, final SceneNode search,
                                                final ReadableSignal<String> label,
                                                ReadableSignal<Boolean> enabled, Runnable onClick) {
        SceneNode button = SceneButton.create(rt, SceneButton.Props
                .builder(label)
                .enabled(enabled)
                .onClick(onClick)
                .build()).get();
        Runnable apply = () -> {
            String text = label.get();
            int natural = rt.measureTextWidth(text == null ? "" : text, button.effectiveFontSize())
                    + button.getPaddingLeft() + button.getPaddingRight();
            int cap = 0;
            Object cached = row.getCachedLayout();
            if (cached instanceof LayoutBox) {
                int width = ((LayoutBox) cached).getWidth();
                if (width > 0) {
                    int inner = Math.max(1, width - row.getPaddingLeft() - row.getPaddingRight());
                    cap = Math.max(1, inner - searchBoxMinWidth(rt, row.effectiveFontSize()) - PANE_GAP_PX);
                }
            }
            button.setPreferredWidth(cap > 0 ? Math.min(natural, cap) : natural);
            button.setPreferredHeight(rt.lineHeight(button.effectiveFontSize())
                    + button.getPaddingTop() + button.getPaddingBottom());
        };
        apply.run();
        rt.bind(label, next -> apply.run());
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
        claimKeyboard(rt, button);
        return button;
    }

    /** 搜索框最小宽（占位文案 + 控件内边距，随生效字号重派生）。 */
    private static void bindSearchMinWidth(SceneRuntime rt, final SceneNode search) {
        Runnable apply = () -> search.setMinWidth(searchBoxMinWidth(rt, search.effectiveFontSize()));
        apply.run();
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
    }

    /** 父宽百分比上限（父无布局盒时返回 0 = 不设上限）。 */
    private static int capWidth(SceneNode parent, int percent) {
        if (parent == null || percent <= 0) {
            return 0;
        }
        Object cached = parent.getCachedLayout();
        if (cached instanceof LayoutBox) {
            int width = ((LayoutBox) cached).getWidth();
            if (width > 0) {
                return Math.max(1, Math.round(width * percent / 100.0F));
            }
        }
        return 0;
    }

    /** 显式先验高（构建期一次 + 每次布局后重派生）：COLUMN 固定兄弟高必须先验可算。 */
    private static void bindPriorHeight(SceneRuntime rt, final SceneNode node) {
        Runnable apply = () -> node.setPreferredHeight(contentHeight(rt, node));
        apply.run();
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
    }

    /**
     * 内容先验高（忽略本节点自身已写入的先验值，避免字号变化后被自锁）。
     *
     * <p><b>主轴语义</b>：COLUMN 的子是纵向堆叠 ⇒ 高取<b>求和</b> + 间距；ROW 的子同排 ⇒ 取<b>最大</b>。
     * 这里曾一律取 max：单行固定兄弟看不出来，但谓词区改成「分段行 + 计数行」的 COLUMN 块后，
     * 先验高被算成单行高（40 而非 60），pane 的主轴余量多出 20px ⇒ 视口被内容撑高 ⇒ 编辑视图根
     * 超出宿主视口（既有断言 700×420 实测 440）。</p>
     */
    private static int contentHeight(SceneRuntime rt, SceneNode node) {
        int inner = node.getText() == null ? 0 : rt.lineHeight(node.effectiveFontSize());
        List<SceneNode> children = node.__getChildren();
        if (node.getFlexDirection() == FlexDirection.COLUMN) {
            int sum = 0;
            int stacked = 0;
            for (SceneNode child : children) {
                if (child.isCollapsed()) {
                    continue;
                }
                sum += priorHeight(rt, child) + child.marginV();
                stacked++;
            }
            if (stacked > 1) {
                sum += node.getGap() * (stacked - 1);
            }
            inner = Math.max(inner, sum);
        } else {
            for (SceneNode child : children) {
                if (child.isCollapsed()) {
                    continue;
                }
                inner = Math.max(inner, priorHeight(rt, child) + child.marginV());
            }
        }
        return inner + node.getPaddingTop() + node.getPaddingBottom();
    }

    /** 子节点先验高（与 {@code ConstraintResolver.priorKnownChildHeight} 同口径）：显式值优先。 */
    private static int priorHeight(SceneRuntime rt, SceneNode node) {
        if (node.getPreferredHeight() > 0) {
            return node.getPreferredHeight();
        }
        return contentHeight(rt, node);
    }

    // ------------------------------------------------------------------ 字号派生几何

    /** 行高：{@code max(36, 文本行高 + 2×PAD)}（生效字号派生）。 */
    private static int rowHeight(SceneRuntime rt, int fontSizePx) {
        return Math.max(ROW_MIN_HEIGHT_PX, rt.lineHeight(fontSizePx) + 2 * ROW_PADDING_PX);
    }

    /** 状态点边长：{@code max(6, round(生效字号 × 0.7))}。 */
    private static int dotSide(int fontSizePx) {
        return Math.max(DOT_MIN_SIDE_PX, Math.round(fontSizePx * DOT_SIDE_RATIO));
    }

    /** 行尾按钮命中区：{@code max(24, 行高 + 2×HIT_PAD)}（下界取 P7/WCAG 目标尺寸，随字号增长）。 */
    private static int hitSide(SceneRuntime rt, int fontSizePx) {
        return Math.max(MIN_HIT_SIDE_PX,
                PickerChrome.triggerIconSide(fontSizePx) + rt.lineHeight(fontSizePx) / 2);
    }

    private static SceneNode label(String text) {
        SceneNode node = new SceneNode();
        node.setText(text);
        node.setHitTestable(false);
        return node;
    }

    /** pane 表面：GROUP 角色配方是唯一外观写入者（主题切换只重派生、不重建节点）。 */
    private static void bindPaneSurface(SceneRuntime rt, SceneNode pane) {
        SceneInteractionState interaction = rt.interactionState(pane);
        interaction.hovered();
        interaction.pressed();
        interaction.focused();
        SceneSurfaceBinder.bind(rt, pane, SceneThemes.surface(rt, SceneTheme.Role.GROUP),
                Signal.create(Boolean.TRUE), interaction);
    }

    /** 主题色解析一次、全 pane 共享（SceneThemes 每次调用都会新建派生信号）。 */
    private static final class Palette {
        private final ReadableSignal<Integer> foreground;
        private final ReadableSignal<Integer> muted;
        private final ReadableSignal<Integer> error;
        private final ReadableSignal<Integer> warning;
        private final ReadableSignal<Integer> success;
        private final ReadableSignal<Integer> borderFocus;
        private final ReadableSignal<Integer> borderDefault;
        private final ReadableSignal<Integer> selectionBackground;

        Palette(SceneRuntime rt) {
            this.foreground = SceneThemes.foreground(rt);
            this.muted = SceneThemes.mutedForeground(rt);
            this.error = SceneThemes.errorText(rt);
            this.warning = SceneThemes.warningText(rt);
            this.success = SceneThemes.successText(rt);
            this.borderFocus = SceneThemes.borderFocus(rt);
            this.borderDefault = SceneThemes.borderDefault(rt);
            this.selectionBackground = SceneThemes.selectionBackground(rt);
        }
    }
}
