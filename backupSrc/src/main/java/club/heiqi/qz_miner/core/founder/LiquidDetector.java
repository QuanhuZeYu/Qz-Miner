package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class LiquidDetector extends BasePositionFounder {
    public LiquidDetector(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig minerConfig) {
        super(center, results, player, minerConfig);
        setName("流体搜索器");
    }

    @Override
    public boolean checkCanAdd(Vector3i pos) {
        if (foundedPositions.contains(pos)) {
            // LOG.info("重复的点");
            return false;
        }
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        int meta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        if (!block.getMaterial().isLiquid()) return false;

        // 判断是不是流动的液体
        return meta == 0;
    }
}
