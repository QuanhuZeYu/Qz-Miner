package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.util.List;

import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.uilib.ui.reactive.Computed;
import club.heiqi.uilib.ui.reactive.Effect;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneButtonVariant;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.input.SceneInteractionState;
import club.heiqi.uilib.ui.scene.input.SceneKey;
import club.heiqi.uilib.ui.scene.input.SceneKeyAction;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.LogicalBox;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.runtime.SceneScrolls;
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceBinder;
import club.heiqi.uilib.ui.scene.theme.SceneTheme;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * 对象组编辑视图（M2）：顶部条 + 主从布局 + 挡位派生 + 键盘与焦点 + 关闭。
 *
 * <p><b>装配关系</b>：本视图是编辑浮层的内容根，由 {@link ObjectGroupEditorFieldRenderer} 经
 * {@code rt.portal} 在 {@code visible} 首次为真时构建，并对根节点显式打开两轴 {@code fillParent}
 * （非锚定 overlay 不自动撑满）。视图自身持有 {@link ObjectGroupListPane#build} 与
 * {@link ObjectGroupDetailPane#build}（后者由 M4 实现，本类只调用），不持有任何配置真值。</p>
 *
 * <p><b>挡位（C3 §6.1）</b>：唯一权威输入是视图根的实际可用宽 {@code W}
 * （{@code rt.layoutDoneSignal()} + {@code getCachedLayout()} 读宽）；{@code W ≥ 620} 为宽挡
 * （列表固定列宽 + 详情并排），{@code W < 620} 为窄挡（列表 ↔ 详情单栏下钻）。阈值只在
 * {@link #WIDE_MIN_WIDTH_PX} 一处表达。首帧宽先用宿主逻辑盒播种（{@code rt.logicalBox()}），
 * 布局完成后由根宽精修，避免首帧误判挡位。选中项存在 {@code state.selectedKey()}，切挡不丢。</p>
 *
 * <p><b>滚动（C3 §6.1，按 A13 修订的实测口径）</b>：视图内有列表与详情两个同级滚动面；
 * 同一时刻只有一个在滚——详情占满剩余高并独立滚动，列表仅在内容超长时自滚。页面滚动不参与，
 * 视图内没有第三层滚动。</p>
 *
 * <p><b>先验尺寸（布局闸门纪律）</b>：{@code ConstraintResolver} 的 grow 分配要求容器主轴先验已知
 * ——ROW 里存在 {@code flexGrow} 子时，所有固定兄弟必须 {@code priorKnownChildWidth} 可算；
 * COLUMN 同理要求固定兄弟高度可算。因此本类对顶部条/窄挡下钻头部显式设 {@code preferredHeight}、
 * 对全部动作按钮显式测量设 {@code preferredWidth}，并对隐藏宿主用 {@code setCollapsed(true)}
 * 声明「退出布局域」（{@code SceneButton} 根节点自身无文本、无 preferredWidth，不显式声明就会
 * 撞掉整条 grow 分配并 WARN）。</p>
 *
 * <p><b>ROW 内禁用 {@code rt.show}（维护纪律）</b>：{@code SceneConditionalRenderer} 的零尺寸 anchor
 * 是「无文本叶」，而 {@code SizingCalculator.computeWidth} 对无文本叶返回可用宽 ⇒ anchor 会在 ROW
 * 主轴按可用宽铺满，吃掉整行并撞掉同排 grow 分配（实测顶部条两个 show 各吃 684px，grow 占位恒 0、
 * 收尾按钮被推出视口）。ROW 内的条件内容请改为「常挂 + 可归零的内容」——文本用显式空串（空文本叶
 * 宽=0，见顶部条脏状态）；需要整块隐藏时把该块挂进 <b>COLUMN 主轴</b>并 {@code setCollapsed(true)}
 * （折叠声明在 COLUMN 主轴高=0；ROW 主轴不适用，无文本叶仍会铺满，见撤销条）。</p>
 *
 * <p><b>键盘（C3 §5.7）</b>：ESC 走浮层单一路径——M1 的 portal 用
 * {@code OverlayDismissPolicy(true,false,false)} 消费 ESC 后回调 {@code dismissRequest}，
 * 视图在 {@link ObjectGroupEditorContext#setDismissHandler} 注册处理器：窄挡下钻中 → 返回列表
 * 并保持打开，否则 → 关闭视图（此路径不依赖焦点，是窄挡下钻的兜底）。ENTER 挂列表 pane 根
 * （窄挡下钻 / 宽挡把焦点移入详情）；窄挡详情挂载即接管焦点；删除、↑/↓、Menu 由列表 pane 自持。
 * 关闭语义只写可见性 signal，不直接挂卸浮层。</p>
 */
public final class ObjectGroupEditorView {

    /**
     * 宽挡最小可用宽（逻辑 px）——C3 §6.1 挡位阈值在本工程的<b>唯一</b>落点。
     *
     * <p>设计的起点值 620，最终值按真机两档校准后只改这一处。</p>
     */
    private static final int WIDE_MIN_WIDTH_PX = 620;
    /** 宽挡列表列宽占可用宽比例（%）：{@code 0.28×W}（C3 §6.1）。 */
    private static final int LIST_WIDTH_PERCENT = 28;
    /** 宽挡列表列宽下限（逻辑 px）。 */
    private static final int LIST_MIN_WIDTH_PX = 220;
    /** 宽挡列表列宽上限（逻辑 px）。 */
    private static final int LIST_MAX_WIDTH_PX = 300;
    /** 视图根内边距（逻辑 px）。 */
    private static final int ROOT_PADDING_PX = 8;
    /** 视图根纵向区间间距（逻辑 px）。 */
    private static final int ROOT_GAP_PX = 8;
    /** 顶部条元素间距（逻辑 px）。 */
    private static final int TOP_BAR_GAP_PX = 8;
    /** 顶部条标题宽上限（占顶部条宽百分比）：固定兄弟不得超过，避免挤掉 grow spacer。 */
    private static final int TOP_TITLE_WIDTH_PERCENT = 20;
    /** 顶部条计数摘要宽上限（占顶部条宽百分比）。 */
    private static final int TOP_SUMMARY_WIDTH_PERCENT = 30;
    /** 顶部条脏状态宽上限（占顶部条宽百分比）。 */
    private static final int TOP_DIRTY_WIDTH_PERCENT = 20;
    /** 顶部条动作按钮宽上限（占顶部条宽百分比）：长语言文案不得把按钮推出视口。 */
    private static final int TOP_ACTION_WIDTH_PERCENT = 25;
    /** 撤销条按钮宽上限（占撤销条宽百分比）。 */
    private static final int UNDO_ACTION_WIDTH_PERCENT = 40;
    /** 窄挡下钻返回按钮宽上限（占头部宽百分比）。 */
    private static final int NARROW_BACK_WIDTH_PERCENT = 50;
    /** 窄挡下钻头部组 id 宽上限（占头部宽百分比）：不得把「← 返回」挤出命中区。 */
    private static final int NARROW_TITLE_WIDTH_PERCENT = 60;
    /** 详情区纵向间距（逻辑 px）。 */
    private static final int DETAIL_GAP_PX = 6;

    private ObjectGroupEditorView() {
    }

    /**
     * 构建编辑视图根（M2 冻结签名）。
     *
     * @param ctx 编辑视图宿主上下文
     * @return 可直接 appendChild 的视图根节点
     */
    public static SceneNode build(ObjectGroupEditorContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        final SceneRuntime rt = ctx.rt();
        final ObjectGroupEditorState state = ctx.state();

        final SceneNode root = SceneNode.column();
        root.setGap(ROOT_GAP_PX);
        root.setPadding(ROOT_PADDING_PX);
        bindPanelSurface(rt, root);

        // 挡位派生：唯一权威输入是视图根实际可用宽（不读分辨率 / GUI Scale / 字号）；
        // 首帧用宿主逻辑盒播种，避免首帧先按窄挡渲染再跳挡。
        final Signal<Integer> availableWidth = Signal.create(Integer.valueOf(seedWidth(rt)));
        final ReadableSignal<Boolean> wide = Computed.create(Boolean.FALSE,
                () -> Boolean.valueOf(availableWidth.get().intValue() >= WIDE_MIN_WIDTH_PX));
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            Object cached = root.getCachedLayout();
            if (cached instanceof LayoutBox) {
                int width = ((LayoutBox) cached).getWidth();
                if (width > 0) {
                    availableWidth.set(Integer.valueOf(width));
                }
            }
        }));

        // 窄挡下钻态：视图内瞬态（唯一共享真值仍是 state.selectedKey()）；回到宽挡自动复位。
        final Signal<Boolean> drilled = Signal.create(Boolean.FALSE);
        rt.bind(wide, isWide -> {
            if (Boolean.TRUE.equals(isWide)) {
                drilled.set(Boolean.FALSE);
            }
        });

        // 列表 pane 根引用：返回列表时把焦点交回列表（列表随后由 rt.show 重挂，M3 自持初始焦点）。
        final SceneNode[] listRoot = new SceneNode[1];

        root.appendChild(buildTopBar(ctx, state));
        root.appendChild(buildUndoBar(ctx, state));
        root.appendChild(buildBody(ctx, state, wide, availableWidth, drilled, listRoot));

        // 单一 ESC 通路：浮层策略（M1）消费 ESC 后回调到此；返回 true = 视图自行处理（保持打开）。
        ctx.setDismissHandler(() -> {
            if (!Boolean.TRUE.equals(wide.get()) && Boolean.TRUE.equals(drilled.get())) {
                backToList(rt, drilled, listRoot);
                return true;
            }
            return false;
        });
        return root;
    }

    // ------------------------------------------------------------------ 顶部条

    /** 顶部条：标题 + 计数摘要 + 脏状态 + （grow 占位）+「完成」；撤销入口在独立的撤销条（见 buildUndoBar）。 */
    private static SceneNode buildTopBar(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state) {
        final SceneRuntime rt = ctx.rt();
        ReadableSignal<Integer> foreground = SceneThemes.foreground(rt);
        ReadableSignal<Integer> muted = SceneThemes.mutedForeground(rt);
        ReadableSignal<Integer> warning = SceneThemes.warningText(rt);

        final SceneNode bar = SceneNode.row();
        bar.setGap(TOP_BAR_GAP_PX);
        bar.setCrossAxisAlign(CrossAxisAlign.CENTER);

        SceneNode title = label(ClientI18n.tr("config.qz_miner.object_group.editor.title"));
        rt.bind(foreground, title::setTextColor);
        bar.appendChild(title);

        SceneNode summary = label("");
        // 计数摘要单一真值：直接复用摘要卡的同一 helper（Lead 裁决 ⑤）。
        rt.bindComputed(() -> ObjectGroupEditorFieldRenderer.summaryTextOf(state), summary::setText);
        rt.bind(muted, summary::setTextColor);
        bar.appendChild(summary);

        // 文本固定兄弟的宽上限：长文案（含未本地化时的长键名）不得挤掉 grow spacer，
        // 否则收尾的「完成/撤销删除」会被推出视口（ROW 闸门正常也会溢出）。
        bindTextCap(rt, bar, title, TOP_TITLE_WIDTH_PERCENT);
        bindTextCap(rt, bar, summary, TOP_SUMMARY_WIDTH_PERCENT);

        SceneNode spacer = new SceneNode();
        spacer.setHitTestable(false);
        spacer.setFlexGrow(1);
        bar.appendChild(spacer);

        // 脏状态：文本在干净态为空串（显式空文本叶宽=0），不做 rt.show（ROW 内禁用 rt.show，见类注释纪律）。
        ReadableSignal<String> dirtyText = Computed.create("",
                () -> Boolean.TRUE.equals(ctx.adapter().dirtySignal(state.path()).get())
                        ? ClientI18n.tr("config.qz_miner.object_group.editor.dirty") : "");
        SceneNode dirty = label("");
        rt.bind(dirtyText, dirty::setText);
        rt.bind(warning, dirty::setTextColor);
        bar.appendChild(dirty);
        bindTextCap(rt, bar, dirty, TOP_DIRTY_WIDTH_PERCENT);

        bar.appendChild(actionButton(rt, bar, TOP_ACTION_WIDTH_PERCENT,
                Signal.create(ClientI18n.tr("config.qz_miner.object_group.editor.done")),
                SceneButtonVariant.PRIMARY, ctx::requestClose));
        // 顶部条是视图根 COLUMN 的固定兄弟：高度必须先验可算（子节点就位后测量），
        // 否则整条主轴 grow 分配被放弃 ⇒ 主体收不到确定高（详情视口残值）。
        bindPriorHeight(rt, bar);
        return bar;
    }

    /**
     * 撤销删除条（W 密度 Q4「删除 + 撤销条」）：有可撤销删除时整条展开，否则整条 {@code setCollapsed(true)}
     * 退出布局域（高 0、不进焦点环）。
     *
     * <p>不使用 {@code rt.show}（ROW 内禁用 rt.show，见类注释纪律）；折叠声明是 UILib 为「内容退出布局域」
     * 提供的正门，在 COLUMN 主轴表现为高 0（宽铺满无害），故本条挂在根 COLUMN 上。</p>
     */
    private static SceneNode buildUndoBar(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state) {
        final SceneRuntime rt = ctx.rt();
        final SceneNode bar = SceneNode.row();
        bar.setGap(TOP_BAR_GAP_PX);
        bar.setCrossAxisAlign(CrossAxisAlign.CENTER);
        bar.appendChild(actionButton(rt, bar, UNDO_ACTION_WIDTH_PERCENT,
                Signal.create(ClientI18n.tr("config.qz_miner.object_group.editor.undo_remove")),
                SceneButtonVariant.STANDARD, () -> state.undoRemove()));

        Runnable apply = () -> {
            boolean visible = Boolean.TRUE.equals(state.canUndoRemove().get());
            bar.setCollapsed(!visible);
            // 折叠时先验高必须归零，否则 COLUMN 主轴仍按旧先验高留位。
            bar.setPreferredHeight(visible ? priorHeight(rt, bar) : 0);
        };
        apply.run();
        rt.bind(state.canUndoRemove(), canUndo -> apply.run());
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
        return bar;
    }

    // ------------------------------------------------------------------ 主从主体

    /** 主从主体：宽挡列表固定列宽 + 详情并排；窄挡单栏下钻（列表 ↔ 详情互斥）。 */
    private static SceneNode buildBody(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state,
                                       final ReadableSignal<Boolean> wide,
                                       final ReadableSignal<Integer> availableWidth,
                                       final Signal<Boolean> drilled, final SceneNode[] listRoot) {
        final SceneRuntime rt = ctx.rt();
        // 详情根引用：宽挡「Enter 焦点进详情」需要它（详情内容与列表分属两个 show 作用域）。
        final SceneNode[] detailRoot = new SceneNode[1];

        final SceneNode body = SceneNode.row();
        body.setGap(ROOT_GAP_PX);
        body.setFillParentHeight(true);
        body.setFillParentWidth(true);

        final ReadableSignal<Boolean> listVisible = Computed.create(Boolean.TRUE,
                () -> Boolean.valueOf(Boolean.TRUE.equals(wide.get()) || !Boolean.TRUE.equals(drilled.get())));
        final ReadableSignal<Boolean> detailVisible = Computed.create(Boolean.FALSE,
                () -> Boolean.valueOf(Boolean.TRUE.equals(wide.get()) || Boolean.TRUE.equals(drilled.get())));
        // 宽挡：固定列宽 clamp(220, 0.28×W, 300)；窄挡非下钻：列表独占整宽（grow=1）；
        // 窄挡下钻：列表让位（grow=0 + collapsed，宽可先验）。
        final ReadableSignal<Integer> listPreferredWidth = Computed.create(Integer.valueOf(0),
                () -> Integer.valueOf(Boolean.TRUE.equals(wide.get())
                        ? listWidth(availableWidth.get().intValue()) : 0));
        final ReadableSignal<Integer> listGrow = Computed.create(Integer.valueOf(1),
                () -> Integer.valueOf(!Boolean.TRUE.equals(wide.get())
                        && !Boolean.TRUE.equals(drilled.get()) ? 1 : 0));
        final ReadableSignal<Integer> detailGrow = Computed.create(Integer.valueOf(0),
                () -> Integer.valueOf(Boolean.TRUE.equals(detailVisible.get()) ? 1 : 0));

        final SceneNode listHost = SceneNode.column();
        listHost.setFillParentHeight(true);
        body.appendChild(listHost);

        final SceneNode detailHost = SceneNode.column();
        detailHost.setFillParentHeight(true);
        body.appendChild(detailHost);

        // 构建期先把策略同步落一次：首帧布局就满足先验条件（否则首帧闸门放弃 -> 首帧残值几何）。
        listHost.setPreferredWidth(listPreferredWidth.get().intValue());
        listHost.setFlexGrow(listGrow.get().intValue());
        listHost.setCollapsed(!Boolean.TRUE.equals(listVisible.get()));
        detailHost.setFlexGrow(detailGrow.get().intValue());
        detailHost.setCollapsed(!Boolean.TRUE.equals(detailVisible.get()));
        rt.bind(listPreferredWidth, width -> listHost.setPreferredWidth(width.intValue()));
        rt.bind(listGrow, grow -> listHost.setFlexGrow(grow.intValue()));
        rt.bind(listVisible, visible -> listHost.setCollapsed(!Boolean.TRUE.equals(visible)));
        rt.bind(detailGrow, grow -> detailHost.setFlexGrow(grow.intValue()));
        rt.bind(detailVisible, visible -> detailHost.setCollapsed(!Boolean.TRUE.equals(visible)));

        // 列表常驻（宽挡并排；窄挡未下钻），详情单实例（宽挡常驻；窄挡下钻时挂载）。
        rt.show(listHost, listVisible, () -> {
            SceneNode pane = ObjectGroupListPane.build(ctx);
            listRoot[0] = pane;
            // 列表 Enter：窄挡下钻到详情；宽挡把焦点移入详情（列表自身不消费 Enter，见 M3）。
            rt.on(pane, SceneEventType.KEY_DOWN, (ev, ectx) -> {
                if (ev.getKeyAction() != SceneKeyAction.PRESSED || ev.getKey() != SceneKey.ENTER) {
                    return;
                }
                ectx.stopPropagation();
                if (state.selection() == null) {
                    return;
                }
                if (Boolean.TRUE.equals(wide.get())) {
                    if (detailRoot[0] != null) {
                        rt.requestFocus(detailRoot[0]);
                    }
                } else {
                    drilled.set(Boolean.TRUE);
                }
            });
            return pane;
        });

        rt.show(detailHost, detailVisible,
                () -> buildDetailHost(ctx, state, wide, drilled, detailRoot, listRoot));
        return body;
    }

    /** 详情宿主：窄挡下钻头部（视图自建）+ 视图内唯一滚动视口 + M4 详情 pane。 */
    private static SceneNode buildDetailHost(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state,
                                             final ReadableSignal<Boolean> wide, final Signal<Boolean> drilled,
                                             SceneNode[] detailRoot, final SceneNode[] listRoot) {
        final SceneRuntime rt = ctx.rt();
        final SceneNode host = SceneNode.column();
        host.setGap(DETAIL_GAP_PX);
        host.setFillParentHeight(true);
        host.setFillParentWidth(true);

        // 窄挡下钻头部：[← 返回] + 当前组 id（返回语义与 ESC 一致，M4 的 pane 保持不变）。
        rt.show(host, Computed.create(Boolean.FALSE, () -> Boolean.valueOf(
                !Boolean.TRUE.equals(wide.get()) && Boolean.TRUE.equals(drilled.get()))),
                () -> buildNarrowHeader(ctx, state, drilled, listRoot));

        // 视图内唯一详情滚动层：占满剩余高、独立滚动（§6.1）；M4 内部不再建第二层滚动。
        final SceneNode viewport = SceneNode.column();
        viewport.setScrollable(true);
        viewport.setClipChildren(true);
        viewport.setFillParentHeight(true);
        viewport.setFillParentWidth(true);
        SceneScrolls.attach(rt, viewport);

        SceneNode pane = ObjectGroupDetailPane.build(ctx);
        detailRoot[0] = pane;
        // 详情根是「宽挡 Enter 焦点进详情」的入口，登记进 Tab 环后 Tab 继续深入 M4 内部控件。
        rt.focusable(pane);
        viewport.appendChild(pane);
        host.appendChild(viewport);
        // 窄挡下钻：详情挂载即接管焦点（列表已卸载，否则焦点为空 ⇒ 键盘事件被 Router 丢弃）。
        if (!Boolean.TRUE.equals(wide.get()) && Boolean.TRUE.equals(drilled.get())) {
            rt.requestFocus(pane);
        }
        return host;
    }

    private static SceneNode buildNarrowHeader(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state,
                                               final Signal<Boolean> drilled, final SceneNode[] listRoot) {
        final SceneRuntime rt = ctx.rt();
        final SceneNode header = SceneNode.row();
        header.setGap(DETAIL_GAP_PX);
        header.setCrossAxisAlign(CrossAxisAlign.CENTER);

        header.appendChild(actionButton(rt, header, NARROW_BACK_WIDTH_PERCENT,
                Signal.create(ClientI18n.tr("config.qz_miner.object_group.detail.narrow_back")),
                SceneButtonVariant.STANDARD, () -> backToList(rt, drilled, listRoot)));

        SceneNode title = label("");
        rt.bindComputed(() -> {
            ObjectGroupEditorState.RowView view = state.selection();
            return view == null ? "" : view.id();
        }, title::setText);
        rt.bind(SceneThemes.foreground(rt), title::setTextColor);
        header.appendChild(title);
        bindTextCap(rt, header, title, NARROW_TITLE_WIDTH_PERCENT);
        // 下钻头部是详情宿主 COLUMN 的固定兄弟：高度必须先验可算（子节点就位后测量），
        // 否则详情视口收不到剩余高（实测残值级）。
        bindPriorHeight(rt, header);
        return header;
    }

    // ------------------------------------------------------------------ 尺寸先验（布局闸门纪律）

    /**
     * 动作按钮：显式先验宽（标签文本 + 控件内边距，可加父宽百分比上限）+ 先验高，
     * 供父 ROW / COLUMN 的 grow 分配扣除。
     *
     * @param capParent   上限基准父节点（null = 不设上限）
     * @param capPercent  宽上限占父宽百分比（{@code capParent} 为 null 时忽略）
     */
    private static SceneNode actionButton(SceneRuntime rt, SceneNode capParent, int capPercent,
                                          final ReadableSignal<String> label,
                                          SceneButtonVariant variant, Runnable onClick) {
        SceneNode button = SceneButton.create(rt, SceneButton.Props
                .builder(label)
                .variant(variant)
                .onClick(onClick)
                .build()).get();
        bindActionWidth(rt, button, label, capParent, capPercent);
        return button;
    }

    /**
     * 按钮先验宽：{@code measureTextWidth(标签) + 左右内边距}。
     *
     * <p>{@code SceneButton} 根节点自身无文本（文本在子节点）⇒ 作为 ROW 固定兄弟
     * {@code priorKnownChildWidth} 恒为 UNCONSTRAINED，会撞掉整条 ROW 的 grow 分配
     * （{@code ConstraintResolver.computeRowGrowWidths} 早退 + WARN）。此处照 UILib 既有范式
     * （{@code StructuredListFieldRenderer.actionButton}「供父 ROW 先验扣除」）显式设宽；
     * 文本源用标签信号而非节点文本，避免首帧 {@code bindText} 落值前测成 0。</p>
     */
    private static void bindActionWidth(SceneRuntime rt, final SceneNode button,
                                        final ReadableSignal<String> label,
                                        final SceneNode capParent, final int capPercent) {
        Runnable apply = () -> {
            String text = label.get();
            int natural = rt.measureTextWidth(text == null ? "" : text, button.effectiveFontSize())
                    + button.getPaddingLeft() + button.getPaddingRight();
            int cap = capWidth(capParent, capPercent);
            button.setPreferredWidth(cap > 0 ? Math.min(natural, cap) : natural);
            // 先验高同样显式声明：按钮内部标签文本经 bindText 落值，构建期按子节点测不到文本，
            // 固定兄弟高会退化成「仅 padding」而撞掉父 COLUMN 的主轴 grow 分配。
            button.setPreferredHeight(rt.lineHeight(button.effectiveFontSize())
                    + button.getPaddingTop() + button.getPaddingBottom());
        };
        apply.run();
        rt.bind(label, next -> apply.run());
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
    }

    /**
     * 文本固定兄弟的宽上限（父宽百分比）+ 单行省略。
     *
     * <p>ROW 的 grow 分配按「固定兄弟先验宽之和」扣减；文本叶的 {@code priorKnownChildWidth}
     * 取测量宽，因此超长文案会把 freeW 吃成 0，grow 兄弟（spacer/标题槽）拿不到宽。
     * 给固定文本加宽上限后，先验宽有界、grow 兄弟与收尾按钮都在视口内。</p>
     */
    private static void bindTextCap(SceneRuntime rt, final SceneNode parent, final SceneNode text,
                                    final int percent) {
        text.setMaxLines(1);
        text.setEllipsis(true);
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            Object cached = parent.getCachedLayout();
            if (cached instanceof LayoutBox) {
                int width = ((LayoutBox) cached).getWidth();
                if (width > 0) {
                    text.setMaxWidth(Math.max(1, Math.round(width * percent / 100.0F)));
                }
            }
        }));
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

    /** 显式先验高（构建期一次 + 每次布局后重派生）：COLUMN 固定兄弟高度必须先验可算。 */
    private static void bindPriorHeight(SceneRuntime rt, final SceneNode node) {
        Runnable apply = () -> node.setPreferredHeight(contentHeight(rt, node));
        apply.run();
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(apply));
    }

    /**
     * 内容先验高（忽略本节点自身的 {@code preferredHeight}，只看文本/子树/内边距）：
     * 供 {@link #bindPriorHeight} 使用，避免「本节点上一轮写入的先验值」把自己锁死（字号变化后不重算）。
     */
    private static int contentHeight(SceneRuntime rt, SceneNode node) {
        int inner = node.getText() == null ? 0 : rt.lineHeight(node.effectiveFontSize());
        List<SceneNode> children = node.__getChildren();
        for (SceneNode child : children) {
            inner = Math.max(inner, priorHeight(rt, child) + child.marginV());
        }
        return inner + node.getPaddingTop() + node.getPaddingBottom();
    }

    /**
     * 子节点先验高（与 {@code ConstraintResolver.priorKnownChildHeight} 同口径）：
     * 显式 {@code preferredHeight} 优先（如命中区下界、分段控件自持高度），否则回退内容高。
     */
    private static int priorHeight(SceneRuntime rt, SceneNode node) {
        if (node.getPreferredHeight() > 0) {
            return node.getPreferredHeight();
        }
        return contentHeight(rt, node);
    }

    /** 宿主视口逻辑宽：首帧挡位判据的播种值（其后由视图根实际可用宽精修）。 */
    private static int seedWidth(SceneRuntime rt) {
        LogicalBox box = rt.logicalBox().get();
        return box == null ? 0 : box.widthPx();
    }

    /** 返回列表：清下钻态，并在列表仍挂载时就交回焦点（重挂场景由 M3 的初始焦点接管）。 */
    private static void backToList(SceneRuntime rt, Signal<Boolean> drilled, SceneNode[] listRoot) {
        drilled.set(Boolean.FALSE);
        SceneNode pane = listRoot[0];
        if (pane != null && pane.__getParent() != null) {
            rt.requestFocus(pane);
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 宽挡列表列宽：{@code clamp(220, 0.28×W, 300)}（C3 §6.1）。 */
    private static int listWidth(int availableWidthPx) {
        int scaled = Math.round(Math.max(0, availableWidthPx) * LIST_WIDTH_PERCENT / 100.0F);
        return Math.max(LIST_MIN_WIDTH_PX, Math.min(LIST_MAX_WIDTH_PX, scaled));
    }

    private static SceneNode label(String text) {
        SceneNode node = new SceneNode();
        node.setText(text);
        node.setHitTestable(false);
        return node;
    }

    /** 视图根表面：PANEL 角色配方是唯一外观写入者（主题切换只重派生、不重建节点）。 */
    private static void bindPanelSurface(SceneRuntime rt, SceneNode root) {
        SceneInteractionState interaction = rt.interactionState(root);
        interaction.hovered();
        interaction.pressed();
        interaction.focused();
        SceneSurfaceBinder.bind(rt, root, SceneThemes.surface(rt, SceneTheme.Role.PANEL),
                Signal.create(Boolean.TRUE), interaction);
    }
}
