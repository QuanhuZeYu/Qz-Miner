package club.heiqi.qz_miner.client.configGUI.objectgroup;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.DraftSignalAdapter;
import club.heiqi.config.ui.editor.Registry;
import club.heiqi.config.ui.field.FieldRenderSupport;
import club.heiqi.config.ui.field.FieldRenderer;
import club.heiqi.qz_miner.client.ClientI18n;
import club.heiqi.uilib.ui.reactive.Computed;
import club.heiqi.uilib.ui.reactive.Effect;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneButtonVariant;
import club.heiqi.uilib.ui.scene.form.FormFieldShell;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.overlay.OverlayDismissPolicy;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * 对象组字段渲染器（M1）：配置页只剩一张摘要卡 + 一个入口，编辑搬到专用全屏编辑视图。
 *
 * <p><b>开屏成本</b>：本渲染器在挂载期只构建「状态点 + 摘要文本 + 一个按钮」三个节点，
 * 不构建任何 picker 面板、不枚举候选、不建成员/组行节点；编辑视图的内容工厂只在
 * {@code visible} 首次变 true 时被调用一次，随 portal 卸载回收。</p>
 *
 * <p><b>数据面</b>：本类不持有第二份配置真值，读写全部经 {@link ObjectGroupEditorState}
 * 到 {@link DraftSignalAdapter#onFieldEdit}（唯一提交点）；draft 变化经
 * {@link ObjectGroupEditorState#syncExternal(Object)} 回灌，配置页 reload/撤销即时同步。</p>
 *
 * <p><b>浮层契约</b>：编辑视图是与配置页同级、占满视口、只有 ESC 能关的浮层
 * （{@code new OverlayDismissPolicy(true, false, false)}：ESC 可关、点外部不关、选中不关），
 * 关闭请求只写可见性 signal，不直接挂卸浮层。</p>
 */
public final class ObjectGroupEditorFieldRenderer implements FieldRenderer {

    /** 摘要卡内元素间距（逻辑 px）。 */
    private static final int CARD_GAP = 6;
    /** 状态点直径下界（逻辑 px）：可读性下限，实际直径 = max(下界, round(生效字号 × 比例))。 */
    private static final int STATUS_DOT_MIN_PX = 8;
    /** 状态点直径与生效字号的比例。 */
    private static final float STATUS_DOT_FONT_RATIO = 0.5f;

    /** 编辑视图可见性由本渲染器持有的信号驱动；关闭请求只写该信号。 */
    private final Supplier<Registry> editorRegistry;

    /**
     * 创建渲染器。
     *
     * <p>registry 以 {@link Supplier} 传入：{@code ConfigUI.buildScreen} 先执行 editor registry
     * customizer、再执行字段 renderer customizer，而本渲染器构造发生在 customizer 内、取用发生在
     * 渲染期，故用惰性持有者把「同一个已冻结 registry」带进来（见 {@code QzMinerConfigGUI}）。</p>
     *
     * @param editorRegistry 当前 screen 的已冻结 value editor registry 供应器
     */
    public ObjectGroupEditorFieldRenderer(Supplier<Registry> editorRegistry) {
        if (editorRegistry == null) throw new IllegalArgumentException("editorRegistry must not be null");
        this.editorRegistry = editorRegistry;
    }

    @Override
    public SceneNode render(SceneRuntime rt, FieldSpec spec, DraftSignalAdapter adapter) {
        final String path = spec.path();
        final ObjectGroupEditorState state = new ObjectGroupEditorState(spec, adapter);
        final Signal<Boolean> open = Signal.create(Boolean.FALSE);
        final ObjectGroupEditorContext context = new Context(rt, adapter, state, open, editorRegistry);

        // 草稿 -> 行投影同步（唯一读面）；配置页 reload/撤销/保存回同步都经此到达视图。
        rt.bind(adapter.draftSignal(path), state::syncExternal);

        // ESC 归属：SceneInputRouter.dispatchKeyboardAndText 在派发前先调 requestTopEscapeDismiss()，
        // 且只有 isDismissOnEscape() 的栈顶浮层才消费 ESC（SceneInputRouter.java:556-559,925-936）；
        // Entry.requestDismiss() 只调用 dismissRequest 回调（SceneOverlayHost.java:249-252），
        // 因此策略保持设计稿口径 (true,false,false)，由 dismissRequest 做「条件关闭」：
        // 窄挡下钻中 → 视图返回列表并保持打开；否则关闭视图。这样 ESC 不依赖焦点是否在视图内。
        rt.portal(open, () -> openEditor(context),
                new OverlayDismissPolicy(true, false, false), context::handleDismissRequest);

        return FormFieldShell.buildBorderless(rt, FieldRenderSupport.labelOf(spec), spec.helper(),
                adapter.errorSignal(path), adapter.dirtySignal(path),
                () -> buildSummaryCard(rt, state, open));
    }

    /**
     * 构建编辑视图根并显式占满视口。
     *
     * <p>overlay root 不自动撑满（{@code SceneOverlayPipelineTest} 的既有口径），因此这里对视图根
     * 显式打开两轴 fillParent，编辑视图再在内部自行滚动；页面滚动不参与。</p>
     *
     * @param context 宿主上下文
     * @return 视图根节点
     */
    private static SceneNode openEditor(ObjectGroupEditorContext context) {
        SceneNode root = ObjectGroupEditorView.build(context);
        root.setFillParentWidth(true);
        root.setFillParentHeight(true);
        return root;
    }

    /**
     * 摘要卡：状态点 + 四元组摘要 + 「管理对象组…」入口。
     *
     * @param rt    场景运行时
     * @param state 编辑状态
     * @param open  编辑视图可见性信号
     * @return 摘要卡节点
     */
    private static SceneNode buildSummaryCard(SceneRuntime rt, ObjectGroupEditorState state,
                                              Signal<Boolean> open) {
        ReadableSignal<Integer> errorColor = SceneThemes.errorText(rt);
        ReadableSignal<Integer> warningColor = SceneThemes.warningText(rt);
        ReadableSignal<Integer> successColor = SceneThemes.successText(rt);
        ReadableSignal<Integer> mutedColor = SceneThemes.mutedForeground(rt);

        SceneNode card = SceneNode.column();
        card.setGap(CARD_GAP);

        SceneNode statusRow = SceneNode.row();
        statusRow.setGap(CARD_GAP);
        statusRow.setHitTestable(false);

        // 状态点：直径随生效字号派生（不写死物理像素），颜色随主题与状态重派生。
        SceneNode dot = new SceneNode();
        dot.setHitTestable(false);
        applyStatusDotSide(dot, STATUS_DOT_MIN_PX);
        // 直径随生效字号派生（下界 = 可读性下限），字号/缩放变化经 layoutDone 纪元重派生。
        rt.bind(rt.layoutDoneSignal(), epoch -> Effect.untrack(() -> {
            int fontSize = dot.effectiveFontSize();
            int side = Math.max(STATUS_DOT_MIN_PX, Math.round(fontSize * STATUS_DOT_FONT_RATIO));
            applyStatusDotSide(dot, side);
        }));
        rt.bind(statusColor(state, errorColor, warningColor, successColor), dot::setBackgroundColor);
        statusRow.appendChild(dot);

        SceneNode summary = new SceneNode();
        summary.setHitTestable(false);
        Signal<String> summaryText = Signal.create(summaryTextOf(state));
        rt.bind(state.rows(), value -> summaryText.set(summaryTextOf(state)));
        rt.bind(summaryText, summary::setText);
        rt.bind(mutedColor, summary::setTextColor);
        statusRow.appendChild(summary);
        card.appendChild(statusRow);

        SceneButton.Props manage = SceneButton.Props
                .builder(Signal.create(ClientI18n.tr("config.qz_miner.object_group.manage")))
                .variant(SceneButtonVariant.PRIMARY)
                .onClick(() -> open.set(Boolean.TRUE))
                .build();
        rt.mount(card, SceneButton.create(rt, manage));
        return card;
    }

    /**
     * 状态点颜色：冲突/未完成 → error，未生效 → warning，否则 success。
     *
     * @param state   编辑状态
     * @param error   错误语义色信号
     * @param warning 警告语义色信号
     * @param success 成功语义色信号
     * @return 颜色信号（随主题与状态重派生）
     */
    private static ReadableSignal<Integer> statusColor(ObjectGroupEditorState state,
                                                       ReadableSignal<Integer> error,
                                                       ReadableSignal<Integer> warning,
                                                       ReadableSignal<Integer> success) {
        return Computed.create(Integer.valueOf(resolveStatusColor(state, error.get(), warning.get(), success.get())),
                () -> Integer.valueOf(resolveStatusColor(state, error.get(), warning.get(), success.get())));
    }

    private static int resolveStatusColor(ObjectGroupEditorState state, Integer error, Integer warning,
                                          Integer success) {
        ObjectGroupEditorState.Summary summary = state.summary();
        if (summary.conflictCount() > 0 || summary.incompleteCount() > 0) return intOf(error);
        if (summary.inactiveCount() > 0) return intOf(warning);
        return intOf(success);
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    /**
     * 写状态点直径与圆角（幂等：值未变时不标脏）。
     *
     * @param dot  状态点节点
     * @param side 直径（逻辑 px）
     */
    private static void applyStatusDotSide(SceneNode dot, int side) {
        int radius = side / 2;
        if (dot.getPreferredWidth() == side && dot.getPreferredHeight() == side
                && dot.getCornerRadius() == radius) {
            return;
        }
        dot.setPreferredWidth(side);
        dot.setPreferredHeight(side);
        dot.setCornerRadius(radius);
    }

    /**
     * 摘要文本：{@code N 组 · M 成员} + 非零问题计数。
     *
     * <p>配置页摘要卡与编辑视图顶部条共用本方法，保证两处口径完全一致（单一真值，不复制文案拼接）。</p>
     *
     * @param state 编辑状态
     * @return 本地化摘要文本
     */
    public static String summaryTextOf(ObjectGroupEditorState state) {
        ObjectGroupEditorState.Summary summary = state.summary();
        StringBuilder out = new StringBuilder();
        out.append(ClientI18n.tr("config.qz_miner.object_group.summary",
                Integer.valueOf(summary.groupCount()), Integer.valueOf(summary.memberCount())));
        if (summary.conflictCount() > 0) {
            out.append(" · ").append(ClientI18n.tr("config.qz_miner.object_group.summary.conflict",
                    Integer.valueOf(summary.conflictCount())));
        }
        if (summary.inactiveCount() > 0) {
            out.append(" · ").append(ClientI18n.tr("config.qz_miner.object_group.summary.inactive",
                    Integer.valueOf(summary.inactiveCount())));
        }
        if (summary.incompleteCount() > 0) {
            out.append(" · ").append(ClientI18n.tr("config.qz_miner.object_group.summary.incomplete",
                    Integer.valueOf(summary.incompleteCount())));
        }
        return out.toString();
    }

    /** 编辑视图宿主上下文实现（唯一实现点；pane 只消费接口）。 */
    private static final class Context implements ObjectGroupEditorContext {
        private final SceneRuntime rt;
        private final DraftSignalAdapter adapter;
        private final ObjectGroupEditorState state;
        private final Signal<Boolean> open;
        private final Supplier<Registry> editorRegistrySupplier;

        Context(SceneRuntime rt, DraftSignalAdapter adapter, ObjectGroupEditorState state,
                Signal<Boolean> open, Supplier<Registry> editorRegistrySupplier) {
            this.rt = rt;
            this.adapter = adapter;
            this.state = state;
            this.open = open;
            this.editorRegistrySupplier = editorRegistrySupplier;
        }

        @Override
        public SceneRuntime rt() { return rt; }

        @Override
        public DraftSignalAdapter adapter() { return adapter; }

        @Override
        public Registry editorRegistry() { return editorRegistrySupplier.get(); }

        @Override
        public ObjectGroupEditorState state() { return state; }

        @Override
        public void requestClose() { open.set(Boolean.FALSE); }

        @Override
        public void setDismissHandler(BooleanSupplier handler) {
            if (handler == null) throw new IllegalArgumentException("handler must not be null");
            dismissHandler = handler;
        }

        @Override
        public void handleDismissRequest() {
            BooleanSupplier handler = dismissHandler;
            if (handler != null && handler.getAsBoolean()) return;
            open.set(Boolean.FALSE);
        }

        private BooleanSupplier dismissHandler;
    }
}
