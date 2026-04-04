package club.heiqi.qz_miner.chain.planner;

import java.util.Queue;
import java.util.Set;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import ic2.core.crop.TileEntityCrop;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
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
    private final TileEntity sampleTileEntity;
    private final ChainSubMode subMode;
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
        TileEntity sampleTileEntity,
        ChainSubMode subMode,
        int maxRadius,
        int maxTargets,
        Queue<ChainTarget> currentFrontier,
        Queue<ChainTarget> nextFrontier,
        Set<ChainTarget> visited) {
        this.world = world;
        this.origin = origin;
        this.sampleBlock = sampleBlock;
        this.sampleMeta = sampleMeta;
        this.sampleTileEntity = sampleTileEntity;
        this.subMode = subMode;
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

    /**
     * 判断目标是否允许作为当前遍历候选点。
     *
     * @param target 候选目标
     * @return 是否允许继续遍历
     */
    public boolean canTraverse(ChainTarget target) {
        if (target == null) {
            return false;
        }

        Block block = world.getBlock(target.getX(), target.getY(), target.getZ());
        if (block == null || block == Blocks.air) {
            return false;
        }

        if (subMode == ChainSubMode.INTERACT_CROP) {
            if (block instanceof BlockCrops) {
                return true;
            }

            TileEntity tileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
            return tileEntity instanceof TileEntityCrop;
        }

        if (subMode != null && subMode.requiresOreMatch()) {
            TileEntity tileEntity = world.getTileEntity(target.getX(), target.getY(), target.getZ());
            return ChainOreRules.isOreBlock(block, tileEntity);
        }

        if (subMode != null && subMode.requiresLogMatch()) {
            int meta = world.getBlockMetadata(target.getX(), target.getY(), target.getZ());
            return ChainLogRules.isLogBlock(world, target.getX(), target.getY(), target.getZ(), block, meta);
        }

        return ChainBlockIdentity.matches(world, sampleBlock, sampleMeta, sampleTileEntity, target);
    }
}
