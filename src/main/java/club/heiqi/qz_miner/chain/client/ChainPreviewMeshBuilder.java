package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 把 preview target 增量转换为由 quad 面组成的无重叠边框网格。
 *
 * <p>拓扑会在每个 target、可见边和 quad 前观察 {@link WorkGate}；视觉参数在 session
 * 创建时冻结，后续可独立替换距离效果而不改变 target/邻接算法。</p>
 */
public class ChainPreviewMeshBuilder {

    public static final int MAX_RENDER_TARGETS = 4096;
    private static final float BASE_RED = 0.25F;
    private static final float BASE_GREEN = 0.9F;
    private static final float BASE_BLUE = 1.0F;
    private static final float DEFAULT_BAR_THICKNESS = 0.045F;
    private static final float[] UNIT_CUBE_VERTICES = {
        0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1,
        0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0
    };
    private static final int[] CUBOID_QUAD_INDICES = {
        0, 1, 2, 3,
        5, 4, 7, 6,
        4, 5, 1, 0,
        3, 2, 6, 7,
        4, 0, 3, 7,
        1, 5, 6, 2
    };
    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;
    private static final int DIRECTION_X_NEGATIVE = 1;
    private static final int DIRECTION_X_POSITIVE = 1 << 1;
    private static final int DIRECTION_Y_NEGATIVE = 1 << 2;
    private static final int DIRECTION_Y_POSITIVE = 1 << 3;
    private static final int DIRECTION_Z_NEGATIVE = 1 << 4;
    private static final int DIRECTION_Z_POSITIVE = 1 << 5;
    private static final int[] FACE_DIRECTIONS = {
        DIRECTION_Z_POSITIVE,
        DIRECTION_Z_NEGATIVE,
        DIRECTION_Y_NEGATIVE,
        DIRECTION_Y_POSITIVE,
        DIRECTION_X_NEGATIVE,
        DIRECTION_X_POSITIVE
    };
    private static final int[][] TUBE_FACES_BY_AXIS = {
        {0, 1, 2, 3},
        {0, 1, 4, 5},
        {2, 3, 4, 5}
    };
    private static final LineSegment[] COMPLETE_SEGMENTS = {
        new LineSegment(0, 1), new LineSegment(1, 2), new LineSegment(2, 3), new LineSegment(0, 3),
        new LineSegment(4, 5), new LineSegment(5, 6), new LineSegment(6, 7), new LineSegment(4, 7),
        new LineSegment(2, 6), new LineSegment(3, 7), new LineSegment(0, 4), new LineSegment(1, 5)
    };
    private static final Map<String, LineSegment[]> SEGMENTS_BY_DIRECTION = createSegmentsByDirection();
    private static final DirectionConfig[] DIRECTION_CONFIGS = createDirectionConfigs();
    private static final WorkGate NEVER_YIELD = new WorkGate() {
        @Override
        public boolean shouldYield() {
            return false;
        }
    };

    /** 创建可跨 tick 恢复的 CPU build session。 */
    public BuildSession begin(Iterable<ChainTarget> previewTargets, VisualParameters visualParameters) {
        Iterable<ChainTarget> targets = previewTargets == null
            ? Collections.<ChainTarget>emptyList()
            : previewTargets;
        VisualParameters visuals = visualParameters == null
            ? VisualParameters.fromCurrentConfig(0.0D, 0.0D, 0.0D)
            : visualParameters;
        return new BuildSession(targets, visuals);
    }

    /** 相同 topology 的相机效果刷新只重建 color stream。 */
    public ColorBuildSession beginRecolor(ChainPreviewMesh mesh, VisualParameters visualParameters) {
        if (mesh == null || !mesh.isRecolorable()) {
            throw new IllegalArgumentException("mesh is not recolorable");
        }
        VisualParameters visuals = visualParameters == null
            ? VisualParameters.fromCurrentConfig(0.0D, 0.0D, 0.0D)
            : visualParameters;
        return new ColorBuildSession(mesh, visuals);
    }

