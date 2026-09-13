package club.heiqi.qz_miner.chain.client;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 预览条柱网格的不可变 CPU 数据。
 *
 * <p>aAux 布局见 {@link #AUX_BYTES_PER_VERTEX}。顶点按几何 key 去重后，appearOrder 取
 * 所有 incident 写入者的最小值，semanticClass / tubeEdge 取首写者。tubeEdge 可达范围：
 * 相邻链直通格点的 tube 相顶点为 0..3（横截面象限槽位），junction 相与共享顶点保留
 * {@link #AUX_UNDEFINED}；长度不符时语义流降级为不可用，原因见
 * {@link #getAuxDegradationReason()} 与 {@link #getAuxDegradedMeshCount()}。</p>
 */
public class ChainPreviewMesh {

    /** aAux 每顶点字节数：x=semanticClass、y=tubeEdge、z/w=appearOrder（u16 小端）。 */
    public static final int AUX_BYTES_PER_VERTEX = 4;

    /** aAux 中「未定义」的 8 位值（semanticClass / tubeEdge）。 */
    public static final int AUX_UNDEFINED = 255;

    /** appearOrder 的「未定义」16 位值。 */
    public static final int APPEAR_ORDER_UNDEFINED = 0xFFFF;

    /** debug 计数器：构造期因 aAux 长度不符而丢弃语义流的网格累计数。 */
    private static final AtomicLong AUX_DEGRADED_MESHES = new AtomicLong();

    public static final ChainPreviewMesh EMPTY = new ChainPreviewMesh(new float[0], new float[0], new int[0], 0);

    private final float[] vertices;
    private final int vertexFloatCount;
    private final float[] colors;
    private final int colorFloatCount;
    private final byte[] aux;
    private final String auxDegradationReason;
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
        this(vertices, colors, indices, blockCount, null);
    }

    /**
     * 创建带语义顶点流的紧凑预览网格。
     *
     * @param aux 语义顶点流；长度必须为顶点数 × {@link #AUX_BYTES_PER_VERTEX}，null 表示未启用
     */
    public ChainPreviewMesh(float[] vertices, float[] colors, int[] indices, int blockCount, byte[] aux) {
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
            false,
            aux);
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
            boolean truncated,
            byte[] aux) {
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
        int vertexCount = this.vertexFloatCount / 3;
        int requiredAuxBytes = vertexCount * AUX_BYTES_PER_VERTEX;
        if (aux == null) {
            this.aux = null;
            this.auxDegradationReason = null;
        } else if (aux.length != requiredAuxBytes) {
            this.aux = null;
            this.auxDegradationReason = "aAux length " + aux.length
                + " != vertexCount(" + vertexCount + ") * " + AUX_BYTES_PER_VERTEX
                + " = " + requiredAuxBytes;
            AUX_DEGRADED_MESHES.incrementAndGet();
        } else {
            this.aux = aux;
            this.auxDegradationReason = null;
        }
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

    /**
     * @return 语义顶点流副本；null 表示未启用。长度恒为顶点数 × {@link #AUX_BYTES_PER_VERTEX}
     */
    public byte[] getAux() {
        return aux == null ? null : Arrays.copyOf(aux, aux.length);
    }

    public int getAuxByteCount() {
        return aux == null ? 0 : aux.length;
    }

    /** @return 是否携带语义顶点流（null 表示未启用或已降级） */
    public boolean isAuxAvailable() {
        return aux != null;
    }

    /**
     * @return 本网格语义流降级原因；null 表示未降级（正常携带，或调用方未提供 aux）
     */
    public String getAuxDegradationReason() {
        return auxDegradationReason;
    }

    /**
     * @return 累计因 aAux 长度不符而降级（丢弃语义流）的网格数；观察口径：
     *         {@code isAuxAvailable()==false && getAuxDegradationReason()!=null}
     */
    public static long getAuxDegradedMeshCount() {
        return AUX_DEGRADED_MESHES.get();
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

    /**
     * 内部顶点数组只读视图（不拷贝）。
     *
     * <p>仅限渲染线程上传路径使用：禁止修改返回数组，禁止跨帧持有。</p>
     */
    public float[] vertexArray() {
        return vertices;
    }

    /**
     * 内部颜色数组只读视图（不拷贝）。
     *
     * <p>仅限渲染线程上传路径使用：禁止修改返回数组，禁止跨帧持有。</p>
     */
    public float[] colorArray() {
        return colors;
    }

    /**
     * 内部索引数组只读视图（不拷贝）。
     *
     * <p>仅限渲染线程上传路径使用：禁止修改返回数组，禁止跨帧持有。</p>
     */
    public int[] indexArray() {
        return indices;
    }

    /**
     * 内部 aAux 数组只读视图（不拷贝）；null 表示未启用。
     *
     * <p>仅限渲染线程上传路径使用：禁止修改返回数组，禁止跨帧持有。</p>
     */
    public byte[] auxArray() {
        return aux;
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
            truncated,
            aux);
    }

    private static int boundedCount(int count, int arrayLength) {
        return Math.max(0, Math.min(count, arrayLength));
    }
}
