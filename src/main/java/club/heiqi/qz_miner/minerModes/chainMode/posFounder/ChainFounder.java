package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.PositionFounder;
import club.heiqi.qz_miner.mixins.GTMixin.CoverableTileEntityAccessor;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.api.metatileentity.CoverableTileEntity;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChainFounder extends PositionFounder {
    public Logger LOG = LogManager.getLogger();
    public Set<Vector3i> visitedChainSet = new HashSet<>();
    public Set<Vector3i> nextChainSet = new HashSet<>();
    public boolean isGTTile = false;
    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     */
    public ChainFounder(AbstractMode mode, Vector3i center, EntityPlayer player) {
        super(mode, center, player);
        nextChainSet.add(center);
        if (CheckCompatibility.isHasClass_MetaTileEntity
            && (mode.tileSample instanceof CoverableTileEntity)
        ) isGTTile = true;
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
                    Vector3i thisPos = new Vector3i(i, j, k);
                    if (i == pos.x && j == pos.y && k == pos.z) continue; // 排除自身
                    if (!checkCanBreak(thisPos)) continue; // 排除不可挖掘方块
                    if (!filter(thisPos)) continue; // 排除非连锁方块
                    result.add(thisPos);
                    sendHeartbeat();
                }
            }
        }
        return result;
    }

    public boolean filter(Vector3i pos) {
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) return false;
        TileEntity te = world.getTileEntity(pos.x,pos.y,pos.z);
        if (world.getBlockMetadata(pos.x, pos.y, pos.z) != mode.blockSampleMeta) return false;
        // 判断瓷砖是否相同
        if (te != null) {
            if (isGTTile
                && (te instanceof CoverableTileEntity gtTe)
            ) {
                CoverableTileEntity sTe = (CoverableTileEntity) mode.tileSample;
                int sMID = ((CoverableTileEntityAccessor) sTe).getMID();
                int tMID = ((CoverableTileEntityAccessor)gtTe).getMID();
                if (sMID == tMID) return true;
            } else if (!isGTTile) return true;
        }
        ItemStack sampleStack = new ItemStack(mode.blockSample); // 样本方块物品
        ItemStack blockStack = new ItemStack(block); // 当前方块物品
        // 1.获取矿词
        int[] sampleOreIDs = OreDictionary.getOreIDs(sampleStack);
        int[] blockOreIDs = OreDictionary.getOreIDs(blockStack);
        // 2.对比是否有相同的矿词
        for (int sampleOreID : sampleOreIDs) {
            for (int blockOreID : blockOreIDs) {
                if (sampleOreID == blockOreID) {
                    return true;
                }
            }
        }
        // 未本地化名称完全相同
        return block.getUnlocalizedName().equals(mode.blockSample.getUnlocalizedName());
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 50_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