    /** 同步便利入口，主要供纯 JVM 几何测试使用。 */
    public ChainPreviewMesh build(List<ChainTarget> previewTargets, VisualParameters visualParameters) {
        BuildSession session = begin(previewTargets, visualParameters);
        session.advance(NEVER_YIELD);
        return session.getMesh();
    }

    /** 保留相机参数入口；生产 cache 会显式冻结完整视觉参数。 */
    public ChainPreviewMesh build(
            List<ChainTarget> previewTargets, double cameraX, double cameraY, double cameraZ) {
        return build(previewTargets, VisualParameters.fromCurrentConfig(cameraX, cameraY, cameraZ));
    }

    /** 单个安全边界的让出查询。 */
    public interface WorkGate {
        boolean shouldYield();
    }

    public interface MeshBuildSession {
        boolean advance(WorkGate gate);

        ChainPreviewMesh getMesh();
    }

    /** 相机相关效果的不可变输入，与条柱拓扑分离。 */
    public static final class VisualParameters {

        private final double cameraX;
        private final double cameraY;
        private final double cameraZ;
        private final double fadeStart;
        private final double fadeEnd;
        private final float maxAlpha;
        private final float minAlpha;
        private final float barThickness;

        public VisualParameters(
                double cameraX,
                double cameraY,
                double cameraZ,
                double fadeStart,
                double fadeEnd,
                float maxAlpha,
                float minAlpha,
                float barThickness) {
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.fadeStart = Math.max(0.0D, fadeStart);
            this.fadeEnd = Math.max(this.fadeStart + 0.001D, fadeEnd);
            this.maxAlpha = clampAlpha(maxAlpha);
            this.minAlpha = clampAlpha(minAlpha);
            this.barThickness = Math.max(0.001F, Math.min(0.99F, barThickness));
        }

        public static VisualParameters fromCurrentConfig(double cameraX, double cameraY, double cameraZ) {
            return new VisualParameters(
                cameraX,
                cameraY,
                cameraZ,
                Config.clientPreviewAlphaFadeStartRadius,
                Config.clientPreviewAlphaFadeEndRadius,
                (float) Config.clientPreviewAlphaStartValue,
                (float) Config.clientPreviewAlphaEndValue,
                DEFAULT_BAR_THICKNESS);
        }

        float alphaFor(double centerX, double centerY, double centerZ) {
            double dx = centerX - cameraX;
            double dy = centerY - cameraY;
            double dz = centerZ - cameraZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= fadeStart) {
                return maxAlpha;
            }
            if (distance >= fadeEnd) {
                return minAlpha;
            }
            float normalized = (float) ((distance - fadeStart) / (fadeEnd - fadeStart));
            float squared = normalized * normalized;
            return maxAlpha - (maxAlpha - minAlpha) * squared;
        }

