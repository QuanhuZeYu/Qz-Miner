package club.heiqi.qz_miner.chain.client.render;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * 连锁预览渲染后端契约（预览渲染后端契约（真源：ChainPreviewRenderBackend））。
 *
 * <p>线程契约：{@link #ensureReady()}、{@link #dispose()} 只在渲染线程调用；
 * GPU 释放必须回到渲染线程。</p>
 *
 * <p>状态契约：帧级 GL 绑定围栏由调用方（ChainPreviewRenderer）统一捕获 / 恢复，
 * 实现内部的 upload / draw 不得各自 glGetInteger 捕获绑定；{@link #dispose()} 可能被
 * 帧外生命周期调用，由实现自行围栏。</p>
 *
 * <p>空网格契约：{@link #uploadTopology(ChainPreviewMesh)} 收到空网格等价于清空当前
 * 绘制范围，不得触发 GL 初始化。</p>
 */
public interface ChainPreviewRenderBackend {

    /** @return 稳定后端 id：legacy | shader */
    String id();

    /** @return 是否使用 CPU 颜色流（legacy=true，shader=false） */
    boolean usesCpuColors();

    /**
     * 惰性初始化；失败返回 false 且不抛异常，也不每帧重试。
     *
     * @return 可以继续上传 / 绘制时为 true
     */
    boolean ensureReady();

    /** 上传拓扑（顶点 / 颜色 / 索引）。 */
    void uploadTopology(ChainPreviewMesh mesh);

    /**
     * 同 topology 的距离效果刷新；语义与历史 uploadColors 一致。
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
