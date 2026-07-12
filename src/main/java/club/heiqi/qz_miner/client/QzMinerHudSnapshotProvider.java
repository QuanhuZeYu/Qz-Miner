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

        lines.add(HudLine.text("status", statusText(phase), statusTone(phase)));
        lines.add(HudLine.text("mode", ClientI18n.tr(
                "hud.qz_miner.current_mode", ClientI18n.tr(selectedMode.getDisplayNameKey())), HudTone.MUTED));
        if (selectedSubMode != null) {
            lines.add(HudLine.text("sub-mode", ClientI18n.tr(
                    "hud.qz_miner.current_sub_mode", ClientI18n.tr(selectedSubMode.getDisplayNameKey())),
                    HudTone.MUTED));
        }
        lines.add(HudLine.text("chain-config", ClientI18n.tr(
                "hud.qz_miner.chain_config",
                clientState.getRequestedChainRadius(),
                clientState.getServerChainRadius(),
                clientState.getRequestedChainMaxBlocks(),
                clientState.getServerChainMaxBlocks()), HudTone.MUTED));
        lines.add(HudLine.text("server-matched", ClientI18n.tr(
                "hud.qz_miner.server_matched", clientState.getServerMatchedTargetCount()), HudTone.MUTED));
        lines.add(HudLine.text("object-group-sync", ClientI18n.tr(
                "hud.qz_miner.object_group_sync",
                clientState.isObjectGroupSyncAccepted()
                        ? ClientI18n.tr("hud.qz_miner.sync.confirmed")
                        : ClientI18n.tr("hud.qz_miner.sync.pending"),
                clientState.getServerObjectGroups().groups().size()), HudTone.MUTED));

        ChainPreviewState previewState = clientState.isPreviewActive() ? previewStateSource.current() : null;
        if (previewState != null) {
            String suffix = previewState.isCompleted()
                    ? ClientI18n.tr("hud.qz_miner.preview.completed")
                    : ClientI18n.tr("hud.qz_miner.preview.calculating");
            lines.add(HudLine.text("preview-matched", ClientI18n.tr(
                    "hud.qz_miner.preview_matched", previewState.getMatchedCount()) + " " + suffix,
                    previewState.isCompleted() ? HudTone.SUCCESS : HudTone.WARNING));
        }

        if (modeDefinition != null && modeDefinition.shouldShowAreaInfo()) {
            int[] dimensions = resolveAreaDimensions(modeDefinition, selectedSubMode);
            lines.add(HudLine.text("server-area", ClientI18n.tr(
                    "hud.qz_miner.server_area",
                    dimensions[0], dimensions[1], dimensions[2],
                    dimensions[0] * dimensions[1] * dimensions[2]), HudTone.MUTED));
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
