package club.heiqi.qz_miner.chain.client;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T49 离线几何探针：用**模拟数据**复现真机生产规模（半径 8 / 1024 目标），把 CPU 网格的
 * 顶点、包围盒、索引上界全部落盘，供离线光栅化与真机探针对照。
 *
 * <p>为什么需要它：真机表型是「整链塌缩成一个面」。真机探针只能证明「首个顶点正确、属性布局正确、
 * MVP 正确」，无法回答「这份几何本身是否塌缩」——本类在纯 JVM 内回答这个问题，并把顶点 dump 出来，
 * 供离线端用真机日志里的 P/MV 复算「应该看到什么」。</p>
 *
 * <p>输出：{@code build/t49-offline/vertices.csv}（相对 origin 的局部坐标）、
 * {@code meta.txt}（origin / 规模 / 包围盒 / 索引上界）。</p>
 */
public class T49OfflineGeometryDumpTest {

    private static final int ORIGIN_X = 1;
    private static final int ORIGIN_Y = 6;
    private static final int ORIGIN_Z = 0;
    private static final int RADIUS = 8;
    private static final int TARGETS = 1024;
    private static final float THICKNESS = 0.045F;

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 64.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, THICKNESS);
    }

    /** 与真机同形：从 origin 出发 6 邻域 BFS、Chebyshev 半径 8、取前 1024 个不同格。 */
    private static List<ChainTarget> bfsVolume(int count, int radius) {
        ChainTarget origin = new ChainTarget(ORIGIN_X, ORIGIN_Y, ORIGIN_Z);
        List<ChainTarget> order = new ArrayList<ChainTarget>();
        Set<Long> visited = new LinkedHashSet<Long>();
        List<ChainTarget> frontier = new ArrayList<ChainTarget>();
        frontier.add(origin);
        visited.add(key(origin));
        int[][] deltas = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        while (!frontier.isEmpty() && order.size() < count) {
            List<ChainTarget> next = new ArrayList<ChainTarget>();
            for (ChainTarget current : frontier) {
                if (order.size() >= count) {
                    break;
                }
                order.add(current);
                for (int[] delta : deltas) {
                    ChainTarget candidate = new ChainTarget(
                        current.getX() + delta[0], current.getY() + delta[1], current.getZ() + delta[2]);
                    if (chebyshev(candidate, origin) > radius) {
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

    private static long key(ChainTarget target) {
        return ((long) target.getX() << 42) ^ ((long) target.getY() << 21) ^ target.getZ();
    }

    private static int chebyshev(ChainTarget a, ChainTarget b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
            Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    @Test
    public void dumpProductionScaleGeometryForOfflineRasterization() throws Exception {
        List<ChainTarget> chronological = bfsVolume(TARGETS, RADIUS);
        Assert.assertEquals("模拟目标数必须等于真机 HUD 的 1024", TARGETS, chronological.size());

        List<ChainTarget> snapshot = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(snapshot);
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(snapshot, visuals(), THICKNESS, null);

        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        int vertexCount = mesh.getVertexCount();
        int quadCount = indices.length / 4;

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        int maxIndex = -1;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            float x = vertices[vertex * 3];
            float y = vertices[vertex * 3 + 1];
            float z = vertices[vertex * 3 + 2];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                Assert.fail("顶点坐标出现 NaN/Inf: vertex=" + vertex);
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        for (int index : indices) {
            maxIndex = Math.max(maxIndex, index);
            Assert.assertTrue("索引不得为负:" + index, index >= 0);
        }

        File directory = new File("build/t49-offline");
        directory.mkdirs();
        try (PrintWriter writer = new PrintWriter(new File(directory, "vertices.csv"), "UTF-8")) {
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                writer.println(String.format(Locale.ROOT, "%.6f,%.6f,%.6f",
                    Float.valueOf(vertices[vertex * 3]),
                    Float.valueOf(vertices[vertex * 3 + 1]),
                    Float.valueOf(vertices[vertex * 3 + 2])));
            }
        }
        try (PrintWriter writer = new PrintWriter(new File(directory, "indices.csv"), "UTF-8")) {
            for (int index : indices) {
                writer.println(index);
            }
        }
        try (PrintWriter writer = new PrintWriter(new File(directory, "meta.txt"), "UTF-8")) {
            writer.println("origin=" + mesh.getOriginX() + "," + mesh.getOriginY() + "," + mesh.getOriginZ());
            writer.println("targets=" + TARGETS);
            writer.println("vertexCount=" + vertexCount);
            writer.println("quadCount=" + quadCount);
            writer.println("triangleIndexCount=" + (quadCount / 4 * 6));
            writer.println("maxIndex=" + maxIndex);
            writer.println("bboxLocal=" + fmt(minX) + "," + fmt(minY) + "," + fmt(minZ)
                + ".." + fmt(maxX) + "," + fmt(maxY) + "," + fmt(maxZ));
            writer.println("extent=" + fmt(maxX - minX) + "," + fmt(maxY - minY) + "," + fmt(maxZ - minZ));
            writer.println("bboxWorld=" + fmt(mesh.getOriginX() + minX) + "," + fmt(mesh.getOriginY() + minY)
                + "," + fmt(mesh.getOriginZ() + minZ) + ".." + fmt(mesh.getOriginX() + maxX)
                + "," + fmt(mesh.getOriginY() + maxY) + "," + fmt(mesh.getOriginZ() + maxZ));
        }

        System.out.println("[t49-geom] targets=" + TARGETS
            + " vertexCount=" + vertexCount
            + " quadCount=" + quadCount
            + " triangleIndexCount=" + (quadCount / 4 * 6)
            + " maxIndex=" + maxIndex
            + " extent=[" + fmt(maxX - minX) + "," + fmt(maxY - minY) + "," + fmt(maxZ - minZ) + "]"
            + " bboxLocal=[" + fmt(minX) + "," + fmt(minY) + "," + fmt(minZ)
            + "]..[" + fmt(maxX) + "," + fmt(maxY) + "," + fmt(maxZ) + "]");

        Assert.assertTrue("几何不得塌缩到 1 格内（真机表型是塌缩）: extent=("
            + (maxX - minX) + "," + (maxY - minY) + "," + (maxZ - minZ) + ")",
            (maxX - minX) > 1.5F && (maxZ - minZ) > 1.5F);
        Assert.assertTrue("索引不得越界: maxIndex=" + maxIndex + " vertexCount=" + vertexCount,
            maxIndex < vertexCount);
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.4f", Float.valueOf(value));
    }
}
