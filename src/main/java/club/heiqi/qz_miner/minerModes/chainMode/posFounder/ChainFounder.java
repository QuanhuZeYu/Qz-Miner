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

import java.util.*;
import java.util.stream.Collectors;

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
        int x = pos.x; int y = pos.y; int z = pos.z;
        Block block = world.getBlock(x, y, z);
        // 快速失败：空气或液体直接返回
        if (block.isAir(world, x, y, z) || block.getMaterial().isLiquid()) return false;

        // 元数据不匹配直接返回
        if (world.getBlockMetadata(x, y, z) != mode.blockSampleMeta) {
            return false;
        }

        // 处理 TileEntity 匹配逻辑
        TileEntity te = world.getTileEntity(x, y, z);
        if (te != null) {
            if (isGTTile) {
                if (te instanceof CoverableTileEntity gtTe) {
                    CoverableTileEntity sampleTe = (CoverableTileEntity) mode.tileSample;
                    int sampleMID = ((CoverableTileEntityAccessor) sampleTe).getMID();
                    int targetMID = ((CoverableTileEntityAccessor) gtTe).getMID();
                    if (sampleMID == targetMID) {
                        return true;
                    }
                }
            } else {
                // 非 GTTile 类型直接通过
                return true;
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
                return true;
            }
        }
        // 判断方块是否相同
        return Block.getIdFromBlock(block) == Block.getIdFromBlock(mode.blockSample);
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 50_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
