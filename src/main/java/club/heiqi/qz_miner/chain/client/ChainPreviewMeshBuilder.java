package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 预览线框网格构建器。
 */
public class ChainPreviewMeshBuilder {

    private static final float[] UNIT_CUBE_VERTICES = {
        0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1,
        0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0
    };

    private static final int[] UNIT_CUBE_INDICES = {
        0, 1, 1, 2, 2, 3, 0, 3,
        4, 5, 5, 6, 6, 7, 4, 7,
        2, 6, 3, 7,
        0, 4, 1, 5
    };

    private static final Set<LineSegment> COMPLETE_SEGMENTS = createCompleteSegments();
    private static final Map<String, LineSegment[]> SEGMENTS_BY_DIRECTION = createSegmentsByDirection();
    private static final DirectionConfig[] DIRECTION_CONFIGS = createDirectionConfigs();

    /**
     * 根据预览目标构建线框网格。
     *
     * @param previewTargets 预览目标列表
     * @return 线框网格数据
     */
    public ChainPreviewMesh build(List<ChainTarget> previewTargets) {
        if (previewTargets == null || previewTargets.isEmpty()) {
            return ChainPreviewMesh.EMPTY;
        }

        List<SpacePoint> spacePoints = new ArrayList<SpacePoint>(previewTargets.size());
        for (ChainTarget target : previewTargets) {
            if (target == null) {
                continue;
            }
            addSpacePoint(spacePoints, new BlockPos(target.getX(), target.getY(), target.getZ()));
        }

        if (spacePoints.isEmpty()) {
            return ChainPreviewMesh.EMPTY;
        }

        List<float[]> verticesList = new ArrayList<float[]>(spacePoints.size());
        List<int[]> indicesList = new ArrayList<int[]>(spacePoints.size());
        int vertexCount = 0;
        int visibleBlockCount = 0;
        for (SpacePoint point : spacePoints) {
            int[] pointIndices = point.buildIndices();
            if (pointIndices.length == 0) {
                continue;
            }

            float[] pointVertices = UNIT_CUBE_VERTICES.clone();
            for (int i = 0; i < pointVertices.length; i += 3) {
                pointVertices[i] += point.position.x;
                pointVertices[i + 1] += point.position.y;
                pointVertices[i + 2] += point.position.z;
            }

            for (int i = 0; i < pointIndices.length; i++) {
                pointIndices[i] += vertexCount;
            }

            verticesList.add(pointVertices);
            indicesList.add(pointIndices);
            vertexCount += 8;
            visibleBlockCount++;
        }

        if (verticesList.isEmpty()) {
            return ChainPreviewMesh.EMPTY;
        }

        return new ChainPreviewMesh(flattenVertices(verticesList), flattenIndices(indicesList), visibleBlockCount);
    }

    /**
     * 添加单个预览点，并补齐与已存在方块的连接关系。
     *
     * @param spacePoints 当前空间点集合
     * @param position 目标位置
     */
    private void addSpacePoint(List<SpacePoint> spacePoints, BlockPos position) {
        for (SpacePoint existingPoint : spacePoints) {
            if (existingPoint.position.equals(position)) {
                return;
            }
        }

        SpacePoint newPoint = new SpacePoint(position);
        for (SpacePoint existingPoint : spacePoints) {
            if (existingPoint.isFullyConnected()) {
                continue;
            }
            if (!isWithinNeighborRange(existingPoint.position, position)) {
                continue;
            }

            for (DirectionConfig directionConfig : DIRECTION_CONFIGS) {
                if (existingPoint.connections.contains(directionConfig.opposite)) {
                    continue;
                }

                BlockPos neighborPosition = position.offset(directionConfig.offsetX, directionConfig.offsetY, directionConfig.offsetZ);
                if (!existingPoint.position.equals(neighborPosition)) {
                    continue;
                }

                newPoint.connections.add(directionConfig.direction);
                existingPoint.connections.add(directionConfig.opposite);
            }
        }

        spacePoints.add(newPoint);
    }

    private boolean isWithinNeighborRange(BlockPos left, BlockPos right) {
        return Math.abs(left.x - right.x) <= 1
            && Math.abs(left.y - right.y) <= 1
            && Math.abs(left.z - right.z) <= 1;
    }

