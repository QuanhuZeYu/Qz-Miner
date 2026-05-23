package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

/**
 * GT 线缆目标匹配器。
 */
public class GregTechCableMatcher implements ChainBlockMatcher {

    private final int sampleMetaTileId;

    public GregTechCableMatcher(int sampleMetaTileId) {
        this.sampleMetaTileId = sampleMetaTileId;
    }

    @Override
    public boolean matches(EntityPlayer player, ChainTarget target) {
        if (player == null || target == null) {
            return false;
        }

        TileEntity tileEntity = player.worldObj.getTileEntity(target.getX(), target.getY(), target.getZ());
        return CompatAdapters.cable().isCable(tileEntity)
            && CompatAdapters.cable().getCableMetaTileId(tileEntity) == sampleMetaTileId;
    }
}
