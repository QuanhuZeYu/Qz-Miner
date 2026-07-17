package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.uilib.ui.hud.api.HudLine;
import club.heiqi.uilib.ui.hud.api.HudSnapshot;
import club.heiqi.uilib.ui.hud.api.HudSnapshotProvider;
import club.heiqi.uilib.ui.hud.api.HudSpan;
import club.heiqi.uilib.ui.hud.api.HudTone;

/** 将连锁客户端状态组装为 UILib 紧凑 HUD 的不可变快照。 */
public final class QzMinerHudSnapshotProvider implements HudSnapshotProvider {

    /** 提供当前预览状态，隔离 HUD 快照与渲染控制器生命周期。 */
    interface PreviewStateSource {
        ChainPreviewState current();
    }

    private final ChainClientState clientState;
    private final ClientPhaseProjection phaseProjection;
    private final PreviewStateSource previewStateSource;

    /**
     * 创建连锁 HUD 快照提供器。
     *
     * @param clientState 客户端连锁状态
     * @param phaseProjection 客户端阶段投影
     */
    public QzMinerHudSnapshotProvider(ChainClientState clientState, ClientPhaseProjection phaseProjection) {
        this(clientState, phaseProjection, new PreviewStateSource() {
            @Override
            public ChainPreviewState current() {
                return ClientProxy.chainPreviewController == null
                        ? null : ClientProxy.chainPreviewController.getPreviewState();
            }
        });
    }

    QzMinerHudSnapshotProvider(ChainClientState clientState, ClientPhaseProjection phaseProjection,
            PreviewStateSource previewStateSource) {
        this.clientState = clientState;
        this.phaseProjection = phaseProjection;
        this.previewStateSource = previewStateSource;
    }

