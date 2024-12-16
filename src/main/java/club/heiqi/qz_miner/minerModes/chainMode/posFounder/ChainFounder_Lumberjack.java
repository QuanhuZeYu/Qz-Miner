package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.minerModes.PositionFounder;
import club.heiqi.qz_miner.minerModes.TaskState;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.MY_LOG.logger;
import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

public class ChainFounder_Lumberjack extends PositionFounder {
    public static int lumberjackRange = 255;

    public Block sampleBlock;
    public String sampleBlockUnLocalizedName;
    public Set<Vector3i> visitedChainSet = new HashSet<>();
    public Set<Vector3i> nextChainSet = new HashSet<>();
    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     * @param lock
     */
    public ChainFounder_Lumberjack(Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
        super(center, player, lock);
        nextChainSet.add(center);
        visitedChainSet.add(center);
        sampleBlock = world.getBlock(center.x, center.y, center.z);
        sampleBlockUnLocalizedName = sampleBlock.getUnlocalizedName();
        int[] sampleOreIDs = OreDictionary.getOreIDs(new ItemStack(sampleBlock));
        boolean isLog = false;
        for (int sampleOreID : sampleOreIDs) {
            if (OreDictionary.getOreName(sampleOreID).equals("logWood")
                || OreDictionary.getOreName(sampleOreID).equals("treeLeaves")) {
                isLog = true;
            }
        }
        if (!isLog) {
            setTaskState(TaskState.STOP);
        }
    }

    @Override
    public void loopLogic() {
        foundChain();
    }

    public void foundChain() {
        int fortune = EnchantmentHelper.getFortuneModifier(player); // 获取附魔附魔等级
        Set<Vector3i> temp2 = new HashSet<>(); // 下一次存储搜索到的链路点
        for (Vector3i pos : nextChainSet) {
            List<Vector3i> box = scanBox(pos);
            for (Vector3i pos2 : box) {
                Block block = world.getBlock(pos2.x, pos2.y, pos2.z);
                if (filter(block) && !visitedChainSet.contains(pos2)) {
                    temp2.add(pos2);
                    if (beforePutCheck()) {
                        return;
                    }
                    try {
                        cache.put(pos2);
                    } catch (InterruptedException e) {
                        logger.info("{} 在睡眠中被打断，已恢复打断标记", this.getClass().getName());
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        return;
                    }
                    checkShouldShutdown();
                }
            }
        }
        // 剔除已经访问过的点
        nextChainSet = new HashSet<>(temp2);
        // 设置已访问点
        visitedChainSet.addAll(nextChainSet);
    }

    public List<Vector3i> scanBox(Vector3i pos) {
        List<Vector3i> result = new ArrayList<>();
        int minX = pos.x - radiusLimit; int maxX = pos.x + radiusLimit;
        int minY = Math.max(0, (pos.y - lumberjackRange)); int maxY = Math.min(255, (pos.y + lumberjackRange));
        int minZ = pos.z - radiusLimit; int maxZ = pos.z + radiusLimit;
        for (int i = Math.max((pos.x - chainRange), minX); i <= Math.min((pos.x + chainRange), maxX); i++) {
            int ir = Math.abs(i - center.x);
            for (int j = Math.max((pos.y - chainRange), minY); j <= Math.min((pos.y + chainRange), maxY); j++) {
                int jr = Math.abs(j - center.y);
                for (int k = Math.max((pos.z - chainRange), minZ); k <= Math.min((pos.z + chainRange), maxZ); k++) {
                    int kr = Math.abs(k - center.z);
                    if (i == pos.x && j == pos.y && k == pos.z) continue; // 排除自身
                    result.add(new Vector3i(i, j, k));
                    int maxRadius = Math.max(ir, Math.max(jr, kr)); // 仅用于提示搜索的最远距离到哪 - 当前最远搜索半径
                    setRadius(maxRadius);
                }
            }
        }
        return result;
    }

    public boolean filter(Block block) {
        ItemStack sampleStack = new ItemStack(sampleBlock);
        ItemStack blockStack = new ItemStack(block);
        int[] sampleOreIDs = OreDictionary.getOreIDs(sampleStack);
        int[] blockOreIDs = OreDictionary.getOreIDs(blockStack);
        for (int sampleOreID : sampleOreIDs) {
            for (int blockOreID : blockOreIDs) {
                if (sampleOreID == blockOreID) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean checkShouldShutdown() {
        if (nextChainSet.isEmpty()) {
//            logger.info("没有找到链路，停止搜索");
            setTaskState(TaskState.STOP);
            return true;
        }
        if (!getIsReady()) {
//            logger.info("玩家未就绪，停止搜索");
            setTaskState(TaskState.STOP);
            return true;
        }
        if (getTaskState() == TaskState.STOP) {
//            logger.info("玩家取消连锁，停止搜索");
            return true;
        }
        return false;
    }
}
