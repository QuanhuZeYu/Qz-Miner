package club.heiqi.qz_miner.chain.client.render;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 预览后端的渲染线程接口，统一管理 GPU 初始化、上传、绘制与释放。
 * 帧内 upload/draw 复用调用方状态围栏，帧外 dispose 自带绑定围栏。
 * 空网格清空绘制范围并保持惰性初始化。
 */
public interface ChainPreviewRenderBackend {

    /** @return 稳定后端 id：legacy | shader */
    String id();

    /** @return 是否使用 CPU 颜色流（legacy=true，shader=false） */
    boolean usesCpuColors();

    /**
     * 惰性初始化；失败记录并返回 false，后续调用沿用失败结果。
     *
     * @return 可以继续上传 / 绘制时为 true
     */
    boolean ensureReady();

    /** 上传拓扑（顶点 / 颜色 / 索引）。 */
    void uploadTopology(ChainPreviewMesh mesh);

    /**
     * 同 topology 的 CPU 颜色效果刷新。
     *
     * @return 是否完成上传（topology 不匹配 / 未初始化时 false）
     */
    boolean uploadColors(ChainPreviewMesh mesh);

    /** 按绘制计划绘制；调用方已完成帧级状态围栏与矩阵平移。 */
    void draw(ChainPreviewDrawPlan plan);

    /** 释放 GPU 资源；帧外入口，自带绑定围栏。 */
    void dispose();

    /** @return 诊断文本（版本、program id、失败原因） */
    String describe();
}
