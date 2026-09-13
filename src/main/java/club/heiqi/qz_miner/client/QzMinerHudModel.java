package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.client.ChainPreviewState.CancelReason;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.TruncationReason;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * 连锁状态 HUD 的不可变显示模型（UILib 4.9 虚拟窗口契约）。
 *
 * <p>职责只到「业务状态 → 语义内容」：行序、稳定行 id、稳定片段 id、文本与语义色调。
 * 本类不持有 scene 节点，也不引用 UILib HUD API——内容树由 {@link QzMinerHudWindow}
 * 在窗口挂载时按本模型构建，内容变化经 signal 驱动（旧行式快照协议已随 4.9 删除）。</p>
 *
 * <p>显示门与旧紧凑 HUD 快照一致：连锁键按下，或阶段处于
 * {@link ChainPhase#PLANNING}/{@link ChainPhase#RUNNING}/{@link ChainPhase#FINISHING}；
 * 门关闭返回 {@link #EMPTY}，内容树零尺寸由宿主整窗隐藏。</p>
 */
public final class QzMinerHudModel {

    /** 片段语义色调（与旧紧凑快照协议的色调语义一一对应；ARGB 映射只发生在渲染层）。 */
    public enum Tone {
        MUTED,
        INFO,
        SUCCESS,
        WARNING
    }

    /** 一个文本片段：稳定 id + 文本 + 语义色调。 */
    public static final class Span {

        private final String id;
        private final String text;
        private final Tone tone;

        Span(String id, String text, Tone tone) {
            this.id = id;
            this.text = text == null ? "" : text;
            this.tone = tone == null ? Tone.MUTED : tone;
        }

        /** @return 片段稳定 id（回归与诊断锚点） */
        public String getId() {
            return id;
        }

        /** @return 片段文本（不含任何旧版样式编码） */
        public String getText() {
            return text;
        }

        /** @return 片段语义色调 */
        public Tone getTone() {
            return tone;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Span)) {
                return false;
            }
            Span span = (Span) other;
            return id.equals(span.id) && text.equals(span.text) && tone == span.tone;
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + text.hashCode();
            result = 31 * result + tone.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return id + "='" + text + "'(" + tone + ")";
        }
    }

    /** 一行内容：稳定行 id + 片段列表 + 内容键。 */
    public static final class Line {

        private final String id;
        private final List<Span> spans;
        private final String contentKey;

        Line(String id, List<Span> spans) {
            this.id = id;
            this.spans = Collections.unmodifiableList(new ArrayList<Span>(spans));
            this.contentKey = buildContentKey(id, this.spans);
        }

        /** @return 行稳定 id（回归与诊断锚点） */
        public String getId() {
            return id;
        }

        /** @return 不可变片段列表（渲染顺序即声明顺序） */
        public List<Span> getSpans() {
            return spans;
        }

        /**
         * keyed scene 列表的内容键：行 id 与全部片段内容共同决定。
         *
         * <p>内容不变 → 键不变（列表按 key 复用既有行节点）；任一片段文本/色调变化 → 键变化
         * （该行重建）。若只用行 id 做键，signal 驱动下列表会复用旧节点而残留旧文本。</p>
         *
         * @return 内容键
         */
        public String contentKey() {
            return contentKey;
        }

        private static String buildContentKey(String id, List<Span> spans) {
            StringBuilder key = new StringBuilder();
            appendPart(key, id);
            for (Span span : spans) {
                appendPart(key, span.getId());
                appendPart(key, span.getText());
                appendPart(key, span.getTone().name());
            }
            return key.toString();
        }

        /** 长度前缀分段编码：任意文本都不会与相邻分段拼出歧义键。 */
        private static void appendPart(StringBuilder key, String part) {
            key.append(part.length()).append(':').append(part);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Line)) {
                return false;
            }
            Line line = (Line) other;
            return id.equals(line.id) && spans.equals(line.spans);
        }

        @Override
        public int hashCode() {
            return 31 * id.hashCode() + spans.hashCode();
        }

        @Override
        public String toString() {
            return id + spans.toString();
        }
    }

    /**
     * 表现投影 header 端口（B1.1）：HUD 只消费投影发布的不可变 O(1) header。
     *
     * <p>装配点未安装投影（{@code ChainPreviewPresentationProjection.installed() == null}）或
     * 尚未发布 header 时返回 null：HUD 不出任何预览行，也不抛异常。</p>
     */
    public interface PresentationHeaderSource {
        /**
         * @return 当前 header；未装配 / 未发布时 null
         */
        ChainPreviewPresentationHeader current();
    }


    /** 空模型：显示门关闭（渲染层据此产生零尺寸内容树 → 宿主整窗隐藏）。 */
    public static final QzMinerHudModel EMPTY = new QzMinerHudModel(Collections.<Line>emptyList());

    private final List<Line> lines;

    private QzMinerHudModel(List<Line> lines) {
        this.lines = Collections.unmodifiableList(new ArrayList<Line>(lines));
    }

    /**
     * 把连锁客户端状态翻译为显示模型（纯函数，headless 可测）。
     *
     * @param clientState    客户端连锁状态
     * @param phaseProjection 客户端阶段投影
     * @param headerSource   表现投影 header 端口（未装配返回 null 的降级实现）
     * @return 不可变显示模型；显示门关闭时为 {@link #EMPTY}
     */
    public static QzMinerHudModel translate(ChainClientState clientState,
            ClientPhaseProjection phaseProjection, PresentationHeaderSource headerSource) {
        if (!isDisplayGateOpen(clientState, phaseProjection)) {
            return EMPTY;
        }
        return new QzMinerHudModel(buildLines(clientState, phaseProjection, headerSource));
    }

    /**
     * 编辑期预览模型：忽略显示门，返回当前设置下的完整卡片内容。
     *
     * <p><b>为什么忽略显示门</b>：显示门表达的是「连锁键按下或执行阶段活跃时才上屏」，
     * 而编辑会话发生在聊天输入屏里——那时连锁键必然松开、阶段为 IDLE，门恒关闭。
     * 预览若照搬显示门就永远零尺寸（不可见、不可命中、不可拖动），编辑入口失去意义。
     * 本方法只服务 UILib 编辑期预览；{@link #translate} 的显示门语义不变。</p>
     *
     * @param clientState    客户端连锁状态
     * @param phaseProjection 客户端阶段投影
     * @param headerSource   表现投影 header 端口（未装配返回 null 的降级实现）
     * @return 当前设置下的显示模型（行集合恒非空）
     */
    public static QzMinerHudModel translateForPreview(ChainClientState clientState,
            ClientPhaseProjection phaseProjection, PresentationHeaderSource headerSource) {
        return new QzMinerHudModel(buildLines(clientState, phaseProjection, headerSource));
    }

    /** 显示门：连锁键按下，或阶段处于 PLANNING/RUNNING/FINISHING。 */
    private static boolean isDisplayGateOpen(ChainClientState clientState,
            ClientPhaseProjection phaseProjection) {
        ChainPhase phase = phaseProjection.getCurrentPhase();
        return clientState.isChainKeyPressed()
                || phase == ChainPhase.PLANNING
                || phase == ChainPhase.RUNNING
                || phase == ChainPhase.FINISHING;
    }

    /** 行集合构建：显示门之外的翻译逻辑由 HUD 与编辑预览共用。 */
    private static List<Line> buildLines(ChainClientState clientState,
            ClientPhaseProjection phaseProjection, PresentationHeaderSource headerSource) {
        ChainPhase phase = phaseProjection.getCurrentPhase();
        List<Line> lines = new ArrayList<Line>();
        ChainMode selectedMode = clientState.getSelectedMode();
        ChainSubMode selectedSubMode = clientState.getSelectedSubMode();
        ChainModeDefinition modeDefinition = ChainModeRegistry.getDefinition(selectedMode);

        lines.add(new Line("status", Arrays.asList(
                labelSpan("status.label", "hud.qz_miner.status.label"),
                span("status.value", statusText(phase), statusTone(phase)))));
        lines.add(new Line("mode", Arrays.asList(
                labelSpan("mode.label", "hud.qz_miner.current_mode.label"),
                span("mode.value", ClientI18n.tr(selectedMode.getDisplayNameKey()), Tone.INFO))));
        if (selectedSubMode != null) {
            lines.add(new Line("sub-mode", Arrays.asList(
                    labelSpan("sub-mode.label", "hud.qz_miner.current_sub_mode.label"),
                    span("sub-mode.value", ClientI18n.tr(selectedSubMode.getDisplayNameKey()), Tone.INFO))));
        }
        lines.add(new Line("chain-config", Arrays.asList(
                labelSpan("chain-config.radius-label", "hud.qz_miner.chain_config.radius.label"),
                span("chain-config.radius-value", clientState.getRequestedChainRadius() + "/"
                        + clientState.getServerChainRadius(), Tone.INFO),
                span("chain-config.separator", ClientI18n.tr("hud.qz_miner.separator") + " ", Tone.MUTED),
                labelSpan("chain-config.blocks-label", "hud.qz_miner.chain_config.blocks.label"),
                span("chain-config.blocks-value", clientState.getRequestedChainMaxBlocks() + "/"
                        + clientState.getServerChainMaxBlocks(), Tone.INFO))));
        lines.add(new Line("server-matched", Arrays.asList(
                labelSpan("server-matched.label", "hud.qz_miner.server_matched.label"),
                span("server-matched.value", String.valueOf(clientState.getServerMatchedTargetCount()),
                        Tone.INFO),
                span("server-matched.unit", ClientI18n.tr("hud.qz_miner.blocks.unit"), Tone.MUTED))));
        boolean groupsConfirmed = clientState.isObjectGroupSyncAccepted();
        lines.add(new Line("object-group-sync", Arrays.asList(
                labelSpan("object-group-sync.label", "hud.qz_miner.object_group_sync.label"),
                span("object-group-sync.state", groupsConfirmed
                        ? ClientI18n.tr("hud.qz_miner.sync.confirmed")
                        : ClientI18n.tr("hud.qz_miner.sync.pending"),
                        groupsConfirmed ? Tone.SUCCESS : Tone.WARNING),
                span("object-group-sync.count-prefix", ClientI18n.tr("hud.qz_miner.count.prefix"),
                        Tone.MUTED),
                span("object-group-sync.count",
                        String.valueOf(clientState.getServerObjectGroups().groups().size()), Tone.INFO),
                span("object-group-sync.count-suffix", ClientI18n.tr("hud.qz_miner.count.suffix"),
                        Tone.MUTED))));

        ChainPreviewPresentationHeader header = headerSource == null ? null : headerSource.current();
        appendPreviewMatchedLine(lines, header);
        appendExecutionProgressLine(lines, header);
        appendTruncationLine(lines, header);
        appendRemoteFailureLine(lines, header);

        if (modeDefinition != null && modeDefinition.shouldShowAreaInfo()) {
            int[] dimensions = resolveAreaDimensions(clientState, modeDefinition, selectedSubMode);
            lines.add(new Line("server-area", Arrays.asList(
                    labelSpan("server-area.label", "hud.qz_miner.server_area.label"),
                    span("server-area.dimensions",
                            dimensions[0] + " x " + dimensions[1] + " x " + dimensions[2], Tone.INFO),
                    span("server-area.equals", " " + ClientI18n.tr("hud.qz_miner.area.equals") + " ",
                            Tone.MUTED),
                    span("server-area.volume",
                            String.valueOf(dimensions[0] * dimensions[1] * dimensions[2]), Tone.INFO),
                    span("server-area.unit", ClientI18n.tr("hud.qz_miner.volume.unit"), Tone.MUTED))));
        }
        return lines;
    }

    /** 「预览已匹配」行：来自投影 header 的 matched/previewCompleted（不读 ChainPreviewState）。 */
    private static void appendPreviewMatchedLine(List<Line> lines, ChainPreviewPresentationHeader header) {
        if (header == null || !header.isPreviewActive()) {
            return;
        }
        boolean completed = header.isPreviewCompleted();
        lines.add(new Line("preview-matched", Arrays.asList(
                labelSpan("preview-matched.label", "hud.qz_miner.preview_matched.label"),
                span("preview-matched.value", String.valueOf(header.getMatchedCount()), Tone.INFO),
                span("preview-matched.unit", ClientI18n.tr("hud.qz_miner.blocks.unit"), Tone.MUTED),
                span("preview-matched.separator", ClientI18n.tr("hud.qz_miner.separator") + " ", Tone.MUTED),
                span("preview-matched.state",
                        ClientI18n.tr(completed
                                ? "hud.qz_miner.preview.completed" : "hud.qz_miner.preview.calculating"),
                        completed ? Tone.SUCCESS : Tone.WARNING))));
    }

    /**
     * 截断行：header 报告截断且投影位 {@code truncationSignalEnabled}
     * （{@code clientPreviewTruncationSignal}，随 header 每 tick 采样）打开时才展示。
     */
    private static void appendTruncationLine(List<Line> lines, ChainPreviewPresentationHeader header) {
        if (header == null || !header.isTruncationSignalEnabled()
                || header.getTruncationReason() == TruncationReason.NONE) {
            return;
        }
        lines.add(new Line("preview-truncated", Arrays.asList(
                labelSpan("preview-truncated.label", "hud.qz_miner.preview.truncated.label"),
                span("preview-truncated.count", ClientI18n.tr("hud.qz_miner.preview.truncated.count",
                        String.valueOf(header.getMatchedCount()),
                        String.valueOf(header.getTotalCount())), Tone.WARNING),
                span("preview-truncated.reason",
                        ClientI18n.tr(truncationReasonKey(header.getTruncationReason())), Tone.WARNING))));
    }

    /**
     * 执行进度行（B5.2）：header 开关打开时展示「已执行 x / 匹配 y」。
     *
     * <p>x 为客户端世界采样确认已破坏的目标位置数（同代单调不减，只统计方块消失为空气的目标）；
     * y 用 header.matchedCount（与「预览已匹配」同源）。开关关闭（默认）不产出该行，
     * 也不会有采样开销（采样由投影侧在开关关闭时短路）。</p>
     */
    private static void appendExecutionProgressLine(List<Line> lines, ChainPreviewPresentationHeader header) {
        if (header == null || !header.isExecutionProgressEnabled()) {
            return;
        }
        lines.add(new Line("preview-execution-progress", Arrays.asList(
                labelSpan("preview-execution-progress.label", "hud.qz_miner.preview.progress.label"),
                span("preview-execution-progress.count",
                        ClientI18n.tr("hud.qz_miner.preview.progress.count",
                                String.valueOf(header.getExecutedCount()),
                                String.valueOf(header.getMatchedCount())),
                        Tone.INFO))));
    }

    /**
     * 远端失败行：{@code cancelReason} 非 NONE 时展示。
     *
     * <p>与截断开关无关——失败反馈是 B0.6 的独立语义；取消会令预览失活但保留原因，
     * 故本行不要求 {@code previewActive}。显示门仍由 {@link #translate} 统一把关。</p>
     */
    private static void appendRemoteFailureLine(List<Line> lines, ChainPreviewPresentationHeader header) {
        if (header == null || header.getCancelReason() == CancelReason.NONE) {
            return;
        }
        lines.add(new Line("preview-remote-failure", Arrays.asList(
                labelSpan("preview-remote-failure.label", "hud.qz_miner.preview.remote.label"),
                span("preview-remote-failure.reason",
                        ClientI18n.tr(cancelReasonKey(header.getCancelReason())), Tone.WARNING))));
    }

    /** 截断原因 → 语言键（调用方保证 reason != NONE）。 */
    private static String truncationReasonKey(TruncationReason reason) {
        if (reason == TruncationReason.HARD_CAP) {
            return "hud.qz_miner.preview.truncated.reason.hard_cap";
        }
        if (reason == TruncationReason.REMOTE_LIMIT) {
            return "hud.qz_miner.preview.truncated.reason.remote_limit";
        }
        return "hud.qz_miner.preview.truncated.reason.max_targets";
    }

    /** 取消原因 → 语言键（调用方保证 reason != NONE）。 */
    private static String cancelReasonKey(CancelReason reason) {
        return reason == CancelReason.REMOTE_UNAVAILABLE
                ? "hud.qz_miner.preview.remote.unavailable"
                : "hud.qz_miner.preview.remote.timeout";
    }

    /** @return 不可变行列表（渲染顺序即声明顺序） */
    public List<Line> getLines() {
        return lines;
    }

    /** @return 是否为空模型（显示门关闭） */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QzMinerHudModel)) {
            return false;
        }
        return lines.equals(((QzMinerHudModel) other).lines);
    }

    @Override
    public int hashCode() {
        return lines.hashCode();
    }

    @Override
    public String toString() {
        return "QzMinerHudModel" + lines;
    }

    private static String statusText(ChainPhase phase) {
        if (phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING) {
            return ClientI18n.tr("hud.qz_miner.status.running");
        }
        if (phase == ChainPhase.PLANNING) {
            return ClientI18n.tr("hud.qz_miner.status.planning");
        }
        return ClientI18n.tr("hud.qz_miner.status.idle");
    }

    private static Tone statusTone(ChainPhase phase) {
        return phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING
                ? Tone.SUCCESS : Tone.WARNING;
    }

    private static Span span(String id, String text, Tone tone) {
        return new Span(id, text, tone);
    }

    private static Span labelSpan(String id, String translationKey) {
        return span(id, ClientI18n.tr(translationKey) + " ", Tone.MUTED);
    }

    private static int[] resolveAreaDimensions(ChainClientState clientState,
            ChainModeDefinition modeDefinition, ChainSubMode subMode) {
        int radius = clientState.getServerChainRadius();
        int[] dimensions = modeDefinition.resolveAreaDimensions(radius, subMode);
        if (dimensions != null) {
            return dimensions;
        }
        int sideLength = radius * 2 + 1;
        return new int[] {sideLength, sideLength, sideLength};
    }
}