    /** {@inheritDoc} */
    @Override
    public HudSnapshot snapshot() {
        ChainPhase phase = phaseProjection.getCurrentPhase();
        if (!clientState.isChainKeyPressed()
                && phase != ChainPhase.PLANNING
                && phase != ChainPhase.RUNNING
                && phase != ChainPhase.FINISHING) {
            return HudSnapshot.EMPTY;
        }

        List<HudLine> lines = new ArrayList<HudLine>();
        ChainMode selectedMode = clientState.getSelectedMode();
        ChainSubMode selectedSubMode = clientState.getSelectedSubMode();
        ChainModeDefinition modeDefinition = ChainModeRegistry.getDefinition(selectedMode);

        lines.add(HudLine.rich("status",
                labelSpan("status.label", "hud.qz_miner.status.label"),
                span("status.value", statusText(phase), statusTone(phase))));
        lines.add(HudLine.rich("mode",
                labelSpan("mode.label", "hud.qz_miner.current_mode.label"),
                span("mode.value", ClientI18n.tr(selectedMode.getDisplayNameKey()), HudTone.INFO)));
        if (selectedSubMode != null) {
            lines.add(HudLine.rich("sub-mode",
                    labelSpan("sub-mode.label", "hud.qz_miner.current_sub_mode.label"),
                    span("sub-mode.value", ClientI18n.tr(selectedSubMode.getDisplayNameKey()), HudTone.INFO)));
        }
        lines.add(HudLine.rich("chain-config",
                labelSpan("chain-config.radius-label", "hud.qz_miner.chain_config.radius.label"),
                span("chain-config.radius-value", clientState.getRequestedChainRadius() + "/"
                        + clientState.getServerChainRadius(), HudTone.INFO),
                span("chain-config.separator", ClientI18n.tr("hud.qz_miner.separator") + " ", HudTone.MUTED),
                labelSpan("chain-config.blocks-label", "hud.qz_miner.chain_config.blocks.label"),
                span("chain-config.blocks-value", clientState.getRequestedChainMaxBlocks() + "/"
                        + clientState.getServerChainMaxBlocks(), HudTone.INFO)));
        lines.add(HudLine.rich("server-matched",
                labelSpan("server-matched.label", "hud.qz_miner.server_matched.label"),
                span("server-matched.value", String.valueOf(clientState.getServerMatchedTargetCount()), HudTone.INFO),
                span("server-matched.unit", ClientI18n.tr("hud.qz_miner.blocks.unit"), HudTone.MUTED)));
        boolean groupsConfirmed = clientState.isObjectGroupSyncAccepted();
        lines.add(HudLine.rich("object-group-sync",
                labelSpan("object-group-sync.label", "hud.qz_miner.object_group_sync.label"),
                span("object-group-sync.state", groupsConfirmed
                        ? ClientI18n.tr("hud.qz_miner.sync.confirmed")
                        : ClientI18n.tr("hud.qz_miner.sync.pending"),
                        groupsConfirmed ? HudTone.SUCCESS : HudTone.WARNING),
                span("object-group-sync.count-prefix", ClientI18n.tr("hud.qz_miner.count.prefix"), HudTone.MUTED),
                span("object-group-sync.count", String.valueOf(clientState.getServerObjectGroups().groups().size()),
                        HudTone.INFO),
                span("object-group-sync.count-suffix", ClientI18n.tr("hud.qz_miner.count.suffix"), HudTone.MUTED)));

        ChainPreviewState previewState = clientState.isPreviewActive() ? previewStateSource.current() : null;
        if (previewState != null) {
            String stateText = previewState.isCompleted()
                    ? ClientI18n.tr("hud.qz_miner.preview.completed")
                    : ClientI18n.tr("hud.qz_miner.preview.calculating");
            lines.add(HudLine.rich("preview-matched",
                    labelSpan("preview-matched.label", "hud.qz_miner.preview_matched.label"),
                    span("preview-matched.value", String.valueOf(previewState.getMatchedCount()), HudTone.INFO),
                    span("preview-matched.unit", ClientI18n.tr("hud.qz_miner.blocks.unit"), HudTone.MUTED),
                    span("preview-matched.separator", ClientI18n.tr("hud.qz_miner.separator") + " ", HudTone.MUTED),
                    span("preview-matched.state", stateText,
                            previewState.isCompleted() ? HudTone.SUCCESS : HudTone.WARNING)));
        }

        if (modeDefinition != null && modeDefinition.shouldShowAreaInfo()) {
            int[] dimensions = resolveAreaDimensions(modeDefinition, selectedSubMode);
            lines.add(HudLine.rich("server-area",
                    labelSpan("server-area.label", "hud.qz_miner.server_area.label"),
                    span("server-area.dimensions", dimensions[0] + " x " + dimensions[1] + " x " + dimensions[2],
                            HudTone.INFO),
                    span("server-area.equals", " " + ClientI18n.tr("hud.qz_miner.area.equals") + " ", HudTone.MUTED),
                    span("server-area.volume", String.valueOf(dimensions[0] * dimensions[1] * dimensions[2]),
                            HudTone.INFO),
                    span("server-area.unit", ClientI18n.tr("hud.qz_miner.volume.unit"), HudTone.MUTED)));
        }
        return HudSnapshot.of(lines);
    }

    private String statusText(ChainPhase phase) {
        if (phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING) {
            return ClientI18n.tr("hud.qz_miner.status.running");
        }
        if (phase == ChainPhase.PLANNING) {
            return ClientI18n.tr("hud.qz_miner.status.planning");
        }
        return ClientI18n.tr("hud.qz_miner.status.idle");
    }

    private HudTone statusTone(ChainPhase phase) {
        return phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING
                ? HudTone.SUCCESS : HudTone.WARNING;
    }

    private HudSpan span(String id, String text, HudTone tone) {
        return new HudSpan(id, text, tone);
    }

    private HudSpan labelSpan(String id, String translationKey) {
        return span(id, ClientI18n.tr(translationKey) + " ", HudTone.MUTED);
    }

    private int[] resolveAreaDimensions(ChainModeDefinition modeDefinition, ChainSubMode subMode) {
        int radius = clientState.getServerChainRadius();
        int[] dimensions = modeDefinition.resolveAreaDimensions(radius, subMode);
        if (dimensions != null) {
            return dimensions;
        }
        int sideLength = radius * 2 + 1;
        return new int[] {sideLength, sideLength, sideLength};
    }
}
