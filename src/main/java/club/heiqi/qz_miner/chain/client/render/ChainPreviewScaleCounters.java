package club.heiqi.qz_miner.chain.client.render;

/**
 * 预览规模计数器：拓扑重建、GPU 上传与帧级 GL 回读（只允许在渲染线程递增）。
 *
 * <p>rebuilds / uploads / culledTargets 由 {@link ChainPreviewDrawPlan} 快照进绘制计划，供 HUD / 真机验收读数；
 * frameCaptures / glIntegerReads 是 B0.4 帧级围栏的自检口径（一次捕获 = 3 次 glGetInteger，
 * 见 {@link ChainPreviewGlBindings#CAPTURED_QUERY_COUNT}），供诊断输出使用。</p>
 *
 * <p>B2.4 口径：culledTargets 为跨重建累计的剔除**目标（条柱）数**（每次拓扑上传把该网格的剔除数累加），
 * cullEvents 为发生剔除（culled &gt; 0）的重建次数；lod=off 时两者恒不增长。
 * 不用 quad 口径：被剔除目标的真实 quad 数必须先生成才能精确，估算值不进计划（Lead 裁定）。</p>
 */
public final class ChainPreviewScaleCounters {

    private long rebuilds;
    private long uploads;
    private long frameCaptures;
    private long glIntegerReads;
    private long culledTargets;
    private long cullEvents;

    /** 记录一次非空拓扑落地上传（一次重建 = 一次拓扑上传 + 一次上传）。 */
    public void recordTopologyUpload() {
        rebuilds++;
        uploads++;
    }

    /** 记录一次仅颜色流上传（同 topology 的相机效果刷新）。 */
    public void recordColorUpload() {
        uploads++;
    }

    /**
     * 按同代刷新决策记录计数（GL 无关，与 renderer 分派共用，保证 headless 断言覆盖生产路径）：
     * TOPOLOGY 仅在非空网格时计一次重建 + 一次上传，COLORS 计一次上传，NONE 不计。
     *
     * @param upload       同代刷新决策结果
     * @param nonEmptyMesh 本次上传的网格是否非空（空网格 = 清空，不计重建）
     */
    public void record(ChainPreviewRefreshDecision.Upload upload, boolean nonEmptyMesh) {
        if (upload == ChainPreviewRefreshDecision.Upload.TOPOLOGY) {
            if (nonEmptyMesh) {
                recordTopologyUpload();
            }
        } else if (upload == ChainPreviewRefreshDecision.Upload.COLORS) {
            recordColorUpload();
        }
    }

    /**
     * 记录一次构建期 LOD / alpha 剔除（B2.4）：累计剔除**目标（条柱）数**；culledTargetCount &lt;= 0 时不计。
     *
     * @param culledTargetCount 本次重建被剔除的目标数（lod=off 传 0）
     */
    public void recordCulled(int culledTargetCount) {
        if (culledTargetCount <= 0) {
            return;
        }
        culledTargets += culledTargetCount;
        cullEvents++;
    }

    /** 记录一次帧级绑定捕获（等价 3 次 glGetInteger）。 */
    public void recordBindingCapture() {
        frameCaptures++;
        glIntegerReads += ChainPreviewGlBindings.CAPTURED_QUERY_COUNT;
    }

    /** @return 累计非空拓扑上传次数 */
    public long getRebuilds() {
        return rebuilds;
    }

    /** @return 累计上传尝试次数（拓扑 + 颜色流） */
    public long getUploads() {
        return uploads;
    }

    /** @return 累计帧级绑定捕获次数 */
    public long getFrameCaptures() {
        return frameCaptures;
    }

    /** @return 累计 glGetInteger 回读次数（帧级围栏口径，不含后端自身回读） */
    public long getGlIntegerReads() {
        return glIntegerReads;
    }

    /** @return 累计被剔除的目标（条柱）数（B2.4） */
    public long getCulledTargets() {
        return culledTargets;
    }

    /** @return 发生剔除的重建次数（culled &gt; 0） */
    public long getCullEvents() {
        return cullEvents;
    }

    /** @return 诊断文本 */
    public String describe() {
        return "preview.rebuilds=" + rebuilds
            + ", preview.uploads=" + uploads
            + ", preview.frameCaptures=" + frameCaptures
            + ", preview.glIntegerReads=" + glIntegerReads
            + ", preview.culledTargets=" + culledTargets
            + ", preview.cullEvents=" + cullEvents;
    }

    /** 生命周期清理：计数归零。 */
    public void reset() {
        rebuilds = 0L;
        uploads = 0L;
        frameCaptures = 0L;
        glIntegerReads = 0L;
        culledTargets = 0L;
        cullEvents = 0L;
    }
}
