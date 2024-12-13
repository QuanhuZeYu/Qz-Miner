package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.minerModes.PositionFounder;
import club.heiqi.qz_miner.minerModes.TaskState;
import net.minecraft.block.Block;
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

public class ChainFounder extends PositionFounder {
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
    public ChainFounder(Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
        super(center, player, lock);
        nextChainSet.add(center);
        visitedChainSet.add(center);
        sampleBlock = world.getBlock(center.x, center.y, center.z);
        sampleBlockUnLocalizedName = sampleBlock.getUnlocalizedName();
    }

    @Override
    public void loopLogic() {
        foundChain();
    }

    public void foundChain() {
        Set<Vector3i> temp2 = new HashSet<>(); // 下一次存储搜索到的链路点
        for (Vector3i pos : nextChainSet) {
            List<Vector3i> box = scanBox(pos);
            for (Vector3i pos2 : box) {
                Block block = world.getBlock(pos2.x, pos2.y, pos2.z);
                if (filter(block) && !visitedChainSet.contains(pos2)) {
                    temp2.add(pos2);
                    checkShouldShutdown();
                }
            }
            // 将temp2中的点 与 center 计算距离sort
            List<Vector3i> sort = sort(new ArrayList<>(temp2));
            // 逐个put到cache中
            for (Vector3i pos2 : sort) {
                if (beforePutCheck()) break;
                try {
                    if (checkCanBreak(pos2)) {
                        cache.put(pos2); canBreakBlockCount++;
                    }
                } catch (InterruptedException e) {
                    logger.warn("缓存队列异常");
                    Thread.currentThread().interrupt(); // 恢复中断状态
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
        int minX = pos.x - radiusLimit; int maxX = pos.x + radiusLimit; // 设定允许搜索的边界
        int minY = Math.max(0, (pos.y - radiusLimit)); int maxY = Math.min(255, (pos.y + radiusLimit)); // 限制Y
        int minZ = pos.z - radiusLimit; int maxZ = pos.z + radiusLimit;
        // 搜索
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

    /**
     * 通过矿词过滤方块
     * @param block 需要过滤的方块
     * @return 是否通过
     */
    public boolean filter(Block block) {
        if (sampleBlock.equals(block)) {
            return true;
        }
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
        if (canBreakBlockCount >= blockLimit) {
            setTaskState(TaskState.STOP);
            return true;
        }
        if (nextChainSet.isEmpty()) {
            setTaskState(TaskState.STOP);
            return true;
        }
        if (!allPlayerStorage.playerStatueMap.get(player.getUniqueID()).getIsReady()) {
            setTaskState(TaskState.STOP);
            return true;
        }
        if (getTaskState() == TaskState.STOP) {
            return true;
        }
        return false;
    }
}
