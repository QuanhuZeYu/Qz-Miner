package club.heiqi.qz_miner.client;

import java.util.List;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.uilib.ui.hud.api.HudWindowFactory;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.SceneChromeTokens;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 连锁状态 HUD 虚拟窗口（UILib 4.9 {@link HudWindowFactory} 契约）。
 *
 * <p>窗口挂载时 {@link #build(SceneRuntime)} 用 scene 代码构建一次内容树，此后内容变化一律走
 * signal：{@link #refresh()} 在客户端 tick 末尾把 {@link QzMinerHudModel} 翻译结果写入 signal，
 * 由宿主帧管线物化。Miner 不自渲染、不自建锚定，锚点/安全区/缩放/裁剪全部由 UILib 宿主负责。</p>
 *
 * <p>线程契约（4.9 起成文）：注册、signal 读写只在客户端主线程。本类唯一写入口
 * {@link #refresh()} 由 {@link QzMinerHudTicker} 在 ClientTickEvent.END 调用；宿主未提供输入源，
 * 窗口只读展示。</p>
 *
 * <p>空内容整窗隐藏：显示门关闭时模型为空 → keyed 列表无行 → 内容根尺寸为零 →
 * 宿主连外壳一起隐藏（对齐旧 EMPTY 快照语义，无需 close 或重新注册）。</p>
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
        SceneNode content = SceneNode.column(ROW_GAP_PX).setHitTestable(false);
        // 内容根由 keyed 列表独占：列表为空 → 内容根零尺寸 → 宿主整窗隐藏（含外壳与外接工具栏）。
        runtime.forEach(content, lines, QzMinerHudModel.Line::contentKey, QzMinerHudWindow::buildLine);
        return content;
    }

    private static SceneNode buildLine(QzMinerHudModel.Line line) {
        SceneNode row = SceneNode.row(SPAN_GAP_PX).setHitTestable(false);
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
