package club.heiqi.qz_miner.chain.planner;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.world.World;

/**
 * 连锁搜索上下文工厂。
 */
public final class ChainSearchContextFactory {

    private ChainSearchContextFactory() {}

    public static ChainSearchContext createBlockFloodFillContext(World world, ChainSession session, BlockSeedSnapshot seedSnapshot) {
        ConcurrentLinkedQueue<ChainTarget> currentFrontier = session.getTraversalTargets();
        ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        return new ChainSearchContext(
            world,
            seedSnapshot.getOrigin(),
            seedSnapshot.getSampleBlock(),
            seedSnapshot.getSampleMeta(),
            seedSnapshot.getSampleTileEntity(),
            session.getSubMode(),
            Config.chainRadius,
            Config.chainMaxBlocks,
            currentFrontier,
            nextFrontier,
            visited);
    }

    public static ChainSearchContext createBlockBoxScanContext(World world, ChainSession session, BlockSeedSnapshot seedSnapshot) {
        ConcurrentLinkedQueue<ChainTarget> currentFrontier = session.getTraversalTargets();
        ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        return new ChainSearchContext(
            world,
            seedSnapshot.getOrigin(),
            seedSnapshot.getSampleBlock(),
            seedSnapshot.getSampleMeta(),
            seedSnapshot.getSampleTileEntity(),
            session.getSubMode(),
            Config.chainRadius,
            Config.chainMaxBlocks,
            currentFrontier,
            nextFrontier,
            visited);
    }
}