        private static float clampAlpha(float alpha) {
            return Math.max(0.0F, Math.min(1.0F, alpha));
        }
    }

    /** 可恢复构建：有界保留 target、去重世界边，再生成无内部端面的接头与管段。 */
    public static final class BuildSession implements MeshBuildSession {

        private final Iterator<ChainTarget> targetIterator;
        private final VisualParameters visuals;
        private final ChainTarget[] retainedTargets = new ChainTarget[MAX_RENDER_TARGETS];
        private final List<BlockPos> positions = new ArrayList<BlockPos>(MAX_RENDER_TARGETS);
        private final Set<BlockPos> occupancy = new HashSet<BlockPos>(MAX_RENDER_TARGETS * 4 / 3 + 1);
        private final Set<GridEdge> edges = new LinkedHashSet<GridEdge>();
        private final Map<GridPoint, Integer> incidence = new LinkedHashMap<GridPoint, Integer>();
        private final Map<MeshVertexKey, Integer> vertexIndices = new LinkedHashMap<MeshVertexKey, Integer>();
        private final FloatArrayBuilder vertices = new FloatArrayBuilder();
        private final FloatArrayBuilder colors = new FloatArrayBuilder();
        private final IntArrayBuilder indices = new IntArrayBuilder();

        private int retainedCount;
        private int retainedReadCursor;
        private int pointCursor;
        private int segmentCursor;
        private int visibleBlockCount;
        private int geometryPhase;
        private int currentFaceCursor;
        private int currentFaceCount;
        private boolean truncated;
        private BlockPos currentPoint;
        private BlockPos meshOrigin;
        private LineSegment[] currentSegments = new LineSegment[0];
        private Iterator<Map.Entry<GridPoint, Integer>> junctionIterator;
        private Iterator<GridEdge> edgeIterator;
        private MeshVertexKey[] currentCorners;
        private int[] currentFaces;
        private ChainPreviewMesh mesh;

        private BuildSession(Iterable<ChainTarget> targets, VisualParameters visuals) {
            this.targetIterator = targets.iterator();
            this.visuals = visuals;
        }

        /**
         * 推进到 gate 要求让出或网格完成。
         *
         * @return 完成时为 true
         */
        @Override
        public boolean advance(WorkGate gate) {
            WorkGate effectiveGate = gate == null ? NEVER_YIELD : gate;
            if (mesh != null) {
                return true;
            }

            while (!truncated && targetIterator.hasNext()) {
                if (effectiveGate.shouldYield()) {
                    return false;
                }
                ChainTarget target = targetIterator.next();
                if (target == null) {
                    continue;
                }
                if (retainedCount < retainedTargets.length) {
                    retainedTargets[retainedCount++] = target;
                } else {
                    truncated = true;
                }
            }

            while (retainedReadCursor < retainedCount) {
                if (effectiveGate.shouldYield()) {
                    return false;
                }
                ChainTarget target = retainedTargets[retainedReadCursor];
                retainedReadCursor++;
                BlockPos position = new BlockPos(target.getX(), target.getY(), target.getZ());
                if (occupancy.add(position)) {
                    if (meshOrigin == null) {
                        meshOrigin = position;
                    }
                    positions.add(position);
                }
            }

            while (pointCursor < positions.size() || currentPoint != null) {
                if (currentPoint == null) {
                    if (effectiveGate.shouldYield()) {
                        return false;
                    }
                    currentPoint = positions.get(pointCursor++);
                    currentSegments = buildVisibleSegments(currentPoint, occupancy);
                    segmentCursor = 0;
                    if (currentSegments.length > 0) {
                        visibleBlockCount++;
                    }
                }

                while (segmentCursor < currentSegments.length) {
                    if (effectiveGate.shouldYield()) {
                        return false;
                    }
                    registerEdge(currentPoint, currentSegments[segmentCursor++]);
                }
                currentPoint = null;
                currentSegments = new LineSegment[0];
            }

            if (geometryPhase == 0) {
                junctionIterator = incidence.entrySet().iterator();
                geometryPhase = 1;
            }
            if (geometryPhase == 1) {
                while (junctionIterator.hasNext() || currentCorners != null) {
                    if (currentCorners == null) {
                        if (effectiveGate.shouldYield()) {
                            return false;
                        }
                        Map.Entry<GridPoint, Integer> entry = junctionIterator.next();
                        int mask = entry.getValue().intValue();
                        if (isStraight(mask)) {
                            continue;
                        }
                        currentCorners = junctionCorners(entry.getKey());
                        currentFaces = junctionFaces(mask);
                        currentFaceCursor = 0;
                        currentFaceCount = currentFaces.length;
                    }
                    while (currentFaceCursor < currentFaceCount) {
                        if (effectiveGate.shouldYield()) {
                            return false;
                        }
                        appendFace(currentCorners, currentFaces[currentFaceCursor++]);
                    }
                    clearCurrentGeometry();
                }
                edgeIterator = edges.iterator();
                geometryPhase = 2;
            }
            if (geometryPhase == 2) {
                while (edgeIterator.hasNext() || currentCorners != null) {
                    if (currentCorners == null) {
                        if (effectiveGate.shouldYield()) {
                            return false;
                        }
                        GridEdge edge = edgeIterator.next();
                        currentCorners = tubeCorners(edge);
                        currentFaces = TUBE_FACES_BY_AXIS[edge.axis];
                        currentFaceCursor = 0;
                        currentFaceCount = currentFaces.length;
                    }
                    while (currentFaceCursor < currentFaceCount) {
                        if (effectiveGate.shouldYield()) {
                            return false;
                        }
                        appendFace(currentCorners, currentFaces[currentFaceCursor++]);
                    }
                    clearCurrentGeometry();
                }
                geometryPhase = 3;
            }

            mesh = new ChainPreviewMesh(
                vertices.backingArray(),
                vertices.size(),
                colors.backingArray(),
                colors.size(),
                indices.backingArray(),
                indices.size(),
                meshOrigin == null ? 0 : meshOrigin.x,
                meshOrigin == null ? 0 : meshOrigin.y,
                meshOrigin == null ? 0 : meshOrigin.z,
                visibleBlockCount,
                truncated);
            return true;
        }

        public boolean isComplete() {
            return mesh != null;
        }

        @Override
        public ChainPreviewMesh getMesh() {
            if (mesh == null) {
                throw new IllegalStateException("Preview mesh build is not complete");
            }
            return mesh;
        }

        private void registerEdge(BlockPos position, LineSegment segment) {
            GridEdge edge = GridEdge.from(position, segment);
            if (!edges.add(edge)) {
                return;
            }
            addIncidence(edge.start, positiveDirection(edge.axis));
            addIncidence(edge.end, negativeDirection(edge.axis));
        }

        private void addIncidence(GridPoint point, int direction) {
            Integer current = incidence.get(point);
            int mask = current == null ? 0 : current.intValue();
            incidence.put(point, Integer.valueOf(mask | direction));
        }

        private MeshVertexKey[] junctionCorners(GridPoint point) {
            return cuboidCorners(
                point.x, -1, point.x, 1,
                point.y, -1, point.y, 1,
                point.z, -1, point.z, 1);
        }

        private int[] junctionFaces(int mask) {
            int count = 0;
            for (int direction : FACE_DIRECTIONS) {
                if ((mask & direction) == 0) {
                    count++;
                }
            }
            int[] faces = new int[count];
            int cursor = 0;
            for (int face = 0; face < FACE_DIRECTIONS.length; face++) {
                if ((mask & FACE_DIRECTIONS[face]) == 0) {
                    faces[cursor++] = face;
                }
            }
            return faces;
        }

        private MeshVertexKey[] tubeCorners(GridEdge edge) {
            int startOffset = isJunction(edge.start) ? 1 : 0;
            int endOffset = isJunction(edge.end) ? -1 : 0;
            if (edge.axis == AXIS_X) {
                return cuboidCorners(
                    edge.start.x, startOffset, edge.end.x, endOffset,
                    edge.start.y, -1, edge.start.y, 1,
                    edge.start.z, -1, edge.start.z, 1);
            }
            if (edge.axis == AXIS_Y) {
                return cuboidCorners(
                    edge.start.x, -1, edge.start.x, 1,
                    edge.start.y, startOffset, edge.end.y, endOffset,
                    edge.start.z, -1, edge.start.z, 1);
            }
            return cuboidCorners(
                edge.start.x, -1, edge.start.x, 1,
                edge.start.y, -1, edge.start.y, 1,
                edge.start.z, startOffset, edge.end.z, endOffset);
        }

        private boolean isJunction(GridPoint point) {
            Integer mask = incidence.get(point);
            return mask != null && !isStraight(mask.intValue());
        }

        private void appendFace(MeshVertexKey[] corners, int face) {
            int faceOffset = face * 4;
            for (int corner = 0; corner < 4; corner++) {
                indices.add(vertexIndex(corners[CUBOID_QUAD_INDICES[faceOffset + corner]]));
            }
        }

        private int vertexIndex(MeshVertexKey key) {
            Integer existing = vertexIndices.get(key);
            if (existing != null) {
                return existing.intValue();
            }
            int index = vertices.size() / 3;
            float halfThickness = visuals.barThickness * 0.5F;
            float x = localCoordinate(key.x, key.offsetX, meshOrigin.x, halfThickness);
            float y = localCoordinate(key.y, key.offsetY, meshOrigin.y, halfThickness);
            float z = localCoordinate(key.z, key.offsetZ, meshOrigin.z, halfThickness);
            vertices.add(x);
            vertices.add(y);
            vertices.add(z);
            float alpha = visuals.alphaFor(meshOrigin.x + (double) x, meshOrigin.y + (double) y,
                meshOrigin.z + (double) z);
            colors.add(BASE_RED);
            colors.add(BASE_GREEN);
            colors.add(BASE_BLUE);
            colors.add(alpha);
            vertexIndices.put(key, Integer.valueOf(index));
            return index;
        }

        private void clearCurrentGeometry() {
            currentCorners = null;
            currentFaces = null;
            currentFaceCursor = 0;
            currentFaceCount = 0;
        }
    }

    /** 距离效果的可恢复 color-only session。 */
    public static final class ColorBuildSession implements MeshBuildSession {

        private final ChainPreviewMesh source;
        private final VisualParameters visuals;
        private float[] colors;
        private int vertexCursor;
        private ChainPreviewMesh mesh;

        private ColorBuildSession(ChainPreviewMesh source, VisualParameters visuals) {
            this.source = source;
            this.visuals = visuals;
        }

        @Override
        public boolean advance(WorkGate gate) {
            WorkGate effectiveGate = gate == null ? NEVER_YIELD : gate;
            if (mesh != null) {
                return true;
            }
            if (colors == null) {
                if (effectiveGate.shouldYield()) {
                    return false;
                }
                colors = new float[source.getColorFloatCount()];
            }

            float[] vertices = source.vertexArray();
            int vertexCount = source.getVertexFloatCount() / 3;
            while (vertexCursor < vertexCount) {
                if (effectiveGate.shouldYield()) {
                    return false;
                }
                int vertexOffset = vertexCursor * 3;
                float alpha = visuals.alphaFor(
                    source.getOriginX() + (double) vertices[vertexOffset],
                    source.getOriginY() + (double) vertices[vertexOffset + 1],
                    source.getOriginZ() + (double) vertices[vertexOffset + 2]);
                int colorOffset = vertexCursor * 4;
                colors[colorOffset] = BASE_RED;
                colors[colorOffset + 1] = BASE_GREEN;
                colors[colorOffset + 2] = BASE_BLUE;
                colors[colorOffset + 3] = alpha;
                vertexCursor++;
            }
            mesh = source.withColors(colors, colors.length);
            return true;
        }

        @Override
        public ChainPreviewMesh getMesh() {
            if (mesh == null) {
                throw new IllegalStateException("Preview mesh recolor is not complete");
            }
            return mesh;
        }
    }

    private static LineSegment[] buildVisibleSegments(BlockPos position, Set<BlockPos> occupancy) {
        Set<String> connections = new HashSet<String>();
        for (DirectionConfig direction : DIRECTION_CONFIGS) {
            if (occupancy.contains(position.offset(direction.offsetX, direction.offsetY, direction.offsetZ))) {
                connections.add(direction.direction);
            }
        }

        Set<LineSegment> visibleSegments = new LinkedHashSet<LineSegment>();
        Collections.addAll(visibleSegments, COMPLETE_SEGMENTS);
        for (String direction : connections) {
            if (direction.length() == 2) {
                removeSegments(visibleSegments, direction, connections);
            }
        }
        return visibleSegments.toArray(new LineSegment[visibleSegments.size()]);
    }

    private static void removeSegments(
            Set<LineSegment> visibleSegments, String direction, Set<String> connections) {
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

    private static int positiveDirection(int axis) {
        if (axis == AXIS_X) {
            return DIRECTION_X_POSITIVE;
        }
        if (axis == AXIS_Y) {
            return DIRECTION_Y_POSITIVE;
        }
        return DIRECTION_Z_POSITIVE;
    }

    private static int negativeDirection(int axis) {
        if (axis == AXIS_X) {
            return DIRECTION_X_NEGATIVE;
        }
        if (axis == AXIS_Y) {
            return DIRECTION_Y_NEGATIVE;
        }
        return DIRECTION_Z_NEGATIVE;
    }

    private static boolean isStraight(int mask) {
        return mask == (DIRECTION_X_NEGATIVE | DIRECTION_X_POSITIVE)
            || mask == (DIRECTION_Y_NEGATIVE | DIRECTION_Y_POSITIVE)
            || mask == (DIRECTION_Z_NEGATIVE | DIRECTION_Z_POSITIVE);
    }

    private static MeshVertexKey[] cuboidCorners(
            long minX, int minOffsetX, long maxX, int maxOffsetX,
            long minY, int minOffsetY, long maxY, int maxOffsetY,
            long minZ, int minOffsetZ, long maxZ, int maxOffsetZ) {
        return new MeshVertexKey[] {
            new MeshVertexKey(minX, minOffsetX, minY, minOffsetY, maxZ, maxOffsetZ),
            new MeshVertexKey(maxX, maxOffsetX, minY, minOffsetY, maxZ, maxOffsetZ),
            new MeshVertexKey(maxX, maxOffsetX, maxY, maxOffsetY, maxZ, maxOffsetZ),
            new MeshVertexKey(minX, minOffsetX, maxY, maxOffsetY, maxZ, maxOffsetZ),
            new MeshVertexKey(minX, minOffsetX, minY, minOffsetY, minZ, minOffsetZ),
            new MeshVertexKey(maxX, maxOffsetX, minY, minOffsetY, minZ, minOffsetZ),
            new MeshVertexKey(maxX, maxOffsetX, maxY, maxOffsetY, minZ, minOffsetZ),
            new MeshVertexKey(minX, minOffsetX, maxY, maxOffsetY, minZ, minOffsetZ)
        };
    }

    private static float localCoordinate(long coordinate, int thicknessOffset, int origin, float halfThickness) {
        return (float) ((double) coordinate - origin + thicknessOffset * (double) halfThickness);
    }

    private static Map<String, LineSegment[]> createSegmentsByDirection() {
        Map<String, LineSegment[]> map = new LinkedHashMap<String, LineSegment[]>();
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
            new DirectionConfig("YP", 0, 1, 0),
            new DirectionConfig("YN", 0, -1, 0),
            new DirectionConfig("XP", 1, 0, 0),
            new DirectionConfig("XN", -1, 0, 0),
            new DirectionConfig("ZP", 0, 0, 1),
            new DirectionConfig("ZN", 0, 0, -1),
            new DirectionConfig("XPYP", 1, 1, 0),
            new DirectionConfig("XPYN", 1, -1, 0),
            new DirectionConfig("XPZP", 1, 0, 1),
            new DirectionConfig("XPZN", 1, 0, -1),
            new DirectionConfig("XNYP", -1, 1, 0),
            new DirectionConfig("XNYN", -1, -1, 0),
            new DirectionConfig("XNZP", -1, 0, 1),
            new DirectionConfig("XNZN", -1, 0, -1),
            new DirectionConfig("YPZP", 0, 1, 1),
            new DirectionConfig("YPZN", 0, 1, -1),
            new DirectionConfig("YNZP", 0, -1, 1),
            new DirectionConfig("YNZN", 0, -1, -1)
        };
    }

    private static final class GridPoint implements Comparable<GridPoint> {

        private final long x;
        private final long y;
        private final long z;

        private GridPoint(long x, long y, long z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int compareTo(GridPoint other) {
            if (x != other.x) {
                return x < other.x ? -1 : 1;
            }
            if (y != other.y) {
                return y < other.y ? -1 : 1;
            }
            if (z != other.z) {
                return z < other.z ? -1 : 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GridPoint)) {
                return false;
            }
            GridPoint that = (GridPoint) other;
            return x == that.x && y == that.y && z == that.z;
        }

        @Override
        public int hashCode() {
            int result = longHash(x);
            result = 31 * result + longHash(y);
            result = 31 * result + longHash(z);
            return result;
        }
    }

    private static final class GridEdge {

        private final GridPoint start;
        private final GridPoint end;
        private final int axis;

        private GridEdge(GridPoint first, GridPoint second) {
            if (first.compareTo(second) <= 0) {
                start = first;
                end = second;
            } else {
                start = second;
                end = first;
            }
            if (start.x != end.x) {
                axis = AXIS_X;
            } else if (start.y != end.y) {
                axis = AXIS_Y;
            } else if (start.z != end.z) {
                axis = AXIS_Z;
            } else {
                throw new IllegalArgumentException("grid edge endpoints must differ");
            }
        }

        private static GridEdge from(BlockPos position, LineSegment segment) {
            int firstOffset = segment.start * 3;
            int secondOffset = segment.end * 3;
            GridPoint first = new GridPoint(
                (long) position.x + (long) UNIT_CUBE_VERTICES[firstOffset],
                (long) position.y + (long) UNIT_CUBE_VERTICES[firstOffset + 1],
                (long) position.z + (long) UNIT_CUBE_VERTICES[firstOffset + 2]);
            GridPoint second = new GridPoint(
                (long) position.x + (long) UNIT_CUBE_VERTICES[secondOffset],
                (long) position.y + (long) UNIT_CUBE_VERTICES[secondOffset + 1],
                (long) position.z + (long) UNIT_CUBE_VERTICES[secondOffset + 2]);
            return new GridEdge(first, second);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GridEdge)) {
                return false;
            }
            GridEdge that = (GridEdge) other;
            return start.equals(that.start) && end.equals(that.end);
        }

        @Override
        public int hashCode() {
            return 31 * start.hashCode() + end.hashCode();
        }
    }

    private static final class MeshVertexKey {

        private final long x;
        private final long y;
        private final long z;
        private final int offsetX;
        private final int offsetY;
        private final int offsetZ;

        private MeshVertexKey(long x, int offsetX, long y, int offsetY, long z, int offsetZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MeshVertexKey)) {
                return false;
            }
            MeshVertexKey that = (MeshVertexKey) other;
            return x == that.x && y == that.y && z == that.z
                && offsetX == that.offsetX && offsetY == that.offsetY && offsetZ == that.offsetZ;
        }

        @Override
        public int hashCode() {
            int result = longHash(x);
            result = 31 * result + longHash(y);
            result = 31 * result + longHash(z);
            result = 31 * result + offsetX;
            result = 31 * result + offsetY;
            result = 31 * result + offsetZ;
            return result;
        }
    }

    private static int longHash(long value) {
        return (int) (value ^ (value >>> 32));
    }

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
            long nextX = (long) x + offsetX;
            long nextY = (long) y + offsetY;
            long nextZ = (long) z + offsetZ;
            if (nextX < Integer.MIN_VALUE || nextX > Integer.MAX_VALUE
                    || nextY < Integer.MIN_VALUE || nextY > Integer.MAX_VALUE
                    || nextZ < Integer.MIN_VALUE || nextZ > Integer.MAX_VALUE) {
                return null;
            }
            return new BlockPos((int) nextX, (int) nextY, (int) nextZ);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BlockPos)) {
                return false;
            }
            BlockPos that = (BlockPos) other;
            return x == that.x && y == that.y && z == that.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }

    private static final class DirectionConfig {

        private final String direction;
        private final int offsetX;
        private final int offsetY;
        private final int offsetZ;

        private DirectionConfig(String direction, int offsetX, int offsetY, int offsetZ) {
            this.direction = direction;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }
    }

    private static final class LineSegment {

        private final int start;
        private final int end;

        private LineSegment(int first, int second) {
            this.start = Math.min(first, second);
            this.end = Math.max(first, second);
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
            return 31 * start + end;
        }
    }

    private static final class FloatArrayBuilder {

        private float[] values = new float[256];
        private int size;

        private void add(float value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private float[] backingArray() {
            return values;
        }

        private void ensureCapacity(int required) {
            if (required <= values.length) {
                return;
            }
            int capacity = growCapacity(values.length, required);
            float[] grown = new float[capacity];
            System.arraycopy(values, 0, grown, 0, size);
            values = grown;
        }
    }

    private static final class IntArrayBuilder {

        private int[] values = new int[256];
        private int size;

        private void add(int value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private int[] backingArray() {
            return values;
        }

        private void ensureCapacity(int required) {
            if (required <= values.length) {
                return;
            }
            int capacity = growCapacity(values.length, required);
            int[] grown = new int[capacity];
            System.arraycopy(values, 0, grown, 0, size);
            values = grown;
        }
    }

    private static int growCapacity(int current, int required) {
        int capacity = Math.max(16, current);
        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }
            capacity *= 2;
        }
        return capacity;
    }
}
