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
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceStyle;
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
 * 图标与行高由生效字号派生；pill 换行按<b>外部约束宽</b>（section 实测分配宽，非 pill 容器自测）
 * + 文本实测宽贪心分包，宽度未知时单列安全退化；分页页大小由运行时逻辑盒高派生。</p>
 *
 * <p><b>pill 可辨识度</b>：模式 pill 走 {@link SceneThemes#selectableSurface} 的
 * {@link SceneTheme.Role#INDICATOR} 角色配方（与 UILib 既有 chip 控件同源同角色）——未选中态即带该角色的
 * 1px 描边与胶囊圆角（描边色取自角色配方的 {@code StateStyle.edge}，本类不拼 RGB）；选中态由该入口把
 * tint 换成主题 accent。横向内边距与行内间距按生效字号派生（{@link #pillPadH}/{@link #pillGap}），
 * 避免小字号下 pill 贴边成串。</p>
 */
public final class ObjectGroupDetailPane {

    /** 区块间距（逻辑 px，非颜色令牌）。 */
    private static final int SECTION_GAP = SceneChromeTokens.GAP_MD;
    /** 区块内部间距（逻辑 px）。 */
    private static final int INNER_GAP = SceneChromeTokens.GAP_SM;
    /** pill 横向内边距下界（逻辑 px）；实际值按生效字号派生（见 {@link #pillPadH}）。 */
    private static final int PILL_PAD_H_MIN = SceneChromeTokens.PAD_MD;
    /** pill 行内间距下界（逻辑 px）；实际值按生效字号派生（见 {@link #pillGap}）。 */
    private static final int PILL_GAP_MIN = SceneChromeTokens.GAP_MD;
    /** pill 纵向内边距下界（逻辑 px）；实际值按生效字号派生（见 {@link #pillPadV}）。 */
    private static final int PILL_PAD_V_MIN = 2;
    /**
     * pill 横向内边距 / 行内间距的字号派生比例：字号的 0.5 倍（16px 字号 ⇒ 8px，32px ⇒ 16px）。
     * 只表达「随生效字号等比」，不假设分辨率、GUI Scale、主题或语言。
     */
    private static final float PILL_METRIC_RATIO = 0.5F;
    /** pill 纵向内边距的字号派生比例：字号的 0.25 倍（16px 字号 ⇒ 4px，32px ⇒ 8px）。 */
    private static final float PILL_PAD_V_RATIO = 0.25F;
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

        // 换行容器：按外部约束宽 + 文本实测宽贪心分包（随宽换行，不写死每行数量）。
        final SceneNode wrapHost = SceneNode.column();
        wrapHost.setGap(INNER_GAP);
        // 换行输入 = 外部约束宽：本 section 的实测分配宽（COLUMN 的 cross 宽由父内宽下传，
        // 与 pill 行内容无关）—— 绝不读 wrapHost / pill 行自身的测量宽，杜绝自引用。
        // 前提：本 section 必须保持 COLUMN 的 fill 语义（容器宽 = 外部约束宽）；若改为
        // WidthSizing.SHRINK，宽度会重新受内容影响 ⇒ 那时必须先改为读详情内容视口的分配宽。
        // 布局完成前保持「宽度未知」（0），wrapModes 按单列安全退化（不并排即不会被裁剪）。
        final Signal<Integer> available = Signal.create(Integer.valueOf(0));
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            final int allocated = allocatedWidthOf(column);
            if (allocated > 0) {
                available.set(Integer.valueOf(allocated));
            }
        }));
        final ReadableSignal<List<List<String>>> rows = Computed.create(() -> {
            // 失效源：外部约束宽 + 布局纪元 + 字号纪元（文本实测宽随字号变化）。
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
        line.setCrossAxisAlign(CrossAxisAlign.CENTER);
        // 行内间距按生效字号派生（与 wrapModes 分包共用 pillGap）：构建期先落一次，布局完成后重派生。
        final Runnable applyGap = () -> line.setGap(pillGap(line.effectiveFontSize()));
        applyGap.run();
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(applyGap));
        for (final String modeId : row) {
            final ReadableSignal<Boolean> selected = Computed.create(() -> Boolean.valueOf(
                    known.get().contains(modeId)));
            line.appendChild(pill(rt, ClientI18n.tr(modeKey(modeId)), selected,
                    () -> toggleMode(state, key, modeId, notice)));
        }
        return line;
    }

    /**
     * 模式 pill：多选 chip（外观走 {@link SceneTheme.Role#INDICATOR} 主题可选配方，命中/键盘可聚焦）。
     *
     * <p>用 INDICATOR 而不是 INPUT：UILib 既有 chip / 多选控件（{@code SceneCheckbox}、
     * {@code SceneNavList}、{@code SceneRadioGroup}、{@code SceneSegmented}、{@code SceneTab}、
     * {@code CategoryNavPane} 的 pill 行）全部消费该角色，其未选中配方自带可辨识 1px {@code edge}
     * 描边 + 胶囊圆角（dark {@code 0x40FFFFFF} / light {@code 0x99FFFFFF}，见 {@code SceneTheme}
     * 角色表）；INPUT 的 idle 描边（dark {@code 0x24FFFFFF}）在深色面板上近乎不可见，正是「一排文字
     * 看不出可多选」的来源。选中态仍由 {@code selectableSurface} 把 tint 换成主题 accent。</p>
     */
    private static SceneNode pill(SceneRuntime rt, String label, ReadableSignal<Boolean> selected,
                                  final Runnable onClick) {
        final SceneNode node = SceneNode.row();
        node.setGap(2);
        node.setCrossAxisAlign(CrossAxisAlign.CENTER);
        bindChipSurface(rt, node, selected, ObjectGroupMemberPane.alwaysEnabled());
        node.appendChild(ObjectGroupMemberPane.textNode(rt, label, SceneThemes.foreground(rt)));
        bindChipMetrics(rt, node, label);
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
        final int chipFontSize = chip.effectiveFontSize();
        chip.setPadding(pillPadV(chipFontSize), pillPadH(chipFontSize),
                pillPadV(chipFontSize), pillPadH(chipFontSize));
        chip.setCrossAxisAlign(CrossAxisAlign.CENTER);
        bindChipSurface(rt, chip, ObjectGroupMemberPane.neverEnabled(), ObjectGroupMemberPane.alwaysEnabled());
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
     * pill 换行分包：按「外部约束宽 + pill 先验外宽」贪心装行。
     *
     * <p>宽度口径与 {@link #pillWidthPx} 同源（pill 外宽 = 文本实测宽 + 左右内边距），行内分隔按
     * {@link #pillGap}（生效字号派生）计入 ⇒ 「分包认为放得下」等价于「行内 pill 总占位 ≤ 约束宽」，
     * 不依赖任何节点自身的测量结果。</p>
     *
     * <p>宽度未知（首帧 / 尚无实测分配宽）时按<b>单列</b>安全退化：不并排就不会横向溢出裁剪；
     * 布局完成后由实测约束宽自动恢复多列，随宽换行能力不受影响。</p>
     *
     * @param rt        场景运行时（提供文本度量）
     * @param probe     生效字号探针（须已进树）
     * @param modeIds   模式 id（保序）
     * @param available 外部约束宽（&lt;= 0 = 未知）
     * @return 每行的模式 id 列表
     */
    static List<List<String>> wrapModes(SceneRuntime rt, SceneNode probe, String[] modeIds, int available) {
        final int fontSize = probe.effectiveFontSize();
        final int gap = pillGap(fontSize);
        final List<List<String>> rows = new ArrayList<List<String>>();
        List<String> current = new ArrayList<String>();
        int used = 0;
        for (String modeId : modeIds) {
            final int width = pillWidthPx(rt, ClientI18n.tr(modeKey(modeId)), fontSize);
            // 放不下才换行（本行首个 pill 永不因宽度被挤出）；宽度未知 ⇒ 每个 pill 独占一行。
            if (!current.isEmpty() && (available <= 0 || used + gap + width > available)) {
                rows.add(Collections.unmodifiableList(current));
                current = new ArrayList<String>();
                used = 0;
            }
            if (!current.isEmpty()) {
                used += gap;
            }
            current.add(modeId);
            used += width;
        }
        if (!current.isEmpty()) {
            rows.add(Collections.unmodifiableList(current));
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * pill 先验外宽：文本实测宽 + 左右内边距（{@link #pillPadH}）。
     *
     * <p>单点口径：{@link #pill} 的 {@code preferredWidth} 与 {@link #wrapModes} 的分包共用本方法，
     * 避免「分包口径」与「实际占位」两处漂移（文本宽与内边距随字号的失效链只走字号纪元）。</p>
     *
     * @param rt       场景运行时
     * @param label    已本地化文案（null 视作空串）
     * @param fontSize 生效字号
     * @return pill 外宽（逻辑 px）
     */
    static int pillWidthPx(SceneRuntime rt, String label, int fontSize) {
        return rt.measureTextWidth(label == null ? "" : label, fontSize) + pillPadH(fontSize) * 2;
    }

    /**
     * pill 横向内边距：按生效字号派生（{@link #PILL_METRIC_RATIO}），下界 = {@link SceneChromeTokens#PAD_MD}。
     *
     * <p>16px 字号 ⇒ 8px/侧（旧值 4px 会让相邻 pill 的文字视觉连成一片）；字号放大时等比放大，
     * 不写死像素档。</p>
     */
    static int pillPadH(int fontSize) {
        return Math.max(PILL_PAD_H_MIN, Math.round(fontSize * PILL_METRIC_RATIO));
    }

    /** pill 行内间距：按生效字号派生（同 {@link #PILL_METRIC_RATIO}），下界 = {@link SceneChromeTokens#GAP_MD}。 */
    static int pillGap(int fontSize) {
        return Math.max(PILL_GAP_MIN, Math.round(fontSize * PILL_METRIC_RATIO));
    }

    /**
     * pill 纵向内边距：按生效字号派生（{@link #PILL_PAD_V_RATIO}），下界 = {@link #PILL_PAD_V_MIN}。
     *
     * <p>16px 字号 ⇒ 上下各 4px（pill 高 = 行高 16 + 8 = 24），与既有紧凑行高一致。</p>
     */
    static int pillPadV(int fontSize) {
        return Math.max(PILL_PAD_V_MIN, Math.round(fontSize * PILL_PAD_V_RATIO));
    }

    /**
     * chip 表面绑定：{@code selectableSurface(INDICATOR, selected)} 提供「主题基线 + 选中语义」，
     * 再经 {@link #chipSurface} 做字段级覆盖（去滤镜、关浮雕），最后交 {@link SceneSurfaceBinder}
     * 独占写入 —— 与 UILib 既有「列表 pill 行」同一条通道（{@code CategoryNavPane.bindRowSurface}
     * 的「selectableSurface + 局部纯函数覆盖」组合）。
     *
     * <p>为什么不用 {@code SceneThemes.derivedSurface}：它从 {@code surface(role)} 起算，拿不到
     * {@code selectableSurface} 的「选中 = 主题 accent」语义（{@code SELECTED_TINT_ALPHA} 是主题私有真值），
     * 自带一份等于复制主题。</p>
     */
    private static void bindChipSurface(SceneRuntime rt, SceneNode node,
                                        ReadableSignal<Boolean> selected, ReadableSignal<Boolean> enabled) {
        final ReadableSignal<SceneSurfaceStyle> selectable =
                SceneThemes.selectableSurface(rt, SceneTheme.Role.INDICATOR, selected);
        final ReadableSignal<SceneSurfaceStyle> recipe = Computed.create(
                chipSurface(selectable.get()), () -> chipSurface(selectable.get()));
        SceneSurfaceBinder.bind(rt, node, recipe, enabled, ObjectGroupMemberPane.interactionOf(rt, node));
    }

    /**
     * chip 配方 = 主题可选配方 + 本地字段级覆盖（纯函数）：
     * {@code backdrop(null)} 不装滤镜（零新增 BACKDROP 采样）、{@code reliefDisabled(true)} 走普通绘制路径。
     *
     * <p>复用 UILib 既有裁决：列表 pill 行应「零滤镜 + 普通绘制」（{@code CategoryNavPane.rowSurface}、
     * {@code VariantChooser} 的「复用行零滤镜」档）。普通路径下 background/border 成对进入绘制出口，
     * 未选中 pill 的 1px 描边才是可统计、可目视的轮廓；浮雕通道下倒角由
     * {@code SceneSurfaceReliefPainter} 输出 ROUNDED_BAND，轮廓既不可枚举也难以判读。</p>
     */
    private static SceneSurfaceStyle chipSurface(SceneSurfaceStyle base) {
        return base.toBuilder().backdrop(null).reliefDisabled(true).build();
    }

    /**
     * pill 尺寸派生（构建期一次 + 每次布局完成重派生）：
     * ① 横向内边距按生效字号；② 先验外宽 = {@link #pillWidthPx}。
     *
     * <p><b>先验宽是 ROW 纪律</b>：pill 是容器节点，无 {@code preferredWidth} 时 ROW 主轴会把「整行内宽」
     * 下传给每个无 grow 子 ⇒ 每枚 pill 被拉成整行宽、第二枚起被裁剪（F1 根因）。显式钉死后
     * 「分包口径 = 实际占位」。</p>
     *
     * <p>{@code setPadding}/{@code setPreferredWidth} 同值短路（不标脏），逐帧重派生无额外布局开销。</p>
     */
    private static void bindChipMetrics(SceneRuntime rt, final SceneNode node, final String label) {
        final Runnable apply = () -> {
            final int fontSize = node.effectiveFontSize();
            // setPadding 的参数顺序是 (top, right, bottom, left)：横向取 pillPadH、纵向取 pillPadV，
            // 否则会出现「左 2 / 右 8」的不对称文字偏移（旧调用即错序，只是 PAD_SM=4 时不易察觉）。
            node.setPadding(pillPadV(fontSize), pillPadH(fontSize),
                    pillPadV(fontSize), pillPadH(fontSize));
            node.setPreferredWidth(pillWidthPx(rt, label, fontSize));
        };
        apply.run();
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
    }

    /** 节点实测分配宽（外宽 - 左右内边距）；无布局盒或宽非正时返回 0（= 未知）。 */
    private static int allocatedWidthOf(SceneNode node) {
        final Object cached = node.getCachedLayout();
        if (cached instanceof LayoutBox) {
            final int width = ((LayoutBox) cached).getWidth()
                    - node.getPaddingLeft() - node.getPaddingRight();
            return width > 0 ? width : 0;
        }
        return 0;
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
