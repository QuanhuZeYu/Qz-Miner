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
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceStyle;
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
 * <p><b>列宽（C3 §6.1 的实测化）</b>：宽挡列表列宽 = {@code clamp(内容下限, 0.28×W, 让详情保底)}，
 * 内容下限来自 {@link ObjectGroupListPane#minContentWidth}（当前语言文案实测），因此长语言文案下
 * 左栏不会重叠；超宽视口下不再是固定上限（旧口径 300 会把左栏钉成窄条而详情独占 2000+ px）。</p>
 *
 * <p><b>背板</b>：视图根用「当前主题 {@code withoutBackdrop()} 变体的 PANEL 配方」——不透明、
 * 不依赖玻璃滤镜，背景配置页文字不再透出（见 {@link #bindPanelSurface}）。</p>
 *
 * <p><b>先验尺寸（布局闸门纪律）</b>：{@code ConstraintResolver} 的 grow 分配要求容器主轴先验已知
 * ——ROW 里存在 {@code flexGrow} 子时，所有固定兄弟必须 {@code priorKnownChildWidth} 可算；
 * COLUMN 同理要求固定兄弟高度可算。因此本类对顶部条/窄挡下钻头部显式设 {@code preferredHeight}、
 * 对全部动作按钮显式测量设 {@code preferredWidth}（{@code SceneButton} 根节点自身无文本、
 * 无 preferredWidth，不显式声明就会撞掉整条 grow 分配并 WARN）；主体三态形态
 * （{@see #buildBody}）不依赖任何「折叠列占位退出」——同一时刻树里只存在激活的那一列。</p>
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
 * 视图在 {@link ObjectGroupEditorContext#setDismissHandler} 注册<b>两段式</b>处理器：
 * ① 搜索词非空 → 清空搜索并保持打开；② 窄挡下钻中 → 返回列表并保持打开；否则 → 关闭视图
 * （此路径不依赖焦点，是窄挡下钻的兜底）。<b>为什么①不能按焦点判定</b>：ESC 在控件派发<b>之前</b>
 * 就被 {@code SceneInputRouter} 无条件消费（{@code SceneInputRouter.java:556-559} →
 * {@code requestTopEscapeDismiss()} 只要栈顶浮层 policy 允许即返回 true，
 * {@code SceneInputRouter.java:925-936}），视图收不到 ESC 键事件，故只能以「有无搜索词」为判据。</p>
 *
 * <p><b>↑/↓ 归属</b>：列表焦点（pane / 行视口 / 搜索框）由列表 pane 自持并
 * {@code stopPropagation}；焦点在详情（宽挡 ENTER 进详情、窄挡下钻）时事件冒泡到视图根，
 * 由视图根转发 {@link ObjectGroupEditorContext#nudgeSelection(int)}——列表实现持有视口与滚动
 * 状态，故「滚动跟随」不需要视图了解列表内部。picker 面板是独立 portal 树
 * （{@code ScenePickerPanel.java:852}），其焦点节点父链不到本根，键盘事件不会误触本视图。
 * ENTER 挂列表 pane 根（窄挡下钻 / 宽挡把焦点移入详情）；窄挡详情挂载即接管焦点；删除、Menu
 * 由列表 pane 自持；指针行激活（{@link ObjectGroupEditorContext#setRowActivateHandler}）
 * 窄挡下钻、宽挡 no-op（保持列表焦点，点击后 ↑/↓ 仍可换组）。关闭语义只写可见性 signal，
 * 不直接挂卸浮层。</p>
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
    /**
     * 宽挡详情列最小可用宽（逻辑 px）。
     *
     * <p>列表列宽不再用固定上下限（旧口径 {@code clamp(220, 0.28×W, 300)}）：固定下限在长语言文案
     * 下列内必然重叠，固定上限又在超宽视口下把列表钉成一条窄条、详情独占 2000+ px。现口径 =
     * 「内容下限（{@link ObjectGroupListPane#minContentWidth} 实测）与比例取大者，再让详情至少保留本值」。</p>
     */
    private static final int DETAIL_MIN_WIDTH_PX = 300;
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

        // 列表列内容下限：由 M3 用当前生效字号 + 当前语言文案实测（随字号/语言/主题内边距变化）。
        final Signal<Integer> listContentMin = Signal.create(
                Integer.valueOf(ObjectGroupListPane.minContentWidth(rt, root.effectiveFontSize())));
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            int min = ObjectGroupListPane.minContentWidth(rt, root.effectiveFontSize());
            if (listContentMin.get().intValue() != min) {
                listContentMin.set(Integer.valueOf(min));
            }
        }));

        root.appendChild(buildTopBar(ctx, state));
        root.appendChild(buildUndoBar(ctx, state));
        root.appendChild(buildBody(ctx, state, wide, availableWidth, listContentMin, drilled, listRoot));

        // 行激活（M3 在指针点击行、select + 焦点交回行视口之后调用）：窄挡 → 下钻到详情，
        // 焦点随后由 buildDetailHost 的既有「挂载即接管焦点」逻辑拿走；宽挡 → no-op ——
        // 详情已并排显示，指针点击后仍应保持列表焦点，使 ↑/↓ 继续在列表里移动选中。
        ctx.setRowActivateHandler(() -> {
            if (!Boolean.TRUE.equals(wide.get())) {
                drilled.set(Boolean.TRUE);
            }
        });

        // 浮层根拦 ↑/↓：焦点在详情（宽挡 ENTER 进详情 / 窄挡下钻）时列表 pane 不在冒泡路径上，
        // 这里把方向键转发给列表注册的选择移动处理器（含滚动跟随，由列表自持实现）。
        // 列表焦点下 ListPane 先 stopPropagation，事件到不了本根 ⇒ 不会重复移动；
        // picker 打开时焦点在 picker 自己的 portal 树内，父链不到本根（见类注释「↑/↓ 归属」）。
        rt.on(root, SceneEventType.KEY_DOWN, (ev, ectx) -> {
            if (ev.getKeyAction() != SceneKeyAction.PRESSED) {
                return;
            }
            SceneKey key = ev.getKey();
            if (key == SceneKey.ARROW_UP) {
                ctx.nudgeSelection(-1);
                ectx.stopPropagation();
            } else if (key == SceneKey.ARROW_DOWN) {
                ctx.nudgeSelection(1);
                ectx.stopPropagation();
            }
        });

        // 单一 ESC 通路：浮层策略（M1）消费 ESC 后回调到此；返回 true = 视图自行处理（保持打开）。
        ctx.setDismissHandler(() -> {
            // 两段式第一段：有搜索词 → 第一次 ESC 只清搜索并保持打开。判据用「有无搜索词」
            // 而不是「焦点是否在搜索框」——ESC 在控件派发前就被 SceneInputRouter 无条件消费
            // （见类注释），视图收不到 ESC 键事件（空/空白搜索视为无搜索，与列表 matchesQuery 同口径）。
            String query = state.search().get();
            if (query != null && !query.trim().isEmpty()) {
                state.search().set("");
                return true;
            }
            // 两段式第二段：窄挡下钻中 → 返回列表并保持打开。
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

    /**
     * 主从主体：<b>三态互斥形态</b>（宽挡并排 / 窄挡列表独占 / 窄挡下钻详情独占）。
     *
     * <p><b>为什么不是「两列常挂 + 折叠退出」（本轮 P0 修复）</b>：UILib 的 {@code setCollapsed}
     * 语义是「内容退出布局域，自身仍按<b>零内容叶</b>留在父流中」
     * （{@code SceneLayoutProps.java:237-249}），而零内容叶在宽度维度取<b>可用宽</b>
     * （{@code SizingCalculator.java:146-151}：折叠节点文本恒 null ⇒ 落「无文本叶，宽 = 可用宽」）。
     * 于是 ROW 里被折叠的列表列仍占满整行宽，并把详情列顺序推到 {@code x = 视口宽} 之外 ——
     * 真机最差挡位（1920×1080 @ GUI Scale 4 = 480×270 逻辑）下钻后详情整列在视口外、含返回头部不可见。
     * UILib 没有「脱离主轴占位」的正门 API（折叠 = 零内容叶留在父流是既定语义），
     * 故此处按挡位切换主体<b>形态</b>：同一时刻树里只存在激活的那一列。</p>
     *
     * <p>挂载容器必须是 COLUMN：{@code rt.show} 的零尺寸 anchor 在 COLUMN 主轴高 0（无害），
     * 在 ROW 主轴会按可用宽铺满（见 {@link ObjectGroupListPane} 类注释的 ROW 纪律）。三态互斥
     * 保证任一时刻只有一棵主体子树挂载，换挡时旧形态整体卸载（pane 随作用域回收）。</p>
     */
    private static SceneNode buildBody(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state,
                                       final ReadableSignal<Boolean> wide,
                                       final ReadableSignal<Integer> availableWidth,
                                       final ReadableSignal<Integer> listContentMin,
                                       final Signal<Boolean> drilled, final SceneNode[] listRoot) {
        final SceneRuntime rt = ctx.rt();
        // 详情根引用：宽挡「Enter 焦点进详情」需要它。
        final SceneNode[] detailRoot = new SceneNode[1];

        final SceneNode holder = SceneNode.column();
        holder.setFillParentHeight(true);
        holder.setFillParentWidth(true);

        final ReadableSignal<Boolean> listOnly = Computed.create(Boolean.TRUE,
                () -> Boolean.valueOf(!Boolean.TRUE.equals(wide.get()) && !Boolean.TRUE.equals(drilled.get())));
        final ReadableSignal<Boolean> detailOnly = Computed.create(Boolean.FALSE,
                () -> Boolean.valueOf(!Boolean.TRUE.equals(wide.get()) && Boolean.TRUE.equals(drilled.get())));

        // 三态宽度策略（结构性，按形态声明，不在 builder 里共用）：
        //   宽挡           → ROW 内「列表列固定列宽（preferredWidth）+ 详情列 grow 吃满剩余」；
        //   窄挡未下钻     → 唯一列在 holder(COLUMN) 里两轴吃满 = body 内宽；
        //   窄挡下钻       → 同上的详情列（含返回头部）。
        rt.show(holder, wide, () -> buildWideBody(ctx, state, wide, availableWidth, listContentMin,
                drilled, listRoot, detailRoot));
        rt.show(holder, listOnly, () -> fillHolder(buildListColumn(ctx, state, wide, drilled,
                listRoot, detailRoot)));
        rt.show(holder, detailOnly, () -> fillHolder(buildDetailHost(ctx, state, wide, drilled,
                detailRoot, listRoot)));
        return holder;
    }

    /** 宽挡形态：列表固定列宽 + 详情并排（两列都是同一 ROW 的直接子，不再有折叠列占位）。 */
    private static SceneNode buildWideBody(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state,
                                           final ReadableSignal<Boolean> wide,
                                           final ReadableSignal<Integer> availableWidth,
                                           final ReadableSignal<Integer> listContentMin,
                                           final Signal<Boolean> drilled, final SceneNode[] listRoot,
                                           final SceneNode[] detailRoot) {
        final SceneRuntime rt = ctx.rt();
        final SceneNode body = SceneNode.row();
        body.setGap(ROOT_GAP_PX);
        body.setFillParentHeight(true);
        body.setFillParentWidth(true);

        // 宽挡列表列宽 = clamp(内容下限, 0.28×W, 让详情保留下限后的余量)（见 listWidth）。
        final ReadableSignal<Integer> listPreferredWidth = Computed.create(Integer.valueOf(0),
                () -> Integer.valueOf(listWidth(availableWidth.get().intValue(),
                        listContentMin.get().intValue())));
        // 宽挡第一列 = ROW 主轴「固定列宽」：显式 preferredWidth，并显式不声明 fillParentWidth ——
        // UILib ConstraintResolver.effectiveGrowRow（ROW 主轴 grow 判定：flexGrow>0 优先，否则
        // fillParentWidth 视为隐式 grow=1）会把声明了 fill 的固定列也拉进 grow 集合，与详情列的
        // 显式 grow=1 等权分配（两列各 338），而列表列只画自己的 preferredWidth（196）
        // ⇒ 详情列被压窄、body 右侧空出一截。这是本轮真实回归的根因。
        final SceneNode listHost = buildListColumn(ctx, state, wide, drilled, listRoot, detailRoot);
        listHost.setFillParentWidth(false);
        listHost.setPreferredWidth(listPreferredWidth.get().intValue());
        rt.bind(listPreferredWidth, width -> listHost.setPreferredWidth(width.intValue()));
        body.appendChild(listHost);

        final SceneNode detailHost = buildDetailHost(ctx, state, wide, drilled, detailRoot, listRoot);
        detailHost.setFlexGrow(1);
        body.appendChild(detailHost);
        return body;
    }

    /**
     * 列表列（宽挡第一列 / 窄挡独占整宽）：<b>只建内容与键盘语义，宽度轴策略由形态工厂声明</b>
     * ——宽挡 = {@code buildWideBody} 里固定 {@code preferredWidth}；窄挡 = {@code fillHolder} 两轴吃满。
     *
     * <p>这里刻意不声明 {@code fillParentWidth}：ROW 主轴上 fill 等于隐式 grow=1
     * （{@code ConstraintResolver.effectiveGrowRow}），共用 builder 会让宽挡的固定列也参与 grow
     * 等权分配，把详情列压窄（详见 {@link #buildWideBody}）。</p>
     *
     * <p>列表 pane 实例随本列挂载一次构建（{@code show} 的 I7 稳定语义：条件不跨界不重建），
     * 列表 Enter 在窄挡下钻、宽挡把焦点移入详情（列表自身不消费 Enter，见 M3）。</p>
     */
    private static SceneNode buildListColumn(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state,
                                            final ReadableSignal<Boolean> wide, final Signal<Boolean> drilled,
                                            final SceneNode[] listRoot, final SceneNode[] detailRoot) {
        final SceneRuntime rt = ctx.rt();
        final SceneNode host = SceneNode.column();
        // 高度两形态一致：宽挡是 ROW 交叉轴 fill，窄挡是 COLUMN 主轴 fill（吃满剩余高）。
        host.setFillParentHeight(true);

        SceneNode pane = ObjectGroupListPane.build(ctx);
        listRoot[0] = pane;
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
        host.appendChild(pane);
        return host;
    }

    /**
     * 窄挡单列形态：唯一列在 holder（COLUMN）里两轴吃满 —— 宽度方向是 COLUMN <b>交叉轴</b>
     * （fill 拉伸，不是主轴 grow），高度方向是 COLUMN 主轴 fill（吃满剩余高）。
     *
     * @param column 单列根（列表列或详情列）
     * @return 同一节点（便于在 show 工厂里链式表达）
     */
    private static SceneNode fillHolder(SceneNode column) {
        column.setFillParentWidth(true);
        column.setFillParentHeight(true);
        return column;
    }

    /** 详情宿主：窄挡下钻头部（视图自建）+ 视图内唯一滚动视口 + M4 详情 pane。 */
    private static SceneNode buildDetailHost(ObjectGroupEditorContext ctx, final ObjectGroupEditorState state,
                                             final ReadableSignal<Boolean> wide, final Signal<Boolean> drilled,
                                             SceneNode[] detailRoot, final SceneNode[] listRoot) {
        final SceneRuntime rt = ctx.rt();
        final SceneNode host = SceneNode.column();
        host.setGap(DETAIL_GAP_PX);
        // 高度两形态一致（宽挡交叉轴 / 窄挡主轴）；宽度轴由形态工厂声明：宽挡靠 flexGrow(1) 吃满剩余，
        // 窄挡由 fillHolder 两轴吃满（同 buildListColumn 的口径）。
        host.setFillParentHeight(true);

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
        if (node.getFlexDirection() == club.heiqi.uilib.ui.scene.layout.FlexDirection.COLUMN) {
            // COLUMN 主轴：纵向堆叠 ⇒ 求和 + 间距（ROW 才取最大，见 ListPane 同名实现）。
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

    /**
     * 返回列表：清下钻态。主体形态随之从「详情单列」切回「列表单列」，列表 pane 由形态工厂重建，
     * 其构建期自持搜索框焦点（M3）；此处的交回焦点覆盖「列表仍在树里」的宽挡路径，避免重复请求。
     */
    private static void backToList(SceneRuntime rt, Signal<Boolean> drilled, SceneNode[] listRoot) {
        drilled.set(Boolean.FALSE);
        SceneNode pane = listRoot[0];
        if (pane != null && pane.__getParent() != null) {
            rt.requestFocus(pane);
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 宽挡列表列宽（C3 §6.1 的比例口径 + 内容下限/详情下限两个实测约束）。
     *
     * <p>取值顺序：以 {@code 0.28×W} 为起点，先保证不低于列表<b>内容下限</b>（否则左栏工具栏
     * 重叠/裁字 —— 真机症状），再保证详情列至少 {@link #DETAIL_MIN_WIDTH_PX}；两者冲突时
     * <b>内容下限优先</b>（重叠是可见缺陷，详情窄只是观感取舍）。</p>
     *
     * @param availableWidthPx 视图根实际可用宽（逻辑 px）
     * @param contentMinWidthPx 列表 pane 实测内容下限（逻辑 px，含其内边距）
     * @return 列表列宽（逻辑 px）
     */
    private static int listWidth(int availableWidthPx, int contentMinWidthPx) {
        int lower = Math.max(1, contentMinWidthPx);
        int bodyAvailable = Math.max(0, availableWidthPx - 2 * ROOT_PADDING_PX - ROOT_GAP_PX);
        int upper = Math.max(lower, bodyAvailable - DETAIL_MIN_WIDTH_PX);
        int ratio = Math.round(Math.max(0, availableWidthPx) * LIST_WIDTH_PERCENT / 100.0F);
        return Math.max(lower, Math.min(upper, Math.max(lower, ratio)));
    }

    private static SceneNode label(String text) {
        SceneNode node = new SceneNode();
        node.setText(text);
        node.setHitTestable(false);
        return node;
    }

    /**
     * 视图根表面：<b>不透明</b> PANEL 配方是唯一外观写入者（主题切换只重派生、不重建节点）。
     *
     * <p><b>为什么不能用 {@link SceneThemes#surface}</b>：液态玻璃档 PANEL 的 idle tint alpha 仅
     * {@code 0x14}（20/255 ≈ 7.8%，见 {@code SceneTheme.Builder} 默认配方），编辑器遮不遮住下层
     * 配置页完全押在玻璃模糊上；真机玻璃滤镜不可用时它退化为纯 tint 叠加 —— 背景配置页的
     * key/value 文案直接透出（用户截图实证）。本视图是占满视口的编辑浮层，背板必须先自足不透明。</p>
     *
     * <p><b>做法</b>：取「当前来源主题的 {@link SceneTheme#withoutBackdrop()} 变体」的 PANEL 配方
     * （全角色 backdrop 置 null、tint 换不透明底色，语义色/边框/圆角保持同套），经
     * {@link SceneThemes#resolve} 的只读主题信号派生 —— 主题切换自动重派生，不重建节点，
     * 不新增 UILib 公共面，也不在业务侧写死 ARGB。内部子控件各自仍走原主题角色（叠在不透明
     * 背板之上，玻璃不可用时也不会透出配置页）。</p>
     */
    private static void bindPanelSurface(SceneRuntime rt, SceneNode root) {
        SceneInteractionState interaction = rt.interactionState(root);
        interaction.hovered();
        interaction.pressed();
        interaction.focused();
        ReadableSignal<SceneTheme> theme = SceneThemes.resolve(rt);
        final SceneSurfaceStyle[] holder = new SceneSurfaceStyle[1];
        Effect.untrack(() -> holder[0] = theme.get().withoutBackdrop().surface(SceneTheme.Role.PANEL));
        ReadableSignal<SceneSurfaceStyle> solidPanel = Computed.create(holder[0],
                () -> theme.get().withoutBackdrop().surface(SceneTheme.Role.PANEL));
        SceneSurfaceBinder.bind(rt, root, solidPanel, Signal.create(Boolean.TRUE), interaction);
    }
}
