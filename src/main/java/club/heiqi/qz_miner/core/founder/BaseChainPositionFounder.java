package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class BaseChainPositionFounder extends BasePositionFounder {
    public BaseChainPositionFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig minerConfig) {
        super(center, results, player, minerConfig);
    }

    @Override
    public boolean checkCanBreak(Vector3i pos) {
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        Vector3i playerPos = new Vector3i((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        int blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);

        // 是空气跳过
        if (block.equals(Blocks.air)) {
            return false;
        }

        // 玩家脚下的一个方块不能被挖掘
        if (pos.x == playerPos.x && pos.y == (playerPos.y - 1) && pos.z == playerPos.z) {
            return false;
        }

        // 不可挖掘跳过
        if (!block.canHarvestBlock(player, blockMeta)) return false;

        // 检查该点连锁小区域内是否有已标记点
        for (Vector3i position : new ArrayList<>(this.positions)) {
            // 判断点 X Y Z 距离 及其曼哈顿距离
        }

        return true;
    }
}
