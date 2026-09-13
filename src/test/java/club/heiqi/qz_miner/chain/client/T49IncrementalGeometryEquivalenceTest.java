package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.GenerationSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.MeshBuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.WorkGate;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T49 离线等价性探针：真机走的是**代级增量**路径（{@code beginGeneration().beginRevision()}），
 * 而此前的离线几何探针走的是**全量** {@code build()}。两者若不等价，真机看到的几何就会与离线复算分叉。
 *
 * <p>本类用与真机同形的模拟数据（半径 8 / 1024 目标）做两种对比：
 * <ol>
 *   <li>单次修订 vs 全量构建；</li>
 *   <li>快照逐步增长（每 64 个目标一次 beginRevision，模拟真机逐批到达）vs 全量构建。</li>
 * </ol>
 * 逐字节比较顶点与索引，并在不一致时给出第一处差异。</p>
 */
public class T49IncrementalGeometryEquivalenceTest {

    private static final int ORIGIN_X = 1;
    private static final int ORIGIN_Y = 6;
    private static final int ORIGIN_Z = 0;
    private static final int RADIUS = 8;
    private static final int TARGETS = 1024;
    private static final float THICKNESS = 0.045F;
    private static final WorkGate NEVER_YIELD = new WorkGate() {
        @Override
        public boolean shouldYield() {
            return false;
        }
    };

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 64.5D, 0.5D, 2.0D, 6.0D, 0.78F, 0.15F, THICKNESS);
    }

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

    private static List<ChainTarget> newestFirst(List<ChainTarget> chronological) {
        List<ChainTarget> copy = new ArrayList<ChainTarget>(chronological);
        Collections.reverse(copy);
        return copy;
    }

    private static long key(ChainTarget target) {
        return ((long) target.getX() << 42) ^ ((long) target.getY() << 21) ^ target.getZ();
    }

    private static int chebyshev(ChainTarget a, ChainTarget b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
            Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
    }

    @Test
    public void singleRevisionMatchesFullBuild() {
        List<ChainTarget> targets = bfsVolume(TARGETS, RADIUS);
        ChainPreviewMesh full = new ChainPreviewMeshBuilder().build(newestFirst(targets), visuals(), THICKNESS, null);

        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession generation = builder.beginGeneration();
        MeshBuildSession session = generation.beginRevision(newestFirst(targets), null, visuals(), THICKNESS);
        Assert.assertTrue("单次修订应在无让出条件下一次跑完", session.advance(NEVER_YIELD));
        ChainPreviewMesh incremental = session.getMesh();

        System.out.println("[t49-incr] single: full(verts=" + full.getVertexCount()
            + ", quads=" + (full.getIndexCount() / 4) + ") incremental(verts=" + incremental.getVertexCount()
            + ", quads=" + (incremental.getIndexCount() / 4) + ")");
        assertSameGeometry("单次修订", full, incremental);
    }

    @Test
    public void growingSnapshotRevisionsMatchFullBuild() {
        List<ChainTarget> targets = bfsVolume(TARGETS, RADIUS);
        ChainPreviewMesh full = new ChainPreviewMeshBuilder().build(newestFirst(targets), visuals(), THICKNESS, null);

        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        GenerationSession generation = builder.beginGeneration();
        MeshBuildSession session = null;
        for (int step = 64; step <= targets.size(); step += 64) {
            session = generation.beginRevision(
                newestFirst(targets.subList(0, step)), null, visuals(), THICKNESS);
            while (!session.advance(NEVER_YIELD)) {
                // 分片推进：与真机安全点语义一致。
            }
        }
        ChainPreviewMesh grown = session.getMesh();
        System.out.println("[t49-incr] growing: full(verts=" + full.getVertexCount()
            + ", quads=" + (full.getIndexCount() / 4) + ") grown(verts=" + grown.getVertexCount()
            + ", quads=" + (grown.getIndexCount() / 4) + ")");
        assertSameGeometry("增长快照", full, grown);
    }

    private static void assertSameGeometry(String label, ChainPreviewMesh expected, ChainPreviewMesh actual) {
        Assert.assertEquals(label + " 顶点数必须一致", expected.getVertexCount(), actual.getVertexCount());
        Assert.assertEquals(label + " 索引数必须一致", expected.getIndexCount(), actual.getIndexCount());
        Assert.assertEquals(label + " origin 必须一致",
            expected.getOriginX() + "," + expected.getOriginY() + "," + expected.getOriginZ(),
            actual.getOriginX() + "," + actual.getOriginY() + "," + actual.getOriginZ());

        float[] expectedVertices = expected.getVertices();
        float[] actualVertices = actual.getVertices();
        int floatCount = expected.getVertexCount() * 3;
        for (int index = 0; index < floatCount; index++) {
            if (Float.compare(expectedVertices[index], actualVertices[index]) != 0) {
                Assert.fail(label + " 顶点第 " + index + " 个分量不一致: full="
                    + fmt(expectedVertices[index]) + " incremental=" + fmt(actualVertices[index])
                    + "（顶点 " + (index / 3) + " 分量 " + (index % 3) + "）");
            }
        }
        int[] expectedIndices = expected.getIndices();
        int[] actualIndices = actual.getIndices();
        int indexCount = expected.getIndexCount();
        for (int index = 0; index < indexCount; index++) {
            if (expectedIndices[index] != actualIndices[index]) {
                Assert.fail(label + " 索引第 " + index + " 项不一致: full=" + expectedIndices[index]
                    + " incremental=" + actualIndices[index]);
            }
        }
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.6f", Float.valueOf(value));
    }
}
