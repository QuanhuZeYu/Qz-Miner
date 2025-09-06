package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.utils.ArrayConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector2i;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import java.util.*;

public class SpaceCalculator {
    public static Logger LOG = LogManager.getLogger();
    // 顶点数据
    public static final float[] vertex = {
    // 后   左下 4     右下 5     右上 6     左上 7
            0, 0, 1,  1, 0, 1,  1, 1, 1,  0, 1, 1,  // 后
    // 前   左下 0     右下 1     右上 2     左上 3
            0, 0, 0,  1, 0, 0,  1, 1, 0,  0, 1, 0,  // 前
    };

    // 索引数据
    public static final int[] index = {
            3,2, 2,1, 1,0, 0,3,  // 前面
            4,5, 5,6, 6,7, 7,4,  // 后面
            2,6, 3,7,            // 上连接
            0,4, 1,5             // 下连接
    };

    // 完整索引
    public static final ArrayList<Vector2i> completeIndex = new ArrayList<>();
    static {
        completeIndex.addAll(Arrays.asList(
                new Vector2i(3,2), new Vector2i(2,1), new Vector2i(1,0), new Vector2i(0,3),
                new Vector2i(4,5), new Vector2i(5,6), new Vector2i(6,7), new Vector2i(7,4),
                new Vector2i(2,6), new Vector2i(3,7),
                new Vector2i(0,4), new Vector2i(1,5)
        ));
    }

    // 方向配置数据
    private static final DirectionConfig[] DIRECTION_CONFIGS = {
            new DirectionConfig("YP", "YN", new Vector3i(0, 1, 0)),
            new DirectionConfig("YN", "YP", new Vector3i(0, -1, 0)),
            new DirectionConfig("XP", "XN", new Vector3i(1, 0, 0)),
            new DirectionConfig("XN", "XP", new Vector3i(-1, 0, 0)),
            new DirectionConfig("ZP", "ZN", new Vector3i(0, 0, 1)),
            new DirectionConfig("ZN", "ZP", new Vector3i(0, 0, -1))
    };

    // 方向映射
    public static final Map<String, String> adjacentMap = new HashMap<>();
    static {
        for (DirectionConfig config : DIRECTION_CONFIGS) {
            adjacentMap.put(config.dir, config.opposite);
        }
    }

    // 方向索引映射
    public static final Map<String, Vector2i[]> adjacentIndexMap = new HashMap<>();
    static {
        adjacentIndexMap.put("YP", new Vector2i[]{new Vector2i(2,6), new Vector2i(3,7), new Vector2i(3,2), new Vector2i(6,7)});
        adjacentIndexMap.put("YN", new Vector2i[]{new Vector2i(0,4), new Vector2i(1,5), new Vector2i(1,0), new Vector2i(4,5)});
        adjacentIndexMap.put("XP", new Vector2i[]{new Vector2i(2,1), new Vector2i(5,6), new Vector2i(2,6), new Vector2i(1,5)});
        adjacentIndexMap.put("XN", new Vector2i[]{new Vector2i(0,3), new Vector2i(7,4), new Vector2i(3,7), new Vector2i(0,4)});
        adjacentIndexMap.put("ZP", new Vector2i[]{new Vector2i(3,2), new Vector2i(2,1), new Vector2i(1,0), new Vector2i(0,3)});
        adjacentIndexMap.put("ZN", new Vector2i[]{new Vector2i(4,5), new Vector2i(5,6), new Vector2i(6,7), new Vector2i(7,4)});
    }

    /**相邻种类对应的索引*/
    public static final Map<Set<String>, int[]> blockRemovalTypes = new HashMap<>();

    // ==================== 实例数据 ====================

    public ArrayList<SpacePoint> spacePoints = new ArrayList<>();

    public boolean hasChange = false;

