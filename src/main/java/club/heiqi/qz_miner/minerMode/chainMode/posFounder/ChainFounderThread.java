package club.heiqi.qz_miner.minerMode.chainMode.posFounder;

import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.AsyncManager;
import club.heiqi.qz_miner.minerMode.PositionFounderThread;
import club.heiqi.qz_miner.mixins.GTMixin.CoverableTileEntityAccessor;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.api.metatileentity.CoverableTileEntity;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.stream.Collectors;

public class ChainFounderThread extends PositionFounderThread {
    public Logger LOG = LogManager.getLogger();
    /**已访问过的坐标*/
    public Set<Vector3i> visitedChainSet = new HashSet<>();
    /**计划访问坐标*/
    public Set<Vector3i> nextChainSet = new HashSet<>();
    public boolean isGTTile = false;
    /**
     * 构造函数准备执行搜索前的准备工作
     */
    public ChainFounderThread(AbstractMode mode) {
        super(mode);
        nextChainSet.add(this.center);
        if (CheckCompatibility.isHasClass_MetaTileEntity
            && (mode.tileSample instanceof CoverableTileEntity)
        ) {
            isGTTile = true;
        }
    }

    @Override
    public void mainLogic() {
        // 1.取出计划访问坐标开始处理
        List<Vector3i> searchList = new ArrayList<>(nextChainSet);
        nextChainSet.clear();
        visitedChainSet.addAll(searchList); // 将该次计划访问坐标加入到已访问坐标中
        // 2.搜索连锁坐标 - 下一次的计划坐标
        Set<Vector3i> result = new HashSet<>();
        for (Vector3i pos : searchList) {
            result.addAll(scanBox(pos));
            if (Thread.currentThread().isInterrupted()) return; // 线程中断提前返回
        }
        // 3.从结果中移除已访问过的坐标，将结果添加到cache
        result.removeAll(visitedChainSet);
        cache.addAll(sort(new ArrayList<>(result)));
        sendHeartbeat(); // 发送心跳
        // 4.将结果作为下一次遍历的坐标
        nextChainSet.addAll(result);
    }

    public List<Vector3i> scanBox(Vector3i pos) {
        List<Vector3i> result = new ArrayList<>();
        int minX = center.x - radiusLimit; int maxX = center.x + radiusLimit; // 设定允许搜索的边界
        int minY = Math.max(0, (center.y - radiusLimit)); int maxY = Math.min(255, (center.y + radiusLimit)); // 限制Y
        int minZ = center.z - radiusLimit; int maxZ = center.z + radiusLimit;
        // 遍历半径为 相邻距离配置 最大边界不超过最大半径
        for (int i = Math.max((pos.x - chainRange), minX); i <= Math.min((pos.x + chainRange), maxX); i++) {
            for (int j = Math.max((pos.y - chainRange), minY); j <= Math.min((pos.y + chainRange), maxY); j++) {
                for (int k = Math.max((pos.z - chainRange), minZ); k <= Math.min((pos.z + chainRange), maxZ); k++) {
                    if (Thread.currentThread().isInterrupted() || !checkHeartBeat()) return result; // 线程中断或心跳超时提前返回
                    Vector3i thisPos = new Vector3i(i, j, k);
                    if (i == pos.x && j == pos.y && k == pos.z) continue; // 排除自身
                    /*if (!checkCanBreak(thisPos)) continue; // 排除不可挖掘方块*/
                    Vector3i res = filter(thisPos);
                    if (res != null) result.add(res);
                    sendHeartbeat();
                }
            }
        }
        // 等待所有future结束
        return result;
    }

    public Vector3i filter(Vector3i pos) {
        /*RunnableFuture<Vector3i> future = new FutureTask<>(() -> {*/
        try {
            int x = pos.x;
            int y = pos.y;
            int z = pos.z;
            World worldObj = manager.player.worldObj;
            Block block = worldObj.getBlock(x, y, z);
            // 快速失败：空气或液体直接返回
            if (block.isAir(worldObj, x, y, z) || block.getMaterial().isLiquid()) return null;

            // 元数据不匹配直接返回
            if (worldObj.getBlockMetadata(x, y, z) != mode.blockSampleMeta) {
                return null;
            }

            // 处理 TileEntity 匹配逻辑
            TileEntity te = worldObj.getTileEntity(x, y, z);
            if (te != null) {
                if (isGTTile) {
                    if (te instanceof CoverableTileEntity gtTe) {
                        CoverableTileEntity sampleTe = (CoverableTileEntity) mode.tileSample;
                        int sampleMID = ((CoverableTileEntityAccessor) sampleTe).getMID();
                        int targetMID = ((CoverableTileEntityAccessor) gtTe).getMID();
                        if (sampleMID == targetMID) {
                            return pos;
                        }
                    }
                } else {
                    // 非 GTTile 类型直接通过
                    return pos;
                }
            }
            // 准备比较对象
            Block sampleBlock = mode.blockSample;
            ItemStack sampleStack = new ItemStack(sampleBlock);
            ItemStack blockStack = new ItemStack(block);
            // 矿词匹配优化（使用 HashSet 加速查找）
            int[] sampleOreIDs = OreDictionary.getOreIDs(sampleStack);
            int[] blockOreIDs = OreDictionary.getOreIDs(blockStack);
            Set<Integer> blockOreSet = Arrays.stream(blockOreIDs).boxed().collect(Collectors.toSet());

            for (int oreId : sampleOreIDs) {
                if (blockOreSet.contains(oreId)) {
                    return pos;
                }
            }
            // 判断方块是否相同
            if (Block.getIdFromBlock(block) == Block.getIdFromBlock(mode.blockSample)) {
                return pos;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            doWaitBool();
        }
        /*});
        AsyncManager.pollTask(future);
        return future;*/
    }
}
