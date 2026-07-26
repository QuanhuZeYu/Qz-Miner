package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 作物兼容适配器。
 */
public interface CropCompatAdapter {

    /**
     * 判断适配器当前是否可用。
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 判断方块或 TileEntity 是否视为可右键连锁的作物。
     *
     * @param block 方块
     * @param tileEntity TileEntity
     * @return 是否为作物
     */
    boolean isCropBlock(Block block, TileEntity tileEntity);

    /**
     * 查询已识别作物的生长状态。
     *
     * <p>可选兼容默认不推测成熟度；只有适配器能可靠证明时才返回已知状态。</p>
     *
     * @param world 当前只读世界
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @param block 当前方块
     * @param metadata 当前完整 metadata
     * @param tileEntity 当前 TileEntity，可为 null
     * @return 三态生长结果
     */
    default CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
            TileEntity tileEntity) {
        return CropGrowthState.UNKNOWN;
    }
}
