package club.heiqi.qz_miner.minerModes.chainMode.posFounder;

import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.block.Block;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     */
    public ChainFounder(Vector3i center, EntityPlayer player) {
        super(center, player);
        nextChainSet.add(center);
        visitedChainSet.add(center);
        sampleBlock = world.getBlock(center.x, center.y, center.z);
        sampleBlockUnLocalizedName = sampleBlock.getUnlocalizedName();
    }

    @Override
    public void run() {
        super.run();
        while (!getStop()) {
            timer = System.currentTimeMillis();
            if (checkShouldShutdown()) break;
            foundChain();
            if (!checkOutTime(50)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    logger.warn("等待时出现异常: {}", e.toString());
                }
            }
        }
        setStop(true);
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
                    if (checkCacheFull_ShouldStop()) {
                        return;
                    }
                    try {
                        cache.put(pos2);
                    } catch (InterruptedException e) {
                        logger.warn("缓存队列异常");
                        Thread.currentThread().interrupt(); // 恢复中断状态
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
        int minY = Math.max(0, (pos.y - radiusLimit)); int maxY = Math.min(255, (pos.y + radiusLimit));
        int minZ = pos.z - radiusLimit; int maxZ = pos.z + radiusLimit;
        for (int i = Math.max((pos.x - chainRange), minX); i <= Math.min((pos.x + chainRange), maxX); i++) {
            int ir = Math.abs(i - center.x);
            for (int j = Math.max((pos.y - chainRange), minY); j <= Math.min((pos.y + chainRange), maxY); j++) {
                int jr = Math.abs(j - center.y);
                for (int k = Math.max((pos.z - chainRange), minZ); k <= Math.min((pos.z + chainRange), maxZ); k++) {
                    int kr = Math.abs(k - center.z);
                    if (i == pos.x && j == pos.y && k == pos.z) continue;
                    result.add(new Vector3i(i, j, k));
                    int maxRadius = Math.max(ir, Math.max(jr, kr));
                    int rl = radiusLimit;
                    int mr = Math.max(rl, maxRadius);
                    setRadius(mr);
                }
            }
        }
        return result;
    }

    public boolean filter(Block block) {
        return block.getUnlocalizedName().equals(sampleBlockUnLocalizedName);
    }

    @Override
    public boolean checkShouldShutdown() {
        if (nextChainSet.isEmpty()) {
//            logger.info("没有找到链路，停止搜索");
            setStop(true);
            return true;
        }
        if (!allPlayerStorage.playerStatueMap.get(player.getUniqueID()).modeManager.getIsReady()) {
//            logger.info("玩家未就绪，停止搜索");
            setStop(true);
            return true;
        }
        if (getStop()) {
//            logger.info("玩家取消连锁，停止搜索");
            return true;
        }
        return false;
    }
}
