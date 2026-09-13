package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 波次 5 独立全量参考模型（T27）：不复用 owner 的差分测试，也不调用其构建器算期望值。
 *
 * <p>规则来源是「几何第一性原理」而非 owner 代码表：单位方块画 12 条棱，面相邻则隐藏共享面的 4 条棱、
 * 边相邻（对角）则隐藏共享的 1 条棱；当对角邻格为空但两侧面邻格都被占用时，该棱是并集的凹边，
 * 必须补回。接头（incidence 非直通）画 2×2×2 立方体上「缺方向」的面，管段画横截面 4 面，
 * 端点为接头时向内侧缩进半厚。顶点按（格点 + 半厚偏移）全局焊接。</p>
 *
 * <p>与 owner 表格的等价性已逐条核对：6 个面的 4 棱、12 个对角的共享棱、以及横截面槽位
 * （X:{0,1,2,3}、Y:{0,1,4,5}、Z:{2,3,4,5}）均与本模型的几何推导一致。</p>
 *
 * <p>比较口径：顶点位置按位（float bits）成集合比较，quad 按其 4 个角点位置的规范键成集合比较，
 * 顶点顺序无关；每顶点 aux 只断言顺序无关事实（最小出现序号、类别随序号、接头首写 0xFF、
 * 管段槽位必须属于「写该顶点的管面槽位集合」）。差分强度由 {@link #compare} 的分类返回保证，
 * 变异检测用例会强制其能识别丢面/位移/原点漂移/aux 错值。</p>
 */
public final class VerifyMeshReferenceModel {

    /** 顶点种别：semanticClass 兜底值（接口冻结 §D）。 */
    public static final int UNDEFINED = 255;
    /** appearOrder 未定义值（接口冻结 §D）。 */
    public static final int APPEAR_ORDER_UNDEFINED = 0xFFFF;
    public static final int MAX_RENDER_TARGETS = 4096;
    public static final int AUX_BYTES_PER_VERTEX = 4;

    private static final int[] FACE_SLOTS_X = {0, 1, 2, 3};
    private static final int[] FACE_SLOTS_Y = {0, 1, 4, 5};
    private static final int[] FACE_SLOTS_Z = {2, 3, 4, 5};
    /** CUBOID_QUAD_INDICES：面序 [ZP, ZN, YN, YP, XN, XP] 的角点顺序（几何约定，非代码表）。 */
    private static final int[][] FACE_QUADS = {
        {0, 1, 2, 3}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 2, 6, 7}, {0, 3, 7, 4}, {1, 2, 6, 5}
    };
    /** 面方向位（ZP/ZN/YN/YP/XN/XP，与 FACE_QUADS 同序）。 */
    private static final int[] FACE_BITS = {32, 16, 4, 8, 1, 2};
    /** incidence 位：负向 = 1<<(2*axis)，正向 = 1<<(2*axis+1)（X:1/2、Y:4/8、Z:16/32）。 */
    private static final int[] POSITIVE_BITS = {2, 8, 32};
    private static final int[] NEGATIVE_BITS = {1, 4, 16};
    /** cuboidCorners 的 12 个参数位：角点索引 -> 参数下标（minX,minOffX,maxX,maxOffX,minY,...）。 */
    private static final int[][] CORNER_PARAMS = {
        {0, 1, 4, 5, 10, 11}, {2, 3, 4, 5, 10, 11}, {2, 3, 6, 7, 10, 11}, {0, 1, 6, 7, 10, 11},
        {0, 1, 4, 5, 8, 9}, {2, 3, 4, 5, 8, 9}, {2, 3, 6, 7, 8, 9}, {0, 1, 6, 7, 8, 9}
    };

    private VerifyMeshReferenceModel() {
    }

    /** 单个顶点的独立期望（顺序无关事实）。 */
    public static final class VertexExpectation {

        public int appearOrder;
        public int semanticClass;
        /** 是否被接头面写过：接头相位先于管面相位，故首写者若为接头则 tubeEdge 必为 255。 */
        public boolean writtenByJunction;
        public final Set<Integer> tubeSlots = new LinkedHashSet<Integer>();
    }

    /** 全量期望：顶点位置集合、quad 集合、逐顶点 aux 事实与计数。 */
    public static final class Expectation {

        public final Map<String, VertexExpectation> vertices = new LinkedHashMap<String, VertexExpectation>();
        public final Set<String> quads = new LinkedHashSet<String>();
        public final List<String> positionKeys = new ArrayList<String>();
        public int visibleBlockCount;
        public int culledTargetCount;
        public boolean truncated;
        public int originX;
        public int originY;
        public int originZ;
        public int quadCount;
        public int semanticFallbackCount;
    }

    /** 差异分类（差分强度：每类独立可见，避免「整体不等」式假通过）。 */
    public static final class Mismatch {

        public final String category;
        public final String detail;

        Mismatch(String category, String detail) {
            this.category = category;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return category + ": " + detail;
        }
    }

    /** 独立归一化：0..5 与 255 合法，其余一律 UNDEFINED（与接口冻结 §D 同口径）。 */
    public static int normalizeSemanticClass(int value) {
        return (value >= 0 && value <= 5) || value == UNDEFINED ? value : UNDEFINED;
    }

    /**
     * 计算全量期望。
     *
     * @param targets 目标流（迭代顺序 = appearOrder 顺序）
     * @param semanticClasses 与目标流同序的类别载体；null 表示未提供（全 255、不计降级）
     * @param barThickness 条柱厚度
     * @param lodEnabled LOD 档位（本模型只覆盖 lod=off；true 时按中心 alpha 剔除不建模）
     */
    public static Expectation build(
            List<ChainTarget> targets, int[] semanticClasses, float barThickness, boolean lodEnabled) {
        return build(targets, semanticClasses, barThickness, lodEnabled, Integer.MIN_VALUE, 0, 0);
    }

    /**
     * 显式锚点版本（B4.1 增量会话可指定 meshOrigin；重锚后锚点不再是首个目标）。
     *
     * @param originX 锚点 X；{@code Integer.MIN_VALUE} 表示按「首个保留目标」自动取
     */
    public static Expectation build(
            List<ChainTarget> targets, int[] semanticClasses, float barThickness, boolean lodEnabled,
            int originX, int originY, int originZ) {
        Expectation expectation = new Expectation();
        if (lodEnabled) {
            throw new IllegalArgumentException("reference model covers lod=off only");
        }
        float half = Math.max(0.001F, Math.min(0.99F, barThickness)) * 0.5F;
        List<int[]> positions = new ArrayList<int[]>();
        List<Integer> orders = new ArrayList<Integer>();
        Set<String> occupancy = new LinkedHashSet<String>();
        int readIndex = 0;
        for (ChainTarget target : targets) {
            int semanticClass = UNDEFINED;
            if (target == null) {
                readIndex++;
                continue;
            }
            if (semanticClasses == null) {
                semanticClass = UNDEFINED;
            } else if (readIndex >= semanticClasses.length) {
                semanticClass = UNDEFINED;
                expectation.semanticFallbackCount++;
            } else {
                int raw = semanticClasses[readIndex];
                semanticClass = normalizeSemanticClass(raw);
                if (semanticClass != raw) {
                    expectation.semanticFallbackCount++;
                }
            }
            readIndex++;
            String key = target.getX() + "," + target.getY() + "," + target.getZ();
            if (!occupancy.add(key)) {
                continue;
            }
            if (occupancy.size() > MAX_RENDER_TARGETS) {
                occupancy.remove(key);
                expectation.truncated = true;
                break;
            }
            positions.add(new int[] {target.getX(), target.getY(), target.getZ()});
            orders.add(Integer.valueOf(semanticClass));
        }
        if (positions.isEmpty()) {
            return expectation;
        }
        boolean autoOrigin = originX == Integer.MIN_VALUE;
        expectation.originX = autoOrigin ? positions.get(0)[0] : originX;
        expectation.originY = autoOrigin ? positions.get(0)[1] : originY;
        expectation.originZ = autoOrigin ? positions.get(0)[2] : originZ;

        // 1) 可见棱集合：从 12 条棱里按 26 邻域占用情况删隐藏棱 / 补凹边。
        Set<String> edges = new LinkedHashSet<String>();
        Map<String, Integer> edgeOrder = new HashMap<String, Integer>();
        Map<String, Integer> incidence = new LinkedHashMap<String, Integer>();
        Map<String, Integer> junctionOrder = new HashMap<String, Integer>();
        for (int index = 0; index < positions.size(); index++) {
            int[] position = positions.get(index);
            List<String> positionEdges = visibleCubeEdges(position, occupancy);
            if (!positionEdges.isEmpty()) {
                expectation.visibleBlockCount++;
            }
            for (String edgeKey : positionEdges) {
                if (!edges.add(edgeKey)) {
                    continue;
                }
                edgeOrder.put(edgeKey, Integer.valueOf(index));
                String[] points = edgeKey.split(";");
                String low = points[0];
                String high = points[1];
                int axis = differingAxis(low, high);
                addIncidence(incidence, low, POSITIVE_BITS[axis], index, junctionOrder);
                addIncidence(incidence, high, NEGATIVE_BITS[axis], index, junctionOrder);
            }
        }

        // 2) 接头面：缺方向的面（非直通接头）。
        for (Map.Entry<String, Integer> entry : incidence.entrySet()) {
            int mask = entry.getValue().intValue();
            if (isStraight(mask)) {
                continue;
            }
            Integer order = junctionOrder.get(entry.getKey());
            int appearOrder = order == null ? APPEAR_ORDER_UNDEFINED : order.intValue();
            String[] point = entry.getKey().split(",");
            int pointX = Integer.parseInt(point[0]);
            int pointY = Integer.parseInt(point[1]);
            int pointZ = Integer.parseInt(point[2]);
            for (int face = 0; face < FACE_BITS.length; face++) {
                if ((mask & FACE_BITS[face]) != 0) {
                    continue;
                }
                addFace(expectation, half, anchorOrigin(expectation), junctionCorners(pointX, pointY, pointZ),
                    FACE_QUADS[face], APPEAR_ORDER_UNDEFINED, true, appearOrder, orders);
            }
        }

        // 3) 管段面：横截面 4 面，端点接头处缩进半厚。
        for (Map.Entry<String, Integer> entry : edgeOrder.entrySet()) {
            String[] points = entry.getKey().split(";");
            int[] low = parsePoint(points[0]);
            int[] high = parsePoint(points[1]);
            int axis = differingAxis(points[0], points[1]);
            int startOffset = isJunction(incidence, points[0]) ? 1 : 0;
            int endOffset = isJunction(incidence, points[1]) ? -1 : 0;
            int[] slots = axis == 0 ? FACE_SLOTS_X : (axis == 1 ? FACE_SLOTS_Y : FACE_SLOTS_Z);
            int[] corners = tubeCorners(low, high, axis, startOffset, endOffset);
            for (int slot = 0; slot < slots.length; slot++) {
                addFace(expectation, half, anchorOrigin(expectation), corners,
                    FACE_QUADS[slots[slot]], slot, false, entry.getValue().intValue(), orders);
            }
        }
        return expectation;
    }

    private static int[] anchorOrigin(Expectation expectation) {
        return new int[] {expectation.originX, expectation.originY, expectation.originZ};
    }

    private static void addFace(
            Expectation expectation, float half, int[] origin, int[] corners, int[] cornerIndices,
            int tubeEdge, boolean junction, int appearOrder, List<Integer> semanticClasses) {
        String[] keys = new String[4];
        for (int corner = 0; corner < 4; corner++) {
            String logical = logicalKey(corners, cornerIndices[corner]);
            keys[corner] = positionKey(logical, half, origin);
            VertexExpectation vertex = expectation.vertices.get(keys[corner]);
            if (vertex == null) {
                vertex = new VertexExpectation();
                vertex.appearOrder = appearOrder;
                vertex.semanticClass = semanticClassForOrder(appearOrder, semanticClasses);
                expectation.vertices.put(keys[corner], vertex);
                expectation.positionKeys.add(keys[corner]);
            } else if (appearOrder != APPEAR_ORDER_UNDEFINED
                    && (vertex.appearOrder == APPEAR_ORDER_UNDEFINED || appearOrder < vertex.appearOrder)) {
                // mergeAppearOrder：出现序号取所有 incident 写入的最小值，类别随序号一起更新。
                vertex.appearOrder = appearOrder;
                vertex.semanticClass = semanticClassForOrder(appearOrder, semanticClasses);
            }
            if (junction) {
                vertex.writtenByJunction = true;
            } else if (!vertex.writtenByJunction) {
                vertex.tubeSlots.add(Integer.valueOf(tubeEdge));
            }
        }
        Arrays.sort(keys);
        StringBuilder quadKey = new StringBuilder();
        for (String key : keys) {
            quadKey.append(key).append(';');
        }
        expectation.quads.add(quadKey.toString());
        expectation.quadCount++;
    }

    private static int semanticClassForOrder(int appearOrder, List<Integer> semanticClasses) {
        if (appearOrder == APPEAR_ORDER_UNDEFINED || appearOrder < 0 || appearOrder >= semanticClasses.size()) {
            return UNDEFINED;
        }
        return semanticClasses.get(appearOrder).intValue();
    }

    private static int[] junctionCorners(int x, int y, int z) {
        // 2×2×2 立方体：min=max=格点，偏移 ±1。
        return new int[] {x, -1, x, 1, y, -1, y, 1, z, -1, z, 1};
    }

    private static int[] tubeCorners(int[] low, int[] high, int axis, int startOffset, int endOffset) {
        if (axis == 0) {
            return new int[] {low[0], startOffset, high[0], endOffset, low[1], -1, low[1], 1, low[2], -1, low[2], 1};
        }
        if (axis == 1) {
            return new int[] {low[0], -1, low[0], 1, low[1], startOffset, high[1], endOffset, low[2], -1, low[2], 1};
        }
        return new int[] {low[0], -1, low[0], 1, low[1], -1, low[1], 1, low[2], startOffset, high[2], endOffset};
    }

    private static String logicalKey(int[] corners, int index) {
        int[] params = CORNER_PARAMS[index];
        StringBuilder key = new StringBuilder();
        for (int param = 0; param < params.length; param++) {
            key.append(corners[params[param]]);
            if (param < params.length - 1) {
                key.append(',');
            }
        }
        return key.toString();
    }

    private static String positionKey(String logical, float half, int[] origin) {
        String[] values = logical.split(",");
        long x = Long.parseLong(values[0]);
        int offsetX = Integer.parseInt(values[1]);
        long y = Long.parseLong(values[2]);
        int offsetY = Integer.parseInt(values[3]);
        long z = Long.parseLong(values[4]);
        int offsetZ = Integer.parseInt(values[5]);
        return bits(local(x, offsetX, origin[0], half)) + ","
            + bits(local(y, offsetY, origin[1], half)) + ","
            + bits(local(z, offsetZ, origin[2], half));
    }

    /** 与生产同式的局部坐标（float 位比较要求逐位一致）。 */
    private static float local(long coordinate, int offset, int origin, float half) {
        return (float) ((double) coordinate - origin + offset * (double) half);
    }

    private static String bits(float value) {
        return Integer.toHexString(Float.floatToIntBits(value));
    }

    private static void addIncidence(
            Map<String, Integer> incidence, String point, int direction, int appearOrder,
            Map<String, Integer> junctionOrder) {
        Integer current = incidence.get(point);
        incidence.put(point, Integer.valueOf((current == null ? 0 : current.intValue()) | direction));
        if (!junctionOrder.containsKey(point)) {
            junctionOrder.put(point, Integer.valueOf(appearOrder));
        }
    }

    private static boolean isJunction(Map<String, Integer> incidence, String point) {
        Integer mask = incidence.get(point);
        return mask != null && !isStraight(mask.intValue());
    }

    private static boolean isStraight(int mask) {
        return mask == (NEGATIVE_BITS[0] | POSITIVE_BITS[0])
            || mask == (NEGATIVE_BITS[1] | POSITIVE_BITS[1])
            || mask == (NEGATIVE_BITS[2] | POSITIVE_BITS[2]);
    }

    private static int differingAxis(String low, String high) {
        String[] first = low.split(",");
        String[] second = high.split(",");
        for (int axis = 0; axis < 3; axis++) {
            if (!first[axis].equals(second[axis])) {
                return axis;
            }
        }
        throw new IllegalArgumentException("edge endpoints must differ: " + low + " / " + high);
    }

    private static int[] parsePoint(String value) {
        String[] parts = value.split(",");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    /** 12 条单位立方体棱（角点用 (x,y,z) 偏移表示，索引与几何约定一致）。 */
    private static final int[][] CUBE_VERTICES = {
        {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1},
        {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}
    };
    private static final int[][] CUBE_EDGES = {
        {0, 1}, {1, 2}, {2, 3}, {0, 3}, {4, 5}, {5, 6}, {6, 7}, {4, 7}, {2, 6}, {3, 7}, {0, 4}, {1, 5}
    };
    /** 6 个面方向的法向（±X/±Y/±Z），面序与 FACE_QUADS 一致。 */
    private static final int[][] FACE_NORMALS = {
        {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}
    };

    /**
     * 独立推导某位置的可见棱（26 邻域）：面相邻删面 4 棱、对角相邻删共享棱、
     * 对角为空但两面邻都在时补回凹边。
     */
    private static List<String> visibleCubeEdges(int[] position, Set<String> occupancy) {
        boolean[][] connected = new boolean[3][3];
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int[] neighbor = {position[0], position[1], position[2]};
                neighbor[axis] += sign;
                if (occupancy.contains(key(neighbor))) {
                    connected[axis][sign + 1] = true;
                }
            }
        }
        boolean[] diagonalOccupied = new boolean[12];
        int[][] diagonalComponents = new int[12][];
        int cursor = 0;
        for (int first = 0; first < 3; first++) {
            for (int second = first + 1; second < 3; second++) {
                for (int signA = -1; signA <= 1; signA += 2) {
                    for (int signB = -1; signB <= 1; signB += 2) {
                        int[] neighbor = {position[0], position[1], position[2]};
                        neighbor[first] += signA;
                        neighbor[second] += signB;
                        diagonalOccupied[cursor] = occupancy.contains(key(neighbor));
                        diagonalComponents[cursor] = new int[] {first, signA + 1, second, signB + 1};
                        cursor++;
                    }
                }
            }
        }
        Set<Integer> visible = new LinkedHashSet<Integer>();
        for (int edge = 0; edge < CUBE_EDGES.length; edge++) {
            visible.add(Integer.valueOf(edge));
        }
        for (int face = 0; face < FACE_NORMALS.length; face++) {
            int[] normal = FACE_NORMALS[face];
            int faceAxis = normal[0] != 0 ? 0 : (normal[1] != 0 ? 1 : 2);
            if (!connected[faceAxis][normal[faceAxis] + 1]) {
                continue;
            }
            for (int edge = 0; edge < CUBE_EDGES.length; edge++) {
                if (edgeOnFace(CUBE_EDGES[edge], faceAxis, normal[faceAxis])) {
                    visible.remove(Integer.valueOf(edge));
                }
            }
        }
        for (int diagonal = 0; diagonal < 12; diagonal++) {
            int[] components = diagonalComponents[diagonal];
            // 对角占用不直接删棱：面方向删棱已覆盖共享棱的情形，对角占用只是抑制凹边补回
            // （与生产实现只在 length==2 方向触发 removeSegments 一致）。
            if (diagonalOccupied[diagonal]) {
                continue;
            }
            if (connected[components[0]][components[1]] && connected[components[2]][components[3]]) {
                visible.add(Integer.valueOf(sharedEdge(components[0], components[1], components[2], components[3])));
            }
        }
        List<String> keys = new ArrayList<String>(visible.size());
        for (Integer edge : visible) {
            int[] first = CUBE_VERTICES[CUBE_EDGES[edge.intValue()][0]];
            int[] second = CUBE_VERTICES[CUBE_EDGES[edge.intValue()][1]];
            int[] pointA = {position[0] + first[0], position[1] + first[1], position[2] + first[2]};
            int[] pointB = {position[0] + second[0], position[1] + second[1], position[2] + second[2]};
            keys.add(edgeKey(pointA, pointB));
        }
        return keys;
    }

    private static boolean edgeOnFace(int[] edge, int faceAxis, int faceSign) {
        int[] first = CUBE_VERTICES[edge[0]];
        int[] second = CUBE_VERTICES[edge[1]];
        int expected = faceSign > 0 ? 1 : 0;
        return first[faceAxis] == expected && second[faceAxis] == expected;
    }

    /** 对角共享棱：两个非零轴取极值、第三轴自由。 */
    private static int sharedEdge(int axisA, int signA, int axisB, int signB) {
        int freeAxis = 3 - axisA - axisB;
        int[] target = new int[3];
        target[axisA] = (signA + 1) / 2;
        target[axisB] = (signB + 1) / 2;
        for (int edge = 0; edge < CUBE_EDGES.length; edge++) {
            int[] first = CUBE_VERTICES[CUBE_EDGES[edge][0]];
            int[] second = CUBE_VERTICES[CUBE_EDGES[edge][1]];
            if (first[freeAxis] == second[freeAxis]) {
                continue;
            }
            if (matches(first, target, axisA, axisB) && matches(second, target, axisA, axisB)) {
                return edge;
            }
        }
        throw new IllegalStateException("no shared edge for diagonal");
    }

    private static boolean matches(int[] vertex, int[] target, int axisA, int axisB) {
        return vertex[axisA] == target[axisA] && vertex[axisB] == target[axisB];
    }

    private static String key(int[] position) {
        return position[0] + "," + position[1] + "," + position[2];
    }

    private static String edgeKey(int[] first, int[] second) {
        return comparePoint(first, second) <= 0
            ? key(first) + ";" + key(second)
            : key(second) + ";" + key(first);
    }

    /** 数值字典序（x,y,z）；不可用字符串序，负坐标下字符串序与数值序不一致。 */
    private static int comparePoint(int[] first, int[] second) {
        for (int axis = 0; axis < 3; axis++) {
            if (first[axis] != second[axis]) {
                return first[axis] < second[axis] ? -1 : 1;
            }
        }
        return 0;
    }

    /** 与实际网格比较（顶点顺序无关）；返回空列表表示等价。 */
    public static List<Mismatch> compare(Expectation expected, ChainPreviewMesh mesh) {
        return compare(expected, mesh.getVertices(), mesh.getVertexFloatCount(), mesh.getIndices(),
            mesh.getIndexCount(), mesh.getAux(), mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(),
            mesh.getBlockCount(), mesh.isTruncated(), mesh.getCulledTargetCount());
    }

    /** 与实际数组比较（便于变异检测：可传入被人为破坏的副本）。 */
    public static List<Mismatch> compare(
            Expectation expected, float[] vertices, int vertexFloatCount, int[] indices, int indexCount,
            byte[] aux, int originX, int originY, int originZ, int blockCount, boolean truncated,
            int culledTargetCount) {
        List<Mismatch> mismatches = new ArrayList<Mismatch>();
        int actualVertexCount = vertexFloatCount / 3;
        if (actualVertexCount != expected.vertices.size()) {
            mismatches.add(new Mismatch("vertexCount",
                "expected " + expected.vertices.size() + " but was " + actualVertexCount));
        }
        Map<String, Integer> actualIndexByPosition = new HashMap<String, Integer>();
        for (int vertex = 0; vertex < actualVertexCount; vertex++) {
            String position = bits(vertices[vertex * 3]) + "," + bits(vertices[vertex * 3 + 1])
                + "," + bits(vertices[vertex * 3 + 2]);
            if (actualIndexByPosition.put(position, Integer.valueOf(vertex)) != null) {
                mismatches.add(new Mismatch("duplicateVertexPosition", "position " + position));
            }
            if (!expected.vertices.containsKey(position)) {
                mismatches.add(new Mismatch("unexpectedVertexPosition", "position " + position));
            }
        }
        for (String position : expected.vertices.keySet()) {
            if (!actualIndexByPosition.containsKey(position)) {
                mismatches.add(new Mismatch("missingVertexPosition", "position " + position));
                break;
            }
        }
        Set<String> actualQuads = new HashSet<String>();
        int actualQuadCount = indexCount / 4;
        for (int quad = 0; quad < actualQuadCount; quad++) {
            String[] corners = new String[4];
            boolean valid = true;
            for (int corner = 0; corner < 4; corner++) {
                int vertex = indices[quad * 4 + corner];
                if (vertex < 0 || vertex >= actualVertexCount) {
                    mismatches.add(new Mismatch("indexOutOfRange", "index " + vertex));
                    valid = false;
                    break;
                }
                corners[corner] = bits(vertices[vertex * 3]) + "," + bits(vertices[vertex * 3 + 1])
                    + "," + bits(vertices[vertex * 3 + 2]);
            }
            if (!valid) {
                continue;
            }
            Arrays.sort(corners);
            StringBuilder quadKey = new StringBuilder();
            for (String corner : corners) {
                quadKey.append(corner).append(';');
            }
            actualQuads.add(quadKey.toString());
        }
        if (actualQuadCount != expected.quadCount) {
            mismatches.add(new Mismatch("quadCount",
                "expected " + expected.quadCount + " but was " + actualQuadCount));
        }
        if (!actualQuads.equals(expected.quads)) {
            int missing = 0;
            for (String quad : expected.quads) {
                if (!actualQuads.contains(quad)) {
                    missing++;
                }
            }
            mismatches.add(new Mismatch("quadSet",
                "expected " + expected.quads.size() + " quads, missing " + missing
                    + ", extra " + (actualQuads.size() - (expected.quads.size() - missing))));
        }
        if (aux.length != actualVertexCount * AUX_BYTES_PER_VERTEX) {
            mismatches.add(new Mismatch("auxLength", "aux bytes " + aux.length
                + " for " + actualVertexCount + " vertices"));
        } else if (aux.length > 0) {
            for (Map.Entry<String, Integer> entry : actualIndexByPosition.entrySet()) {
                VertexExpectation expectation = expected.vertices.get(entry.getKey());
                if (expectation == null) {
                    continue;
                }
                int vertex = entry.getValue().intValue();
                int offset = vertex * AUX_BYTES_PER_VERTEX;
                int semanticClass = aux[offset] & 0xFF;
                int tubeEdge = aux[offset + 1] & 0xFF;
                int appearOrder = (aux[offset + 2] & 0xFF) | ((aux[offset + 3] & 0xFF) << 8);
                if (appearOrder != expectation.appearOrder) {
                    mismatches.add(new Mismatch("auxAppearOrder", "vertex " + vertex + " expected "
                        + expectation.appearOrder + " but was " + appearOrder));
                    break;
                }
                if (semanticClass != expectation.semanticClass) {
                    mismatches.add(new Mismatch("auxSemanticClass", "vertex " + vertex + " expected "
                        + expectation.semanticClass + " but was " + semanticClass));
                    break;
                }
                if (expectation.writtenByJunction) {
                    if (tubeEdge != UNDEFINED) {
                        mismatches.add(new Mismatch("auxTubeEdge", "junction-first vertex " + vertex
                            + " expected 255 but was " + tubeEdge));
                        break;
                    }
                } else if (!expectation.tubeSlots.contains(Integer.valueOf(tubeEdge))) {
                    mismatches.add(new Mismatch("auxTubeEdge", "vertex " + vertex
                        + " tubeEdge " + tubeEdge + " not in " + expectation.tubeSlots));
                    break;
                }
            }
        }
        if (originX != expected.originX || originY != expected.originY || originZ != expected.originZ) {
            mismatches.add(new Mismatch("origin", "expected (" + expected.originX + "," + expected.originY
                + "," + expected.originZ + ") but was (" + originX + "," + originY + "," + originZ + ")"));
        }
        if (blockCount != expected.visibleBlockCount) {
            mismatches.add(new Mismatch("blockCount", "expected " + expected.visibleBlockCount
                + " but was " + blockCount));
        }
        if (truncated != expected.truncated) {
            mismatches.add(new Mismatch("truncated", "expected " + expected.truncated + " but was " + truncated));
        }
        if (culledTargetCount != expected.culledTargetCount) {
            mismatches.add(new Mismatch("culledTargetCount", "expected " + expected.culledTargetCount
                + " but was " + culledTargetCount));
        }
        return mismatches;
    }
}