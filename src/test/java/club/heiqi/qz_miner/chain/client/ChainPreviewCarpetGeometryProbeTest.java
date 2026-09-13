package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * task-45（T48）生产几何有效性探针：真机 61 目标水平地毯（原点 (-70,65,264)、Chebyshev 半径 8、
 * 全部候选位于 y=65 平面）的 CPU 网格**真实坐标**核验。
 *
 * <p>此前所有探针只比 verts/indices 计数与逐字节相等，从未读过顶点坐标；本类直接读世界坐标，
 * 断言：y 向压平（一层地毯）、无坐标塌缩、无零面积面片、竖管格点覆盖，并打印全部量值供真机对照。</p>
 *
 * <p>三个同形变体（都限制在 y=65 平面，镜像真机 matcher 只匹配该层草方块）：
 * <ul>
 *   <li>{@code bfs61}：按生产遍历顺序（6 邻域 BFS + visited 去重 + Chebyshev 半径门）取前 61 格 —— 连续团；</li>
 *   <li>{@code spread61}：在半径 8 的 17×17 平面内均匀取 61 格（含四角）—— 还原「67 格草皮散布、只匹配 61」的形态；</li>
 *   <li>{@code full289}：整张 17×17（参考上界）。</li>
 * </ul></p>
 */
public class ChainPreviewCarpetGeometryProbeTest {

