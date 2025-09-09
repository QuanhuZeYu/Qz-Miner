package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class LiquidDetector extends BasePositionFounder {
    public LiquidDetector(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig minerConfig) {
        super(center, results, player, minerConfig);
    }

    @Override
    public boolean checkCanAdd(Vector3i pos) {
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        return block.getMaterial().isLiquid();
    }
}