    private float[] flattenVertices(List<float[]> verticesList) {
        int totalLength = 0;
        for (float[] vertices : verticesList) {
            totalLength += vertices.length;
        }

        float[] merged = new float[totalLength];
        int offset = 0;
        for (float[] vertices : verticesList) {
            System.arraycopy(vertices, 0, merged, offset, vertices.length);
            offset += vertices.length;
        }
        return merged;
    }

    private int[] flattenIndices(List<int[]> indicesList) {
        int totalLength = 0;
        for (int[] indices : indicesList) {
            totalLength += indices.length;
        }

        int[] merged = new int[totalLength];
        int offset = 0;
        for (int[] indices : indicesList) {
            System.arraycopy(indices, 0, merged, offset, indices.length);
            offset += indices.length;
        }
        return merged;
    }

    private static Set<LineSegment> createCompleteSegments() {
        Set<LineSegment> segments = new HashSet<LineSegment>();
        Collections.addAll(segments,
            new LineSegment(0, 1), new LineSegment(1, 2), new LineSegment(2, 3), new LineSegment(0, 3),
            new LineSegment(4, 5), new LineSegment(5, 6), new LineSegment(6, 7), new LineSegment(4, 7),
            new LineSegment(2, 6), new LineSegment(3, 7), new LineSegment(0, 4), new LineSegment(1, 5));
        return segments;
    }

    private static Map<String, LineSegment[]> createSegmentsByDirection() {
        Map<String, LineSegment[]> map = new HashMap<String, LineSegment[]>();
        map.put("YP", new LineSegment[] {new LineSegment(2, 3), new LineSegment(6, 7), new LineSegment(2, 6), new LineSegment(3, 7)});
        map.put("YN", new LineSegment[] {new LineSegment(0, 1), new LineSegment(4, 5), new LineSegment(0, 4), new LineSegment(1, 5)});
        map.put("XP", new LineSegment[] {new LineSegment(1, 2), new LineSegment(5, 6), new LineSegment(1, 5), new LineSegment(2, 6)});
        map.put("XN", new LineSegment[] {new LineSegment(0, 3), new LineSegment(4, 7), new LineSegment(0, 4), new LineSegment(3, 7)});
        map.put("ZP", new LineSegment[] {new LineSegment(0, 1), new LineSegment(1, 2), new LineSegment(2, 3), new LineSegment(0, 3)});
        map.put("ZN", new LineSegment[] {new LineSegment(4, 5), new LineSegment(5, 6), new LineSegment(6, 7), new LineSegment(4, 7)});
        map.put("XPYP", new LineSegment[] {new LineSegment(2, 6)});
        map.put("XPYN", new LineSegment[] {new LineSegment(1, 5)});
        map.put("XPZP", new LineSegment[] {new LineSegment(1, 2)});
        map.put("XPZN", new LineSegment[] {new LineSegment(5, 6)});
        map.put("XNYP", new LineSegment[] {new LineSegment(3, 7)});
        map.put("XNYN", new LineSegment[] {new LineSegment(0, 4)});
        map.put("XNZP", new LineSegment[] {new LineSegment(0, 3)});
        map.put("XNZN", new LineSegment[] {new LineSegment(4, 7)});
        map.put("YPZP", new LineSegment[] {new LineSegment(2, 3)});
        map.put("YPZN", new LineSegment[] {new LineSegment(6, 7)});
        map.put("YNZP", new LineSegment[] {new LineSegment(0, 1)});
        map.put("YNZN", new LineSegment[] {new LineSegment(4, 5)});
        return map;
    }