    private static final int ORIGIN_X = -70;
    private static final int ORIGIN_Y = 65;
    private static final int ORIGIN_Z = 264;
    private static final int RADIUS = 8;
    private static final float THICKNESS = 0.045F;
    private static final float CELL = 1.0F;

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 64.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, THICKNESS);
    }

    /** 生产遍历序（y=65 平面内 6 邻域 BFS、visited 去重、Chebyshev 半径 8）的前 count 格。 */
    private static List<ChainTarget> bfsCarpet(int count) {
        ChainTarget origin = new ChainTarget(ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        List<ChainTarget> order = new ArrayList<ChainTarget>();
        Set<Long> visited = new LinkedHashSet<Long>();
        List<ChainTarget> frontier = new ArrayList<ChainTarget>();
        frontier.add(origin);
        visited.add(key(origin));
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};
        while (!frontier.isEmpty() && order.size() < count) {
            List<ChainTarget> next = new ArrayList<ChainTarget>();
            for (ChainTarget current : frontier) {
                if (order.size() >= count) {
                    break;
                }
                order.add(current);
                for (int index = 0; index < 4; index++) {
                    ChainTarget candidate = new ChainTarget(
                        current.getX() + dx[index], ORIGIN_Y, current.getZ() + dz[index]);
                    if (chebyshev(candidate, origin) > RADIUS) {
                        continue;
                    }
                    if (!visited.add(key(candidate))) {
                        continue;
                    }
                    next.add(candidate);
                }
            }
            frontier = next;
        }
        return order;
    }

    /** 半径 8 的 17×17 平面内均匀取 count 格（含四角）：镜像真机稀疏草皮散布形态。 */
    private static List<ChainTarget> spreadCarpet(int count) {
        List<ChainTarget> all = new ArrayList<ChainTarget>();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                all.add(new ChainTarget(ORIGIN_X + x, ORIGIN_Y, ORIGIN_Z + z));
            }
        }
        List<ChainTarget> picked = new ArrayList<ChainTarget>(count);
        for (int index = 0; index < count; index++) {
            int at = (int) Math.round((double) index * (all.size() - 1) / (double) (count - 1));
            picked.add(all.get(at));
        }
        return picked;
    }

    private static List<ChainTarget> fullCarpet() {
        List<ChainTarget> all = new ArrayList<ChainTarget>();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                all.add(new ChainTarget(ORIGIN_X + x, ORIGIN_Y, ORIGIN_Z + z));
            }
        }
        return all;
    }

    private static long key(ChainTarget target) {
        return ((long) target.getX() << 42) ^ ((long) target.getY() << 21) ^ target.getZ();
    }

    private static int chebyshev(ChainTarget a, ChainTarget b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
            Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    @Test
    public void sixtyOneCellCarpetGeometryIsPlanarAndUncollapsed() {
        assertCarpet("bfs61", bfsCarpet(61), 61);
        assertCarpet("spread61", spreadCarpet(61), 61);
        assertCarpet("full289", fullCarpet(), 289);
    }

    /**
     * 竖管 (x,z) 分布与格子拓扑一致：
     * <ul>
     *   <li>61 个**孤立**格 ⇒ 每格 4 根竖管角点（244），与「61 格的 4 角」预期一致；</li>
     *   <li>连通地毯 ⇒ 共享边按既有设计移除，只剩外角（61 格连通团 44、17×17 实心 4）；</li>
     * </ul>
     * 这是既有共享面/共享边语义（非缺陷），真机若为整片草地则应渲染成「外框 + 角柱」。
     */
    @Test
    public void verticalTubeDistributionMatchesCellTopology() {
        GeometryStats sparse = analyze(build(spreadCarpet(61)));
        Assert.assertEquals("61 个孤立格必须各有 4 根竖管角点",
            61 * 4, sparse.verticalCorners.size());
        Assert.assertEquals("每个竖管角点 4 个面", 61 * 4 * 4, sparse.verticalFaces);
        Assert.assertEquals("孤立格竖管 x 跨度（±8 格）", 18, sparse.verticalXSpan);
        Assert.assertEquals("孤立格竖管 z 跨度（±8 格）", 18, sparse.verticalZSpan);

        GeometryStats blob = analyze(build(bfsCarpet(61)));
        Assert.assertTrue("连通地毯的竖管角点必须少于孤立情形（共享边移除）: "
            + blob.verticalCorners.size(), blob.verticalCorners.size() < 61 * 4);
        Assert.assertTrue("连通地毯仍有边界角柱: " + blob.verticalCorners.size(),
            blob.verticalCorners.size() >= 4);

        GeometryStats full = analyze(build(fullCarpet()));
        Assert.assertEquals("17×17 实心地毯只剩 4 个外角竖管", 4, full.verticalCorners.size());
    }

    private static void assertCarpet(String label, List<ChainTarget> targets, int expectedCells) {
        ChainPreviewMesh mesh = build(targets);
        GeometryStats stats = analyze(mesh);
        System.out.println("[t48-carpet] variant=" + label + " cells=" + expectedCells
            + " verts=" + mesh.getVertexCount()
            + " quads=" + stats.quadCount
            + " bboxX=[" + fmt(stats.minX) + "," + fmt(stats.maxX) + "]"
            + " bboxY=[" + fmt(stats.minY) + "," + fmt(stats.maxY) + "]"
            + " bboxZ=[" + fmt(stats.minZ) + "," + fmt(stats.maxZ) + "]"
            + " extent=[" + fmt(stats.maxX - stats.minX) + ","
            + fmt(stats.maxY - stats.minY) + "," + fmt(stats.maxZ - stats.minZ) + "]"
            + " uniqueXZ=" + stats.uniqueXZ
            + " latticeCorners=" + stats.latticeCorners.size()
            + " verticalFaces=" + stats.verticalFaces
            + " verticalCorners=" + stats.verticalCorners.size()
            + " degenerate=" + stats.degenerateQuads + "/" + stats.quadCount
            + " origin=" + mesh.getOriginX() + "," + mesh.getOriginY() + "," + mesh.getOriginZ());

        Assert.assertEquals(label + " 目标必须互异", expectedCells, uniqueCells(targets));
        Assert.assertTrue(label + " y 向必须压平（一层地毯）: " + (stats.maxY - stats.minY),
            stats.maxY - stats.minY <= CELL + 0.1);
        Assert.assertTrue(label + " 坐标不得塌缩到 ±1 格: extent=("
            + (stats.maxX - stats.minX) + "," + (stats.maxZ - stats.minZ) + ")",
            (stats.maxX - stats.minX) > 1.5 && (stats.maxZ - stats.minZ) > 1.5);
        Assert.assertTrue(label + " x/z 跨度不得超过直径+厚度: "
            + (stats.maxX - stats.minX) + "," + (stats.maxZ - stats.minZ),
            (stats.maxX - stats.minX) <= 2 * RADIUS + CELL + 2 * THICKNESS + 0.01
                && (stats.maxZ - stats.minZ) <= 2 * RADIUS + CELL + 2 * THICKNESS + 0.01);
        Assert.assertEquals(label + " 不得有零面积面片", 0, stats.degenerateQuads);
    }

    private static int uniqueCells(List<ChainTarget> targets) {
        Set<Long> unique = new LinkedHashSet<Long>();
        for (ChainTarget target : targets) {
            unique.add(key(target));
        }
        return unique.size();
    }

    private static ChainPreviewMesh build(List<ChainTarget> chronological) {
        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(snapshot);
        return new ChainPreviewMeshBuilder().build(snapshot, visuals(), THICKNESS, null);
    }

    private static String fmt(float value) {
        return String.format(java.util.Locale.ROOT, "%.4f", Float.valueOf(value));
    }

    // ------------------------------------------------------------------ 几何统计

    private static GeometryStats analyze(ChainPreviewMesh mesh) {
        GeometryStats stats = new GeometryStats();
        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        float originX = mesh.getOriginX();
        float originY = mesh.getOriginY();
        float originZ = mesh.getOriginZ();
        stats.minX = Float.MAX_VALUE;
        stats.minY = Float.MAX_VALUE;
        stats.minZ = Float.MAX_VALUE;
        stats.maxX = Float.NEGATIVE_INFINITY;
        stats.maxY = Float.NEGATIVE_INFINITY;
        stats.maxZ = Float.NEGATIVE_INFINITY;

        Set<Long> uniqueXZ = new LinkedHashSet<Long>();
        int vertexCount = mesh.getVertexCount();
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            float x = originX + vertices[vertex * 3];
            float y = originY + vertices[vertex * 3 + 1];
            float z = originZ + vertices[vertex * 3 + 2];
            stats.minX = Math.min(stats.minX, x);
            stats.maxX = Math.max(stats.maxX, x);
            stats.minY = Math.min(stats.minY, y);
            stats.maxY = Math.max(stats.maxY, y);
            stats.minZ = Math.min(stats.minZ, z);
            stats.maxZ = Math.max(stats.maxZ, z);
            uniqueXZ.add(xzKey(x, z));
            stats.latticeCorners.add(cornerKey(Math.round(x), Math.round(z)));
        }
        stats.uniqueXZ = uniqueXZ.size();

        stats.quadCount = indices.length / 4;
        for (int quad = 0; quad < stats.quadCount; quad++) {
            float[] cx = new float[4];
            float[] cy = new float[4];
            float[] cz = new float[4];
            for (int corner = 0; corner < 4; corner++) {
                int vertex = indices[quad * 4 + corner];
                cx[corner] = originX + vertices[vertex * 3];
                cy[corner] = originY + vertices[vertex * 3 + 1];
                cz[corner] = originZ + vertices[vertex * 3 + 2];
            }
            if (area(cx, cy, cz) < 1.0E-9F) {
                stats.degenerateQuads++;
            }
            float xSpan = span(cx);
            float ySpan = span(cy);
            float zSpan = span(cz);
            if (ySpan > 0.5F && xSpan <= 0.05F && zSpan <= 0.05F) {
                // 竖管面：一条横向轴恒定、另一条只有厚度宽、y 向跨 1 格
                stats.verticalFaces++;
                if (xSpan <= zSpan) {
                    stats.verticalCorners.add(cornerKey(
                        Math.round(cx[0]), Math.round((min(cz) + max(cz)) * 0.5F)));
                } else {
                    stats.verticalCorners.add(cornerKey(
                        Math.round((min(cx) + max(cx)) * 0.5F), Math.round(cz[0])));
                }
            }
        }
        stats.verticalXSpan = latticeSpan(stats.verticalCorners, true);
        stats.verticalZSpan = latticeSpan(stats.verticalCorners, false);
        return stats;
    }

    private static float min(float[] values) {
        float min = Float.MAX_VALUE;
        for (float value : values) {
            min = Math.min(min, value);
        }
        return min;
    }

    private static float max(float[] values) {
        float max = Float.NEGATIVE_INFINITY;
        for (float value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static float span(float[] values) {
        return max(values) - min(values);
    }

    /** 面片面积（两三角面积和）。 */
    private static float area(float[] x, float[] y, float[] z) {
        double area = 0.0D;
        for (int corner = 1; corner < 3; corner++) {
            area += triangleArea(x[0], y[0], z[0], x[corner], y[corner], z[corner],
                x[corner + 1], y[corner + 1], z[corner + 1]);
        }
        return (float) area;
    }

    private static double triangleArea(
            float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz) {
        double ux = bx - ax;
        double uy = by - ay;
        double uz = bz - az;
        double vx = cx - ax;
        double vy = cy - ay;
        double vz = cz - az;
        double crossX = uy * vz - uz * vy;
        double crossY = uz * vx - ux * vz;
        double crossZ = ux * vy - uy * vx;
        return 0.5D * Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
    }

    private static long xzKey(float x, float z) {
        long xi = Math.round(x * 10000.0F);
        long zi = Math.round(z * 10000.0F);
        return (xi << 32) ^ (zi & 0xFFFFFFFFL);
    }

    private static long cornerKey(long x, long z) {
        return (x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int latticeSpan(Set<Long> corners, boolean xAxis) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long corner : corners) {
            long raw = corner.longValue();
            long value = xAxis ? (raw >> 32) : ((int) (raw & 0xFFFFFFFFL));
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (min > max) {
            return 0;
        }
        return (int) (max - min + 1);
    }

    private static final class GeometryStats {
        private float minX;
        private float minY;
        private float minZ;
        private float maxX;
        private float maxY;
        private float maxZ;
        private int quadCount;
        private int uniqueXZ;
        private int verticalFaces;
        private final Set<Long> latticeCorners = new LinkedHashSet<Long>();
        private final Set<Long> verticalCorners = new LinkedHashSet<Long>();
        private int verticalXSpan;
        private int verticalZSpan;
        private int degenerateQuads;
    }
}
