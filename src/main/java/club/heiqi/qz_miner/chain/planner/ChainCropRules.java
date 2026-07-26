package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.CropGrowthState;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 作物交互判定规则。
 */
public final class ChainCropRules {

    private ChainCropRules() {}

    /**
     * 判断当前方块或 TileEntity 是否视为可右键连锁的作物。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为作物
     */
    public static boolean isCropBlock(Block block, TileEntity tileEntity) {
        return CompatAdapters.isCropBlock(block, tileEntity);
    }

    /**
     * 查询当前 live 作物的三态生长结果。
     *
     * @return MATURE、IMMATURE 或 fail-closed 的 UNKNOWN
     */
    public static CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
            TileEntity tileEntity) {
        return CompatAdapters.growthState(world, x, y, z, block, metadata, tileEntity);
    }

    /**
     * 判断目标是否已可靠确认未成熟。
     *
     * <p>MATURE 与 UNKNOWN 都拒绝，禁止把兼容缺失误当作可施肥目标。</p>
     */
    public static boolean isReliablyImmature(World world, int x, int y, int z, Block block, int metadata,
            TileEntity tileEntity) {
        return growthState(world, x, y, z, block, metadata, tileEntity) == CropGrowthState.IMMATURE;
    }
}
