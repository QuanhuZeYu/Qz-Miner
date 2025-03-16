package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.AbstractMode;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChainGroupFounder extends ChainFounder_Strict{
    public Logger LOG = LogManager.getLogger();
    public List<BlockInfo> sameList = new ArrayList<>();
    public boolean inSame = false;
    public ChainGroupFounder(AbstractMode mode, Vector3i center, EntityPlayer player) {
        super(mode, center, player);
        readConfig();
        String id = mode.blockSample.getUnlocalizedName();
        for (BlockInfo i : sameList) {
            if (i.id.equals(id)) {
                inSame = true;
                break;
            }
        }
    }


    @Override
    public void mainLogic() {
        if (!inSame) return;
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

    @Override
    public List<Vector3i> scanBox(Vector3i pos) {
        List<Vector3i> result = new ArrayList<>();
        int minX = center.x - radiusLimit; int maxX = center.x + radiusLimit; // 设定允许搜索的边界
        int minY = Math.max(0, (center.y - radiusLimit)); int maxY = Math.min(255, (center.y + radiusLimit)); // 限制Y
        int minZ = center.z - radiusLimit; int maxZ = center.z + radiusLimit;
        // 遍历半径为 相邻距离配置 最大边界不超过最大半径
        for (int i = Math.max((pos.x - chainRange), minX); i <= Math.min((pos.x + chainRange), maxX); i++) {
            for (int j = Math.max((pos.y - chainRange), minY); j <= Math.min((pos.y + chainRange), maxY); j++) {
                for (int k = Math.max((pos.z - chainRange), minZ); k <= Math.min((pos.z + chainRange), maxZ); k++) {
                    Vector3i thisPos = new Vector3i(i,j,k);
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

    /**
     * 严格过滤模式
     * @return 是否通过
     */
    @Override
    public boolean filter(Vector3i pos) {
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        String id = block.getUnlocalizedName();
        if (block.isAir(world, pos.x, pos.y, pos.z) || block.getMaterial().isLiquid()) return false;
        boolean hasSame = false;
        for (BlockInfo info : sameList) {
            if (info.id.equals(id)) {
                hasSame = true;
                break;
            }
        }
        return hasSame;
    }

    public static class BlockInfo {
        public String id;
        public int meta;
    }
    @Override
    public void readConfig() {
        String[] sameList = Config.chainGroup;
        Gson gson = new Gson();
        for (String x : sameList) {
            try {
                BlockInfo info = gson.fromJson(x, BlockInfo.class);
                this.sameList.add(info);
            } catch (JsonSyntaxException e) {
                LOG.error("解析字符串时出错: {}", x);
            }
        }
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
