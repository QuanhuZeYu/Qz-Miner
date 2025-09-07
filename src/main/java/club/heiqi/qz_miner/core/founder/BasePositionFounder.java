package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import club.heiqi.qz_miner.thread.Pauseable;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class BasePositionFounder extends Pauseable {
    public Logger LOG = LogManager.getLogger();

    public Vector3i center;
    public EntityPlayer player;
    public MinerConfig minerConfig;
    public LinkedBlockingQueue<Vector3i> positions;

    public int curCount = 0; // 包含初始加入的中心块

    public BasePositionFounder(
            Vector3i center,
            LinkedBlockingQueue<Vector3i> results,
            EntityPlayer player,
            MinerConfig minerConfig
    ) {
        this.center = center;
        this.player = player;
        this.positions = results;
        this.minerConfig = minerConfig;
        addResult(center);
    }

    @Override
    public void run() {
        int curRadius = 1;
        try {
            while (curCount < minerConfig.blockLimit && curRadius <= minerConfig.bigRadius) {
                // LOG.info("当前半径: {} 当前块数: {}", curRadius, curCount);
                for (int x = center.x - curRadius; x <= center.x + curRadius; x++) {
                    for (int y = center.y - curRadius; y <= center.y + curRadius; y++) {
                        for (int z = center.z - curRadius; z <= center.z + curRadius; z++) {
                            Vector3i pos = new Vector3i(x, y, z);
                            if (checkCanBreak(pos)) {
                                this.addResult(pos);
                            }
                            if (curCount >= minerConfig.blockLimit) {
                                return;
                            }
                            waitUntil();
                            if (Thread.currentThread().isInterrupted()) {
                                LOG.info("线程被中断");
                                return;
                            }
                        }
                    }
                }
                curRadius++;
                if (curRadius > minerConfig.bigRadius) {
                    break; // 超出半径范围，退出
                }
            }
        } finally {
            LOG.info("搜索执行完毕");
        }
    }

    public boolean checkCanBreak(Vector3i pos) {
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        Vector3i playerPos = new Vector3i((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        int blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);
        if (block.equals(Blocks.air)) {
            return false;
        }
        // 玩家脚下的一个方块不能被挖掘
        if (pos.x == playerPos.x && pos.y == (playerPos.y - 1) && pos.z == playerPos.z) {
            return false;
        }
        return block.canHarvestBlock(player, blockMeta);
    }

    public void addResult(Vector3i pos) {
        // LOG.info("添加位置: x: {} y: {} z: {}", pos.x, pos.y, pos.z);
        try {
            this.positions.put(pos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 重新设置中断标志位
        }
        curCount++;
    }
}
