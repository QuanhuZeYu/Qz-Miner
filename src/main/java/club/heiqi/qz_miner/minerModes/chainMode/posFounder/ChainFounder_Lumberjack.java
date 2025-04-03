package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChainFounder_Lumberjack extends PositionFounder {
    public static int lumberjackRange = 255;

    public boolean isLog = false;
    public Block sampleBlock;
    public String sampleBlockUnLocalizedName;
    public Set<Vector3i> visitedChainSet = new HashSet<>();
    public Set<Vector3i> nextChainSet = new HashSet<>();

    public ChainFounder_Lumberjack(AbstractMode mode, Vector3i center, EntityPlayer player) {
        super(mode, center, player);
        nextChainSet.add(center);
        visitedChainSet.add(center);
        sampleBlock = world.getBlock(center.x, center.y, center.z);
        sampleBlockUnLocalizedName = sampleBlock.getUnlocalizedName();
        int[] sampleOreIDs = OreDictionary.getOreIDs(new ItemStack(sampleBlock));
        for (int sampleOreID : sampleOreIDs) {
            if (OreDictionary.getOreName(sampleOreID).equals("logWood")
                || OreDictionary.getOreName(sampleOreID).equals("treeLeaves")) {
                isLog = true;
            }
        }
    }

    @Override
    public void mainLogic() {
        if (!isLog) return;
        // 1.建立当前遍历所需列表，添加已访问点
        List<Vector3i> searchList = new ArrayList<>(nextChainSet);
        nextChainSet = new HashSet<>();
        visitedChainSet.addAll(searchList);
        // 2.搜索连锁点
        Set<Vector3i> result = new HashSet<>();
        for (Vector3i pos : searchList) {
            result.addAll(scanBox(pos));
            if (Thread.currentThread().isInterrupted()) return; // 线程中断提前返回
        }
        // 3.从结果中移除已访问过的点，将结果添加到cache
        result.removeAll(visitedChainSet);
        result.removeIf(pos -> !filter(pos)); // 筛选点
        cache.addAll(sort(new ArrayList<>(result)));
        sendHeartbeat(); // 发送心跳
        // 4.将结果作为下一次遍历的点
        nextChainSet.addAll(result);
    }

    public List<Vector3i> scanBox(Vector3i pos) {
        List<Vector3i> result = new ArrayList<>();
        int minX = pos.x - radiusLimit; int maxX = pos.x + radiusLimit;
        int minY = Math.max(0, (pos.y - lumberjackRange)); int maxY = Math.min(255, (pos.y + lumberjackRange));
        int minZ = pos.z - radiusLimit; int maxZ = pos.z + radiusLimit;
        for (int i = Math.max((pos.x - chainRange), minX); i <= Math.min((pos.x + chainRange), maxX); i++) {
            for (int j = Math.max((pos.y - chainRange), minY); j <= Math.min((pos.y + chainRange), maxY); j++) {
                for (int k = Math.max((pos.z - chainRange), minZ); k <= Math.min((pos.z + chainRange), maxZ); k++) {
                    if (Thread.currentThread().isInterrupted()) return result; // 线程中断提前返回
                    if (i == pos.x && j == pos.y && k == pos.z) continue; // 排除自身
                    result.add(new Vector3i(i, j, k));
                    sendHeartbeat();
                }
            }
        }
        return result;
    }

    public boolean filter(Vector3i pos) {
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) return false;
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

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
