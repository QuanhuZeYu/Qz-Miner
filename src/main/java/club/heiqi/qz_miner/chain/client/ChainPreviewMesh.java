package club.heiqi.qz_miner.chain.client;

import java.util.Arrays;

/**
 * 预览条柱网格的不可变 CPU 数据。
 */
public class ChainPreviewMesh {

    public static final ChainPreviewMesh EMPTY = new ChainPreviewMesh(new float[0], new float[0], new int[0], 0);

    private final float[] vertices;
    private final int vertexFloatCount;
    private final float[] colors;
    private final int colorFloatCount;
    private final int[] indices;
    private final int indexCount;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final int blockCount;
    private final boolean truncated;

    /**
     * 创建紧凑预览网格。
     *
     * @param vertices 顶点数据
     * @param colors 顶点颜色数据
     * @param indices 索引数据
     * @param blockCount 方块数量
     */
    public ChainPreviewMesh(float[] vertices, float[] colors, int[] indices, int blockCount) {
        this(
            vertices,
            vertices == null ? 0 : vertices.length,
            colors,
            colors == null ? 0 : colors.length,
            indices,
            indices == null ? 0 : indices.length,
            0,
            0,
            0,
            blockCount,
            false);
    }

    ChainPreviewMesh(
            float[] vertices,
            int vertexFloatCount,
            float[] colors,
            int colorFloatCount,
            int[] indices,
            int indexCount,
            int originX,
            int originY,
            int originZ,
            int blockCount,
            boolean truncated) {
        this.vertices = vertices == null ? new float[0] : vertices;
        this.vertexFloatCount = boundedCount(vertexFloatCount, this.vertices.length);
        this.colors = colors == null ? new float[0] : colors;
        this.colorFloatCount = boundedCount(colorFloatCount, this.colors.length);
        this.indices = indices == null ? new int[0] : indices;
        this.indexCount = boundedCount(indexCount, this.indices.length);
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.blockCount = Math.max(0, blockCount);
        this.truncated = truncated;
    }

    public float[] getVertices() {
        return Arrays.copyOf(vertices, vertexFloatCount);
    }

    public float[] getColors() {
        return Arrays.copyOf(colors, colorFloatCount);
    }

    public int[] getIndices() {
        return Arrays.copyOf(indices, indexCount);
    }

    public int getVertexFloatCount() {
        return vertexFloatCount;
    }

    public int getColorFloatCount() {
        return colorFloatCount;
    }

    public int getIndexCount() {
        return indexCount;
    }

    public int getBlockCount() {
        return blockCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int getOriginX() {
        return originX;
    }

    public int getOriginY() {
        return originY;
    }

    public int getOriginZ() {
        return originZ;
    }

    public boolean isRecolorable() {
        return vertexFloatCount % 3 == 0
            && colorFloatCount % 4 == 0
            && vertexFloatCount / 3 == colorFloatCount / 4;
    }

    public boolean isEmpty() {
        return vertexFloatCount == 0 || colorFloatCount == 0 || indexCount == 0;
    }

    float[] vertexArray() {
        return vertices;
    }

    float[] colorArray() {
        return colors;
    }

    int[] indexArray() {
        return indices;
    }

    ChainPreviewMesh withColors(float[] nextColors, int nextColorFloatCount) {
        return new ChainPreviewMesh(
            vertices,
            vertexFloatCount,
            nextColors,
            nextColorFloatCount,
            indices,
            indexCount,
            originX,
            originY,
            originZ,
            blockCount,
            truncated);
    }

    private static int boundedCount(int count, int arrayLength) {
        return Math.max(0, Math.min(count, arrayLength));
    }
}
