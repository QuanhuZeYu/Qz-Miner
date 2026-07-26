package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/**
 * 与冻结 seed 同种的 live source 匹配器。
 *
 * <p>实例只保存流体纯值身份与 seed 可用性，不持有 World、TileEntity 或未来补源状态。</p>
 */
public final class LiquidSourceBlockMatcher implements ChainBlockMatcher {

    private final String seedFluidIdentity;
    private final boolean seedSource;

    /**
     * 从冻结 seed 创建匹配器。
     *
     * @param seedBlock 冻结 seed 方块
     * @param seedMetadata 冻结完整 metadata
     */
    public LiquidSourceBlockMatcher(Block seedBlock, int seedMetadata) {
        this.seedFluidIdentity = ChainLiquidRules.fluidIdentity(seedBlock);
        this.seedSource = ChainLiquidRules.isSeedSource(seedBlock, seedMetadata, seedFluidIdentity);
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (!seedSource || player == null || target == null || player.worldObj == null) {
            return false;
        }

        World world = player.worldObj;
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        try {
            Block candidateBlock = world.getBlock(x, y, z);
            int candidateMetadata = world.getBlockMetadata(x, y, z);
            return ChainLiquidRules.matchesSource(
                    seedFluidIdentity, world, x, y, z, candidateBlock, candidateMetadata);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }
}
