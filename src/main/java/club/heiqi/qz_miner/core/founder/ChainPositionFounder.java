package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.BaseOperator;
import club.heiqi.qz_miner.core.MinerConfig;
import cpw.mods.fml.common.FMLCommonHandler;
import gregtech.common.blocks.BlockOresAbstract;
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
    public void run1() {
        int curRadius = 1;
        while (curCount < minerConfig.blockLimit && curRadius <= minerConfig.bigRadius) {
            for (int x = center.x - curRadius; x <= center.x + curRadius; x++) {
                for (int y = center.y - curRadius; y <= center.y + curRadius; y++) {
                    for (int z = center.z - curRadius; z <= center.z + curRadius; z++) {
                        Vector3i pos = new Vector3i(x, y, z);

                        if (checkCanAdd(pos)) {
                            this.addResult(pos);
                        }

                        // 检查性流程    检查数量     检查线程是否被中断
                        if (curCount >= minerConfig.blockLimit) {
                            return;
                        }
                        waitUntil();
                        if (Thread.currentThread().isInterrupted()) {
                            // LOG.info("线程被中断");
                            return;
                        }
                    }
                }
            }
            curRadius++;
            if (curRadius > minerConfig.bigRadius) {
                // 超出半径范围，退出
                return;
            }
        }
    }

    @Override
    public boolean checkCanAdd(Vector3i pos) {
        if (foundedPositions.contains(pos)) {
            // LOG.info("重复的点");
            return false;
        }
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        // 是空气跳过
        if (block.equals(Blocks.air) || block.getMaterial().isLiquid() || block.equals(Blocks.bedrock)) {
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

    @Override
    public void addResult(Vector3i pos) {
        try {
            this.positions.put(pos);
            this.foundedPositions.add(pos);
            curCount++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 重新设置中断标志位
        }

        // 触发矿脉探索功能
        if (BaseOperator.hasVP_API && DeterminingIdentical.hasBlockBaseOre &&
                player.worldObj.isRemote && FMLCommonHandler.instance().getEffectiveSide().isClient() &&
                player.worldObj.getBlock(pos.x, pos.y, pos.z) instanceof BlockOresAbstract
        ) {
            player.worldObj.getBlock(pos.x, pos.y, pos.z).onBlockActivated(player.worldObj, pos.x, pos.y, pos.z, player, 0,0,0,0);
        }
    }
}
