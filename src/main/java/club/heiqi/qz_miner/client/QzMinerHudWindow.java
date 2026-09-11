package club.heiqi.qz_miner.client;

import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.uilib.ui.hud.api.HudWindowFactory;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.render.UiBackdrop;
import club.heiqi.uilib.ui.render.UiGlassMaterial;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.SceneChromeTokens;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceBinder;
import club.heiqi.uilib.ui.scene.theme.SceneSurfaceStyle;
import club.heiqi.uilib.ui.scene.theme.SceneTheme;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * 连锁状态 HUD 虚拟窗口（UILib 4.9 {@link HudWindowFactory} 契约）。
 *
 * <p>窗口挂载时 {@link #build(SceneRuntime)} 用 scene 代码构建一次内容树，此后内容变化一律走
 * signal：{@link #refresh()} 在客户端 tick 末尾把 {@link QzMinerHudModel} 翻译结果写入 signal，
 * 由宿主帧管线物化。Miner 不自渲染、不自建锚定，锚点/安全区/缩放/裁剪全部由 UILib 宿主负责。</p>
 *
 * <h3>液态玻璃卡片（4.9 公开材质契约）</h3>
 * <p>内容根之下常驻一张液态玻璃卡片：滤镜用公开材质 API
 * {@link UiBackdrop#liquidGlass(UiGlassMaterial, int, float)}，表面（tint/边框/圆角/滤镜）经公开
 * {@link SceneSurfaceBinder#bind} 独占写入，配方取自当前主题的
 * {@link SceneTheme.Role#PANEL} 角色、只钉死滤镜参数，其余分量随主题变化。宿主外壳关闭
 * （{@code HudSpec.chrome(false)}，见 {@code ClientProxy.init}）：卡片自绘底色与内边距，
 * 避免「宿主半透明外壳 + 卡片玻璃」双层底色。色调 token 与语义模型不变。</p>
 *
 * <h3>线程与刷新契约</h3>
 * <p>注册、signal 读写只在客户端主线程。本类唯一写入口 {@link #refresh()} 由
 * {@link QzMinerHudTicker} 在 ClientTickEvent.END 调用；宿主未提供输入源，窗口只读展示。</p>
 *
 * <h3>空内容整窗隐藏</h3>
 * <p>显示门关闭时模型为空 → 外层卡片列表为空 → 卡片整体卸载 → 内容根尺寸为零 →
 * 宿主连外壳一起隐藏（对齐旧 EMPTY 快照语义，无需 close 或重新注册）。卡片节点键稳定、
 * 内容变化复用（不重建），卡内行列表按行内容键增量更新。</p>
 */
public final class QzMinerHudWindow implements HudWindowFactory {

    /** HUD 注册 id（全局唯一；重复注册由 UILib 注册表拒绝）。 */
    public static final String HUD_ID = "qz_miner:chain-status";

    /** HUD 文本字号（UILib 4.9 宿主默认 token 口径，logical px）。 */
    static final int FONT_SIZE_PX = 14;

    /** 行内片段间距（logical px）。 */
    static final int SPAN_GAP_PX = 4;

    /** 行间距（logical px）。 */
    static final int ROW_GAP_PX = 2;

    /** 卡片横向内边距（logical px）：卡片自绘内缘，替代宿主 chrome(true) 外壳内边距。 */
    static final int CARD_PADDING_X_PX = 8;

    /** 卡片纵向内边距（logical px）。 */
    static final int CARD_PADDING_Y_PX = 6;

    /** 卡片玻璃材质档（与 UILib 默认深色主题 PANEL 角色同源）。 */
    static final UiGlassMaterial GLASS_MATERIAL = UiGlassMaterial.DARK_THIN;

    /** 卡片玻璃背景模糊半径（logical px）。 */
    static final int GLASS_BLUR_PX = 10;

    /** 卡片玻璃透镜强度（0 = 无折射）。 */
    static final float GLASS_LENS = 0.60F;

    /** 单卡片键：卡片常驻复用，内容增量由卡内按行 key 的列表承担。 */
    static final String CARD_KEY = "qz_miner:chain-status-card";

    /**
     * HUD 锚定边距（logical px）。
     *
     * <p>同时是 {@code HudSpec.margin} 与编辑期默认放置（{@code HudPlacement.defaultOf}）的值：
     * 编辑预览的起始位置必须与关闭态 HUD 的默认位置一致，否则用户一进编辑就看到 HUD「跳位」。</p>
     */
    public static final int HUD_MARGIN_PX = 8;

    /** 常启信号：HUD 卡片只读展示、不参与命中，表面绑定恒取 idle 档。 */
    private static final ReadableSignal<Boolean> ALWAYS_ENABLED = () -> Boolean.TRUE;

    private final ChainClientState clientState;
    private final ClientPhaseProjection phaseProjection;
    private final QzMinerHudModel.PreviewStateSource previewStateSource;

    /** 显示模型 signal：内容唯一真值，由宿主帧末 flush 物化。 */
    private final Signal<QzMinerHudModel> model = Signal.create(QzMinerHudModel.EMPTY);

    /** 最近一次发布的模型（客户端主线程独占）：同值不重发，避免入队同值 pendingWrite。 */
    private QzMinerHudModel published = QzMinerHudModel.EMPTY;

    /** 行列表只读视图：订阅点仍是模型 signal，不额外创建 Computed/effect。 */
    private final ReadableSignal<List<QzMinerHudModel.Line>> lines =
            new ReadableSignal<List<QzMinerHudModel.Line>>() {
                @Override
                public List<QzMinerHudModel.Line> get() {
                    return model.get().getLines();
                }
            };

    /** 卡片列表视图：显示门关闭（空模型）时为空列表 → 卡片卸载 → 宿主整窗隐藏。 */
    private final ReadableSignal<List<QzMinerHudModel>> cards =
            new ReadableSignal<List<QzMinerHudModel>>() {
                @Override
                public List<QzMinerHudModel> get() {
                    QzMinerHudModel current = model.get();
                    return current.isEmpty() ? Collections.<QzMinerHudModel>emptyList()
                            : Collections.singletonList(current);
                }
            };

    /**
     * 编辑期预览模型只读视图：忽略显示门（编辑会话里连锁键必然松开），
     * 订阅点仍是模型 signal —— HUD 侧模型变化会驱动预览重算。
     */
    private final ReadableSignal<QzMinerHudModel> previewModel =
            new ReadableSignal<QzMinerHudModel>() {
                @Override
                public QzMinerHudModel get() {
                    model.get();
                    return QzMinerHudModel.translateForPreview(
                            clientState, phaseProjection, previewStateSource);
                }
            };

    /** 预览卡片列表视图：恒一张卡片（预览不跟随显示门，保证拖动命中面始终存在）。 */
    private final ReadableSignal<List<QzMinerHudModel>> previewCards =
            () -> Collections.singletonList(previewModel.get());

    /** 预览行列表只读视图（同 {@link #lines}，数据源换成预览模型）。 */
    private final ReadableSignal<List<QzMinerHudModel.Line>> previewLines =
            () -> previewModel.get().getLines();

    /**
     * 创建窗口工厂（生产入口：预览状态取当前预览控制器）。
     *
     * @param clientState     客户端连锁状态
     * @param phaseProjection 客户端阶段投影
     */
    public QzMinerHudWindow(ChainClientState clientState, ClientPhaseProjection phaseProjection) {
        this(clientState, phaseProjection, new QzMinerHudModel.PreviewStateSource() {
            @Override
            public ChainPreviewState current() {
                return ClientProxy.chainPreviewController == null
                        ? null : ClientProxy.chainPreviewController.getPreviewState();
            }
        });
    }

    QzMinerHudWindow(ChainClientState clientState, ClientPhaseProjection phaseProjection,
            QzMinerHudModel.PreviewStateSource previewStateSource) {
        this.clientState = clientState;
        this.phaseProjection = phaseProjection;
        this.previewStateSource = previewStateSource;
    }

    /**
     * 客户端主线程冲刷一次：业务状态 → 显示模型 → signal。
     *
     * <p>值不变时不写 signal；写侧不 flush，物化交给宿主帧管线（4.9 单一收口）。</p>
     */
    public void refresh() {
        QzMinerHudModel next = QzMinerHudModel.translate(clientState, phaseProjection, previewStateSource);
        if (next.equals(published)) {
            return;
        }
        published = next;
        model.set(next);
    }

    /** @return 最近一次发布的显示模型（测试与诊断探针；客户端主线程读写）。 */
    QzMinerHudModel currentModel() {
        return published;
    }

    /** {@inheritDoc} */
    @Override
    public SceneNode build(SceneRuntime runtime) {
        // 内容根：SHRINK 包裹、无内边距 —— 卡片卸载后尺寸自然为零（宿主整窗隐藏）。
        SceneNode content = SceneNode.column().setHitTestable(false)
                .setWidthSizing(SceneNode.WidthSizing.SHRINK);
        // 单卡片列表：键稳定 → 内容变化复用卡内行列表；空模型 → 卡片卸载。
        runtime.forEach(content, cards, QzMinerHudWindow::cardKey,
                ignored -> buildCard(runtime, lines));
        return content;
    }

    /**
     * 编辑期预览工厂（{@code HudEditTarget.previewFactory}）。
     *
     * <p><b>与 HUD 工厂的关键差异：根可命中。</b>UILib 编辑宿主把拖动 handler 挂在
     * {@code previewFactory.build(rt)} 返回的内容根上（{@code ChatHudEditPreviews} 对
     * {@code layer.content()} 注册 POINTER_DOWN/MOVE/UP/CANCEL）；命中路由只为
     * {@code hitTestable=true} 的节点派发指针事件，因此预览根必须可命中，否则按下事件到不了
     * 拖动 handler，预览「看得见、拖不动」。</p>
     *
     * <p>HUD 工厂的根相反：关闭态 HUD 浮在游戏画面上、不得拦截玩家输入，故恒
     * {@code setHitTestable(false)}（见 {@link #build}）。两者共用同一张卡片的构建代码，
     * 只有「根」的命中性不同——预览多包一层可命中的包裹根作拖动命中面，内部卡片与文本
     * 保持不可命中，只读展示语义不被编辑期改写。</p>
     *
     * <p>预览内容不跟随显示门（{@link QzMinerHudModel#translateForPreview}）：编辑会话在
     * 聊天输入屏里，连锁键松开、阶段 IDLE，照搬显示门会让预览零尺寸而无法拖动。</p>
     *
     * @return 预览内容根工厂（与 {@link HudEditTarget} 的 previewFactory 同契约）
     */
    public HudWindowFactory previewFactory() {
        return runtime -> buildPreviewRoot(runtime);
    }

    /** 预览根：可命中的拖动命中面（包裹根），内部卡片/文本仍不可命中。 */
    private SceneNode buildPreviewRoot(SceneRuntime runtime) {
        SceneNode dragSurface = SceneNode.column().setHitTestable(true)
                .setWidthSizing(SceneNode.WidthSizing.SHRINK);
        runtime.forEach(dragSurface, previewCards, QzMinerHudWindow::cardKey,
                ignored -> buildCard(runtime, previewLines));
        return dragSurface;
    }

    /** @return 单卡片稳定键（卡片节点常驻复用，重建由卡内行列表承担）。 */
    static String cardKey(QzMinerHudModel model) {
        return CARD_KEY;
    }

    /** 构建液态玻璃卡片：公开表面绑定独占外观写入，行内容走传入的 keyed 列表数据源。 */
    private SceneNode buildCard(SceneRuntime runtime,
            ReadableSignal<List<QzMinerHudModel.Line>> lineSource) {
        SceneNode card = SceneNode.column(ROW_GAP_PX).setHitTestable(false)
                .setWidthSizing(SceneNode.WidthSizing.SHRINK)
                .setPadding(CARD_PADDING_Y_PX, CARD_PADDING_X_PX, CARD_PADDING_Y_PX, CARD_PADDING_X_PX);
        // 唯一外观写入者：background/border/cornerRadius/backdrop/surfaceElevation 全归绑定器；
        // 本节点此后不再静态写这些属性（契约 §4 属性归属）。
        SceneSurfaceBinder.bind(runtime, card, cardSurface(runtime), ALWAYS_ENABLED,
                runtime.interactionState(card));
        runtime.forEach(card, lineSource, QzMinerHudModel.Line::contentKey, QzMinerHudWindow::buildLine);
        return card;
    }

    /**
     * 卡片表面配方：主题 {@link SceneTheme.Role#PANEL} 基线 ⊕ 显式液态玻璃滤镜——
     * color 分量（tint/边框/圆角）随主题，滤镜参数由 Miner 钉死。
     */
    private static ReadableSignal<SceneSurfaceStyle> cardSurface(SceneRuntime runtime) {
        return SceneThemes.derivedSurface(runtime, SceneTheme.Role.PANEL,
                style -> style.toBuilder()
                        .backdrop(UiBackdrop.liquidGlass(GLASS_MATERIAL, GLASS_BLUR_PX, GLASS_LENS))
                        .build());
    }

    private static SceneNode buildLine(QzMinerHudModel.Line line) {
        SceneNode row = SceneNode.row(SPAN_GAP_PX).setHitTestable(false)
                .setWidthSizing(SceneNode.WidthSizing.SHRINK);
        for (QzMinerHudModel.Span span : line.getSpans()) {
            row.appendChild(new SceneNode()
                    .setText(span.getText())
                    .setTextColor(colorOf(span.getTone()))
                    .setFontSize(FONT_SIZE_PX)
                    .setHitTestable(false));
        }
        return row;
    }

    /**
     * 语义色调 → UILib HUD 文本 token（4.9 起色调由 scene token 表达，宿主不再有 HUD 专用调色板）。
     *
     * @param tone 片段语义色调
     * @return ARGB 文本色
     */
    static int colorOf(QzMinerHudModel.Tone tone) {
        switch (tone) {
            case MUTED:
                return SceneChromeTokens.HUD_TEXT_MUTED;
            case SUCCESS:
                return SceneChromeTokens.HUD_TEXT_SUCCESS;
            case WARNING:
                return SceneChromeTokens.HUD_TEXT_WARNING;
            case INFO:
            default:
                return SceneChromeTokens.HUD_TEXT_INFO;
        }
    }
}
