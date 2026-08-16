package club.heiqi.qz_miner.chain.planner;

import java.util.Queue;
import java.util.Set;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 连锁搜索上下文。
 *
 * 状态分层：
 * - currentFrontier：当前轮需要处理的候选点
 * - nextFrontier：下一轮需要处理的候选点（从当前轮扩展出来的邻居）
 * - visited：所有已检查过的点（避免重复检查）
 */
public class ChainSearchContext {

    private final World world;
    private final ChainTarget origin;
    private final Block sampleBlock;
    private final int sampleMeta;
    private final TileIdentityToken sampleTileIdentity;
    /** 仅供既有预览/特殊模式兼容，普通 same-block matcher 只读 sampleTileIdentity。 */
    private final TileEntity sampleTileEntity;
    private final ChainSubMode subMode;
    private final int maxRadius;
    private final int maxTargets;
    private final ModeExtensionSnapshot modeExtension;
    private final FrozenModePredicate frozenModePredicate;
    private final Queue<ChainTarget> currentFrontier;
    private final Queue<ChainTarget> nextFrontier;
    private final Set<ChainTarget> visited;
    private final PlanningCandidateGate planningCandidateGate;
    private final PlanningCandidateGate.CandidateBlockReader planningCandidateBlockReader;
    private ChainCandidateFilter candidateFilter;
    private int confirmedCount;
    private int scanDepth;
    private boolean targetLimitExceeded;
    private long progressRevision;

    public ChainSearchContext(
        World world,
        ChainTarget origin,
        Block sampleBlock,
        int sampleMeta,
        TileEntity sampleTileEntity,
        ChainSubMode subMode,
        int maxRadius,
        int maxTargets,
        Queue<ChainTarget> currentFrontier,
        Queue<ChainTarget> nextFrontier,
        Set<ChainTarget> visited) {
        this(world, origin, sampleBlock, sampleMeta, CompatAdapters.captureTileIdentity(sampleTileEntity),
                sampleTileEntity, subMode, maxRadius, maxTargets,
                currentFrontier, nextFrontier, visited, null);
    }

    public ChainSearchContext(
        World world, ChainTarget origin, Block sampleBlock, int sampleMeta, TileEntity sampleTileEntity,
        ChainSubMode subMode, int maxRadius, int maxTargets, Queue<ChainTarget> currentFrontier,
        Queue<ChainTarget> nextFrontier, Set<ChainTarget> visited, ModeExtensionSnapshot modeExtension) {
        this(world, origin, sampleBlock, sampleMeta, CompatAdapters.captureTileIdentity(sampleTileEntity),
                sampleTileEntity, subMode, maxRadius, maxTargets, currentFrontier, nextFrontier, visited, modeExtension);
    }

    /** 创建显式携带纯值种子身份的搜索上下文。 */
    public ChainSearchContext(
        World world, ChainTarget origin, Block sampleBlock, int sampleMeta,
        TileIdentityToken sampleTileIdentity, TileEntity sampleTileEntity,
        ChainSubMode subMode, int maxRadius, int maxTargets, Queue<ChainTarget> currentFrontier,
        Queue<ChainTarget> nextFrontier, Set<ChainTarget> visited, ModeExtensionSnapshot modeExtension) {
        this(world, origin, sampleBlock, sampleMeta, sampleTileIdentity, sampleTileEntity, subMode,
                maxRadius, maxTargets, currentFrontier, nextFrontier, visited, modeExtension, null);
    }

    /** 供同包纯逻辑测试注入候选方块读取器，不改变生产 World 权威。 */
    ChainSearchContext(
        World world, ChainTarget origin, Block sampleBlock, int sampleMeta,
        TileIdentityToken sampleTileIdentity, TileEntity sampleTileEntity,
        ChainSubMode subMode, int maxRadius, int maxTargets, Queue<ChainTarget> currentFrontier,
        Queue<ChainTarget> nextFrontier, Set<ChainTarget> visited, ModeExtensionSnapshot modeExtension,
        PlanningCandidateGate.CandidateBlockReader planningCandidateBlockReader) {
        this.world = world;
        this.origin = origin;
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileIdentity = sampleTileIdentity == null ? TileIdentityToken.unresolved() : sampleTileIdentity;
        this.sampleTileEntity = sampleTileEntity;
        this.subMode = subMode;
        this.maxRadius = maxRadius;
        this.maxTargets = maxTargets;
        this.modeExtension = modeExtension == null ? ModeExtensionSnapshot.EMPTY : modeExtension;
        this.frozenModePredicate = new FrozenModePredicate(this.modeExtension);
        this.currentFrontier = currentFrontier;
        this.nextFrontier = nextFrontier;
        this.visited = visited;
        this.planningCandidateGate = new PlanningCandidateGate();
        this.planningCandidateBlockReader = planningCandidateBlockReader == null
                ? target -> world != null && target != null
                        && world.getBlock(target.getX(), target.getY(), target.getZ()) == Blocks.air
                : planningCandidateBlockReader;
    }

