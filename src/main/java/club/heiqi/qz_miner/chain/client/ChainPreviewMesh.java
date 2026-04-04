package club.heiqi.qz_miner.chain.client;

/**
 * 预览线框网格数据。
 */
public class ChainPreviewMesh {

    public static final ChainPreviewMesh EMPTY = new ChainPreviewMesh(new float[0], new int[0], 0);

    private final float[] vertices;
    private final int[] indices;
    private final int blockCount;

    /**
     * 创建预览线框网格。
     *
     * @param vertices 顶点数据
     * @param indices 索引数据
     * @param blockCount 方块数量
     */
    public ChainPreviewMesh(float[] vertices, int[] indices, int blockCount) {
        this.vertices = vertices == null ? new float[0] : vertices;
        this.indices = indices == null ? new int[0] : indices;
        this.blockCount = Math.max(0, blockCount);
    }

    public float[] getVertices() {
        return vertices;
    }

    public int[] getIndices() {
        return indices;
    }

    public int getBlockCount() {
        return blockCount;
    }

    public boolean isEmpty() {
        return vertices.length == 0 || indices.length == 0;
    }
}
