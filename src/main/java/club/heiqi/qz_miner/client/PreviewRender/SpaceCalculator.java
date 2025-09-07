package club.heiqi.qz_miner.client.PreviewRender;

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
    // 前   左下 0     右下 1     右上 2     左上 3
            0, 0, 1,  1, 0, 1,  1, 1, 1,  0, 1, 1,  // 前
    // 后   左下 4     右下 5     右上 6     左上 7
            0, 0, 0,  1, 0, 0,  1, 1, 0,  0, 1, 0,  // 后
    };

    // 索引数据
    public static final int[] index = {
            0,1, 1,2, 2,3, 0,3,  // 前面
            4,5, 5,6, 6,7, 4,7,  // 后面
            2,6, 3,7,            // 上连接
            0,4, 1,5             // 下连接
    };

    // 完整索引
    public static final ArrayList<Vector2i> completeIndex = new ArrayList<>();
    static {
        completeIndex.addAll(Arrays.asList(
                new Vector2i(0,1), new Vector2i(1,2), new Vector2i(2,3), new Vector2i(0,3),
                new Vector2i(4,5), new Vector2i(5,6), new Vector2i(6,7), new Vector2i(4,7),
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
            new DirectionConfig("ZN", "ZP", new Vector3i(0, 0, -1)),
            // 斜向连接 以XYZ为第一序 PN为第二序
            new DirectionConfig("XPYP", "XNYN", new Vector3i(1,1,0)),
            new DirectionConfig("XPYN", "XNYP", new Vector3i(1,-1,0)),
            new DirectionConfig("XPZP", "XNZN", new Vector3i(1,0,1)),
            new DirectionConfig("XPZN", "XNZP", new Vector3i(1,0,-1)),
            new DirectionConfig("XNYP", "XPYN", new Vector3i(-1,1,0)),
            new DirectionConfig("XNYN", "XPYP", new Vector3i(-1,-1,0)),
            new DirectionConfig("XNZP", "XPZN", new Vector3i(-1,0,1)),
            new DirectionConfig("XNZN", "XPZP", new Vector3i(-1,0,-1)),
            new DirectionConfig("YPZP", "YNZN", new Vector3i(0,1,1)),
            new DirectionConfig("YPZN", "YNZP", new Vector3i(0,1,-1)),
            new DirectionConfig("YNZP", "YPZN", new Vector3i(0,-1,1)),
            new DirectionConfig("YNZN", "YPZP", new Vector3i(0,-1,-1)),

    };

    // 方向映射
    public static final Map<String, String> connectDirMap = new HashMap<>();
    static {
        for (DirectionConfig config : DIRECTION_CONFIGS) {
            connectDirMap.put(config.dir, config.opposite);
        }
    }

    // 方向索引映射
    public static final Map<String, Vector2i[]> connectIndexMap = new HashMap<>();
    static {
        connectIndexMap.put("YP", new Vector2i[]{new Vector2i(2,3), new Vector2i(6,7), new Vector2i(2,6), new Vector2i(3,7)});
        connectIndexMap.put("YN", new Vector2i[]{new Vector2i(0,1), new Vector2i(4,5), new Vector2i(0,4), new Vector2i(1,5)});
        connectIndexMap.put("XP", new Vector2i[]{new Vector2i(1,2), new Vector2i(5,6), new Vector2i(1,5), new Vector2i(2,6)});
        connectIndexMap.put("XN", new Vector2i[]{new Vector2i(0,3), new Vector2i(4,7), new Vector2i(0,4), new Vector2i(3,7)});
        connectIndexMap.put("ZP", new Vector2i[]{new Vector2i(0,1), new Vector2i(1,2), new Vector2i(2,3), new Vector2i(0,3)});
        connectIndexMap.put("ZN", new Vector2i[]{new Vector2i(4,5), new Vector2i(5,6), new Vector2i(6,7), new Vector2i(4,7)});
        // 斜向
        connectIndexMap.put("XPYP", new Vector2i[]{new Vector2i(2,6)});
        connectIndexMap.put("XPYN", new Vector2i[]{new Vector2i(1,5)});
        connectIndexMap.put("XPZP", new Vector2i[]{new Vector2i(1,2)});
        connectIndexMap.put("XPZN", new Vector2i[]{new Vector2i(5,6)});
        connectIndexMap.put("XNYP", new Vector2i[]{new Vector2i(3,7)});
        connectIndexMap.put("XNYN", new Vector2i[]{new Vector2i(0,4)});
        connectIndexMap.put("XNZP", new Vector2i[]{new Vector2i(0,3)});
        connectIndexMap.put("XNZN", new Vector2i[]{new Vector2i(4,7)});
        connectIndexMap.put("YPZP", new Vector2i[]{new Vector2i(2,3)});
        connectIndexMap.put("YPZN", new Vector2i[]{new Vector2i(6,7)});
        connectIndexMap.put("YNZP", new Vector2i[]{new Vector2i(0,1)});
        connectIndexMap.put("YNZN", new Vector2i[]{new Vector2i(4,5)});
    }

    /**相邻种类对应的索引*/
    public static final Map<Set<String>, int[]> blockRemovalTypes = new HashMap<>();

    /**
     * 减去对应方向的索引
     */
    public static void removeEdges(Collection<Vector2i> index, String direction) {
        Vector2i[] edges = connectIndexMap.get(direction);
        if (edges != null) {
            for (Vector2i edge : edges) {
                index.remove(edge);
            }
        }
    }

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
            if (existingPoint.connectAll) continue;

            // 检查曼哈顿距离是否大于2
            Vector3i offsetVec = new Vector3i(existingPoint.position).sub(position);
            int offset = Math.abs(offsetVec.x) + Math.abs(offsetVec.y) + Math.abs(offsetVec.z);
            if (offset > 2) continue;

            for (DirectionConfig config : DIRECTION_CONFIGS) {
                // config.dir 当前检查方向;  config.opposite 当前检查方向的对向
                Vector3i neighborPos = new Vector3i(position).add(config.offset);

                if (!existingPoint.connectionsSet.contains(config.opposite) &&
                        existingPoint.position.equals(neighborPos)
                ) {
                    // 更新相邻关系
                    newPoint.connectionsSet.add(config.dir);
                    existingPoint.connectionsSet.add(config.opposite);

                    // 更新完整状态
                    if (newPoint.connectionsSet.size() == 18) newPoint.connectAll = true;
                    if (existingPoint.connectionsSet.size() == 18) existingPoint.connectAll = true;
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
        public Set<Vector2i> completeIndex = new HashSet<>(SpaceCalculator.completeIndex);
        public Set<String> connectionsSet = new HashSet<>();
        public boolean connectAll = false;

        public SpacePoint(Vector3i position) {
            this.position = position;
        }

        public int[] getIndices() {
            if (connectionsSet.isEmpty()) {
                return getCompleteIndexByIntArray().clone();
            }
            if (SpaceCalculator.blockRemovalTypes.containsKey(connectionsSet)) {
                return blockRemovalTypes.get(connectionsSet).clone();
            }

            // 减去对应方向的面索引
            for (String connectionDir : connectionsSet) {
                // 不对斜边进行减边处理
                if (connectionDir.length() != 2) continue;
                removeEdges(connectionDir);
            }

            // // 加回所斜向连接的线段
            // Set<Vector2i> sameLines = findOblique();
            // completeIndex.addAll(sameLines);

            int[] result = getCompleteIndexByIntArray();
            blockRemovalTypes.put(new HashSet<>(connectionsSet), result.clone());

            LOG.info("缓存 -> 相邻状态: {}, 对应索引: {}", connectionsSet, result);

            return result.clone();
        }

        private int[] getCompleteIndexByIntArray() {
            int[] indices = new int[completeIndex.size() * 2];
            int count = 0;
            for (Vector2i v : completeIndex) {
                indices[count] = v.x;
                indices[count+1] = v.y;
                count += 2;
            }
            return indices;
        }

        /**
         * @param direction 传入方向只包含直接方向 不包含斜向
         */
        public void removeEdges(String direction) {
            // 先移除该方向面的边索引
            SpaceCalculator.removeEdges(completeIndex, direction);


            // 寻找包含该方向分量的斜边, 并检查此斜边是否未连接
            for (String oblique : connectIndexMap.keySet()) {
                if (oblique.length() == 2) continue; // 排除非斜向
                String dirA = oblique.substring(0,2);
                String dirB = oblique.substring(2,4);
                // 检查该斜向是否在直接方向周围 - 检查分量是否包含直接方向 - 没有包含跳过
                if (!(direction.equals(dirA) || direction.equals(dirB))) continue;
                // 跳过已连接
                if (connectionsSet.contains(oblique)) continue;

                // 检查两个分量代表的直接方向是否都连接了 都连接则把斜向索引加回去
                if (connectionsSet.contains(dirA) && connectionsSet.contains(dirB)) {
                    Vector2i[] needAdds = connectIndexMap.get(oblique);
                    Collections.addAll(completeIndex, needAdds);
                }
            }

        }

        public void addBack() {
            // 检查所有斜向
            for (String oblique : connectIndexMap.keySet()) {
                if (oblique.length() != 4) continue;
                // 检查自身是否连接 连接了则跳过
                if (connectionsSet.contains(oblique)) {

                    continue;
                }
                String dirA = oblique.substring(0,2);
                String dirB = oblique.substring(2,4);
                // 检查斜向的两个分量的直接连接向是否连接了
                if (connectionsSet.contains(dirA) && connectionsSet.contains(dirB)) {
                    Vector2i[] needAdds = connectIndexMap.get(oblique);
                    Collections.addAll(completeIndex, needAdds);
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
