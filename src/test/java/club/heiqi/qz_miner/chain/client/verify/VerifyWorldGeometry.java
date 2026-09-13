package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.Assert;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/** T31 波次 6 独立验证·世界坐标几何比较工具（容差匹配，顺序无关）。 */
public final class VerifyWorldGeometry {

    /** 默认容差：跨锚点的 float 加法可能差 1 ulp；顶点最小间距 0.045 远大于该粒度。 */
    public static final float DEFAULT_TOLERANCE = 1.0E-4F;

    private VerifyWorldGeometry() {
    }

    /** @return 世界坐标顶点列表（local + meshOrigin） */
    public static List<float[]> vertices(ChainPreviewMesh mesh) {
        List<float[]> vertices = new ArrayList<float[]>();
        float[] local = mesh.getVertices();
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            vertices.add(new float[] {
                mesh.getOriginX() + local[vertex * 3],
                mesh.getOriginY() + local[vertex * 3 + 1],
                mesh.getOriginZ() + local[vertex * 3 + 2]
            });
        }
        return vertices;
    }

    public static boolean near(float[] left, float[] right, float tolerance) {
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(left[axis] - right[axis]) > tolerance) {
                return false;
            }
        }
        return true;
    }

    /** 集合相等（容差、顺序无关）；返回差异说明，null 表示相等。 */
    public static String diff(List<float[]> expected, List<float[]> actual, float tolerance) {
        if (expected.size() != actual.size()) {
            return "size expected=" + expected.size() + " actual=" + actual.size();
        }
        boolean[] used = new boolean[actual.size()];
        for (float[] candidate : expected) {
            int match = -1;
            for (int index = 0; index < actual.size(); index++) {
                if (!used[index] && near(candidate, actual.get(index), tolerance)) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                return "missing " + candidate[0] + "," + candidate[1] + "," + candidate[2];
            }
            used[match] = true;
        }
        return null;
    }

    /** @return expected 中不在 actual 里的元素（容差、顺序无关） */
    public static List<float[]> difference(List<float[]> expected, List<float[]> actual, float tolerance) {
        boolean[] used = new boolean[actual.size()];
        List<float[]> remaining = new ArrayList<float[]>();
        for (float[] candidate : expected) {
            int match = -1;
            for (int index = 0; index < actual.size(); index++) {
                if (!used[index] && near(candidate, actual.get(index), tolerance)) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                remaining.add(candidate);
            } else {
                used[match] = true;
            }
        }
        return remaining;
    }

    public static List<float[]> intersect(List<float[]> left, List<float[]> right, float tolerance) {
        List<float[]> result = new ArrayList<float[]>();
        boolean[] used = new boolean[right.size()];
        for (float[] candidate : left) {
            for (int index = 0; index < right.size(); index++) {
                if (!used[index] && near(candidate, right.get(index), tolerance)) {
                    used[index] = true;
                    result.add(candidate);
                    break;
                }
            }
        }
        return result;
    }

    /** 断言 changed 的每个顶点都落在 target 的 limit 格 Chebyshev 邻域内。 */
    public static void assertWithinNeighborhood(
            String label, List<float[]> changed, int[] block, float limit) {
        for (float[] vertex : changed) {
            for (int axis = 0; axis < 3; axis++) {
                float distance = Math.abs(vertex[axis] - block[axis]);
                Assert.assertTrue(label + " 受影响顶点越出 26 邻域: " + vertex[0] + "," + vertex[1] + ","
                    + vertex[2] + " 轴 " + axis + " 距 " + block[axis] + " = " + distance,
                    distance <= limit);
            }
        }
    }

    /** 顶点分类键（用于计数集合大小）。 */
    public static TreeSet<String> keys(List<float[]> vertices) {
        TreeSet<String> keys = new TreeSet<String>();
        for (float[] vertex : vertices) {
            keys.add(vertex[0] + "," + vertex[1] + "," + vertex[2]);
        }
        return keys;
    }
}
