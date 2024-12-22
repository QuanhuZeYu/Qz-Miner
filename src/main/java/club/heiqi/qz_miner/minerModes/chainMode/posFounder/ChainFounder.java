package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.minerModes.PositionFounder;
import club.heiqi.qz_miner.minerModes.TaskState;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.MY_LOG.LOG;
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
        checkShouldShutdown();
    }

    public void foundChain() {
        Set<Vector3i> temp2 = new HashSet<>(); // 下一次存储搜索到的链路点
        for (Vector3i pos : nextChainSet) { // 遍历下个节点下的所有点
            List<Vector3i> box = scanBox(pos); // 每个点所需要搜索的范围
            for (Vector3i pos2 : box) { // 遍历box范围下的点 - 如果是所需要的点则先缓存到temp2
                Block block = world.getBlock(pos2.x, pos2.y, pos2.z);
                if (!visitedChainSet.contains(pos2)) {
                    if (!filter(block, pos2)) continue;
                    temp2.add(pos2);
                }
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
//                LOG.info("{} 在睡眠中被打断，已恢复打断标记", this.getClass().getName());
                Thread.currentThread().interrupt(); // 恢复中断状态
                return;
            }
        }
        // 将搜索到的链路放入下个节点容器以便进行遍历
        nextChainSet = new HashSet<>(temp2);
        // 设置已访问点
        visitedChainSet.addAll(nextChainSet);
    }

    public List<Vector3i> scanBox(Vector3i pos) {
        List<Vector3i> result = new ArrayList<>();
        int minX = center.x - radiusLimit; int maxX = center.x + radiusLimit; // 设定允许搜索的边界
        int minY = Math.max(0, (center.y - radiusLimit)); int maxY = Math.min(255, (center.y + radiusLimit)); // 限制Y
        int minZ = center.z - radiusLimit; int maxZ = center.z + radiusLimit;
        // 搜索
        for (int i = Math.max((pos.x - chainRange), minX); i <= Math.min((pos.x + chainRange), maxX); i++) {
            int xr = Math.abs(i - center.x); // 记录当前半径值
            for (int j = Math.max((pos.y - chainRange), minY); j <= Math.min((pos.y + chainRange), maxY); j++) {
                int yr = Math.abs(j - center.y);
                for (int k = Math.max((pos.z - chainRange), minZ); k <= Math.min((pos.z + chainRange), maxZ); k++) {
                    int zr = Math.abs(k - center.z);
                    if (i == pos.x && j == pos.y && k == pos.z) continue; // 排除自身
                    if (!checkCanBreak(new Vector3i(i, j, k))) continue; // 排除不可挖掘方块
                    result.add(new Vector3i(i, j, k));
                    int maxRadius = Math.max(xr, Math.max(yr, zr)); // 仅用于提示搜索的最远距离到哪 - 当前最远搜索半径
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
    public boolean filter(Block block, Vector3i pos) {
        if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) return false;
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
        if (nextChainSet.isEmpty()) {
            setTaskState(TaskState.STOP);
            return true;
        }
        if (!getIsReady()) {
            setTaskState(TaskState.STOP);
            return true;
        }
        if (getTaskState() == TaskState.STOP) {
            return true;
        }
        if (Thread.currentThread().isInterrupted()) {
            setTaskState(TaskState.STOP);
            return true;
        }

        if (player.getHealth() <= 2) {
            return true;
        }
        return false;
    }

    @Override
    public boolean checkCanBreak(Vector3i pos) {
        World world = player.worldObj;
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        int meta = world.getBlockMetadata(pos.x, pos.y, pos.z);
        try {
            EntityPlayerMP player = allPlayerStorage.playerStatueMap.get(this.player.getUniqueID()).playerMP;
            ItemInWorldManager iwm = player.theItemInWorldManager;

            // 判断是否为创造模式
            if (iwm.getGameType().isCreative()) {
                return true;
            }
        } catch (Exception ignored) {

        }
        ItemStack holdItem = player.getCurrentEquippedItem();
        // 判断工具能否挖掘
        if (holdItem != null) {
            return block.canHarvestBlock(player, meta);
        }
        // 判断是否为空气
        if (block == Blocks.air) {
            return false;
        }
        // 判断是否为流体
        if (block.getMaterial().isLiquid()) {
            return false;
        }
        // 判断是否为基岩
        if (block == Blocks.bedrock) {
            return false;
        }
        return true;
    }
}
