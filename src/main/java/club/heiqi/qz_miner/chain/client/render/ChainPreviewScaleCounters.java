package club.heiqi.qz_miner.chain.client.render;

/**
 * 预览规模计数器：拓扑重建与 GPU 上传次数（只允许在渲染线程递增）。
 *
 * <p>数值由 {@link ChainPreviewDrawPlan#derive} 快照进绘制计划，供 HUD / 真机验收读数。</p>
 */
public final class ChainPreviewScaleCounters {

    private long rebuilds;
    private long uploads;

    /** 记录一次非空拓扑落地上传（一次重建 = 一次拓扑上传 + 一次上传）。 */
    public void recordTopologyUpload() {
        rebuilds++;
        uploads++;
    }

    /** 记录一次仅颜色流上传（同 topology 的相机效果刷新）。 */
    public void recordColorUpload() {
        uploads++;
    }

    /** @return 累计非空拓扑上传次数 */
    public long getRebuilds() {
        return rebuilds;
    }

    /** @return 累计上传尝试次数（拓扑 + 颜色流） */
    public long getUploads() {
        return uploads;
    }

    /** 生命周期清理：计数归零。 */
    public void reset() {
        rebuilds = 0L;
        uploads = 0L;
    }
}
