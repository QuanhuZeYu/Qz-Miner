package club.heiqi.qz_miner.chain.planner;

import java.util.Queue;
import java.util.Set;

import net.minecraft.block.Block;
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
    private final int maxRadius;
    private final int maxTargets;
    private final Queue<ChainTarget> currentFrontier;
    private final Queue<ChainTarget> nextFrontier;
    private final Set<ChainTarget> visited;
    private int confirmedCount;
    private int scanDepth;

    public ChainSearchContext(
        World world,
        ChainTarget origin,
        Block sampleBlock,
        int sampleMeta,
        int maxRadius,
        int maxTargets,
        Queue<ChainTarget> currentFrontier,
        Queue<ChainTarget> nextFrontier,
        Set<ChainTarget> visited) {
        this.world = world;
        this.origin = origin;
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.maxRadius = maxRadius;
        this.maxTargets = maxTargets;
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

    public int getMaxRadius() {
        return maxRadius;
    }

    public int getMaxTargets() {
        return maxTargets;
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
}
