package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 把 preview target 增量转换为由 quad 面组成的无重叠边框网格。
 *
 * <p>拓扑会在每个 target、可见边和 quad 前观察 {@link WorkGate}；视觉参数在 session
 * 创建时冻结，后续可独立替换距离效果而不改变 target/邻接算法。</p>
 *
 * <p>B0.7：target 先做有界去重（{@link #MAX_RENDER_TARGETS} 个唯一坐标）再占用配额，
 * 重复 target 不再消耗配额，也不改变 {@code ChainPreviewState} 的计数语义。</p>
 *
 * <p>语义顶点流 aAux（顶点数 × {@link ChainPreviewMesh#AUX_BYTES_PER_VERTEX}）：x=semanticClass、
 * y=tubeEdge、z/w=appearOrder（u16 小端）。appearOrder 为 target 进入预览集合的序号（0 起，
 * 同代递增、跨代重置）；顶点按 {@link MeshVertexKey} 去重后，同一顶点的 appearOrder 取所有
 * incident 写入者的最小值，无归属时写 0xFFFF。tubeEdge 取首写者，本轮实测可达 {0, 1, 255}：
 * 相邻链直通格点的 tube 相顶点只首写槽位 0/1，junction 相与共享顶点为
 * {@link ChainPreviewMesh#AUX_UNDEFINED}；槽位 2/3 在「junction 相先于 tube 相」的首写策略下
 * 不可达（每端点 4 角点已被 junction 的两个面全覆盖），留待 B2.3/B3.x 再评估。
 * <p>B2.4 LOD（lod=auto）：构建期按方块中心 alpha &lt;= lodMinAlpha 剔除远处散点目标，
 * 双阈值 enter=lodMinAlpha / exit=lodMinAlpha+{@link VisualParameters#LOD_EXIT_ALPHA_MARGIN} 防抖；
 * 剔除不占用 4096 配额，也不生成任何顶点/索引/aux。已知限制（本轮登记，不引入高风险改动）：
 * (a) 剔除集合变化会重排 appearOrder（连续性不变、序号压缩），lod=auto + animation 开启时
 * 生长窗口可能跳变一次；(b) 滞回记忆满 {@link #MAX_RENDER_TARGETS} 时整体清空，丢一次滞回。
 * 超距合并/外壳档位（拓扑改写）未实现，下一批评估。</p>
 *
 * semanticClass 由构建入口的类别载体按目标出现序号提供（id 冻结源 {@link ChainPreviewSemanticClass}，
 * 即接口冻结 §D 的 0..5 或 255 UNDEFINED），去重后与 appearOrder 同源、取最小 incident 目标；
 * 载体缺失/越界/非法值一律按 255 兜底并计数，见
 * {@link BuildSession#getSemanticClassFallbackCount()} 与 {@link #getSemanticClassFallbackTotal()}。</p>
 */
public class ChainPreviewMeshBuilder {

    public static final int MAX_RENDER_TARGETS = 4096;
    /** 代级重锚距离默认值（格，Chebyshev）：新增目标距锚点超过该值时整代重锚。 */
    public static final int DEFAULT_REANCHOR_DISTANCE = 256;
    /** debug 计数器：加载语义类别载体时按 255 兜底的目标准数（跨 session 累加）。 */
    private static final AtomicLong SEMANTIC_CLASS_FALLBACKS = new AtomicLong();
    /**
     * LOD 双阈值记忆：被剔除目标的位置，避免在 enter/exit 之间翻转闪烁。
     * **只归构建线程所有**（无锁），有界于 {@link #MAX_RENDER_TARGETS}；
     * 生命周期/换代经 {@link #resetLodHysteresis()} 置位请求，由构建线程消费后清理。
     */
    private final Set<BlockPos> lodCulledPositions = new HashSet<BlockPos>();
    /** 清理请求位：主线程置位、构建线程消费；避免主线程直接触碰构建线程的集合。 */
    private final LodResetSignal lodHysteresisResetSignal = new LodResetSignal();
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

    /**
     * 请求清理 LOD 双阈值记忆（生命周期 / 换代时调用）。
     *
     * <p>线程契约：本方法可能由主线程调用，因此**只置位 volatile 请求位后立即返回**，
     * 不触碰 {@link #lodCulledPositions}；集合只归构建线程所有，由构建线程在分片开始与
     * 构建结束时消费该位并实际清理：置位后的下一次构建分片生效，未置位不清，重复置位幂等。
     * 每秒相机刷新重建 session 不会触发本方法，滞回记忆按设计保留。</p>
     */
    public void resetLodHysteresis() {
        lodHysteresisResetSignal.request();
    }

    /**
     * @return 当前 LOD 双阈值记忆中的位置数（诊断/测试用；**仅构建线程读取**，
     *         只在没有并发构建分片时调用）。每次成功构建结束时会把记忆裁剪为
     *         「本轮实际剔除的位置」，因此不会跨代残留未再出现的旧目标
     */
    public int getLodHysteresisMemorySize() {
        return lodCulledPositions.size();
    }

    /** 创建可跨 tick 恢复的 CPU build session。 */
    public BuildSession begin(Iterable<ChainTarget> previewTargets, VisualParameters visualParameters) {
        Iterable<ChainTarget> targets = previewTargets == null
            ? Collections.<ChainTarget>emptyList()
            : previewTargets;
        VisualParameters visuals = visualParameters == null
            ? VisualParameters.fromCurrentConfig(0.0D, 0.0D, 0.0D)
            : visualParameters;
        return new BuildSession(
            targets, visuals, null, lodCulledPositions, lodHysteresisResetSignal, null);
    }

    /**
     * 显式注入 barThickness 的构建入口。
     *
     * <p>配置值由会话侧读取 settings 后以标量传入，Builder 不直连 Config；
     * 其余视觉参数仍由 visuals 承载。</p>
     */
    public BuildSession begin(
            Iterable<ChainTarget> previewTargets, VisualParameters visualParameters, float barThickness) {
        return begin(previewTargets, visualParameters, barThickness, null);
    }

    /**
     * 携带目标语义类别的构建入口（B2.3）。
     *
     * <p>semanticClasses 与 previewTargets 的迭代顺序严格同序：索引 i 即第 i 个被读取目标的
     * 出现序号（{@code RenderSnapshot.getTargets()} 为最新→最早）。取值见接口冻结 §D：
     * 0 PRIMARY_LOCAL / 1 SUB_MODE_LOCAL / 2 REMOTE_PREDICTED / 3 TRUNCATED / 4 DEFERRED /
     * 5 EXECUTED / 255 UNDEFINED。传 null 表示未提供类别（全部按 255，不计降级）；数组短于
     * 目标数或元素非法时仅对应目标按 255 兜底并计数，前面的类别绝不错位。</p>
     */
    public BuildSession begin(
            Iterable<ChainTarget> previewTargets, VisualParameters visualParameters,
            float barThickness, int[] semanticClasses) {
        Iterable<ChainTarget> targets = previewTargets == null
            ? Collections.<ChainTarget>emptyList()
            : previewTargets;
        VisualParameters visuals = visualParameters == null
            ? VisualParameters.fromCurrentConfig(0.0D, 0.0D, 0.0D)
            : visualParameters;
        return new BuildSession(
            targets, visuals.withBarThickness(barThickness), semanticClasses,
            lodCulledPositions, lodHysteresisResetSignal, null);
    }

    /**
     * 创建代级增量会话（B4.1 第一步：锚点稳定 + 时间序装配；26 邻域增量跳过属第二步）。
     *
     * <p>与既有一次性 {@code begin/build} 入口相互独立：本入口的代内装配顺序为时间序
     * （最早→最新），meshOrigin = 代内首个目标且代内稳定，超重锚距离时整代重锚。</p>
     */
    public GenerationSession beginGeneration() {
        return new GenerationSession(this, DEFAULT_REANCHOR_DISTANCE);
    }

    /** @param reanchorDistance 重锚距离（格，Chebyshev），钳制到 >=1 */
    public GenerationSession beginGeneration(int reanchorDistance) {
        return new GenerationSession(this, reanchorDistance);
    }

    /** 内部：显式锚点的构建入口；代级会话与差分基线共用同一套装配代码。 */
    BuildSession beginWithOrigin(
            Iterable<ChainTarget> previewTargets, VisualParameters visualParameters,
            int[] semanticClasses, int originX, int originY, int originZ) {
        Iterable<ChainTarget> targets = previewTargets == null
            ? Collections.<ChainTarget>emptyList()
            : previewTargets;
        VisualParameters visuals = visualParameters == null
            ? VisualParameters.fromCurrentConfig(0.0D, 0.0D, 0.0D)
            : visualParameters;
        return new BuildSession(
            targets, visuals, semanticClasses, lodCulledPositions, lodHysteresisResetSignal,
            new BlockPos(originX, originY, originZ));
    }

    /**
     * 差分基线入口：把 {@code orderedTargets} 按给定顺序（时间序）一次性全量装配，
     * 并使用显式锚点，供差分测试与 {@link GenerationSession} 对照。
     */
    ChainPreviewMesh buildWithOrigin(
            List<ChainTarget> orderedTargets, VisualParameters visualParameters,
            float barThickness, int[] semanticClasses, int originX, int originY, int originZ) {
        VisualParameters visuals = (visualParameters == null
            ? VisualParameters.fromCurrentConfig(0.0D, 0.0D, 0.0D)
            : visualParameters).withBarThickness(barThickness);
        BuildSession session = beginWithOrigin(
            orderedTargets, visuals, semanticClasses, originX, originY, originZ);
        session.advance(NEVER_YIELD);
        return session.getMesh();
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

    /** 显式 barThickness 的同步便利入口，主要供纯 JVM 几何测试使用。 */
    public ChainPreviewMesh build(
            List<ChainTarget> previewTargets, VisualParameters visualParameters, float barThickness) {
        return build(previewTargets, visualParameters, barThickness, null);
    }

    /** 携带语义类别的同步便利入口，主要供纯 JVM 几何测试使用。 */
    public ChainPreviewMesh build(
            List<ChainTarget> previewTargets, VisualParameters visualParameters,
            float barThickness, int[] semanticClasses) {
        BuildSession session = begin(previewTargets, visualParameters, barThickness, semanticClasses);
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

        /** LOD 双阈值退出侧余量：alpha >= lodMinAlpha + 余量 才恢复已剔除目标。 */
        public static final float LOD_EXIT_ALPHA_MARGIN = 0.05F;

        private final double cameraX;
        private final double cameraY;
        private final double cameraZ;
        private final double fadeStart;
        private final double fadeEnd;
        private final float maxAlpha;
        private final float minAlpha;
        private final float barThickness;
        private final boolean lodEnabled;
        private final float lodMinAlpha;

        public VisualParameters(
                double cameraX,
                double cameraY,
                double cameraZ,
                double fadeStart,
                double fadeEnd,
                float maxAlpha,
                float minAlpha,
                float barThickness) {
            this(cameraX, cameraY, cameraZ, fadeStart, fadeEnd, maxAlpha, minAlpha, barThickness,
                false, 0.0F);
        }

        /**
         * 携带 LOD 策略的完整构造。
         *
         * @param lodEnabled 是否启用构建期 LOD 剔除（clientPreviewLod=auto）；false 时逐字等于现状
         * @param lodMinAlpha 剔除进入阈值（clientPreviewLodMinAlpha）
         */
        public VisualParameters(
                double cameraX,
                double cameraY,
                double cameraZ,
                double fadeStart,
                double fadeEnd,
                float maxAlpha,
                float minAlpha,
                float barThickness,
                boolean lodEnabled,
                float lodMinAlpha) {
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.fadeStart = Math.max(0.0D, fadeStart);
            this.fadeEnd = Math.max(this.fadeStart + 0.001D, fadeEnd);
            this.maxAlpha = clampAlpha(maxAlpha);
            this.minAlpha = clampAlpha(minAlpha);
            this.barThickness = Math.max(0.001F, Math.min(0.99F, barThickness));
            this.lodEnabled = lodEnabled;
            this.lodMinAlpha = clampAlpha(lodMinAlpha);
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

        /** @return 只替换 barThickness 的不可变副本；配置注入用 */
        public VisualParameters withBarThickness(float nextBarThickness) {
            return new VisualParameters(
                cameraX, cameraY, cameraZ, fadeStart, fadeEnd, maxAlpha, minAlpha, nextBarThickness,
                lodEnabled, lodMinAlpha);
        }

        /** @return 只替换 LOD 策略的不可变副本；会话侧从 settings 快照注入 */
        public VisualParameters withLod(boolean nextLodEnabled, float nextLodMinAlpha) {
            return new VisualParameters(
                cameraX, cameraY, cameraZ, fadeStart, fadeEnd, maxAlpha, minAlpha, barThickness,
                nextLodEnabled, nextLodMinAlpha);
        }

        public float getBarThickness() {
            return barThickness;
        }

        public boolean isLodEnabled() {
            return lodEnabled;
        }

        public float getLodMinAlpha() {
            return lodMinAlpha;
        }

        /** @return 恢复已剔除目标的退出阈值（进入阈值 + {@link #LOD_EXIT_ALPHA_MARGIN}，钳制到 1） */
        public float getLodExitAlpha() {
            return Math.min(1.0F, lodMinAlpha + LOD_EXIT_ALPHA_MARGIN);
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

    /**
     * 代级增量会话（B4.1：锚点稳定 + 时间序装配 + 26 邻域可见段缓存）。
     *
     * <p>目标按「首次出现」时间序累积；每次 {@link #extend} 接收当前完整快照（最新→最早），
     * 反向取出新增目标追加到时间序尾部，并复用同一套 junction/tube 装配代码产出完整网格。</p>
     *
     * <p>增量边界（Option A，Lead 裁定）：真正跳过的是**逐位置邻域扫描**
     * （{@code buildVisibleSegments}，1 Hz 全量重建里最贵的一块）——新增目标只重算自身及
     * 26 邻域的可见段，其余位置复用代级缓存；装配发射仍是 O(网格)，**不是**「成本 ∝ 新增目标数」。
     * append-only 局部发射与「逐字节等价全量」不相容（共享面重算 / 全局顶点去重 / min-incident aux），
     * 已作为独立项留待将来评估。</p>
     *
     * <p>缓存线程契约：positions/positionIndex/cachedSegments/occupancy 只归构建线程所有；
     * 缓存失效范围 = 新增目标 + 其 26 邻域（仅自身结果被替换）；{@link #dispose()} 释放全部缓存，
     * 不跨代残留。lod=auto 时剔除集合随相机变化，本阶段回退全量可见段重算（登记为边界）。</p>
     *
     * <p>锚点契约：meshOrigin = 代内首个目标，代内不变；新增目标与锚点 Chebyshev 距离超过
     * {@link #getReanchorDistance()} 时整代重锚（等效一次全量重建，锚点移到该目标）。</p>
     *
     * <p>线程契约：会话状态只归构建线程所有；{@link #dispose()} 可能由主线程调用，只置位
     * volatile 请求位，实际释放与后续拒绝由构建线程在下次 {@link #extend} 入口消费，不跨代残留。</p>
     *
     * <p><b>生产接口契约（B4.1 第二步阶段 B 冻结）</b>：RenderCache 按代创建一个会话，
     * 代内每次修订调用一次 {@link #extend}（传完整快照 + settings 快照派生的 visuals/类别），
     * 世代变化 / 世界切换 / lifecycle 复位时 {@link #dispose()} 旧会话并按需新建；
     * 每秒相机刷新复用同一会话（保 LOD 滞回与增量缓存）。</p>
     *
     * <p><b>装配顺序契约变更（有意，已登记）</b>：生产装配由既有一次性入口的「最新→最早」
     * 改为本会话的「时间序（最早→最新）」，几何不变、顶点/aux 顺序变化。
     * <b>appearOrder 方向修正</b>：由「最新目标=0」改为「最早出现=0」（符合用户拍板的
     * 「保留出现顺序」）；默认 animation=off，生产默认观感不变；
     * 真机验收项：开启 animation 后生长顺序必须等于出现顺序。</p>
     */
    public static final class GenerationSession {

        private final ChainPreviewMeshBuilder builder;
        private final int reanchorDistance;
        private final List<ChainTarget> chronology = new ArrayList<ChainTarget>();
        private final IntArrayBuilder chronologyClasses = new IntArrayBuilder();
        private final Set<BlockPos> knownPositions = new HashSet<BlockPos>();
        /** Option A 代级缓存：与 chronology 同序的位置、位置索引、可见段与占用集。 */
        private final List<BlockPos> positions = new ArrayList<BlockPos>(MAX_RENDER_TARGETS);
        private final Map<BlockPos, Integer> positionIndex = new HashMap<BlockPos, Integer>();
        private final List<LineSegment[]> cachedSegments =
            new ArrayList<LineSegment[]>(MAX_RENDER_TARGETS);
        private final Set<BlockPos> occupancy = new HashSet<BlockPos>(MAX_RENDER_TARGETS * 4 / 3 + 1);

        private int visibleBlockCount;
        private boolean overflowed;
        /** 当前在飞的装配会话（构建线程内）；再次 beginRevision 或 dispose 后即被取代/丢弃。 */
        private BuildSession activeRevision;

        private volatile boolean disposeRequested;
        private boolean disposed;
        private BlockPos anchor;
        private ChainPreviewMesh mesh;
        private int reanchorCount;
        private int semanticClassFallbackCount;

        private GenerationSession(ChainPreviewMeshBuilder builder, int reanchorDistance) {
            this.builder = builder;
            this.reanchorDistance = Math.max(1, reanchorDistance);
        }

        /**
         * 开始一次代内修订：把快照并入代级缓存（仅新增目标 + 26 邻域刷新可见段），
         * 返回**可分片、可续跑**的装配会话。安全点与既有 {@link BuildSession#advance} 完全一致
         * （按位置/句柄块检查 gate），避免大网格单次装配超帧预算；半装配状态不对外可见
         * （只有 advance 返回 true 后 getMesh() 才可用）。再次调用本方法即开始新修订并取代旧会话。
         *
         * @param snapshotNewestFirst 当前完整快照，顺序同 {@code RenderSnapshot.getTargets()}（最新→最早）
         * @param semanticClasses 与快照同序的类别载体；null 表示全部 255
         * @param visuals 视觉参数（相机/淡出/LOD），构建线程只读
         * @param barThickness 显式条柱厚度
         */
        public MeshBuildSession beginRevision(
                List<ChainTarget> snapshotNewestFirst, int[] semanticClasses,
                VisualParameters visuals, float barThickness) {
            if (consumeDisposeRequest()) {
                throw new IllegalStateException("GenerationSession disposed");
            }
            if (visuals == null) {
                throw new IllegalArgumentException("visuals");
            }
            mergeSnapshot(snapshotNewestFirst, semanticClasses, visuals);

            BuildSession session;
            if (positions.isEmpty()) {
                session = builder.begin(
                    Collections.<ChainTarget>emptyList(), visuals, barThickness, null);
            } else if (visuals.isLodEnabled()) {
                // LOD 剔除集合随相机/阈值变化，缓存不适用：本阶段回退全量可见段重算（登记边界）。
                session = builder.beginWithOrigin(
                    chronology, visuals.withBarThickness(barThickness), chronologyClasses.exactArray(),
                    anchor.x, anchor.y, anchor.z);
            } else {
                PreparedTopology topology = new PreparedTopology(
                    positions, occupancy, chronologyClasses.exactArray(), cachedSegments,
                    visibleBlockCount, overflowed);
                session = builder.beginPrepared(
                    topology, visuals.withBarThickness(barThickness), anchor);
            }
            activeRevision = session;
            return session;
        }

        /**
         * 一次性入口（测试/参考用）：等价于 {@code beginRevision(...).advance(NEVER_YIELD)} + getMesh()。
         * 生产路径请用 {@link #beginRevision} 的分片形态。
         */
        public ChainPreviewMesh extend(
                List<ChainTarget> snapshotNewestFirst, int[] semanticClasses,
                VisualParameters visuals, float barThickness) {
            MeshBuildSession revision = beginRevision(
                snapshotNewestFirst, semanticClasses, visuals, barThickness);
            revision.advance(NEVER_YIELD);
            mesh = revision.getMesh();
            return mesh;
        }

        /** 把快照并入代级缓存：新增 unique 目标按时间序追加，锚点/重锚按契约更新。 */
        private void mergeSnapshot(
                List<ChainTarget> snapshotNewestFirst, int[] semanticClasses, VisualParameters visuals) {
            List<ChainTarget> snapshot = snapshotNewestFirst == null
                ? Collections.<ChainTarget>emptyList()
                : snapshotNewestFirst;
            for (int index = snapshot.size() - 1; index >= 0; index--) {
                ChainTarget target = snapshot.get(index);
                if (target == null) {
                    continue;
                }
                BlockPos position = new BlockPos(target.getX(), target.getY(), target.getZ());
                if (!knownPositions.add(position)) {
                    continue;
                }
                if (positions.size() >= MAX_RENDER_TARGETS) {
                    // 超配额：装配阶段按 truncated 语义只保留前 MAX 个；停止累积保持内存有界。
                    overflowed = true;
                    break;
                }
                chronology.add(target);
                chronologyClasses.add(semanticClassAt(semanticClasses, index));
                positionIndex.put(position, Integer.valueOf(positions.size()));
                positions.add(position);
                cachedSegments.add(null);
                occupancy.add(position);
                if (anchor == null) {
                    anchor = position;
                } else if (chebyshevDistance(anchor, position) > reanchorDistance) {
                    anchor = position;
                    reanchorCount++;
                }
            }

            if (!visuals.isLodEnabled()) {
                // Option A：只重算新增目标及其 26 邻域的可见段，其余复用缓存。
                refreshVisibleSegments();
            }
        }

        /**
         * 刷新代级可见段缓存：只重算新增目标（缓存为 null 的位置）与其 26 邻域中已存在的位置，
         * 其余位置的结果原样复用；同时增量维护 {@code visibleBlockCount}。
         */
        private void refreshVisibleSegments() {
            LinkedHashSet<Integer> dirty = new LinkedHashSet<Integer>();
            for (int index = 0; index < cachedSegments.size(); index++) {
                if (cachedSegments.get(index) == null) {
                    dirty.add(Integer.valueOf(index));
                }
            }
            List<Integer> newlyAdded = new ArrayList<Integer>(dirty);
            for (Integer index : newlyAdded) {
                BlockPos position = positions.get(index.intValue());
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    BlockPos neighbor = position.offset(offset[0], offset[1], offset[2]);
                    if (neighbor == null) {
                        continue;
                    }
                    Integer neighborIndex = positionIndex.get(neighbor);
                    if (neighborIndex != null) {
                        dirty.add(neighborIndex);
                    }
                }
            }
            for (Integer index : dirty) {
                int slot = index.intValue();
                LineSegment[] previous = cachedSegments.get(slot);
                if (previous != null && previous.length > 0) {
                    visibleBlockCount--;
                }
                LineSegment[] segments = buildVisibleSegments(positions.get(slot), occupancy);
                cachedSegments.set(slot, segments);
                if (segments.length > 0) {
                    visibleBlockCount++;
                }
            }
        }

        /** 允许任意线程调用：只置位请求位；实际释放由构建线程在下次 {@link #extend} 入口完成。 */
        public void dispose() {
            disposeRequested = true;
        }

        /** @return 是否已请求或完成释放（构建线程读取为权威值） */
        public boolean isDisposed() {
            return disposed || disposeRequested;
        }

        /** @return 代内累积的唯一目标数（仅构建线程读取） */
        public int getGenerationTargetCount() {
            return chronology.size();
        }

        public int getReanchorDistance() {
            return reanchorDistance;
        }

        /** @return 本代已发生的重锚次数（仅构建线程读取） */
        public int getReanchorCount() {
            return reanchorCount;
        }

        /** @return 累积期类别载体缺失/非法而按 255 兜底的目标准数 */
        public int getSemanticClassFallbackCount() {
            return semanticClassFallbackCount;
        }

        public int getAnchorX() {
            return anchor == null ? 0 : anchor.x;
        }

        public int getAnchorY() {
            return anchor == null ? 0 : anchor.y;
        }

        public int getAnchorZ() {
            return anchor == null ? 0 : anchor.z;
        }

        /** @return 最近一次 {@link #extend} 产出的网格；尚未构建时为 null */
        public ChainPreviewMesh getMesh() {
            return mesh;
        }

        private int semanticClassAt(int[] semanticClasses, int index) {
            if (semanticClasses == null) {
                // 冻结口径：null = 未提供类别，不计降级（与 BuildSession.semanticClassAt 同口径）。
                return ChainPreviewSemanticClass.UNDEFINED;
            }
            if (index >= semanticClasses.length) {
                semanticClassFallbackCount++;
                return ChainPreviewSemanticClass.UNDEFINED;
            }
            int value = semanticClasses[index];
            int normalized = ChainPreviewSemanticClass.normalize(value);
            if (normalized != value) {
                semanticClassFallbackCount++;
            }
            return normalized;
        }

        private boolean consumeDisposeRequest() {
            if (!disposeRequested) {
                return false;
            }
            disposeRequested = false;
            activeRevision = null;
            chronology.clear();
            chronologyClasses.reset();
            knownPositions.clear();
            positions.clear();
            positionIndex.clear();
            cachedSegments.clear();
            occupancy.clear();
            visibleBlockCount = 0;
            overflowed = false;
            anchor = null;
            mesh = null;
            reanchorCount = 0;
            semanticClassFallbackCount = 0;
            disposed = true;
            return true;
        }

        private static int chebyshevDistance(BlockPos first, BlockPos second) {
            long dx = Math.abs((long) first.x - (long) second.x);
            long dy = Math.abs((long) first.y - (long) second.y);
            long dz = Math.abs((long) first.z - (long) second.z);
            return (int) Math.max(dx, Math.max(dy, dz));
        }
    }

    /** Option A：代级缓存的只读拓扑快照（构建线程内传递，不跨线程/不跨代）。 */
    private static final class PreparedTopology {

        private final List<BlockPos> positions;
        private final Set<BlockPos> occupancy;
        private final int[] semanticClasses;
        private final List<LineSegment[]> visibleSegments;
        private final int visibleBlockCount;
        private final boolean truncated;

        private PreparedTopology(
                List<BlockPos> positions,
                Set<BlockPos> occupancy,
                int[] semanticClasses,
                List<LineSegment[]> visibleSegments,
                int visibleBlockCount,
                boolean truncated) {
            this.positions = positions;
            this.occupancy = occupancy;
            this.semanticClasses = semanticClasses;
            this.visibleSegments = visibleSegments;
            this.visibleBlockCount = visibleBlockCount;
            this.truncated = truncated;
        }
    }

    /** 内部：用代级缓存拓扑装配（Option A）；与全量路径共用同一套 junction/tube 装配代码。 */
    private BuildSession beginPrepared(
            PreparedTopology topology, VisualParameters visuals, BlockPos fixedOrigin) {
        return new BuildSession(
            topology, visuals, fixedOrigin, lodCulledPositions, lodHysteresisResetSignal);
    }

    /** 26 邻域偏移（增量缓存失效范围）。 */
    private static final int[][] NEIGHBOR_OFFSETS = createNeighborOffsets();

    private static int[][] createNeighborOffsets() {
        int[][] offsets = new int[26][];
        int cursor = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    offsets[cursor++] = new int[] {dx, dy, dz};
                }
            }
        }
        return offsets;
    }

    /** 可恢复构建：有界保留 target、去重世界边，再生成无内部端面的接头与管段。 */
    public static final class BuildSession implements MeshBuildSession {

        private final Iterator<ChainTarget> targetIterator;
        private final VisualParameters visuals;
        private final int[] semanticClasses;
        private final Set<BlockPos> lodCulledPositions;
        private final LodResetSignal lodHysteresisResetSignal;
        private final Set<BlockPos> lodCulledThisBuild;
        /** 显式锚点（代级会话/差分基线）；null = 既有一次性入口（首读目标）。 */
        private final BlockPos fixedOrigin;
        /** Option A：代级缓存拓扑；非 null 时跳过目标采集与逐位置邻域扫描（仅 lod=off 使用）。 */
        private final PreparedTopology preparedTopology;
        private final List<LineSegment[]> preparedSegments;
        private final int[] positionSemanticClasses = new int[MAX_RENDER_TARGETS];
        private final List<BlockPos> positions = new ArrayList<BlockPos>(MAX_RENDER_TARGETS);
        private final Set<BlockPos> occupancy = new HashSet<BlockPos>(MAX_RENDER_TARGETS * 4 / 3 + 1);
        private final Set<GridEdge> edges = new LinkedHashSet<GridEdge>();
        private final Map<GridPoint, Integer> incidence = new LinkedHashMap<GridPoint, Integer>();
        private final Map<GridPoint, Integer> junctionAppearOrders = new LinkedHashMap<GridPoint, Integer>();
        private final Map<MeshVertexKey, Integer> vertexIndices = new LinkedHashMap<MeshVertexKey, Integer>();
        private final FloatArrayBuilder vertices = new FloatArrayBuilder();
        private final FloatArrayBuilder colors = new FloatArrayBuilder();
        private final IntArrayBuilder indices = new IntArrayBuilder();
        private final ByteArrayBuilder aux = new ByteArrayBuilder();

        private int targetReadCount;
        private int semanticClassFallbackCount;
        private int culledTargetCount;
        private int pointCursor;
        private int segmentCursor;
        private int visibleBlockCount;
        private int geometryPhase;
        private int currentFaceCursor;
        private int currentFaceCount;
        private int currentAppearOrder = ChainPreviewMesh.APPEAR_ORDER_UNDEFINED;
        private boolean currentFacesAreTube;
        private boolean truncated;
        private BlockPos currentPoint;
        private BlockPos meshOrigin;
        private LineSegment[] currentSegments = new LineSegment[0];
        private Iterator<Map.Entry<GridPoint, Integer>> junctionIterator;
        private Iterator<GridEdge> edgeIterator;
        private MeshVertexKey[] currentCorners;
        private int[] currentFaces;
        private ChainPreviewMesh mesh;

        private BuildSession(
                Iterable<ChainTarget> targets,
                VisualParameters visuals,
                int[] semanticClasses,
                Set<BlockPos> lodCulledPositions,
                LodResetSignal lodHysteresisResetSignal,
                BlockPos fixedOrigin) {
            this.targetIterator = targets.iterator();
            this.visuals = visuals;
            this.semanticClasses = semanticClasses == null
                ? null
                : Arrays.copyOf(semanticClasses, semanticClasses.length);
            this.lodCulledPositions = lodCulledPositions;
            this.lodHysteresisResetSignal = lodHysteresisResetSignal;
            this.lodCulledThisBuild = visuals.isLodEnabled()
                ? new HashSet<BlockPos>()
                : null;
            this.fixedOrigin = fixedOrigin;
            this.meshOrigin = fixedOrigin;
            this.preparedTopology = null;
            this.preparedSegments = null;
        }

        /** Option A：用代级缓存拓扑直接进入装配阶段（跳过采集与邻域扫描）。 */
        private BuildSession(
                PreparedTopology topology,
                VisualParameters visuals,
                BlockPos fixedOrigin,
                Set<BlockPos> lodCulledPositions,
                LodResetSignal lodHysteresisResetSignal) {
            this.targetIterator = Collections.<ChainTarget>emptyList().iterator();
            this.visuals = visuals;
            this.semanticClasses = null;
            this.lodCulledPositions = lodCulledPositions;
            this.lodHysteresisResetSignal = lodHysteresisResetSignal;
            this.lodCulledThisBuild = null;
            this.fixedOrigin = fixedOrigin;
            this.meshOrigin = fixedOrigin;
            this.preparedTopology = topology;
            this.preparedSegments = topology.visibleSegments;
            this.truncated = topology.truncated;
            this.visibleBlockCount = topology.visibleBlockCount;
            this.positions.addAll(topology.positions);
            this.occupancy.addAll(topology.occupancy);
            int classCount = Math.min(
                topology.semanticClasses.length, this.positionSemanticClasses.length);
            System.arraycopy(
                topology.semanticClasses, 0, this.positionSemanticClasses, 0, classCount);
        }

        /**
         * 推进到 gate 要求让出或网格完成。
         *
         * @return 完成时为 true
         */
        @Override
        public boolean advance(WorkGate gate) {
            WorkGate effectiveGate = gate == null ? NEVER_YIELD : gate;
            consumeLodHysteresisReset();
            if (mesh != null) {
                return true;
            }

            while (!truncated && targetIterator.hasNext()) {
                if (effectiveGate.shouldYield()) {
                    return false;
                }
                ChainTarget target = targetIterator.next();
                int semanticClass = semanticClassAt(targetReadCount++);
                if (target == null) {
                    continue;
                }
                BlockPos position = new BlockPos(target.getX(), target.getY(), target.getZ());
                if (visuals.isLodEnabled() && shouldCullForLod(position)) {
                    // LOD=auto：远处散点在构建期直接不生成几何，不占配额也不进入拓扑。
                    culledTargetCount++;
                    continue;
                }
                if (occupancy.contains(position)) {
                    // 重复 target 不占配额，也不进入拓扑；类别取首次出现（= 最小出现序号）。
                    continue;
                }
                if (occupancy.size() >= MAX_RENDER_TARGETS) {
                    truncated = true;
                    break;
                }
                occupancy.add(position);
                positionSemanticClasses[positions.size()] = semanticClass;
                positions.add(position);
                if (meshOrigin == null) {
                    meshOrigin = position;
                }
            }

            while (pointCursor < positions.size() || currentPoint != null) {
                if (currentPoint == null) {
                    if (effectiveGate.shouldYield()) {
                        return false;
                    }
                    int appearOrder = pointCursor++;
                    currentPoint = positions.get(appearOrder);
                    currentAppearOrder = appearOrder;
                    if (preparedSegments != null) {
                        // Option A：复用代级缓存段（26 邻域内已重算），不再重复邻域扫描。
                        currentSegments = preparedSegments.get(appearOrder);
                    } else {
                        currentSegments = buildVisibleSegments(currentPoint, occupancy);
                        if (currentSegments.length > 0) {
                            visibleBlockCount++;
                        }
                    }
                    segmentCursor = 0;
                }

                while (segmentCursor < currentSegments.length) {
                    if (effectiveGate.shouldYield()) {
                        return false;
                    }
                    registerEdge(currentPoint, currentSegments[segmentCursor++], currentAppearOrder);
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
                        Integer appearOrder = junctionAppearOrders.get(entry.getKey());
                        currentAppearOrder = appearOrder == null
                            ? ChainPreviewMesh.APPEAR_ORDER_UNDEFINED
                            : appearOrder.intValue();
                        currentFacesAreTube = false;
                        currentCorners = junctionCorners(entry.getKey());
                        currentFaces = junctionFaces(mask);
                        currentFaceCursor = 0;
                        currentFaceCount = currentFaces.length;
                    }
                    while (currentFaceCursor < currentFaceCount) {
                        if (effectiveGate.shouldYield()) {
                            return false;
                        }
                        appendCurrentFace();
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
                        currentAppearOrder = edge.appearOrder;
                        currentFacesAreTube = true;
                        currentCorners = tubeCorners(edge);
                        currentFaces = TUBE_FACES_BY_AXIS[edge.axis];
                        currentFaceCursor = 0;
                        currentFaceCount = currentFaces.length;
                    }
                    while (currentFaceCursor < currentFaceCount) {
                        if (effectiveGate.shouldYield()) {
                            return false;
                        }
                        appendCurrentFace();
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
                truncated,
                culledTargetCount,
                aux.exactArray());
            if (lodCulledThisBuild != null) {
                // 成功构建结束（lod=auto）：把滞回记忆裁剪为「本轮实际仍被剔除的位置」，
                // 换代/目标消失后不残留旧条目；lod=off 完全不触碰记忆。
                lodCulledPositions.retainAll(lodCulledThisBuild);
            }
            consumeLodHysteresisReset();
            return true;
        }

        /** 构建线程消费主线程的清理请求；返回是否实际清理。 */
        private boolean consumeLodHysteresisReset() {
            if (!lodHysteresisResetSignal.consume()) {
                return false;
            }
            lodCulledPositions.clear();
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

        /**
         * @return 因载体缺失、越界或元素非法而按 255 兜底的目标准数；构建期即可观察，
         *         用于确认「长度不一致」没有被静默错位吞掉
         */
        public int getSemanticClassFallbackCount() {
            return semanticClassFallbackCount;
        }

        /**
         * @return 本轮因 alpha &lt;= lodMinAlpha 被剔除的目标（条柱）数；lod=off 恒 0。
         *         计数单位为「目标」，与 {@link ChainPreviewMesh#getCulledTargetCount()} 同源
         */
        public int getCulledTargetCount() {
            return culledTargetCount;
        }

        /**
         * LOD 双阈值判定：方块中心 alpha &lt;= enter 时剔除；已剔除目标必须 alpha &gt;=
         * exit（enter + {@link VisualParameters#LOD_EXIT_ALPHA_MARGIN}）才恢复，
         * 因此阈值附近来回不会闪烁。状态由 Builder 持有并跨 session 复用（仅构建线程）。
         */
        private boolean shouldCullForLod(BlockPos position) {
            boolean alreadyCulled = lodCulledPositions.contains(position);
            float alpha = visuals.alphaFor(
                position.x + 0.5D, position.y + 0.5D, position.z + 0.5D);
            if (alreadyCulled) {
                if (alpha >= visuals.getLodExitAlpha()) {
                    lodCulledPositions.remove(position);
                    return false;
                }
                lodCulledThisBuild.add(position);
                return true;
            }
            if (alpha > visuals.getLodMinAlpha()) {
                return false;
            }
            if (lodCulledPositions.size() >= MAX_RENDER_TARGETS) {
                // 有界保护：记忆满即清空（登记为已知限制：丢一次滞回），重新进入双阈值周期。
                lodCulledPositions.clear();
            }
            lodCulledPositions.add(position);
            lodCulledThisBuild.add(position);
            return true;
        }

        /** 载体按原始目标流索引取值；越界/非法值经 {@link ChainPreviewSemanticClass#normalize(int)} 兜底并计数。 */
        private int semanticClassAt(int targetIndex) {
            if (semanticClasses == null) {
                return ChainPreviewSemanticClass.UNDEFINED;
            }
            if (targetIndex >= semanticClasses.length) {
                recordSemanticClassFallback();
                return ChainPreviewSemanticClass.UNDEFINED;
            }
            int value = semanticClasses[targetIndex];
            int normalized = ChainPreviewSemanticClass.normalize(value);
            if (normalized != value) {
                recordSemanticClassFallback();
            }
            return normalized;
        }

        private void recordSemanticClassFallback() {
            semanticClassFallbackCount++;
            SEMANTIC_CLASS_FALLBACKS.incrementAndGet();
        }

        /** 顶点类别与 appearOrder 同源：取最小 incident 目标（即该出现序号）的类别。 */
        private int semanticClassForOrder(int appearOrder) {
            if (appearOrder == ChainPreviewMesh.APPEAR_ORDER_UNDEFINED
                    || appearOrder < 0
                    || appearOrder >= positions.size()) {
                return ChainPreviewSemanticClass.UNDEFINED;
            }
            return positionSemanticClasses[appearOrder];
        }

        private void registerEdge(BlockPos position, LineSegment segment, int appearOrder) {
            GridEdge edge = GridEdge.from(position, segment, appearOrder);
            if (!edges.add(edge)) {
                return;
            }
            addIncidence(edge.start, positiveDirection(edge.axis), appearOrder);
            addIncidence(edge.end, negativeDirection(edge.axis), appearOrder);
        }

        private void addIncidence(GridPoint point, int direction, int appearOrder) {
            Integer current = incidence.get(point);
            int mask = current == null ? 0 : current.intValue();
            incidence.put(point, Integer.valueOf(mask | direction));
            if (!junctionAppearOrders.containsKey(point)) {
                // positions 按出现顺序处理，首次登记即该接头的最小 appearOrder。
                junctionAppearOrders.put(point, Integer.valueOf(appearOrder));
            }
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

        private void appendCurrentFace() {
            int slot = currentFaceCursor++;
            // slot = TUBE_FACES_BY_AXIS[axis] 下标（横截面象限槽位）；本轮实测仅 0/1 会被首写，
            // 2/3 数值域合法但不可达（见类 javadoc）。
            int tubeEdge = currentFacesAreTube ? slot : ChainPreviewMesh.AUX_UNDEFINED;
            appendFace(currentCorners, currentFaces[slot], tubeEdge, currentAppearOrder);
        }

        private void appendFace(MeshVertexKey[] corners, int face, int tubeEdge, int appearOrder) {
            int faceOffset = face * 4;
            for (int corner = 0; corner < 4; corner++) {
                indices.add(vertexIndex(
                    corners[CUBOID_QUAD_INDICES[faceOffset + corner]], tubeEdge, appearOrder));
            }
        }

        private int vertexIndex(MeshVertexKey key, int tubeEdge, int appearOrder) {
            Integer existing = vertexIndices.get(key);
            if (existing != null) {
                int index = existing.intValue();
                mergeAppearOrder(index, appearOrder);
                return index;
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
            // 接口冻结 §A/§D：semanticClass 与 appearOrder 同源（最小 incident 目标）。
            aux.add((byte) semanticClassForOrder(appearOrder));
            aux.add((byte) tubeEdge);
            aux.add((byte) (appearOrder & 0xFF));
            aux.add((byte) ((appearOrder >>> 8) & 0xFF));
            vertexIndices.put(key, Integer.valueOf(index));
            return index;
        }

        /**
         * 同一顶点的 appearOrder 与 semanticClass 取所有 incident 目标序号的最小值对应的目标
         * （后写命中同 key 时取 min 更新，类别随序号一起更新）。
         */
        private void mergeAppearOrder(int vertexIndex, int appearOrder) {
            if (appearOrder == ChainPreviewMesh.APPEAR_ORDER_UNDEFINED) {
                return;
            }
            int offset = vertexIndex * ChainPreviewMesh.AUX_BYTES_PER_VERTEX;
            int current = (aux.get(offset + 2) & 0xFF) | ((aux.get(offset + 3) & 0xFF) << 8);
            if (current == ChainPreviewMesh.APPEAR_ORDER_UNDEFINED || appearOrder < current) {
                aux.set(offset, (byte) semanticClassForOrder(appearOrder));
                aux.set(offset + 2, (byte) (appearOrder & 0xFF));
                aux.set(offset + 3, (byte) ((appearOrder >>> 8) & 0xFF));
            }
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
        private final int appearOrder;

        private GridEdge(GridPoint first, GridPoint second, int appearOrder) {
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
            this.appearOrder = appearOrder;
        }

        private static GridEdge from(BlockPos position, LineSegment segment, int appearOrder) {
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
            return new GridEdge(first, second, appearOrder);
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

        /** @return 长度恰为 size 的数组（代级类序列按值传递用） */
        private int[] exactArray() {
            if (size == values.length) {
                return values;
            }
            int[] exact = new int[size];
            System.arraycopy(values, 0, exact, 0, size);
            return exact;
        }

        private void reset() {
            size = 0;
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

    /** 语义顶点流构建器；{@link #exactArray()} 保证长度 == size。 */
    private static final class ByteArrayBuilder {

        private byte[] values = new byte[256];
        private int size;

        private void add(byte value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private byte get(int index) {
            return values[index];
        }

        private void set(int index, byte value) {
            values[index] = value;
        }

        private byte[] exactArray() {
            if (size == values.length) {
                return values;
            }
            byte[] exact = new byte[size];
            System.arraycopy(values, 0, exact, 0, size);
            return exact;
        }

        private void ensureCapacity(int required) {
            if (required <= values.length) {
                return;
            }
            int capacity = growCapacity(values.length, required);
            byte[] grown = new byte[capacity];
            System.arraycopy(values, 0, grown, 0, size);
            values = grown;
        }
    }

    /** @return 累计按 255 兜底的目标准数（debug 计数器；null 载体不计入） */
    public static long getSemanticClassFallbackTotal() {
        return SEMANTIC_CLASS_FALLBACKS.get();
    }

    /** 主线程置位、构建线程消费的无锁请求位（volatile 布尔，不做同步块）。 */
    private static final class LodResetSignal {

        private volatile boolean requested;

        private void request() {
            requested = true;
        }

        /** @return 本次是否消费到未处理的请求；消费即复位，重复置位幂等 */
        private boolean consume() {
            if (!requested) {
                return false;
            }
            requested = false;
            return true;
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
