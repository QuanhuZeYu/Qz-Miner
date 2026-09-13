package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T39 波次 8 lod=auto 相机序列契约：期望值由独立状态机复算（enter/exit 双阈值 + 逐代滞回记忆）。
 *
 * <p>模型口径（独立于实现）：fresh 目标 alpha &le; enter 才剔除；已在记忆中的目标必须 alpha &ge;
 * exit 才恢复；每次构建结束后记忆 = 本轮实际仍被剔除的位置。相机沿 X 逐格逼近（d=100→90），
 * 相机置于 (0.5-d, .5, .5)、目标 x=0..9 的中心为 (x+0.5,.5,.5)，故距离 = x + d。</p>
 */
public class LodCameraSequenceContractTest {

    private static final float MAX_ALPHA = 1.0F;
    private static final float MIN_ALPHA = 0.05F;
    private static final double FADE_START = 0.0D;
    private static final double FADE_END = 100.0D;

    private static float alphaFor(double distance) {
        if (distance <= FADE_START) {
            return MAX_ALPHA;
        }
        if (distance >= FADE_END) {
            return MIN_ALPHA;
        }
        float normalized = (float) ((distance - FADE_START) / (FADE_END - FADE_START));
        return MAX_ALPHA - (MAX_ALPHA - MIN_ALPHA) * (normalized * normalized);
    }

    private static VisualParameters visuals(int cameraDistance) {
        return new VisualParameters(
            0.5D - (double) cameraDistance, 0.5D, 0.5D,
            FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA, 0.045F, true, MIN_ALPHA);
    }

    /** stride=3 的隔离目标：保证每根条柱的世界坐标窗口互不重叠（相邻条柱会互相落进对方窗口）。 */
    private static List<ChainTarget> targets(int count) {
        List<ChainTarget> list = new ArrayList<ChainTarget>();
        for (int index = 0; index < count; index++) {
            list.add(new ChainTarget(index * 3, 0, 0));
        }
        return list;
    }

    private static int blockX(List<ChainTarget> targets, int index) {
        return targets.get(index).getX();
    }

    @Test
    public void cameraApproachSequenceMatchesIndependentHysteresisModel() {
        List<ChainTarget> targets = targets(10);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        Set<Integer> memory = new LinkedHashSet<Integer>();
        StringBuilder trace = new StringBuilder();

        for (int cameraDistance = 100; cameraDistance >= 60; cameraDistance--) {
            ChainPreviewMesh mesh = builder.build(targets, visuals(cameraDistance));
            Set<Integer> expectedCulled = new LinkedHashSet<Integer>();
            for (int index = 0; index < targets.size(); index++) {
                double distance = blockX(targets, index) + cameraDistance;
                float alpha = alphaFor(distance);
                float exit = Math.min(1.0F, MIN_ALPHA + VisualParameters.LOD_EXIT_ALPHA_MARGIN);
                boolean remembered = memory.contains(Integer.valueOf(index));
                boolean culled = remembered ? alpha < exit : alpha <= MIN_ALPHA;
                if (culled) {
                    expectedCulled.add(Integer.valueOf(index));
                }
            }
            memory = expectedCulled;
            trace.append("d=").append(cameraDistance).append(" culled=").append(expectedCulled.size()).append(' ');

            Assert.assertEquals("相机序列 d=" + cameraDistance + " 剔除集合必须与独立模型一致: " + trace,
                expectedCulled.size(), mesh.getCulledTargetCount());
            Assert.assertEquals("可见块数必须与模型一致: " + trace,
                targets.size() - expectedCulled.size(), mesh.getBlockCount());
            for (int index = 0; index < targets.size(); index++) {
                boolean present = hasBar(mesh, blockX(targets, index));
                boolean expectedPresent = !expectedCulled.contains(Integer.valueOf(index));
                if (present != expectedPresent) {
                    StringBuilder detail = new StringBuilder();
                    detail.append("目标 x=").append(blockX(targets, index)).append(" d=").append(cameraDistance)
                        .append(" 模型期望可见=").append(expectedPresent).append(" 实际=").append(present)
                        .append(" distance=").append(blockX(targets, index) + cameraDistance)
                        .append(" alpha=").append(alphaFor(blockX(targets, index) + cameraDistance))
                        .append(" | meshOriginX=").append(mesh.getOriginX())
                        .append(" blocks=").append(mesh.getBlockCount())
                        .append(" culledCount=").append(mesh.getCulledTargetCount())
                        .append(" memoryModel=").append(expectedCulled)
                        .append(" memoryImpl=").append(builder.getLodHysteresisMemorySize())
                        .append(" worldX=").append(worldXRanges(mesh));
                    Assert.fail(detail.toString());
                }
            }
            Assert.assertEquals("滞回记忆规模必须与模型一致: " + trace,
                expectedCulled.size(), builder.getLodHysteresisMemorySize());
        }
        // 非平凡性：序列必须从"全剔除"推进到"部分恢复"（两端差异 >= 3），否则说明模型/实现都在空转
        Assert.assertTrue("序列必须发生多次恢复（防止平凡通过）: " + trace,
            memory.size() <= 5 && trace.indexOf("culled=10") >= 0);
    }

    /** @return 世界坐标 X 的最小/最大与顶点数（诊断用）。 */
    private static String worldXRanges(ChainPreviewMesh mesh) {
        float[] vertices = mesh.getVertices();
        float min = Float.MAX_VALUE;
        float max = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            float worldX = mesh.getOriginX() + vertices[vertex * 3];
            min = Math.min(min, worldX);
            max = Math.max(max, worldX);
        }
        return "[" + min + "," + max + "] verts=" + (mesh.getVertexFloatCount() / 3);
    }

    /** 目标 x 的条柱是否存在（按世界坐标包围盒归属）。 */
    private static boolean hasBar(ChainPreviewMesh mesh, int x) {
        float[] vertices = mesh.getVertices();
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            float worldX = mesh.getOriginX() + vertices[vertex * 3];
            float worldY = mesh.getOriginY() + vertices[vertex * 3 + 1];
            float worldZ = mesh.getOriginZ() + vertices[vertex * 3 + 2];
            if (worldX >= x - 0.1F && worldX <= x + 1.1F
                    && worldY >= -0.1F && worldY <= 1.1F
                    && worldZ >= -0.1F && worldZ <= 1.1F) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void lodOffStaysExactEquivalenceDuringCameraSweep() {
        List<ChainTarget> targets = targets(10);
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        for (int cameraDistance = 100; cameraDistance >= 60; cameraDistance--) {
            VisualParameters off = new VisualParameters(
                0.5D - (double) cameraDistance, 0.5D, 0.5D,
                FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA, 0.045F, false, MIN_ALPHA);
            ChainPreviewMesh mesh = builder.build(targets, off);
            Assert.assertEquals("lod=off 不得剔除", 0, mesh.getCulledTargetCount());
            Assert.assertEquals(10, mesh.getBlockCount());
            Assert.assertEquals("lod=off 不得写入滞回记忆", 0, builder.getLodHysteresisMemorySize());
        }
    }
}
