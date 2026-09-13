package club.heiqi.qz_miner.chain.client.render;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

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
 *
 * <p>B4.2 口径：peak* 四项是**生命周期内**的容量峰值（顶点数 / 索引数 / aux 字节 / 代级缓存条目数），
 * 逐项取历史最大值，不因单次会话结束而回落；{@link #reset()} 与其它计数一起归零。
 * 生产来源为 {@code GenerationSession.publishCapacityInto(this)}（构建线程采样、消费线程交接），
 * 也可由持有网格的一方直接 {@link #recordCapacity(ChainPreviewMesh, int)}。</p>
 */
public final class ChainPreviewScaleCounters {

    private long rebuilds;
    private long uploads;
    private long frameCaptures;
    private long glIntegerReads;
    private long culledTargets;
    private long cullEvents;
    private long peakVertexCount;
    private long peakIndexCount;
    private long peakAuxBytes;
    private long peakGenerationCacheEntries;

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

    /**
     * 记录一次容量占用（B4.2）：四项分别取历史最大值；负值按 0 处理，不制造无意义峰值。
     *
     * <p>线程契约：与其它计数一致——由计数器的持有者在自己的线程调用，本类不做同步。</p>
     *
     * @param vertexCount            顶点数（{@link ChainPreviewMesh#getVertexCount()}）
     * @param indexCount             索引数（{@link ChainPreviewMesh#getIndexCount()}）
     * @param auxBytes               aux 字节数（{@link ChainPreviewMesh#getAuxByteCount()}）
     * @param generationCacheEntries 代级缓存条目数（B4.1 会话保留的唯一目标数）
     */
    public void recordCapacity(int vertexCount, int indexCount, int auxBytes, int generationCacheEntries) {
        peakVertexCount = Math.max(peakVertexCount, Math.max(0, vertexCount));
        peakIndexCount = Math.max(peakIndexCount, Math.max(0, indexCount));
        peakAuxBytes = Math.max(peakAuxBytes, Math.max(0, auxBytes));
        peakGenerationCacheEntries =
            Math.max(peakGenerationCacheEntries, Math.max(0, generationCacheEntries));
    }

    /**
     * 记录一次容量占用（便捷重载）：网格侧三项从 {@code mesh} 现读，null 网格按 0 处理。
     *
     * @param mesh                   本次构建产物；null 表示无产物
     * @param generationCacheEntries 代级缓存条目数
     */
    public void recordCapacity(ChainPreviewMesh mesh, int generationCacheEntries) {
        recordCapacity(
            mesh == null ? 0 : mesh.getVertexCount(),
            mesh == null ? 0 : mesh.getIndexCount(),
            mesh == null ? 0 : mesh.getAuxByteCount(),
            generationCacheEntries);
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

    /** @return 生命周期内顶点数峰值（B4.2） */
    public long getPeakVertexCount() {
        return peakVertexCount;
    }

    /** @return 生命周期内索引数峰值（B4.2） */
    public long getPeakIndexCount() {
        return peakIndexCount;
    }

    /** @return 生命周期内 aux 字节数峰值（B4.2） */
    public long getPeakAuxBytes() {
        return peakAuxBytes;
    }

    /** @return 生命周期内代级缓存条目数峰值（B4.2） */
    public long getPeakGenerationCacheEntries() {
        return peakGenerationCacheEntries;
    }

    /** @return 诊断文本 */
    public String describe() {
        return "preview.rebuilds=" + rebuilds
            + ", preview.uploads=" + uploads
            + ", preview.frameCaptures=" + frameCaptures
            + ", preview.glIntegerReads=" + glIntegerReads
            + ", preview.culledTargets=" + culledTargets
            + ", preview.cullEvents=" + cullEvents
            + ", preview.peakVertices=" + peakVertexCount
            + ", preview.peakIndices=" + peakIndexCount
            + ", preview.peakAuxBytes=" + peakAuxBytes
            + ", preview.peakGenerationCacheEntries=" + peakGenerationCacheEntries;
    }

    /** 生命周期清理：全部计数与容量峰值归零。 */
    public void reset() {
        rebuilds = 0L;
        uploads = 0L;
        frameCaptures = 0L;
        glIntegerReads = 0L;
        culledTargets = 0L;
        cullEvents = 0L;
        peakVertexCount = 0L;
        peakIndexCount = 0L;
        peakAuxBytes = 0L;
        peakGenerationCacheEntries = 0L;
    }
}
