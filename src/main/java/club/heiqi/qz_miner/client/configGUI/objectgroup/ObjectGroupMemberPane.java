package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.config.ui.editor.CandidateSourceValueEditorProvider;
import club.heiqi.config.ui.editor.CategorizedValueEditorProvider;
import club.heiqi.config.ui.editor.Codec;
import club.heiqi.config.ui.editor.CurrentValuePresenter;
import club.heiqi.config.ui.editor.ListMemberCodec;
import club.heiqi.config.ui.editor.PickerCandidateSource;
import club.heiqi.config.ui.editor.PickerQuery;
import club.heiqi.config.ui.editor.Registry;
import club.heiqi.config.ui.editor.SearchPickerCategories;
import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.ValueEditorProvider;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.config.ui.field.PickerDensityPreferenceSource;
import club.heiqi.config.ui.field.PickerIconResolver;
import club.heiqi.config.ui.field.PickerRevisionBridge;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.client.picker.BlockPickerProvider;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import club.heiqi.uilib.ui.reactive.Computed;
import club.heiqi.uilib.ui.reactive.Effect;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.ScenePickerPanel;
import club.heiqi.uilib.ui.scene.control.search.PickerChrome;
import club.heiqi.uilib.ui.scene.input.SceneInteractionState;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.LogicalBox;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.SceneChromeTokens;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceBinder;
import club.heiqi.uilib.ui.scene.theme.SceneTheme;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * 对象组成员区（M5）：成员载体 + 图标 + 编辑/删除 + picker 接线（单组）。
 *
 * <p><b>数据边界（硬）</b>：成员 selector 的解析/规范化只走 {@link ObjectGroupParser#parseSelector(String)}
 * 与 {@code ObjectGroupPickerCodec}（经 {@link ListMemberCodec}）；现值/图标呈现只走既有
 * {@link CurrentValuePresenter}（Miner 的 {@code BlockSelectorCurrentValuePresenter}）；
 * 冲突与错误一律取 {@link ObjectGroupEditorState#parseResult()} / {@link ObjectGroupEditorState#views()}
 * 的真源，本类不复刻任何解析器内部逻辑（不建第二份校验真值）。</p>
 *
 * <p><b>写入边界（硬）</b>：一切落地都经 {@link ObjectGroupEditorState} 的编辑命令
 * （{@code addMember / removeMember / replaceMember}），并对 {@code EditResult.rejection()}
 * 给出结构化提示（{@link ObjectGroupDetailPane#rejectionText} 映射 i18n），不静默失败。</p>
 *
 * <p><b>载体裁决（Q1 小实验结论）</b>：{@code SceneVirtualGrid} <b>不可</b>承载本行——
 * 其单元是「固定 {@code cellWidth × cellHeight} 的 COLUMN + {@code clipChildren(true)} + 图标独占
 * 剩余高」契约，{@code onCellMount} 追加的第二个动作行必然被裁剪；且 {@code cellWidth} 是构建期
 * 常量，接不了运行期宽度派生。故退为<b>分页窗口</b>：每页只挂载「页大小」行，页大小由运行时逻辑盒高
 * 与生效字号派生（{@link #rowsPerPageSignal}），永不一次性构建 128 个成员的全部子树
 * （C3 §9.1 / C1 P-03 口径）。<b>不做嵌套滚动</b>：C3 §6.1 要求视图内只此一层滚动，
 * 分页天然不新增滚动体，滚轮归属外层的唯一视口。</p>
 *
 * <p><b>成员编辑态（U3 已落地）</b>：编辑器驱动 UILib 公开入口
 * {@code ScenePickerPanel.Builder.memberEditRequest(ReadableSignal<Long>)}（本类的
 * {@link MemberEditor#editRequest()}，值 = 成员稳定 id），由面板内部「成员卡 [编辑]」同一条通路
 * 进入编辑态（初值 = 该成员现状目标态，带变体者预开变体浮层）；提交仍走唯一原子边界
 * {@code selectionCommit} → {@code replaceMember}（原位替换），取消走面板既有 onCancel，
 * 成员数据零变化。</p>
 */
public final class ObjectGroupMemberPane {

    /** 区块/行间距（逻辑 px，非颜色令牌）。 */
    private static final int GAP = SceneChromeTokens.GAP_SM;
    /** 行内边距（逻辑 px，非颜色令牌）。 */
    private static final int ROW_PAD = SceneChromeTokens.PAD_SM;
    /** 行高下限（逻辑 px）；实际行高 = max(本值, 文本行高 + 2×内边距)。 */
    private static final int ROW_HEIGHT_MIN = 24;
    /** 分页页大小下限（行）。 */
    private static final int PAGE_ROWS_MIN = 4;
    /** 分页页大小上限（行）。 */
    private static final int PAGE_ROWS_MAX = 24;
    /** 页高预算占运行时逻辑盒高的百分比（页大小的派生源；不写死分辨率）。 */
    private static final int PAGE_VIEWPORT_PERCENT = 35;
    /** 逻辑盒尚未就绪时的页高预算回落（按文本行数表达，仍随字号缩放）。 */
    private static final int PAGE_FALLBACK_LINES = 8;

    /** 常量启用信号（控件自身无禁用态时的默认）。 */
    private static final ReadableSignal<Boolean> ALWAYS_ENABLED = () -> Boolean.TRUE;
    /** 常量禁用信号（未选中配方的 chip/pill 用）。 */
    private static final ReadableSignal<Boolean> NEVER_ENABLED = () -> Boolean.FALSE;
    /** SPI 路径下字段侧不物化候选（面板自取窗口切片）。 */
    private static final ReadableSignal<SearchPickerData.SearchResult> NO_RESULTS =
            SearchPickerData.SearchResult::empty;

    private ObjectGroupMemberPane() {
    }

    /**
     * 构建单组成员区（冻结签名）。
     *
     * @param ctx 宿主上下文
     * @param key 组行 key
     * @return 可直接 appendChild 的成员区节点（组不存在时为空容器，不抛异常）
     */
    public static SceneNode build(ObjectGroupEditorContext ctx, long key) {
        return build(ctx, key, new MemberEditor(ctx, key));
    }

    /**
     * 构建单组成员区（共享编辑入口形态）：详情页的成员区与「高级：原始规则」逐行修正入口
     * 共用同一 {@link MemberEditor}，从而共用同一个 picker 实例与同一份临时态。
     *
     * @param ctx    宿主上下文
     * @param key    组行 key
     * @param editor 共享编辑入口（包内可见；由 {@link ObjectGroupDetailPane} 创建）
     * @return 成员区节点
     */
    static SceneNode build(ObjectGroupEditorContext ctx, long key, MemberEditor editor) {
        final SceneRuntime rt = ctx.rt();
        final ObjectGroupEditorState state = ctx.state();
        final SceneNode root = SceneNode.column();
        root.setGap(GAP);

        final ObjectGroupEditorState.RowView view = state.viewOf(key);
        if (view == null) {
            return root;
        }
        final ValueEditorProvider provider = providerOf(ctx);
        final CurrentValuePresenter presenter = provider == null ? null : provider.currentValuePresenter();

        final ReadableSignal<List<String>> members = Computed.create(() -> memberListOf(state, key));
        final ReadableSignal<Integer> count = Computed.create(() -> Integer.valueOf(members.get().size()));
        final ReadableSignal<Integer> total = Computed.create(() -> Integer.valueOf(state.summary().memberCount()));
        final ReadableSignal<String> limitReason = Computed.create(() -> limitReasonOf(count.get(), total.get()));

        root.appendChild(toolbar(rt, editor, count, limitReason));

        final SceneNode emptyHost = SceneNode.column();
        rt.show(emptyHost, Computed.create(() -> Boolean.valueOf(count.get().intValue() == 0)),
                () -> textNode(rt, ClientI18n.tr("config.qz_miner.object_group.members.empty"),
                        SceneThemes.warningText(rt)));
        root.appendChild(emptyHost);

        // 生效字号探针：只用于派生行高/页大小，零尺寸零内容。
        final SceneNode probe = new SceneNode();
        probe.setHitTestable(false);
        root.appendChild(probe);

        final ReadableSignal<Integer> rowsPerPage = rowsPerPageSignal(rt, probe);
        final Signal<Integer> page = Signal.create(Integer.valueOf(0));
        final ReadableSignal<Integer> pageCount = Computed.create(() -> Integer.valueOf(
                pageCountOf(count.get().intValue(), rowsPerPage.get().intValue())));
        final ReadableSignal<Integer> pageIndex = Computed.create(() -> Integer.valueOf(
                clamp(page.get().intValue(), 0, pageCount.get().intValue() - 1)));
        // 成员数变化 ⇒ 回第一页（原页号可能已越界）。
        rt.bind(count, value -> {
            if (page.get().intValue() != 0) {
                page.set(Integer.valueOf(0));
            }
        });
        final ReadableSignal<List<Integer>> pageItems = Computed.create(() -> indicesOf(
                pageIndex.get().intValue(), rowsPerPage.get().intValue(), count.get().intValue()));

        final SceneNode rowsHost = SceneNode.column();
        rowsHost.setGap(GAP);
        rt.forEach(rowsHost, pageItems, index -> index,
                index -> memberRow(rt, ctx, key, index.intValue(), editor, presenter));
        root.appendChild(rowsHost);

        root.appendChild(pagerBar(rt, page, pageIndex, pageCount,
                "config.qz_miner.object_group.members.page",
                "config.qz_miner.object_group.members.page_prev",
                "config.qz_miner.object_group.members.page_next"));

        root.appendChild(noticeNode(rt, editor.notice()));
        root.appendChild(editor.host(rt));
        return root;
    }

    // ------------------------------------------------------------------ 工具条 / 空态 / 分页

    /**
     * 工具条：{@code 成员 (N)  <spacer>  [添加成员…]} + 达界原因行（达界时禁用入口）。
     *
     * <p><b>文案结构</b>：区块标题键（{@code members.label}）+ 紧凑计数键（{@code members.count}，
     * 值为纯括号数字）拼成「成员 (N)」；不得再用「%s 个成员」这类自带名词的计数文案，
     * 否则标题与计数会合成为「成员 2 个成员」（真机 y=841 行实证）。</p>
     */
    private static SceneNode toolbar(SceneRuntime rt, MemberEditor editor,
                                     ReadableSignal<Integer> count, ReadableSignal<String> limitReason) {
        final SceneNode bar = SceneNode.row();
        bar.setGap(GAP);
        bar.setCrossAxisAlign(CrossAxisAlign.CENTER);
        bar.appendChild(textNode(rt, ClientI18n.tr("config.qz_miner.object_group.members.label"),
                SceneThemes.foreground(rt)));

        final SceneNode countText = textNode(rt, "", SceneThemes.mutedForeground(rt));
        rt.bindComputed(() -> ClientI18n.tr("config.qz_miner.object_group.members.count", count.get()),
                countText::setText);
        bar.appendChild(countText);

        final SceneNode spacer = new SceneNode();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        bar.appendChild(spacer);

        final ReadableSignal<Boolean> canAdd = Computed.create(() -> Boolean.valueOf(
                limitReason.get() == null || limitReason.get().isEmpty()));
        bar.appendChild(actionButton(rt, ClientI18n.tr("config.qz_miner.object_group.members.add"),
                canAdd, editor::requestAdd));

        final SceneNode column = SceneNode.column();
        column.setGap(GAP);
        column.appendChild(bar);

        // 达界原因：结构化显示（禁用入口必须给出原因，不静默失败）。
        final SceneNode reason = textNode(rt, "", SceneThemes.warningText(rt));
        rt.bind(limitReason, value -> reason.setText(value == null ? "" : value));
        rt.bind(limitReason, value -> reason.setCollapsed(value == null || value.isEmpty()));
        column.appendChild(reason);
        return column;
    }

    /**
     * 达界原因：128 成员/组 或 2048 成员总量（取 State 命令同一组常量，不另立阈值）。
     *
     * @param count 本组成员数
     * @param total 全配置成员总数
     * @return 本地化原因；未达界返回空串
     */
    static String limitReasonOf(Integer count, Integer total) {
        if (count != null && count.intValue() >= ObjectGroup.MAX_MEMBERS) {
            return ClientI18n.tr("config.qz_miner.object_group.limit.members");
        }
        if (total != null && total.intValue() >= ObjectGroupRuleSet.MAX_TOTAL_MEMBERS) {
            return ClientI18n.tr("config.qz_miner.object_group.limit.total");
        }
        return "";
    }

    /**
     * 分页条：上一页 / 「第 x / y 页」/ 下一页；仅一页时整条退出布局域。
     *
     * @param rt        场景运行时
     * @param page      页号写入口
     * @param pageIndex 夹取后的当前页号
     * @param pageCount 总页数
     * @param pageKey   页码文案键（两个整型参数）
     * @param prevKey   上一页文案键
     * @param nextKey   下一页文案键
     * @return 分页条节点
     */
    static SceneNode pagerBar(SceneRuntime rt, Signal<Integer> page, ReadableSignal<Integer> pageIndex,
                              ReadableSignal<Integer> pageCount, String pageKey,
                              String prevKey, String nextKey) {
        final SceneNode bar = SceneNode.row();
        bar.setGap(GAP);
        bar.setCrossAxisAlign(CrossAxisAlign.CENTER);

        final ReadableSignal<Boolean> hasPrev = Computed.create(() -> Boolean.valueOf(
                pageIndex.get().intValue() > 0));
        bar.appendChild(actionButton(rt, ClientI18n.tr(prevKey), hasPrev, () -> page.set(Integer.valueOf(
                Math.max(0, pageIndex.get().intValue() - 1)))));

        final SceneNode label = textNode(rt, "", SceneThemes.mutedForeground(rt));
        rt.bindComputed(() -> ClientI18n.tr(pageKey, Integer.valueOf(pageIndex.get().intValue() + 1),
                Integer.valueOf(pageCount.get().intValue())), label::setText);
        bar.appendChild(label);

        final ReadableSignal<Boolean> hasNext = Computed.create(() -> Boolean.valueOf(
                pageIndex.get().intValue() < pageCount.get().intValue() - 1));
        bar.appendChild(actionButton(rt, ClientI18n.tr(nextKey), hasNext, () -> page.set(Integer.valueOf(
                pageIndex.get().intValue() + 1))));

        rt.bindComputed(() -> Boolean.valueOf(pageCount.get().intValue() > 1),
                value -> bar.setCollapsed(!Boolean.TRUE.equals(value)));
        return bar;
    }

    // ------------------------------------------------------------------ 成员行

    /** 单行成员条目：图标 + 规范化 selector（registry 与 @meta 分列）+ [编辑] + [删除]。 */
    private static SceneNode memberRow(SceneRuntime rt, ObjectGroupEditorContext ctx, long key,
                                       int index, MemberEditor editor, CurrentValuePresenter presenter) {
        final ObjectGroupEditorState state = ctx.state();
        final SceneNode row = SceneNode.row();
        row.setGap(GAP);
        row.setPadding(ROW_PAD);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        SceneSurfaceBinder.bind(rt, row, SceneThemes.surface(rt, SceneTheme.Role.INPUT),
                ALWAYS_ENABLED, interactionOf(rt, row));

        final ReadableSignal<String> raw = Computed.create(() -> memberAt(state, key, index));
        final ReadableSignal<ObjectGroupSelector> parsed = Computed.create(() -> parseOrNull(raw.get()));
        final ReadableSignal<CurrentValuePresenter.Presentation> shown =
                Computed.create(() -> presentOf(presenter, raw.get()));

        final SceneNode order = textNode(rt, Integer.toString(index + 1), SceneThemes.mutedForeground(rt));
        row.appendChild(order);

        final SceneNode icon = new SceneNode();
        icon.setHitTestable(false);
        icon.setCornerRadius(SceneChromeTokens.RADIUS_SM);
        bindIcon(rt, icon, shown);
        row.appendChild(icon);

        final SceneNode info = SceneNode.column();
        info.setFlexGrow(1);
        info.setGap(2);
        final SceneNode primary = textNode(rt, "", SceneThemes.foreground(rt));
        final SceneNode secondary = textNode(rt, "", SceneThemes.mutedForeground(rt));
        final ReadableSignal<Integer> normal = SceneThemes.foreground(rt);
        final ReadableSignal<Integer> invalid = SceneThemes.errorText(rt);
        rt.bindComputed(() -> primaryTextOf(parsed.get(), raw.get()), primary::setText);
        rt.bindComputed(() -> parsed.get() == null ? invalid.get() : normal.get(), primary::setTextColor);
        rt.bindComputed(() -> secondaryTextOf(parsed.get(), raw.get(), shown.get()), secondary::setText);
        info.appendChild(primary);
        info.appendChild(secondary);
        row.appendChild(info);

        row.appendChild(actionButton(rt, ClientI18n.tr("config.qz_miner.object_group.members.edit"),
                ALWAYS_ENABLED, () -> editor.requestEdit(index)));
        row.appendChild(actionButton(rt, ClientI18n.tr("config.qz_miner.object_group.members.remove"),
                ALWAYS_ENABLED, () -> removeMember(state, key, index, editor)));
        return row;
    }

    /** 删除成员：唯一落地路径 = {@code removeMember} 命令；拒绝时给出结构化提示。 */
    private static void removeMember(ObjectGroupEditorState state, long key, int index, MemberEditor editor) {
        final ObjectGroupEditorState.EditResult result = state.removeMember(key, index);
        if (!result.accepted()) {
            editor.showRejection(result.rejection());
        } else {
            editor.clearNotice();
        }
    }

    /** 图标：{@code round(生效字号×1.5)}（与触发器同源口径），随布局纪元/字号重派生。 */
    private static void bindIcon(SceneRuntime rt, SceneNode icon,
                                 ReadableSignal<CurrentValuePresenter.Presentation> shown) {
        final ReadableSignal<Integer> placeholder = SceneThemes.mutedForeground(rt);
        final int initial = PickerChrome.triggerIconSide(icon.effectiveFontSize());
        icon.setPreferredWidth(initial);
        icon.setPreferredHeight(initial);
        rt.bindComputed(() -> shown.get() == null ? null : shown.get().image(), icon::setImageSource);
        rt.bindComputed(() -> Integer.valueOf(shown.get() == null || shown.get().image() == null
                ? placeholder.get().intValue() : SceneChromeTokens.TRANSPARENT), icon::setBackgroundColor);
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            final int side = PickerChrome.triggerIconSide(icon.effectiveFontSize());
            icon.setPreferredWidth(side);
            icon.setPreferredHeight(side);
        }));
    }

    /**
     * 现值呈现（唯一通路 = 既有 {@link CurrentValuePresenter}）：无法呈现/未注册时返回 null。
     *
     * <p>{@code present} 只接受「值的列表」（Miner 实现取首元素），故成员级调用包成单元素列表；
     * 解析失败与未知方块由 presenter 自带文案承载，本类不再自建文案。</p>
     */
    private static CurrentValuePresenter.Presentation presentOf(CurrentValuePresenter presenter, String raw) {
        if (presenter == null || raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return presenter.present(Collections.singletonList(raw));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 主文本：可解析 ⇒ 规范化 registry；不可解析 ⇒ 原始 raw（红字，无损暴露）。 */
    static String primaryTextOf(ObjectGroupSelector selector, String raw) {
        return selector == null ? (raw == null ? "" : raw) : selector.registry();
    }

    /** 副文本：{@code @meta}；不可解析时给出「选择器无法解析」提示；可解析且候选有名时追加展示名。 */
    private static String secondaryTextOf(ObjectGroupSelector selector, String raw,
                                          CurrentValuePresenter.Presentation shown) {
        if (selector == null) {
            return ClientI18n.tr("config.qz_miner.object_group.reject.selector");
        }
        final String canonical = selector.canonical();
        final int separator = canonical.lastIndexOf('@');
        final String meta = separator < 0 ? canonical : canonical.substring(separator);
        if (shown == null) {
            return meta;
        }
        final String title = shown.title();
        if (title == null || title.isEmpty() || title.equals(canonical) || title.equals(raw)) {
            return meta;
        }
        return meta + " · " + title;
    }

    /** 通知行：结构化提示（拒绝原因），空串时退出布局域。 */
    static SceneNode noticeNode(SceneRuntime rt, ReadableSignal<String> notice) {
        final SceneNode node = textNode(rt, "", SceneThemes.errorText(rt));
        rt.bind(notice, value -> node.setText(value == null ? "" : value));
        rt.bind(notice, value -> node.setCollapsed(value == null || value.isEmpty()));
        return node;
    }

    // ------------------------------------------------------------------ 分页数学（共享）

    /**
     * 页大小信号：页高预算 ÷ 行高，行高 = max(下限, 文本行高 + 2×内边距)，
     * 预算 = 运行时逻辑盒高 × {@value #PAGE_VIEWPORT_PERCENT}%（逻辑盒未就绪时按文本行数回落）。
     *
     * <p>失效源 = 布局纪元 + 字号纪元 + 逻辑盒（窗口/缩放/字号/资源包变化即时重派生），
     * 不写死任何分辨率或字号。</p>
     *
     * @param rt    场景运行时
     * @param probe 生效字号探针节点（须已进树）
     * @return 每页行数（{@value #PAGE_ROWS_MIN}..{@value #PAGE_ROWS_MAX}）
     */
    static ReadableSignal<Integer> rowsPerPageSignal(final SceneRuntime rt, final SceneNode probe) {
        return Computed.create(() -> {
            rt.layoutDoneSignal().get();
            rt.fontEpochSignal().get();
            final LogicalBox box = rt.logicalBox().get();
            final int fontSize = probe.effectiveFontSize();
            final int rowHeight = Math.max(ROW_HEIGHT_MIN, rt.lineHeight(fontSize) + ROW_PAD * 2);
            final int budget = box != null && box.isPresent()
                    ? box.heightPx() * PAGE_VIEWPORT_PERCENT / 100
                    : PAGE_FALLBACK_LINES * rowHeight;
            return Integer.valueOf(clamp(budget / Math.max(1, rowHeight), PAGE_ROWS_MIN, PAGE_ROWS_MAX));
        });
    }

    /** 页数：{@code ceil(count / rowsPerPage)}，至少 1 页。 */
    static int pageCountOf(int count, int rowsPerPage) {
        if (count <= 0 || rowsPerPage <= 0) {
            return 1;
        }
        return (count + rowsPerPage - 1) / rowsPerPage;
    }

    /** 当前页成员下标（0 起、保序、只读）；越界页返回空列表。 */
    static List<Integer> indicesOf(int pageIndex, int rowsPerPage, int count) {
        if (count <= 0 || rowsPerPage <= 0) {
            return Collections.emptyList();
        }
        final int start = Math.max(0, pageIndex) * rowsPerPage;
        if (start >= count) {
            return Collections.emptyList();
        }
        final int end = Math.min(count, start + rowsPerPage);
        final List<Integer> out = new ArrayList<Integer>(end - start);
        for (int i = start; i < end; i++) {
            out.add(Integer.valueOf(i));
        }
        return Collections.unmodifiableList(out);
    }

    /** 闭区间夹取。 */
    static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    // ------------------------------------------------------------------ 数据读取（只读面）

    /** 成员 raw 列表（来自行视图的保真口径，不丢弃失效项）。 */
    static List<String> memberListOf(ObjectGroupEditorState state, long key) {
        final ObjectGroupEditorState.RowView view = state.viewOf(key);
        return view == null ? Collections.<String>emptyList() : view.members();
    }

    /** 第 {@code index} 个成员 raw；越界返回空串。 */
    static String memberAt(ObjectGroupEditorState state, long key, int index) {
        final List<String> members = memberListOf(state, key);
        return index < 0 || index >= members.size() ? "" : members.get(index);
    }

    /** 解析 selector；失败返回 null（失败不抛、不静默改写）。 */
    static ObjectGroupSelector parseOrNull(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return ObjectGroupParser.parseSelector(raw);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** 用既有 codec 解码单个成员（{@link ListMemberCodec} 是成员级绑定的显式契约）。 */
    static SearchPickerData.Selection decodeMember(Codec codec, String raw) {
        if (!(codec instanceof ListMemberCodec) || raw == null) {
            return null;
        }
        try {
            return ((ListMemberCodec) codec).decodeMember(raw);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** registry 中取方块选择器 provider（成员 selector 的编辑器 id 是 Miner 自己的稳定常量）。 */
    static ValueEditorProvider providerOf(ObjectGroupEditorContext ctx) {
        final Registry registry = ctx.editorRegistry();
        return registry == null ? null : registry.find(BlockPickerProvider.ID);
    }

    // ------------------------------------------------------------------ 通用节点

    /** 不可命中的文本叶，前景经主题语义信号绑定（主题切换只重派生色值）。 */
    static SceneNode textNode(SceneRuntime rt, String value, ReadableSignal<Integer> color) {
        final SceneNode node = new SceneNode();
        node.setText(value == null ? "" : value);
        node.setHitTestable(false);
        rt.bind(color, node::setTextColor);
        return node;
    }

    /**
     * 场景按钮（复用 UILib 按钮控件，不自绘命中/外观）。
     *
     * <p><b>必须显式钉外宽</b>：{@code SceneButton} 根节点自身无文本（文本在子节点）、无
     * {@code preferredWidth}，而布局的 ROW grow 先验闸门（{@code ConstraintResolver}：
     * 固定兄弟是「有子容器且无 preferredWidth」⇒ 先验宽 UNCONSTRAINED）会在同 ROW 存在
     * {@code flexGrow} 兄弟时放弃整条 grow 分配并 WARN，令 grow 兄弟零宽、整行溢出。
     * 范式同 UILib {@code StructuredListFieldRenderer.actionButton}：标签实测宽 + 按钮左右内边距；
     * 并按布局纪元重派生（字号/倍率变化即时跟随，不留静态快照）。</p>
     */
    static SceneNode actionButton(SceneRuntime rt, String label, ReadableSignal<Boolean> enabled, Runnable onClick) {
        final SceneNode button = SceneButton.create(rt, new SceneButton.Props(
                Signal.create(label), enabled, onClick)).get();
        bindButtonWidth(rt, button, label);
        return button;
    }

    /**
     * 钉按钮外宽：构建期先给定值（父 ROW 首帧即可先验），此后只随「生效字号」变化重派生
     * ——变更信号 + 惰性增量：同一字号下不做度量、不写属性（布局纪元变化零成本）。
     *
     * @param rt     场景运行时（文本度量）
     * @param button 按钮根（{@code SceneButton.create} 产物）
     * @param label  标签文本
     */
    private static void bindButtonWidth(final SceneRuntime rt, final SceneNode button, final String label) {
        final int[] appliedFontSize = { -1 };
        final Runnable apply = () -> {
            final int fontSize = labelFontSize(button);
            if (fontSize == appliedFontSize[0]) {
                return;
            }
            appliedFontSize[0] = fontSize;
            final int textWidth = rt.measureTextWidth(label == null ? "" : label, fontSize);
            button.setPreferredWidth(Math.max(1,
                    textWidth + button.getPaddingLeft() + button.getPaddingRight()));
        };
        apply.run();
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
    }

    /** 按钮标签节点的生效字号（SceneButton 的文本在首个子节点上；无子节点时退化为根）。 */
    private static int labelFontSize(SceneNode button) {
        final List<SceneNode> children = button.__getChildren();
        return (children.isEmpty() ? button : children.get(0)).effectiveFontSize();
    }

    /**
     * 交互状态获取：构建期先「声明关心 hover/pressed/focused」再交给表面绑定
     * （Router 对未创建的 signal 直接短路，不声明则事件驱动不了配方状态档）。
     *
     * @param rt   场景运行时
     * @param node 目标节点
     * @return 交互状态
     */
    static SceneInteractionState interactionOf(SceneRuntime rt, SceneNode node) {
        final SceneInteractionState interaction = rt.interactionState(node);
        interaction.hovered();
        interaction.pressed();
        interaction.focused();
        return interaction;
    }

    /** @return 常量启用信号（控件自身无禁用态时的默认） */
    static ReadableSignal<Boolean> alwaysEnabled() {
        return ALWAYS_ENABLED;
    }

    /** @return 常量禁用信号（未选中配方的 chip/pill 用） */
    static ReadableSignal<Boolean> neverEnabled() {
        return NEVER_ENABLED;
    }

    /**
     * 单行省略绑定：按容器实测宽写 {@code maxTextWidth}（布局纪元重派生，不写死宽度/字号）。
     *
     * @param rt        场景运行时
     * @param container 提供可用宽的容器（其 cached layout 宽 = 子文本可用宽）
     * @param targets   需要单行省略的文本节点
     */
    static void bindEllipsisWidth(SceneRuntime rt, SceneNode container, final SceneNode... targets) {
        for (SceneNode target : targets) {
            target.setMaxLines(1);
            target.setEllipsis(true);
        }
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            final Object cached = container.getCachedLayout();
            if (!(cached instanceof LayoutBox)) {
                return;
            }
            final int width = Math.max(1, ((LayoutBox) cached).getWidth());
            for (SceneNode target : targets) {
                target.setMaxTextWidth(width);
            }
        }));
    }

    // ------------------------------------------------------------------ 共享编辑入口

    /**
     * 成员区与 picker 的共享编辑入口（包内可见）：持有 picker 的全部临时态与懒建闩锁。
     *
     * <p>生命周期与所在详情内容一致：详情（或其 keyed 项）回收时，本对象随节点一起被丢弃，
     * 不跨组、不跨次打开复用（草稿才是唯一真值）。</p>
     *
     * <p><b>成员编辑态请求</b>：{@link #editRequest()} 是 UILib
     * {@code ScenePickerPanel.Builder.memberEditRequest(ReadableSignal<Long>)} 的宿主写入口；
     * 面板在内容 Owner 内按「打开边沿 + 打开期间变化」读取，值 = 成员稳定 id（本类约定 = 成员下标）。</p>
     */
    static final class MemberEditor {
        private final ObjectGroupEditorContext ctx;
        private final long key;
        /** 面板懒建闩锁：首次「添加/编辑」请求前不构建 {@code ScenePickerPanel}（§9.1-1）。 */
        private final Signal<Boolean> requested = Signal.create(Boolean.FALSE);
        private final Signal<Boolean> open = Signal.create(Boolean.FALSE);
        private final Signal<String> query = Signal.create("");
        private final Signal<String> error = Signal.create("");
        /** 结构化提示（空串 = 无提示）。 */
        private final Signal<String> notice = Signal.create("");
        /** 编辑目标成员下标；-1 = 新增模式。 */
        private final Signal<Integer> editTarget = Signal.create(Integer.valueOf(-1));
        /**
         * 成员编辑态请求（面板 {@code memberEditRequest} 的宿主写入口）：值 = 成员稳定 id
         * （本类约定 = 成员下标）；null = 无请求（新增路径必须置 null，否则会沿用旧请求再进编辑态）。
         */
        private final Signal<Long> editRequest = Signal.create(null);
        /** 编辑目标被打开时的 raw（防「下标仍在范围内但已指向别的成员」的错位写入）。 */
        private String editAnchorRaw;
        /** 面板内最近一次删除的原位次与原值（有界 1 条；供面板 5s 原位撤销）。 */
        private int removedIndex = -1;
        private String removedRaw;

        MemberEditor(ObjectGroupEditorContext ctx, long key) {
            this.ctx = ctx;
            this.key = key;
        }

        /** @return 组行 key */
        long key() {
            return key;
        }

        /** 新增入口：picker 以空白初值打开（先把编辑态请求置 null，避免沿用旧请求再次进入编辑态）。 */
        void requestAdd() {
            editTarget.set(Integer.valueOf(-1));
            editAnchorRaw = null;
            editRequest.set(null);
            query.set("");
            error.set("");
            requested.set(Boolean.TRUE);
            open.set(Boolean.TRUE);
        }

        /**
         * 编辑入口（详情行 [编辑] / 高级原始规则 [修正]）：以既有成员为初值打开 picker。
         *
         * <p>接线按 UILib 宿主契约：<b>先写</b> {@link #editRequest}（值 = 成员稳定 id = 成员下标），
         * <b>再置</b> {@code open}（同帧即可，面板在打开边沿按当时请求值生效）；初值（候选/模式/变体 key）
         * 由面板经 {@code currentMembers} 自取，带变体者预开变体浮层。</p>
         *
         * @param index 成员下标
         */
        void requestEdit(int index) {
            editAnchorRaw = memberAt(ctx.state(), key, index);
            editTarget.set(Integer.valueOf(index));
            query.set("");
            error.set("");
            requested.set(Boolean.TRUE);
            editRequest.set(Long.valueOf(index));
            open.set(Boolean.TRUE);
        }

        /**
         * 面板成员卡 [编辑] 回调：面板已在同一条内部通路进入编辑态，这里只同步目标下标与校验锚点。
         *
         * <p>刻意<b>不</b>写 {@link #editRequest}：该信号是「由宿主发起进入编辑态」的入口，
         * 面板内点击已处于编辑态，再写同值请求会对同一目标重入一次。</p>
         *
         * @param memberId 成员稳定 id（= 成员下标）
         */
        void onPanelEdit(long memberId) {
            final int index = (int) memberId;
            editAnchorRaw = memberAt(ctx.state(), key, index);
            editTarget.set(Integer.valueOf(index));
        }

        /** @return 成员编辑态请求信号（面板 {@code memberEditRequest} 的宿主写入口） */
        ReadableSignal<Long> editRequest() {
            return editRequest;
        }

        /**
         * 面板「当前成员」快照（仅编辑目标一条）：memberId = 成员下标，供面板定位并预开变体浮层；
         * 候选本体经既有惰性候选源 {@code exact()} 解析（O(1) 定位 + 至多一次分片物化），
         * 解析不到即 unknown（不伪造候选、不改写 raw）。
         *
         * @param state  编辑状态
         * @param codec  成员级 codec（ListMemberCodec）
         * @param source 惰性候选源；null = 仅展示 key，不做本体解析
         * @return 当前成员快照信号（新增模式为空列表）
         */
        ReadableSignal<List<SearchPickerData.CurrentMember>> currentMembers(
                final ObjectGroupEditorState state, final Codec codec, final PickerCandidateSource source) {
            return Computed.create(() -> {
                final int index = editTarget.get().intValue();
                if (index < 0) {
                    return Collections.<SearchPickerData.CurrentMember>emptyList();
                }
                final String raw = memberAt(state, key, index);
                if (raw.isEmpty()) {
                    return Collections.<SearchPickerData.CurrentMember>emptyList();
                }
                final SearchPickerData.Selection selection = decodeMember(codec, raw);
                SearchPickerData.Candidate candidate = null;
                if (selection != null && source != null) {
                    try {
                        candidate = source.exact(selection.candidateKey());
                    } catch (RuntimeException ignored) {
                        candidate = null;
                    }
                }
                return Collections.singletonList(new SearchPickerData.CurrentMember(
                        index, selection, candidate, candidate != null));
            });
        }

        /**
         * 面板受控当前选择：编辑目标成员的解码结果（新增模式 = null）。
         *
         * <p>面板在「点击候选时」用它恢复该候选的已选模式与变体 key（与成员卡 [编辑] 同源口径）；
         * 进入编辑态由 {@link #editRequest()} 请求信号承担（见 {@link #requestEdit(int)}），
         * 本选择信号只作为受控初值面。</p>
         */
        ReadableSignal<SearchPickerData.Selection> currentSelection(final ObjectGroupEditorState state,
                                                                   final Codec codec) {
            return Computed.create(() -> {
                final int index = editTarget.get().intValue();
                return index < 0 ? null : decodeMember(codec, memberAt(state, key, index));
            });
        }

        /** 结构化提示：把命令拒绝原因映射为 i18n 文案（不静默失败）。 */
        void showRejection(ObjectGroupEditorState.Rejection rejection) {
            final String text = ObjectGroupDetailPane.rejectionText(rejection);
            if (text != null && !text.isEmpty()) {
                notice.set(text);
            }
        }

        /** 清除提示（成功写入后）。 */
        void clearNotice() {
            notice.set("");
        }

        /** @return 结构化提示信号 */
        ReadableSignal<String> notice() {
            return notice;
        }

        /** @return 编辑目标下标（-1 = 新增模式） */
        int editTargetIndex() {
            return editTarget.get().intValue();
        }

        /** @return 编辑目标被打开时的 raw（校验用） */
        String editAnchorRaw() {
            return editAnchorRaw;
        }

        /**
         * 登记面板内删除的原位次与原值（5s 撤销的恢复依据：必须原位次插回，追加会改序）。
         *
         * @param index 原下标
         * @param raw   原 selector
         */
        void rememberRemoved(int index, String raw) {
            removedIndex = index;
            removedRaw = raw;
        }

        /** 释放删除 tombstone（撤销成功 / 面板丢弃 / 被新删除替换）。 */
        void clearRemoved() {
            removedIndex = -1;
            removedRaw = null;
        }

        /** @return 最近一次删除的原下标（无则 -1） */
        int removedIndex() {
            return removedIndex;
        }

        /** @return 最近一次删除的原 selector（无则 null） */
        String removedRaw() {
            return removedRaw;
        }

        /** @return picker 宿主：首次请求前零面板构建（懒建）。 */
        SceneNode host(SceneRuntime rt) {
            final SceneNode host = SceneNode.column();
            rt.show(host, requested, () -> createPanel(ctx, this));
            return host;
        }
    }

    // ------------------------------------------------------------------ picker 装配

    /**
     * 装配成员 picker（唯一实例；仅在首次「添加/编辑」请求时构建）。
     *
     * <p>装配全部走 UILib 公开面：{@link ScenePickerPanel} 的受控 props、SPI 候选源 + 版本桥、
     * 分类维度受控注入、密度档位；本类不复制面板内部逻辑。写回经 {@code selectionCommit}
     * 单点落到 State 命令。</p>
     */
    private static SceneNode createPanel(ObjectGroupEditorContext ctx, MemberEditor editor) {
        final SceneRuntime rt = ctx.rt();
        final ValueEditorProvider provider = providerOf(ctx);
        if (provider == null) {
            // pane 不得抛异常：给出结构化提示（生产路径由 ObjectGroupPickerRegistration 保证已注册）。
            editor.notice.set(ClientI18n.tr("config.qz_miner.object_group.picker.unavailable"));
            return new SceneNode();
        }
        final Codec codec = provider.codec();
        final PickerCandidateSource source = candidateSourceOf(provider);
        final Signal<String> categoryKey = Signal.create(null);
        final Signal<Integer> dimension = Signal.create(Integer.valueOf(0));

        final ReadableSignal<SearchPickerData.SearchResult> results = source == null
                ? legacyResults(provider, editor)
                : NO_RESULTS;
        final ScenePickerPanel.Props.Builder builder = ScenePickerPanel.Props.builder(editor.query, results,
                ALWAYS_ENABLED,
                next -> {
                    editor.error.set("");
                    editor.query.set(next);
                },
                selection -> { },
                visualAdapterOf(rt, provider))
                .selectionCommit(selection -> commit(ctx, editor, codec, selection))
                .currentSelection(editor.currentSelection(ctx.state(), codec))
                .presentation(provider.presentation())
                .panelPresentation(provider.panelPresentation())
                .variantSearchEnabled(true)
                .error(editor.error)
                .currentMembers(editor.currentMembers(ctx.state(), codec, source), editor::onPanelEdit)
                // U3 编辑态：宿主请求信号由详情行 [编辑] / 高级区 [修正] 写入（新增路径置 null）。
                // 面板在内容 Owner 内按打开边沿/打开期间变化读取，命中则走内部 editMember 同一通路。
                .memberEditRequest(editor.editRequest())
                // 面板内删除 = removeMember 命令；5s 撤销 = insertMember 原位次插回（顺序保真，
                // 不用追加式 addMember）。memberId 由本类约定为成员下标，映射可靠。
                .onRemoveCurrent(memberId -> removeInPanel(ctx, editor, (int) memberId))
                .onRestoreCurrent(memberId -> restoreInPanel(ctx, editor, memberId), editor::clearRemoved)
                .onBeginAdd(() -> editor.editTarget.set(Integer.valueOf(-1)))
                .onCancel(() -> {
                    editor.editTarget.set(Integer.valueOf(-1));
                    editor.query.set("");
                    editor.error.set("");
                })
                .open(editor.open)
                .onCloseRequest(() -> editor.open.set(Boolean.FALSE))
                .resultsCategoryFiltered(source != null)
                .densityPreference(PickerDensityPreferenceSource.installed());
        wireCategories(builder, provider, categoryKey, dimension);
        if (source != null) {
            final PickerRevisionBridge bridge =
                    PickerRevisionBridge.forSource(source, rt.environment());
            bridge.bindTo(rt);
            final Computed<PickerQuery> sourceQuery = Computed.create(() -> PickerQuery.text(
                    editor.query.get(), dimension.get().intValue(), categoryKey.get()));
            builder.candidateSource(source, sourceQuery, bridge.versionSignal());
        }
        return ScenePickerPanel.create(rt, builder.build()).root();
    }

    /**
     * 面板内删除：唯一落地路径 = {@code removeMember} 命令；同时登记原位次 tombstone，
     * 供面板 5s 撤销（{@link #restoreInPanel}）原位插回。
     *
     * @param ctx    宿主上下文
     * @param editor 共享编辑入口
     * @param index  成员下标（= 面板 memberId）
     * @return 是否已提交删除（false = 面板保持原状态并给结构化提示）
     */
    private static boolean removeInPanel(ObjectGroupEditorContext ctx, MemberEditor editor, int index) {
        final ObjectGroupEditorState state = ctx.state();
        final long key = editor.key();
        final String raw = memberAt(state, key, index);
        final ObjectGroupEditorState.EditResult result = state.removeMember(key, index);
        if (!result.accepted()) {
            editor.showRejection(result.rejection());
            return false;
        }
        editor.rememberRemoved(index, raw);
        editor.editTarget.set(Integer.valueOf(-1));
        editor.clearNotice();
        return true;
    }

    /**
     * 面板内撤销：按原位次插回（{@code insertMember}，顺序保真）；拒绝时保留 tombstone
     * （用户可重试，窗口到期由面板丢弃）并给结构化提示。
     *
     * @param ctx      宿主上下文
     * @param editor   共享编辑入口
     * @param memberId 面板回调的成员 id（= 原下标）
     * @return 是否已恢复
     */
    private static boolean restoreInPanel(ObjectGroupEditorContext ctx, MemberEditor editor, long memberId) {
        final String raw = editor.removedRaw();
        if (raw == null || editor.removedIndex() != (int) memberId) {
            return false;
        }
        final ObjectGroupEditorState.EditResult result = ctx.state().insertMember(
                editor.key(), editor.removedIndex(), raw);
        if (!result.accepted()) {
            editor.showRejection(result.rejection());
            return false;
        }
        editor.clearRemoved();
        editor.clearNotice();
        return true;
    }

    /** 提交单点：新增 ⇒ {@code addMember}；编辑 ⇒ {@code replaceMember}（原位）。 */
    private static boolean commit(ObjectGroupEditorContext ctx, MemberEditor editor, Codec codec,
                                  SearchPickerData.Selection selection) {
        if (selection == null) {
            return false;
        }
        final ObjectGroupEditorState state = ctx.state();
        final long key = editor.key();
        final int target = editor.editTargetIndex();
        if (!(codec instanceof ListMemberCodec)) {
            editor.showRejection(ObjectGroupEditorState.Rejection.SELECTOR_UNPARSABLE);
            return false;
        }
        if (target >= 0 && (editor.editAnchorRaw() == null
                || !editor.editAnchorRaw().equals(memberAt(state, key, target)))) {
            // 打开编辑态后成员列表被外部改动（reload/撤销）：拒绝错位写入，不猜目标。
            editor.showRejection(ObjectGroupEditorState.Rejection.INDEX_OUT_OF_RANGE);
            return false;
        }
        final String canonical;
        try {
            final Object encoded = ((ListMemberCodec) codec).encodeMember(
                    target < 0 ? "" : memberAt(state, key, target), selection);
            if (!(encoded instanceof String)) {
                editor.showRejection(ObjectGroupEditorState.Rejection.SELECTOR_UNPARSABLE);
                return false;
            }
            canonical = (String) encoded;
        } catch (RuntimeException invalid) {
            editor.showRejection(ObjectGroupEditorState.Rejection.SELECTOR_UNPARSABLE);
            return false;
        }
        final ObjectGroupEditorState.EditResult result = target < 0
                ? state.addMember(key, canonical)
                : state.replaceMember(key, target, canonical);
        if (!result.accepted()) {
            editor.showRejection(result.rejection());
            return false;
        }
        editor.clearNotice();
        editor.editTarget.set(Integer.valueOf(-1));
        return true;
    }

    /** 分类受控注入（维度/分类键都经信号受控，面板不重复推导维度语义）。 */
    private static void wireCategories(ScenePickerPanel.Props.Builder builder, ValueEditorProvider provider,
                                       final Signal<String> categoryKey, final Signal<Integer> dimension) {
        if (!(provider instanceof CategorizedValueEditorProvider)) {
            return;
        }
        final CategorizedValueEditorProvider categorized = (CategorizedValueEditorProvider) provider;
        builder.categories(Computed.create(() -> SearchPickerCategories.immutableCopy(
                categorized.categories(dimension.get().intValue()))));
        builder.categoryOf(candidateKey -> categorized.categoryOf(dimension.get().intValue(), candidateKey));
        builder.currentCategoryKey(categoryKey, categoryKey::set);
        if (categorized.categoryDimensionCount() > 1) {
            builder.dimension(dimension, next -> {
                categoryKey.set(null);
                dimension.set(next);
            });
        }
    }

    /** 非 SPI 回落：搜索 lane 结果（关闭时不求值，避免关闭态下的全表搜索）。 */
    private static ReadableSignal<SearchPickerData.SearchResult> legacyResults(final ValueEditorProvider provider,
                                                                              final MemberEditor editor) {
        return Computed.create(() -> {
            if (!Boolean.TRUE.equals(editor.open.get())) {
                return SearchPickerData.SearchResult.empty();
            }
            try {
                final SearchPickerData.SearchResult searched =
                        provider.searchFunction().search(editor.query.get(), Integer.MAX_VALUE);
                if (searched == null) {
                    editor.error.set(provider.presentation().searchError());
                    return SearchPickerData.SearchResult.empty();
                }
                editor.error.set("");
                return searched;
            } catch (RuntimeException exception) {
                editor.error.set(provider.presentation().searchError());
                return SearchPickerData.SearchResult.empty();
            }
        });
    }

    /** 惰性候选源探测（实现 SPI 才走查询式路径）。 */
    private static PickerCandidateSource candidateSourceOf(ValueEditorProvider provider) {
        if (!(provider instanceof CandidateSourceValueEditorProvider)) {
            return null;
        }
        return ((CandidateSourceValueEditorProvider) provider).candidateSource();
    }

    /** 展示适配器：provider 给出图标源时接上 UILib 有界图标缓存（缓存释放随当前 Owner）。 */
    private static VisualAdapter visualAdapterOf(SceneRuntime rt, ValueEditorProvider provider) {
        final PickerIconResolver resolver =
                PickerIconResolver.of(provider, rt.environment().resources());
        if (resolver == null) {
            return provider.visualAdapter();
        }
        rt.__onCleanup(resolver::release);
        return resolver;
    }
}