    public World getWorld() {
        return world;
    }

    public ChainTarget getOrigin() {
        return origin;
    }

    public Block getSampleBlock() {
        return sampleBlock;
    }

    public int getSampleMeta() {
        return sampleMeta;
    }

    /** @return 主线程捕获并冻结的 TileEntity 纯值身份 */
    public TileIdentityToken getSampleTileIdentity() {
        return sampleTileIdentity;
    }

    public TileEntity getSampleTileEntity() {
        return sampleTileEntity;
    }

    /**
     * 获取当前子模式快照。
     *
     * @return 当前子模式
     */
    public ChainSubMode getSubMode() {
        return subMode;
    }

    public int getMaxRadius() {
        return maxRadius;
    }

    public int getMaxTargets() {
        return maxTargets;
    }

    public ModeExtensionSnapshot getModeExtension() {
        return modeExtension;
    }

    /** @return 与 matcher/filter 共享的冻结对象组谓词 */
    public FrozenModePredicate getFrozenModePredicate() {
        return frozenModePredicate;
    }

    public Queue<ChainTarget> getCurrentFrontier() {
        return currentFrontier;
    }

    public Queue<ChainTarget> getNextFrontier() {
        return nextFrontier;
    }

    public Set<ChainTarget> getVisited() {
        return visited;
    }

    public ChainCandidateFilter getCandidateFilter() {
        return candidateFilter;
    }

    public void setCandidateFilter(ChainCandidateFilter candidateFilter) {
        this.candidateFilter = candidateFilter;
    }

    public int getConfirmedCount() {
        return confirmedCount;
    }

    public void incrementConfirmedCount() {
        confirmedCount++;
        recordDurableProgress();
    }

    /** 标记已确认存在第一个无法纳入本轮安全上限的目标。 */
    void markTargetLimitExceeded() {
        targetLimitExceeded = true;
    }

    /** @return 遍历是否已确认结果被目标上限截断 */
    public boolean isTargetLimitExceeded() {
        return targetLimitExceeded;
    }

    /** @return 每次已提交候选事务或可恢复游标推进都会单调增加的 worker-local revision */
    public long getProgressRevision() {
        return progressRevision;
    }

    public int getScanDepth() {
        return scanDepth;
    }

    public void setScanDepth(int scanDepth) {
        int normalized = Math.max(0, scanDepth);
        if (this.scanDepth != normalized) {
            this.scanDepth = normalized;
            recordDurableProgress();
        }
    }

    /**
     * 判断目标是否允许作为当前遍历候选点。
     *
     * @param target 候选目标
     * @return 是否允许继续遍历
     */
    public boolean canTraverse(ChainTarget target) {
        return candidateFilter != null && candidateFilter.canTraverse(target);
    }

    /**
     * 经上下文唯一 deadline 门尝试提交一个 planning 候选事务。
     *
     * @param control 当前并行 Tick 控制对象
     * @param target 当前候选坐标
     * @return 候选事务结果
     */
    PlanningCandidateGate.CommitResult tryCommitPlanningCandidate(
        ParallelTickControl control,
        ChainTarget target) {
        PlanningCandidateGate.CommitResult result =
                planningCandidateGate.tryCommit(control, target, planningCandidateBlockReader);
        if (result == PlanningCandidateGate.CommitResult.AIR_COMMITTED
                || result == PlanningCandidateGate.CommitResult.NORMAL_COMMITTED) {
            recordDurableProgress();
        }
        return result;
    }

    /** 经同一 deadline 门提交 candidate filter 事务，避免空气探测耗尽窗口后继续读取世界。 */
    PlanningCandidateGate.FilterResult tryCommitPlanningCandidateFilter(
        ParallelTickControl control,
        ChainTarget target) {
        PlanningCandidateGate.FilterResult result =
                planningCandidateGate.tryCommitFilter(control, target, candidateFilter);
        if (result == PlanningCandidateGate.FilterResult.ACCEPTED
                || result == PlanningCandidateGate.FilterResult.REJECTED) {
            recordDurableProgress();
        }
        return result;
    }

    /** 记录已提交到可恢复 traverser/context 状态、下一分片不会重做的真实推进。 */
    void recordDurableProgress() {
        if (progressRevision != Long.MAX_VALUE) {
            progressRevision++;
        }
    }
}
