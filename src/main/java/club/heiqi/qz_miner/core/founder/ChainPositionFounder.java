package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class ChainPositionFounder extends BasePositionFounder {
    public ChainPositionFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig minerConfig) {
        super(center, results, player, minerConfig);
        setName("连锁搜索器");
    }

    @Override
    public boolean checkCanAdd(Vector3i pos) {
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        // 是空气跳过
        if (block.equals(Blocks.air) || block.getMaterial().isLiquid()) {
            return false;
        }
        Vector3i playerPos = new Vector3i((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        int blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);


        // 玩家脚下的一个方块不能被挖掘
        if (pos.x == playerPos.x && pos.y == (playerPos.y - 1) && pos.z == playerPos.z) {
            return false;
        }

        // 判断是否与样本相同
        if (!DeterminingIdentical.Identical(sampleBlock, sampleBlockMeta, sampleTileEntity, pos, player))
            return false;

        // 检查该点连锁小区域内是否有已标记点
        boolean inRange = false;
        for (Vector3i position : foundedPositions) {
            // 判断点 X Y Z 距离 及其曼哈顿距离
            Vector3i offsetDistance = new Vector3i(position).sub(pos);
            int xOffset = Math.abs(offsetDistance.x);
            int yOffset = Math.abs(offsetDistance.y);
            int zOffset = Math.abs(offsetDistance.z);

            if (xOffset <= minerConfig.smallRadius &&
                    yOffset <= minerConfig.smallRadius &&
                    zOffset <= minerConfig.smallRadius
            ) {
                inRange = true;
                break;
            }
        }
        if (!inRange) return false;

        if (player.capabilities.isCreativeMode) return true;
        return block.canHarvestBlock(player, blockMeta);
    }
}
