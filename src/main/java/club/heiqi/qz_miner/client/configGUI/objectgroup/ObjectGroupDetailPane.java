package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import club.heiqi.uilib.ui.reactive.Computed;
import club.heiqi.uilib.ui.reactive.Effect;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneInputType;
import club.heiqi.uilib.ui.scene.control.SceneTextInput;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyAction;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.SceneChromeTokens;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceBinder;
import club.heiqi.uilib.ui.scene.theme.SceneTheme;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * 对象组详情 pane（M4）：标识区 + 适用模式 pill + 冲突状态条 + 成员区 + 高级原始规则。
 *
 * <p><b>选择真源</b>：本 pane 自行读 {@link ObjectGroupEditorState#selection()}；未选中时给出引导占位
 * （不抛异常）。内容按选中行 key 做 keyed 重建（{@code rt.forEach}），因此「调用方每次选中变化重建」
 * 与「调用方只挂一次、内容自行切换」两种接法都成立，且不存在第二份选中真值。</p>
 *
 * <p><b>数据边界（硬）</b>：id/modes/成员的读写全部经 {@link ObjectGroupEditorState} 的编辑命令；
 * 冲突与错误只消费 {@link ObjectGroupEditorState#parseResult()}（{@code ObjectGroupParser} 的
 * {@code errors()} 唯一真源）与 {@link ObjectGroupEditorState#views()}，本类不复刻
 * {@code findOverlapErrors} 或任何解析器内部判定。</p>
 *
 * <p><b>边界（§5.5-3）</b>：id ≤ {@link ObjectGroup#MAX_ID_LENGTH} 字符（输入框长度上限 + 行内红字）；
 * 成员 ≤ {@link ObjectGroup#MAX_MEMBERS}/组、总量 ≤ {@code ObjectGroupRuleSet#MAX_TOTAL_MEMBERS}
 * （成员区禁用入口并给原因）；失效 modes 值保留为可取消的「已失效」项（{@code RowView.unknownModes()}），
 * 经 {@code removeUnknownMode} 取消，绝不静默丢弃。</p>
 *
 * <p><b>动态化（§6.2）</b>：颜色全走 {@link SceneThemes}；字号不写死（继承父链声明/倍率）；
 * 图标与行高由生效字号派生；pill 换行按容器实测宽 + 文本实测宽贪心分包；分页页大小由运行时逻辑盒高派生。</p>
 */
public final class ObjectGroupDetailPane {

    /** 区块间距（逻辑 px，非颜色令牌）。 */
    private static final int SECTION_GAP = SceneChromeTokens.GAP_MD;
    /** 区块内部间距（逻辑 px）。 */
    private static final int INNER_GAP = SceneChromeTokens.GAP_SM;
    /** pill 横向内边距（逻辑 px）。 */
    private static final int PILL_PAD_H = SceneChromeTokens.PAD_SM;
    /** pill 纵向内边距（逻辑 px）。 */
    private static final int PILL_PAD_V = 2;
    /** 状态点直径下限（逻辑 px）；实际直径随生效字号派生。 */
    private static final int STATUS_DOT_MIN_PX = 8;

    /** 整表级解析错误在 {@code errors()} 里的键（= 字段 path，不归属任何行）。 */
    private static final String GLOBAL_ERROR_PATH = ObjectGroupEditorState.PATH;

    private ObjectGroupDetailPane() {
    }

    /**
     * 构建组详情（冻结签名）。
     *
     * @param ctx 宿主上下文
     * @return 可直接 appendChild 的详情根节点（未选中时为引导占位）
     */
    public static SceneNode build(ObjectGroupEditorContext ctx) {
        final SceneRuntime rt = ctx.rt();
        final ObjectGroupEditorState state = ctx.state();
        final SceneNode root = SceneNode.column();
        root.setGap(SECTION_GAP);

        // 引导占位：未选中时可见（内容区同时为空）。
        final SceneNode placeholderHost = SceneNode.column();
        root.appendChild(placeholderHost);
        rt.show(placeholderHost, Computed.create(() -> Boolean.valueOf(state.selection() == null)),
                () -> ObjectGroupMemberPane.textNode(rt,
                        ClientI18n.tr("config.qz_miner.object_group.detail.empty"),
                        SceneThemes.mutedForeground(rt)));

        // 详情内容：唯一选中项 ⇒ 长度为 0/1 的 keyed 列表（切换选中 = 旧内容回收 + 新内容构建）。
        final SceneNode contentHost = SceneNode.column();
        root.appendChild(contentHost);
        final ReadableSignal<List<Long>> selectedKeys = Computed.create(() -> {
            final ObjectGroupEditorState.RowView view = state.selection();
            return view == null ? Collections.<Long>emptyList()
                    : Collections.singletonList(Long.valueOf(view.key()));
        });
        rt.forEach(contentHost, selectedKeys, key -> key, key -> content(ctx, key.longValue()));
        return root;
    }

    /** 单个组的详情内容（三区 + 高级）。 */
    private static SceneNode content(ObjectGroupEditorContext ctx, long key) {
        final SceneRuntime rt = ctx.rt();
        final ObjectGroupEditorState state = ctx.state();
        final SceneNode root = SceneNode.column();
        root.setGap(SECTION_GAP);
        if (state.viewOf(key) == null) {
            return root;
        }
        // 本详情内的结构化提示（区级拒绝原因），与成员区提示各自独立、互不覆盖。
        final Signal<String> notice = Signal.create("");
        final ObjectGroupMemberPane.MemberEditor editor = new ObjectGroupMemberPane.MemberEditor(ctx, key);

        root.appendChild(statusBanner(rt, state, key));
        root.appendChild(idSection(rt, state, key));
        root.appendChild(modesSection(rt, state, key, notice));
        root.appendChild(ObjectGroupMemberPane.build(ctx, key, editor));
        root.appendChild(advancedSection(rt, state, key, editor, notice));
        root.appendChild(ObjectGroupMemberPane.noticeNode(rt, notice));
        return root;
    }

    // ------------------------------------------------------------------ 状态条（冲突 / 整表级错误）

    /**
     * 冲突状态条（§5.5-5）：冲突真源唯一 = 解析器 {@code errors()}；
     * 行级错误映射到当前行，整表级错误（{@code client.objectGroups} 键）也在此照实提示。
     */
    private static SceneNode statusBanner(SceneRuntime rt, ObjectGroupEditorState state, long key) {
        final SceneNode host = SceneNode.column();
        final ReadableSignal<String> rowError = Computed.create(() -> {
            final ObjectGroupEditorState.RowView view = state.viewOf(key);
            return view == null || view.error() == null ? "" : view.error();
        });
        final ReadableSignal<String> globalError = Computed.create(() -> {
            final String value = state.parseResult().errors().get(GLOBAL_ERROR_PATH);
            return value == null ? "" : value;
        });
        final ReadableSignal<Boolean> visible = Computed.create(() -> Boolean.valueOf(
                !rowError.get().isEmpty() || !globalError.get().isEmpty()));
        rt.show(host, visible, () -> {
            final SceneNode banner = SceneNode.column();
            banner.setGap(2);
            final SceneNode headline = SceneNode.row();
            headline.setGap(INNER_GAP);
            headline.setCrossAxisAlign(CrossAxisAlign.CENTER);

            // 状态点：直径随生效字号派生（不写死物理尺寸），颜色走主题错误语义色。
            final SceneNode dot = new SceneNode();
            dot.setHitTestable(false);
            headline.appendChild(dot);
            final SceneNode title = ObjectGroupMemberPane.textNode(rt, "", SceneThemes.errorText(rt));
            headline.appendChild(title);
            banner.appendChild(headline);

            // 原始错误文本（解析器真源，照实展示为次要信息；本地化标题由上方承担）。
            final SceneNode detail = ObjectGroupMemberPane.textNode(rt, "", SceneThemes.mutedForeground(rt));
            rt.bindComputed(() -> rowError.get().isEmpty() ? globalError.get() : rowError.get(), detail::setText);
            banner.appendChild(detail);

            rt.bindComputed(() -> rowError.get().isEmpty()
                    ? ClientI18n.tr("config.qz_miner.object_group.parse.global")
                    : ClientI18n.tr("config.qz_miner.object_group.conflict.message"), title::setText);
            rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
                final int side = Math.max(STATUS_DOT_MIN_PX, Math.round(dot.effectiveFontSize() * 0.5f));
                dot.setPreferredWidth(side);
                dot.setPreferredHeight(side);
                dot.setCornerRadius(side / 2);
            }));
            rt.bind(SceneThemes.errorText(rt), dot::setBackgroundColor);
            return banner;
        });
        return host;
    }

    // ------------------------------------------------------------------ 标识区

    /**
     * 组标识区：{@code SceneTextInput} + 就地校验（重复/空/超长 ⇒ 行内红字，错误绝不进对话框）。
     *
     * <p>写入经 {@code renameGroup} 命令；被拒绝时保留用户输入并就地提示（便于修正），
     * 成功或外部回灌（reload/撤销）时才同步回草稿值。</p>
     */
    private static SceneNode idSection(SceneRuntime rt, final ObjectGroupEditorState state, final long key) {
        final SceneNode column = SceneNode.column();
        column.setGap(2);
        column.appendChild(ObjectGroupMemberPane.textNode(rt,
                ClientI18n.tr("config.qz_miner.object_group.id.label"), SceneThemes.mutedForeground(rt)));

        final Signal<String> text = Signal.create(idOf(state, key));
        final Signal<String> error = Signal.create("");
        final SceneTextInput.Handle handle = SceneTextInput.createHandle(rt,
                SceneTextInput.Props.builder(text)
                        .placeholder("")
                        .maxLength(ObjectGroup.MAX_ID_LENGTH)
                        .inputType(SceneInputType.TEXT)
                        .onChange(next -> applyRename(state, key, next, error))
                        .build());
        final SceneNode input = handle.component().get();
        column.appendChild(input);

        // 外部变化回灌：输入框聚焦时不覆盖用户正在输入的文本。
        final ReadableSignal<Boolean> focused = rt.interactionState(input).focused();
        rt.bind(state.rows(), value -> {
            if (!Boolean.TRUE.equals(focused.get())) {
                text.set(idOf(state, key));
            }
        });

        final ReadableSignal<Integer> muted = SceneThemes.mutedForeground(rt);
        final ReadableSignal<Integer> invalid = SceneThemes.errorText(rt);
        final SceneNode hint = ObjectGroupMemberPane.textNode(rt, "", muted);
        rt.bindComputed(() -> error.get().isEmpty()
                ? ClientI18n.tr("config.qz_miner.object_group.id.helper") : error.get(), hint::setText);
        rt.bindComputed(() -> error.get().isEmpty() ? muted.get() : invalid.get(), hint::setTextColor);
        ObjectGroupMemberPane.bindEllipsisWidth(rt, column, hint);
        column.appendChild(hint);
        return column;
    }

    /** 就地校验的写入：成功清错，拒绝则行内红字（结构化反馈，不静默失败）。 */
    private static void applyRename(ObjectGroupEditorState state, long key, String next, Signal<String> error) {
        final ObjectGroupEditorState.EditResult result = state.renameGroup(key, next);
        error.set(result.accepted() ? "" : rejectionText(result.rejection()));
    }

    /** 当前组 id（行不存在时空串）。 */
    private static String idOf(ObjectGroupEditorState state, long key) {
        final ObjectGroupEditorState.RowView view = state.viewOf(key);
        return view == null ? "" : view.id();
    }

    // ------------------------------------------------------------------ 适用模式区

    /**
     * 适用模式区：7 个已知模式 pill 多选（随宽换行）+ 空选提示 + 失效模式值（可取消）。
     *
     * <p>标签一律走 {@link ClientI18n}（键 {@code config.qz_miner.object_group.mode.<id>}），
     * 不显示原始 key；切换经 {@code toggleGroupMode} 命令，拒绝时给结构化提示。</p>
     */
    private static SceneNode modesSection(SceneRuntime rt, final ObjectGroupEditorState state, final long key,
                                          final Signal<String> notice) {
        final SceneNode column = SceneNode.column();
        column.setGap(INNER_GAP);
        column.appendChild(ObjectGroupMemberPane.textNode(rt,
                ClientI18n.tr("config.qz_miner.object_group.modes.label"), SceneThemes.mutedForeground(rt)));

        final ReadableSignal<List<String>> known = Computed.create(() -> modesOf(state, key));
        final ReadableSignal<List<String>> unknown = Computed.create(() -> unknownModesOf(state, key));
        final String[] modeIds = ObjectGroupMode.ids();

        // 换行容器：按容器实测宽 + 文本实测宽贪心分包（随宽换行，不写死每行数量）。
        final SceneNode wrapHost = SceneNode.column();
        wrapHost.setGap(INNER_GAP);
        final Signal<Integer> available = Signal.create(Integer.valueOf(0));
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            final Object cached = wrapHost.getCachedLayout();
            if (cached instanceof LayoutBox) {
                available.set(Integer.valueOf(((LayoutBox) cached).getWidth()));
            }
        }));
        final ReadableSignal<List<List<String>>> rows = Computed.create(() -> {
            // 失效源：容器实测宽 + 布局纪元 + 字号纪元（文本实测宽随字号变化）。
            rt.layoutDoneSignal().get();
            rt.fontEpochSignal().get();
            return wrapModes(rt, wrapHost, modeIds, available.get().intValue());
        });
        rt.forEach(wrapHost, rows, ModesRowKey::of, row -> modesRow(rt, state, key, known, row, notice));
        column.appendChild(wrapHost);

        // 空选提示：未选择模式的组不会生效（§1.3 缺口）。
        final SceneNode inactiveHost = SceneNode.column();
        rt.show(inactiveHost, Computed.create(() -> Boolean.valueOf(known.get().isEmpty())),
                () -> ObjectGroupMemberPane.textNode(rt,
                        ClientI18n.tr("config.qz_miner.object_group.modes.none"), SceneThemes.warningText(rt)));
        column.appendChild(inactiveHost);

        // 失效模式值：保留原值 + 可取消（不得静默丢弃）。
        final SceneNode unknownHost = SceneNode.row();
        unknownHost.setGap(INNER_GAP);
        rt.forEach(unknownHost, unknown, modeId -> modeId,
                modeId -> unknownChip(rt, state, key, modeId, notice));
        rt.bind(unknown, value -> unknownHost.setCollapsed(value == null || value.isEmpty()));
        column.appendChild(unknownHost);
        return column;
    }

    /** 一行 pill（行内容随换行结果变化 ⇒ key = 该行模式 id 序列，重排即重建）。 */
    private static SceneNode modesRow(SceneRuntime rt, final ObjectGroupEditorState state, final long key,
                                      ReadableSignal<List<String>> known, List<String> row, Signal<String> notice) {
        final SceneNode line = SceneNode.row();
        line.setGap(INNER_GAP);
        line.setCrossAxisAlign(CrossAxisAlign.CENTER);
        for (final String modeId : row) {
            final ReadableSignal<Boolean> selected = Computed.create(() -> Boolean.valueOf(
                    known.get().contains(modeId)));
            line.appendChild(pill(rt, ClientI18n.tr(modeKey(modeId)), selected,
                    () -> toggleMode(state, key, modeId, notice)));
        }
        return line;
    }

    /** 模式 pill：多选 chip（外观走主题可选配方，命中/键盘可聚焦）。 */
    private static SceneNode pill(SceneRuntime rt, String label, ReadableSignal<Boolean> selected,
                                  final Runnable onClick) {
        final SceneNode node = SceneNode.row();
        node.setGap(2);
        node.setPadding(PILL_PAD_H, PILL_PAD_V, PILL_PAD_H, PILL_PAD_V);
        node.setCrossAxisAlign(CrossAxisAlign.CENTER);
        SceneSurfaceBinder.bind(rt, node,
                SceneThemes.selectableSurface(rt, SceneTheme.Role.INPUT, selected),
                ObjectGroupMemberPane.alwaysEnabled(), ObjectGroupMemberPane.interactionOf(rt, node));
        node.appendChild(ObjectGroupMemberPane.textNode(rt, label, SceneThemes.foreground(rt)));
        rt.focusable(node);
        rt.on(node, SceneEventType.CLICK, (event, ectx) -> {
            onClick.run();
            ectx.stopPropagation();
        });
        rt.on(node, SceneEventType.KEY_DOWN, (event, ectx) -> {
            if (event.getKeyAction() == SceneKeyAction.PRESSED && !event.isRepeat()
                    && (event.getKey() == SceneKey.ENTER || event.getKey() == SceneKey.SPACE)) {
                onClick.run();
                ectx.stopPropagation();
            }
        });
        return node;
    }

    /** 模式切换：唯一落地路径 = {@code toggleGroupMode} 命令。 */
    private static void toggleMode(ObjectGroupEditorState state, long key, String modeId, Signal<String> notice) {
        final ObjectGroupEditorState.EditResult result = state.toggleGroupMode(key, modeId);
        notice.set(result.accepted() ? "" : rejectionText(result.rejection()));
    }

    /** 失效模式项：原值 + 「已失效」+ 取消入口（{@code removeUnknownMode} 命令）。 */
    private static SceneNode unknownChip(SceneRuntime rt, final ObjectGroupEditorState state, final long key,
                                         final String modeId, final Signal<String> notice) {
        final SceneNode chip = SceneNode.row();
        chip.setGap(2);
        chip.setPadding(PILL_PAD_H, PILL_PAD_V, PILL_PAD_H, PILL_PAD_V);
        chip.setCrossAxisAlign(CrossAxisAlign.CENTER);
        SceneSurfaceBinder.bind(rt, chip,
                SceneThemes.selectableSurface(rt, SceneTheme.Role.INPUT, ObjectGroupMemberPane.neverEnabled()),
                ObjectGroupMemberPane.alwaysEnabled(), ObjectGroupMemberPane.interactionOf(rt, chip));
        chip.appendChild(ObjectGroupMemberPane.textNode(rt, modeId, SceneThemes.warningText(rt)));
        chip.appendChild(ObjectGroupMemberPane.textNode(rt,
                ClientI18n.tr("config.qz_miner.object_group.modes.unknown"), SceneThemes.warningText(rt)));
        chip.appendChild(ObjectGroupMemberPane.actionButton(rt,
                ClientI18n.tr("config.qz_miner.object_group.modes.unknown_remove"),
                ObjectGroupMemberPane.alwaysEnabled(), () -> {
                    final ObjectGroupEditorState.EditResult result = state.removeUnknownMode(key, modeId);
                    notice.set(result.accepted() ? "" : rejectionText(result.rejection()));
                }));
        return chip;
    }

    /** 模式 i18n 键：{@code config.qz_miner.object_group.mode.<modeId>}（键由 M7 提供）。 */
    static String modeKey(String modeId) {
        return "config.qz_miner.object_group.mode." + modeId;
    }

    /** 已知模式 id（保序、只读）。 */
    private static List<String> modesOf(ObjectGroupEditorState state, long key) {
        final ObjectGroupEditorState.RowView view = state.viewOf(key);
        return view == null ? Collections.<String>emptyList() : view.modes();
    }

    /**
     * 失效模式值（保序、无损、去重）。
     *
     * <p>去重的理由：keyed 列表要求 key 唯一，而手工编辑过的配置里同一失效值可能出现多次
     * （State 侧 {@code unknownModes()} 是原样保真列表）；取消时按值逐次移除，与
     * {@code removeUnknownMode} 的命令语义一致。</p>
     */
    private static List<String> unknownModesOf(ObjectGroupEditorState state, long key) {
        final ObjectGroupEditorState.RowView view = state.viewOf(key);
        return view == null ? Collections.<String>emptyList()
                : new ArrayList<String>(new LinkedHashSet<String>(view.unknownModes()));
    }

    /**
     * pill 换行分包：按文本实测宽 + 内边距贪心装行；容器宽未知（首帧）时退化为单行。
     *
     * @param rt        场景运行时（提供文本度量）
     * @param probe     生效字号探针（须已进树）
     * @param modeIds   模式 id（保序）
     * @param available 容器实测宽（0 = 未知）
     * @return 每行的模式 id 列表
     */
    static List<List<String>> wrapModes(SceneRuntime rt, SceneNode probe, String[] modeIds, int available) {
        final int fontSize = probe.effectiveFontSize();
        final List<List<String>> rows = new ArrayList<List<String>>();
        List<String> current = new ArrayList<String>();
        int used = 0;
        for (String modeId : modeIds) {
            final String label = ClientI18n.tr(modeKey(modeId));
            final int width = rt.measureTextWidth(label, fontSize) + PILL_PAD_H * 2 + INNER_GAP;
            if (!current.isEmpty() && available > 0 && used + width > available) {
                rows.add(Collections.unmodifiableList(current));
                current = new ArrayList<String>();
                used = 0;
            }
            current.add(modeId);
            used += width;
        }
        if (!current.isEmpty()) {
            rows.add(Collections.unmodifiableList(current));
        }
        return Collections.unmodifiableList(rows);
    }

    // ------------------------------------------------------------------ 高级：原始规则

    /**
     * 高级「原始规则」（折叠，默认收起）：只读展示规范化 selector 列表 + 逐行修正入口。
     *
     * <p>无损口径：可解析项显示 {@code canonical()}（规范化形态），不可解析项照实显示原始 raw 并标红；
     * 初版不做批量文本编辑（Q5）。列表与成员区同口径分页，不一次性构建全部行。</p>
     */
    private static SceneNode advancedSection(SceneRuntime rt, final ObjectGroupEditorState state, final long key,
                                             final ObjectGroupMemberPane.MemberEditor editor, final Signal<String> notice) {
        final SceneNode column = SceneNode.column();
        column.setGap(INNER_GAP);
        final Signal<Boolean> expanded = Signal.create(Boolean.FALSE);
        final ReadableSignal<Integer> count = Computed.create(() -> Integer.valueOf(
                ObjectGroupMemberPane.memberListOf(state, key).size()));

        final SceneNode header = SceneNode.row();
        header.setGap(INNER_GAP);
        header.setCrossAxisAlign(CrossAxisAlign.CENTER);
        header.appendChild(ObjectGroupMemberPane.actionButton(rt,
                ClientI18n.tr("config.qz_miner.object_group.advanced.label"),
                ObjectGroupMemberPane.alwaysEnabled(),
                () -> expanded.set(Boolean.valueOf(!Boolean.TRUE.equals(expanded.get())))));
        final SceneNode countText = ObjectGroupMemberPane.textNode(rt, "", SceneThemes.mutedForeground(rt));
        rt.bindComputed(() -> "(" + count.get().intValue() + ")", countText::setText);
        header.appendChild(countText);
        column.appendChild(header);

        final SceneNode listHost = SceneNode.column();
        rt.show(listHost, expanded, () -> rawRuleList(rt, state, key, editor));
        column.appendChild(listHost);
        return column;
    }

    /** 原始规则列表：分页窗口 + 每行「规范化 selector + [修正]」。 */
    private static SceneNode rawRuleList(SceneRuntime rt, final ObjectGroupEditorState state, final long key,
                                         final ObjectGroupMemberPane.MemberEditor editor) {
        final SceneNode column = SceneNode.column();
        column.setGap(INNER_GAP);
        final SceneNode probe = new SceneNode();
        probe.setHitTestable(false);
        column.appendChild(probe);

        final ReadableSignal<List<String>> members = Computed.create(() -> ObjectGroupMemberPane.memberListOf(state, key));
        final ReadableSignal<Integer> count = Computed.create(() -> Integer.valueOf(members.get().size()));
        final ReadableSignal<Integer> rowsPerPage = ObjectGroupMemberPane.rowsPerPageSignal(rt, probe);
        final Signal<Integer> page = Signal.create(Integer.valueOf(0));
        final ReadableSignal<Integer> pageCount = Computed.create(() -> Integer.valueOf(
                ObjectGroupMemberPane.pageCountOf(count.get().intValue(), rowsPerPage.get().intValue())));
        final ReadableSignal<Integer> pageIndex = Computed.create(() -> Integer.valueOf(
                ObjectGroupMemberPane.clamp(page.get().intValue(), 0, pageCount.get().intValue() - 1)));
        rt.bind(count, value -> {
            if (page.get().intValue() != 0) {
                page.set(Integer.valueOf(0));
            }
        });
        final ReadableSignal<List<Integer>> pageItems = Computed.create(() -> ObjectGroupMemberPane.indicesOf(
                pageIndex.get().intValue(), rowsPerPage.get().intValue(), count.get().intValue()));

        final SceneNode list = SceneNode.column();
        list.setGap(INNER_GAP);
        rt.forEach(list, pageItems, index -> index,
                index -> rawRuleRow(rt, state, key, index.intValue(), editor));
        column.appendChild(list);
        column.appendChild(ObjectGroupMemberPane.pagerBar(rt, page, pageIndex, pageCount,
                "config.qz_miner.object_group.members.page",
                "config.qz_miner.object_group.members.page_prev",
                "config.qz_miner.object_group.members.page_next"));
        return column;
    }

    /** 单行原始规则：序号 + 规范化 selector（不可解析时标红照实显示）+ [修正] 入口。 */
    private static SceneNode rawRuleRow(SceneRuntime rt, final ObjectGroupEditorState state, final long key,
                                        int index, final ObjectGroupMemberPane.MemberEditor editor) {
        final SceneNode row = SceneNode.row();
        row.setGap(INNER_GAP);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.appendChild(ObjectGroupMemberPane.textNode(rt, Integer.toString(index + 1),
                SceneThemes.mutedForeground(rt)));

        final ReadableSignal<String> raw = Computed.create(() -> ObjectGroupMemberPane.memberAt(state, key, index));
        final ReadableSignal<ObjectGroupSelector> parsed = Computed.create(
                () -> ObjectGroupMemberPane.parseOrNull(raw.get()));
        final ReadableSignal<Integer> normal = SceneThemes.foreground(rt);
        final ReadableSignal<Integer> invalid = SceneThemes.errorText(rt);
        final SceneNode value = ObjectGroupMemberPane.textNode(rt, "", normal);
        value.setFlexGrow(1);
        rt.bindComputed(() -> parsed.get() == null ? raw.get() : parsed.get().canonical(), value::setText);
        rt.bindComputed(() -> parsed.get() == null ? invalid.get() : normal.get(), value::setTextColor);
        ObjectGroupMemberPane.bindEllipsisWidth(rt, row, value);
        row.appendChild(value);

        row.appendChild(ObjectGroupMemberPane.actionButton(rt,
                ClientI18n.tr("config.qz_miner.object_group.members.edit"),
                ObjectGroupMemberPane.alwaysEnabled(), () -> editor.requestEdit(index)));
        return row;
    }

    // ------------------------------------------------------------------ 拒绝原因文案

    /**
     * 编辑命令拒绝原因 → i18n 文案（M4/M5 共用；代码里不写死自然语言，键由 M7 提供）。
     *
     * @param rejection 拒绝原因
     * @return 本地化文案；无原因时为空串
     */
    static String rejectionText(ObjectGroupEditorState.Rejection rejection) {
        if (rejection == null) {
            return "";
        }
        switch (rejection) {
            case NO_TARGET: return ClientI18n.tr("config.qz_miner.object_group.reject.no_target");
            case ID_EMPTY: return ClientI18n.tr("config.qz_miner.object_group.id.empty");
            case ID_TOO_LONG: return ClientI18n.tr("config.qz_miner.object_group.id.too_long");
            case ID_DUPLICATED: return ClientI18n.tr("config.qz_miner.object_group.id.duplicated");
            case GROUP_LIMIT: return ClientI18n.tr("config.qz_miner.object_group.limit.groups");
            case MEMBER_LIMIT: return ClientI18n.tr("config.qz_miner.object_group.limit.members");
            case TOTAL_LIMIT: return ClientI18n.tr("config.qz_miner.object_group.limit.total");
            case MODE_UNKNOWN: return ClientI18n.tr("config.qz_miner.object_group.reject.mode_unknown");
            case SELECTOR_UNPARSABLE: return ClientI18n.tr("config.qz_miner.object_group.reject.selector");
            case SELECTOR_TOO_LONG: return ClientI18n.tr("config.qz_miner.object_group.reject.selector_too_long");
            case NOTHING_TO_UNDO: return ClientI18n.tr("config.qz_miner.object_group.reject.nothing_to_undo");
            case INDEX_OUT_OF_RANGE: return ClientI18n.tr("config.qz_miner.object_group.reject.index");
            default: return "";
        }
    }

    /** 换行行 key：行内模式 id 序列（换行结果变化 ⇒ key 变化 ⇒ 重建该行）。 */
    private static final class ModesRowKey {
        private ModesRowKey() {
        }

        static String of(List<String> row) {
            final StringBuilder out = new StringBuilder();
            for (String modeId : row) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(modeId);
            }
            return out.toString();
        }
    }
}