    public void add(Vector3i position) {
        SpacePoint newPoint = new SpacePoint(position);

        // 检查是否存在 存在则跳过
        for (SpacePoint existingPoint : spacePoints) {
            if (existingPoint.position.equals(position)) return;
        }

        for (SpacePoint existingPoint : spacePoints) {
            if (existingPoint.adjacentAll) continue;

            // 检查曼哈顿距离是否大于1
            Vector3i offsetVec = new Vector3i(existingPoint.position).sub(position);
            int offset = Math.abs(offsetVec.x) + Math.abs(offsetVec.y) + Math.abs(offsetVec.z);
            if (offset > 1) continue;

            for (DirectionConfig config : DIRECTION_CONFIGS) {
                // config.dir 当前检查方向;  config.opposite 当前检查方向的对向
                Vector3i neighborPos = new Vector3i(position).add(config.offset);

                if (!existingPoint.adjacentSet.contains(config.opposite) &&
                        existingPoint.position.equals(neighborPos)
                ) {
                    // 更新相邻关系
                    newPoint.adjacentSet.add(config.dir);
                    existingPoint.adjacentSet.add(config.opposite);

                    // 更新完整状态
                    if (newPoint.adjacentSet.size() == 6) newPoint.adjacentAll = true;
                    if (existingPoint.adjacentSet.size() == 6) existingPoint.adjacentAll = true;
                }
            }
        }

        spacePoints.add(newPoint);
        hasChange = true;
    }

    public VertexAndIndex getVertexAndIndex() {
        ArrayList<float[]> verticesList = new ArrayList<>();
        ArrayList<int[]> indicesList = new ArrayList<>();

        int vertexCount = 0;
        for (SpacePoint point : spacePoints) {
            int[] indices = point.getIndices();
            if (indices.length == 0) continue;

            float[] vertices = SpaceCalculator.vertex.clone();

            // 更新顶点
            for (int i = 0; i < vertices.length; i += 3) {
                vertices[i] += point.position.x;
                vertices[i + 1] += point.position.y;
                vertices[i + 2] += point.position.z;
            }

            // 更新索引
            for (int i = 0; i < indices.length; i++) {
                indices[i] += vertexCount;
            }

            // 将数据复制到结果数组
            verticesList.add(vertices);
            indicesList.add(indices);

            vertexCount += 8;
        }

        hasChange = false;
        return new VertexAndIndex(ArrayConverter.convertF(verticesList), ArrayConverter.convertI(indicesList));
    }

    // 方向配置内部类
    private static class DirectionConfig {
        final String dir;
        final String opposite;
        final Vector3i offset;

        DirectionConfig(String dir, String opposite, Vector3i offset) {
            this.dir = dir;
            this.opposite = opposite;
            this.offset = offset;
        }
    }


    public static class SpacePoint {
        public Vector3i position;
        public ArrayList<Vector2i> completeIndex = new ArrayList<>(SpaceCalculator.completeIndex);
        public Set<String> adjacentSet = new HashSet<>();
        public boolean adjacentAll = false;

        public SpacePoint(Vector3i position) {
            this.position = position;
        }

        public int[] getIndices() {
            if (adjacentSet.isEmpty()) {
                return getCompleteIndexByIntArray().clone();
            }
            if (SpaceCalculator.blockRemovalTypes.containsKey(adjacentSet)) {
                return blockRemovalTypes.get(adjacentSet).clone();
            }
            for (String adjacent: adjacentSet) {
                removeEdges(adjacent);
            }
            int[] result = getCompleteIndexByIntArray();
            blockRemovalTypes.put(new HashSet<>(adjacentSet), result.clone());

            LOG.info("缓存 -> 相邻状态: {}, 对应索引: {}", adjacentSet, result);

            return result.clone();
        }

        private int[] getCompleteIndexByIntArray() {
            int[] indices = new int[completeIndex.size() * 2];
            for (int i = 0; i < completeIndex.size(); i++) {
                Vector2i index = completeIndex.get(i);
                indices[i * 2] = index.x;
                indices[i * 2 + 1] = index.y;
            }
            return indices;
        }

        public void removeEdges(String direction) {
            Vector2i[] edges = adjacentIndexMap.get(direction);
            if (edges != null) {
                for (Vector2i edge : edges) {
                    completeIndex.remove(edge);
                }
            }
        }

        public boolean equals(Object other) {
            if (!(other instanceof SpacePoint)) return false;
            return this.position.equals(((SpacePoint) other).position);
        }
    }


    public static class VertexAndIndex {
        public float[] vertices;
        public int[] indices;

        public VertexAndIndex(float[] vertices, int[] indices) {
            this.vertices = vertices;
            this.indices = indices;
        }
    }
}
