package club.heiqi.qz_miner.chain.client.verify;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * T7 独立网格不变量审计（preview-verifier 自持实现）。
 *
 * <p>只依赖 {@link ChainPreviewMesh} 的公开访问器，检查项：
 * 索引范围、quad 结构、去重、非退化、面朝向、闭合流形、顶点位置唯一性。</p>
 */
public final class VerifyMeshAudit {

    /** 审计结果：全部为 0 表示通过。 */
    public static final class Report {

        public int indexOutOfRange;
        public int indexCountNotQuadAligned;
        public int unusedVertex;
        public int degenerateQuad;
        public int duplicateQuad;
        public int duplicateVertexPosition;
        public int nonManifoldEdge;
        public int openEdge;
        public int inconsistentWinding;
        public double signedVolume;
        public int quadCount;
        public int vertexCount;
    }

    private VerifyMeshAudit() {
    }

    public static Report audit(ChainPreviewMesh mesh) {
        Report report = new Report();
        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        report.vertexCount = vertices.length / 3;
        report.quadCount = indices.length / 4;
        if (indices.length % 4 != 0) {
            report.indexCountNotQuadAligned++;
        }
        boolean[] used = new boolean[report.vertexCount];
        Set<String> quadKeys = new HashSet<String>();
        Set<String> vertexKeys = new HashSet<String>();
        Map<Long, int[]> edges = new HashMap<Long, int[]>();
        for (int offset = 0; offset + 3 < indices.length; offset += 4) {
            int first = indices[offset];
            int second = indices[offset + 1];
            int third = indices[offset + 2];
            int fourth = indices[offset + 3];
            int[] quad = {first, second, third, fourth};
            for (int value : quad) {
                if (value < 0 || value >= report.vertexCount) {
                    report.indexOutOfRange++;
                } else {
                    used[value] = true;
                }
            }
            if (report.indexOutOfRange > 0) {
                continue;
            }
            String[] keys = new String[4];
            for (int corner = 0; corner < 4; corner++) {
                keys[corner] = positionKey(vertices, quad[corner]);
            }
            java.util.Arrays.sort(keys);
            StringBuilder quadKey = new StringBuilder();
            for (String key : keys) {
                quadKey.append(key).append(';');
            }
            if (!quadKeys.add(quadKey.toString())) {
                report.duplicateQuad++;
            }
            if (crossProductLength(vertices, first, second, third) == 0.0D
                    || crossProductLength(vertices, first, third, fourth) == 0.0D) {
                report.degenerateQuad++;
                continue;
            }
            report.signedVolume += signedTriangleVolume(vertices, first, second, third);
            report.signedVolume += signedTriangleVolume(vertices, first, third, fourth);
            for (int corner = 0; corner < 4; corner++) {
                int from = quad[corner];
                int to = quad[(corner + 1) & 3];
                long key = (((long) Math.min(from, to)) << 32) | (Math.max(from, to) & 0xFFFFFFFFL);
                int[] state = edges.get(Long.valueOf(key));
                if (state == null) {
                    state = new int[2];
                    edges.put(Long.valueOf(key), state);
                }
                state[0]++;
                state[1] += from < to ? 1 : -1;
            }
        }
        for (int index = 0; index < report.vertexCount; index++) {
            if (!used[index]) {
                report.unusedVertex++;
            }
            if (!vertexKeys.add(positionKey(vertices, index))) {
                report.duplicateVertexPosition++;
            }
        }
        for (int[] state : edges.values()) {
            if (state[0] != 2) {
                if (state[0] < 2) {
                    report.openEdge++;
                } else {
                    report.nonManifoldEdge++;
                }
            } else if (state[1] != 0) {
                report.inconsistentWinding++;
            }
        }
        return report;
    }

    private static String positionKey(float[] vertices, int index) {
        int offset = index * 3;
        return bits(vertices[offset]) + ":" + bits(vertices[offset + 1]) + ":" + bits(vertices[offset + 2]);
    }

    private static int bits(float value) {
        return Float.floatToIntBits(value == 0.0F ? 0.0F : value);
    }

    private static double crossProductLength(float[] vertices, int first, int second, int third) {
        int firstOffset = first * 3;
        int secondOffset = second * 3;
        int thirdOffset = third * 3;
        double abX = vertices[secondOffset] - vertices[firstOffset];
        double abY = vertices[secondOffset + 1] - vertices[firstOffset + 1];
        double abZ = vertices[secondOffset + 2] - vertices[firstOffset + 2];
        double acX = vertices[thirdOffset] - vertices[firstOffset];
        double acY = vertices[thirdOffset + 1] - vertices[firstOffset + 1];
        double acZ = vertices[thirdOffset + 2] - vertices[firstOffset + 2];
        double crossX = abY * acZ - abZ * acY;
        double crossY = abZ * acX - abX * acZ;
        double crossZ = abX * acY - abY * acX;
        return crossX * crossX + crossY * crossY + crossZ * crossZ;
    }

    private static double signedTriangleVolume(float[] vertices, int first, int second, int third) {
        int firstOffset = first * 3;
        int secondOffset = second * 3;
        int thirdOffset = third * 3;
        double firstX = vertices[firstOffset];
        double firstY = vertices[firstOffset + 1];
        double firstZ = vertices[firstOffset + 2];
        double secondX = vertices[secondOffset];
        double secondY = vertices[secondOffset + 1];
        double secondZ = vertices[secondOffset + 2];
        double thirdX = vertices[thirdOffset];
        double thirdY = vertices[thirdOffset + 1];
        double thirdZ = vertices[thirdOffset + 2];
        return (firstX * (secondY * thirdZ - secondZ * thirdY)
            + firstY * (secondZ * thirdX - secondX * thirdZ)
            + firstZ * (secondX * thirdY - secondY * thirdX)) / 6.0D;
    }
}
