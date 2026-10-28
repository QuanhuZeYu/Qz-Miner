package club.heiqi.qz_miner.chain.client.render;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 持有者线程累积预览上传、帧级围栏回读及容量峰值，绘制计划读取标量快照。
 * LOD 计剔除目标数及发生剔除的重建次数，峰值在生命周期内逐项累积，reset 统一归零。
 * 容量由构建线程采样、消费线程交接；本对象采用所属线程串行访问。
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
     * 累计本次构建被 LOD 剔除的目标数，正数同时计一次剔除事件。
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
     * 记录容量占用，四项分别取历史最大值，负输入归零。
     *
     * <p>线程契约：与其它计数一致——由计数器的持有者在自己的线程调用，本类不做同步。</p>
     *
     * @param vertexCount            顶点数（{@link ChainPreviewMesh#getVertexCount()}）
     * @param indexCount             索引数（{@link ChainPreviewMesh#getIndexCount()}）
     * @param auxBytes               aux 字节数（{@link ChainPreviewMesh#getAuxByteCount()}）
     * @param generationCacheEntries 代级会话保留的缓存条目总数
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
