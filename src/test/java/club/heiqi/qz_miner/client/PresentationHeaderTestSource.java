package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationHeader;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;

/**
 * 测试用表现投影 header 端口：每次读取都把给定 {@link ChainPreviewState} 采样进一个真实
 * {@link ChainPreviewPresentationProjection}，模拟生产装配点每 tick 的 sampleAndPublish。
 */
final class PresentationHeaderTestSource implements QzMinerHudModel.PresentationHeaderSource {

    private final ChainPreviewState preview;
    private final ClientPhaseProjection phaseProjection;
    private final ChainPreviewPresentationProjection presentation = new ChainPreviewPresentationProjection();
    private boolean truncationSignalEnabled;

    PresentationHeaderTestSource(ChainPreviewState preview, ClientPhaseProjection phaseProjection) {
        this.preview = preview;
        this.phaseProjection = phaseProjection;
    }

    /** 设置投影位（生产来自 ChainPreviewVisualSettings.current()）。 */
    void setTruncationSignalEnabled(boolean enabled) {
        this.truncationSignalEnabled = enabled;
    }

    @Override
    public ChainPreviewPresentationHeader current() {
        presentation.sampleAndPublish(preview, null, phaseProjection, 0L, 0L, 0L, 0L, 0L,
                truncationSignalEnabled);
        return presentation.currentHeader();
    }
}
