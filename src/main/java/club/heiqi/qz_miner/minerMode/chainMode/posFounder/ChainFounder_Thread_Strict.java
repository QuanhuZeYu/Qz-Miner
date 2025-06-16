package club.heiqi.qz_miner.minerMode.chainMode.posFounder;

import bartworks.system.material.TileEntityMetaGeneratedBlock;
import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.PositionFounderThread;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.api.metatileentity.CoverableTileEntity;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChainFounder_Thread_Strict extends PositionFounderThread {
    public Set<Vector3i> visitedChainSet = new HashSet<>();
    public Set<Vector3i> nextChainSet = new HashSet<>();
    public boolean isGTTile = false;
    public boolean isGTBlockOre = false;
    public boolean isBW = false;

    public ChainFounder_Thread_Strict(AbstractMode mode) {
        super(mode);
        nextChainSet.add(center);
        if (CheckCompatibility.isHasClass_MetaTileEntity && mode.tileSample instanceof CoverableTileEntity) isGTTile = true;
        if (CheckCompatibility.isHasClass_TileEntityOre && mode.tileSample instanceof TileEntityOres) isGTBlockOre = true;
        if (CheckCompatibility.isHasClass_TileEntityMetaGeneratedBlock && mode.tileSample instanceof TileEntityMetaGeneratedBlock) isBW = true;
    }

    @Override
    public void mainLogic() {
        // 1.建立当前遍历所需列表，添加已访问点
        List<Vector3i> searchList = new ArrayList<>(nextChainSet);
        nextChainSet = new HashSet<>();
        visitedChainSet.addAll(searchList);
        // 2.搜索连锁点
        Set<Vector3i> result = new HashSet<>();
        for (Vector3i pos : searchList) {
            if (Thread.currentThread().isInterrupted()) return; // 线程中断提前返回
            result.addAll(scanBox(pos));
        }
        // 3.从结果中移除已访问过的点，将结果添加到cache
        result.removeAll(visitedChainSet);
        cache.addAll(sort(new ArrayList<>(result)));
        sendHeartbeat(); // 发送心跳
        // 4.将结果作为下一次遍历的点
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
                    Vector3i thisPos = new Vector3i(i,j,k);
                    if (i == pos.x && j == pos.y && k == pos.z) continue; // 排除自身
                    /*if (!checkCanBreak(thisPos)) continue; // 排除不可挖掘方块
                    if (!filter(thisPos)) continue; // 排除非连锁方块*/
                    Vector3i res = filter(thisPos);
                    if (res != null) result.add(new Vector3i(res));
                    sendHeartbeat();
                }
            }
        }
        return result;
    }

    /**
     * 严格过滤模式
     * @return 是否通过
     */
    public Vector3i filter(Vector3i pos) {
        /*RunnableFuture<Vector3i> future = new FutureTask<>(() -> {*/
        try {
            World world = manager.player.worldObj;
            final Block block = world.getBlock(pos.x, pos.y, pos.z);
            final int blockID = Block.getIdFromBlock(block); // 此方块ID
            final int sampleBlockID = Block.getIdFromBlock(mode.blockSample); // 样本ID
            // 1.筛选方块ID
            if (blockID != sampleBlockID) {
                /*LOG.info("方块ID不同: 样本:{} - 当前{}",sampleBlockID,blockID);*/
                return null;
            }
            final int thisMeta = world.getBlockMetadata(pos.x, pos.y, pos.z); // 此方块Meta
            // 空气和水直接拒绝
            if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) {
                /*LOG.info("空气和水直接拒绝");*/
                return null;
            }
            TileEntity te = world.getTileEntity(pos.x, pos.y, pos.z);
            // 2.筛选Meta值
            if (thisMeta != mode.blockSampleMeta) {
                /*LOG.info("meta: 样本: {} - 当前: {}",mode.blockSampleMeta,thisMeta);*/
                return null;
            }
            // 3.筛选Tile
            if (te != null || mode.tileSample != null) {
                // 3.1筛选格雷Tile
                if (isGTTile
                        && (te instanceof CommonMetaTileEntity gtTe)
                ) {
                    int sMID = ((CommonMetaTileEntity) mode.tileSample).getMetaTileID();
                    int tMID = gtTe.getMetaTileID();
                    if (sMID == tMID) {
                        return pos;
                    }
                    return null;
                }
                // 3.2矿物Meta
                else if (isGTBlockOre
                        && (te instanceof TileEntityOres bTe)
                ) {
                    int sMID = ((TileEntityOres) mode.tileSample).mMetaData;
                    int tMID = bTe.mMetaData;
                    if (sMID == tMID) {
                        return pos;
                    }
                    return null;
                } else if (!isGTBlockOre) {
                    return pos;
                }
                // 3.3bart-work
                else if (isBW
                        && (te instanceof TileEntityMetaGeneratedBlock bTe)
                ) {
                    int tMeta = bTe.mMetaData;
                    int sMeta = ((TileEntityMetaGeneratedBlock) mode.tileSample).mMetaData;
                    if (tMeta == sMeta) {
                        return pos;
                    }
                    return null;
                }
                // 3.4非以上情况Tile相同
                return pos;
            }
            return pos;
        } catch (Exception e) {
            return null;
        } finally {
            doWaitBool();
        }
        /*});
        AsyncManager.pollTask(future);
        return future;*/
    }




    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
