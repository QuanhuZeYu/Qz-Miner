package club.heiqi.qz_miner.chain.planner;

import java.util.Queue;
import java.util.Set;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import club.heiqi.qz_miner.objectgroup.ModeExtensionSnapshot;
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
    private ChainCandidateFilter candidateFilter;
    private int confirmedCount;
    private int scanDepth;

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
    }

    public int getScanDepth() {
        return scanDepth;
    }

    public void setScanDepth(int scanDepth) {
        this.scanDepth = Math.max(0, scanDepth);
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
}
