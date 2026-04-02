package club.heiqi.qz_miner.chain.planner;

import java.util.Queue;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * 连锁搜索上下文。
 */
public class ChainSearchContext {

    private final World world;
    private final ChainTarget origin;
    private final Block sampleBlock;
    private final int sampleMeta;
    private final int maxRadius;
    private final int maxTargets;
    private final Queue<ChainTarget> frontier;
    private final Set<ChainTarget> visited;
    private final Set<ChainTarget> matched;

    public ChainSearchContext(
        World world,
        ChainTarget origin,
        Block sampleBlock,
        int sampleMeta,
        int maxRadius,
        int maxTargets,
        Queue<ChainTarget> frontier,
        Set<ChainTarget> visited,
        Set<ChainTarget> matched) {
        this.world = world;
        this.origin = origin;
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.maxRadius = maxRadius;
        this.maxTargets = maxTargets;
        this.frontier = frontier;
        this.visited = visited;
        this.matched = matched;
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

    public Queue<ChainTarget> getFrontier() {
        return frontier;
    }

    public Set<ChainTarget> getVisited() {
        return visited;
    }

    public Set<ChainTarget> getMatched() {
        return matched;
    }
}
