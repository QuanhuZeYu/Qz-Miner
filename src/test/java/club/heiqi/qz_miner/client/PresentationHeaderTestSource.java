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
    private boolean executionProgressEnabled;
    private int executedCount;

    PresentationHeaderTestSource(ChainPreviewState preview, ClientPhaseProjection phaseProjection) {
        this.preview = preview;
        this.phaseProjection = phaseProjection;
    }

    /** 设置投影位（生产来自 ChainPreviewVisualSettings.current()）。 */
    void setTruncationSignalEnabled(boolean enabled) {
        this.truncationSignalEnabled = enabled;
    }

    /** 设置执行进度投影位与已执行计数（B5.2；关闭时应传 0）。 */
    void setExecutionProgress(boolean enabled, int executed) {
        this.executionProgressEnabled = enabled;
        this.executedCount = executed;
    }

    @Override
    public ChainPreviewPresentationHeader current() {
        presentation.sampleAndPublish(preview, null, phaseProjection, 0L, 0L, 0L, 0L, 0L,
                truncationSignalEnabled, executionProgressEnabled, executedCount);
        return presentation.currentHeader();
    }
}
