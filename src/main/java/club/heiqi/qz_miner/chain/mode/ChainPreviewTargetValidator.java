package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.chain.planner.ChainTarget;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 子模式预览目标校验器。
 */
public interface ChainPreviewTargetValidator {

    /**
     * 判断目标是否允许启动预览。
     *
     * @param world 当前世界
     * @param target 当前目标
     * @param sampleTileEntity 目标 TileEntity 快照
     * @return 是否允许预览
     */
    boolean canPreview(World world, ChainTarget target, TileEntity sampleTileEntity);
}