    private static DirectionConfig[] createDirectionConfigs() {
        return new DirectionConfig[] {
            new DirectionConfig("YP", "YN", 0, 1, 0),
            new DirectionConfig("YN", "YP", 0, -1, 0),
            new DirectionConfig("XP", "XN", 1, 0, 0),
            new DirectionConfig("XN", "XP", -1, 0, 0),
            new DirectionConfig("ZP", "ZN", 0, 0, 1),
            new DirectionConfig("ZN", "ZP", 0, 0, -1),
            new DirectionConfig("XPYP", "XNYN", 1, 1, 0),
            new DirectionConfig("XPYN", "XNYP", 1, -1, 0),
            new DirectionConfig("XPZP", "XNZN", 1, 0, 1),
            new DirectionConfig("XPZN", "XNZP", 1, 0, -1),
            new DirectionConfig("XNYP", "XPYN", -1, 1, 0),
            new DirectionConfig("XNYN", "XPYP", -1, -1, 0),
            new DirectionConfig("XNZP", "XPZN", -1, 0, 1),
            new DirectionConfig("XNZN", "XPZP", -1, 0, -1),
            new DirectionConfig("YPZP", "YNZN", 0, 1, 1),
            new DirectionConfig("YPZN", "YNZP", 0, 1, -1),
            new DirectionConfig("YNZP", "YPZN", 0, -1, 1),
            new DirectionConfig("YNZN", "YPZP", 0, -1, -1)
        };
    }

    /**
     * 单个预览方块点。
     */
    private static final class SpacePoint {

        private final BlockPos position;
        private final Set<String> connections = new HashSet<String>();

        private SpacePoint(BlockPos position) {
            this.position = position;
        }

        private boolean isFullyConnected() {
            return connections.size() == DIRECTION_CONFIGS.length;
        }

        private int[] buildIndices() {
            Set<LineSegment> visibleSegments = new HashSet<LineSegment>(COMPLETE_SEGMENTS);
            for (String direction : connections) {
                if (direction.length() != 2) {
                    continue;
                }
                removeSegments(visibleSegments, direction);
            }

            if (visibleSegments.isEmpty()) {
                return new int[0];
            }

            int[] indices = new int[visibleSegments.size() * 2];
            int index = 0;
            for (LineSegment segment : visibleSegments) {
                indices[index++] = segment.start;
                indices[index++] = segment.end;
            }
            return indices;
        }

        private void removeSegments(Collection<LineSegment> visibleSegments, String direction) {
            LineSegment[] faceSegments = SEGMENTS_BY_DIRECTION.get(direction);
            if (faceSegments != null) {
                for (LineSegment segment : faceSegments) {
                    visibleSegments.remove(segment);
                }
            }

            for (Map.Entry<String, LineSegment[]> entry : SEGMENTS_BY_DIRECTION.entrySet()) {
                String diagonalDirection = entry.getKey();
                if (diagonalDirection.length() == 2) {
                    continue;
                }
                String directionA = diagonalDirection.substring(0, 2);
                String directionB = diagonalDirection.substring(2, 4);
                if (!direction.equals(directionA) && !direction.equals(directionB)) {
                    continue;
                }
                if (connections.contains(diagonalDirection)) {
                    continue;
                }
                if (connections.contains(directionA) && connections.contains(directionB)) {
                    Collections.addAll(visibleSegments, entry.getValue());
                }
            }
        }
    }

    /**
     * 方块位置快照。
     */
    private static final class BlockPos {

        private final int x;
        private final int y;
        private final int z;

        private BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private BlockPos offset(int offsetX, int offsetY, int offsetZ) {
            return new BlockPos(x + offsetX, y + offsetY, z + offsetZ);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BlockPos)) {
                return false;
            }
            BlockPos blockPos = (BlockPos) other;
            return x == blockPos.x && y == blockPos.y && z == blockPos.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }

    /**
     * 方向配置。
     */
    private static final class DirectionConfig {

        private final String direction;
        private final String opposite;
        private final int offsetX;
        private final int offsetY;
        private final int offsetZ;

        private DirectionConfig(String direction, String opposite, int offsetX, int offsetY, int offsetZ) {
            this.direction = direction;
            this.opposite = opposite;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }
    }

    /**
     * 线段索引。
     */
    private static final class LineSegment {

        private final int start;
        private final int end;

        private LineSegment(int first, int second) {
            if (first <= second) {
                this.start = first;
                this.end = second;
            } else {
                this.start = second;
                this.end = first;
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LineSegment)) {
                return false;
            }
            LineSegment that = (LineSegment) other;
            return start == that.start && end == that.end;
        }

        @Override
        public int hashCode() {
            int result = start;
            result = 31 * result + end;
            return result;
        }
    }
}
