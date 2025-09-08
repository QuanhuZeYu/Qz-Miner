package club.heiqi.qz_miner.core.founder;

import club.heiqi.qz_miner.core.MinerConfig;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.concurrent.LinkedBlockingQueue;

public class BlastingLoggingFounder extends BasePositionFounder {
    public BlastingLoggingFounder(Vector3i center, LinkedBlockingQueue<Vector3i> results, EntityPlayer player, MinerConfig minerConfig) {
        super(center, results, player, minerConfig);
    }

    @Override
    public void run1() {
        int curRadius = 1;
        int highRadius = 1;
        while (curCount < minerConfig.blockLimit) {
            // LOG.info("当前半径: {} 当前块数: {}", curRadius, curCount);
            for (int x = center.x - curRadius; x <= center.x + curRadius; x++) {
                int minY = Math.max(center.y - highRadius, 0);
                int maxY = Math.min(center.y + highRadius, 255);
                for (int y = center.y - highRadius; y <= center.y + highRadius; y++) {
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
                if (minY == 0 && maxY == 255) break; // 超出高度范围
            }
            curRadius = Math.min(curRadius+1, minerConfig.bigRadius);
            highRadius++;
        }
    }

    @Override
    public boolean checkCanBreak(Vector3i pos) {
        Block block = player.worldObj.getBlock(pos.x, pos.y, pos.z);
        if (block.equals(Blocks.air) || block.getMaterial().isLiquid()) {
            return false;
        }
        Vector3i playerPos = new Vector3i((int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ));
        int blockMeta = player.worldObj.getBlockMetadata(pos.x, pos.y, pos.z);

        // 玩家脚下的一个方块不能被挖掘
        if (pos.x == playerPos.x && pos.y == (playerPos.y - 1) && pos.z == playerPos.z) {
            return false;
        }

        // 检查是否是木头或树叶
        boolean founded = false;
        int[] oreIDs = OreDictionary.getOreIDs(new ItemStack(block));
        for (int oreID : oreIDs) {
            String oreName = OreDictionary.getOreName(oreID);
            if (!oreName.equals("logWood") && !oreName.equals("treeLeaves")) continue;
            founded = true;
        }
        if (!founded) return false;

        // 如果是创造模式全都能挖掘
        if (player.capabilities.isCreativeMode) return true;
        return block.canHarvestBlock(player, blockMeta);
    }
}
