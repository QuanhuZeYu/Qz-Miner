package club.heiqi.qz_miner.minerModes.rangeMode.posFounder;

import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import java.util.HashSet;
import java.util.Set;

import static club.heiqi.qz_miner.MY_LOG.logger;

public class Sphere extends PositionFounder {
    public Set<Vector3i> cacheSet = new HashSet<>();
    public Set<Vector3i> shellSet = new HashSet<>();
    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     */
    public Sphere(Vector3i center, EntityPlayer player) {
        super(center, player);
        shellSet.add(center);
        setRadius(1);
    }

    @Override
    public void run() {
        while (!getStop()) {
            timer = System.currentTimeMillis();
            scanSurrounding();
            setRadius(getRadius() + 1);
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

    public void scanSurrounding() {
        // 缓存上一次的cacheSet
        Set<Vector3i> lastCacheSet = new HashSet<>(cacheSet);
        // 获取球体外围一圈点附加到cacheSet中
        for (Vector3i pos : shellSet) {
            Vector3i up = new Vector3i(pos.x, pos.y + 1, pos.z);
            Vector3i down = new Vector3i(pos.x, pos.y - 1, pos.z);
            Vector3i left = new Vector3i(pos.x - 1, pos.y, pos.z);
            Vector3i right = new Vector3i(pos.x + 1, pos.y, pos.z);
            Vector3i front = new Vector3i(pos.x, pos.y, pos.z + 1);
            Vector3i back = new Vector3i(pos.x, pos.y, pos.z - 1);
            cacheSet.add(up);
            cacheSet.add(down);
            cacheSet.add(left);
            cacheSet.add(right);
            cacheSet.add(front);
            cacheSet.add(back);
        }
        shellSet.clear();
        // 新的球体减去旧的球体获得壳层
        for (Vector3i pos : cacheSet) {
            // 检查pos是否在lastCacheSet中，并且检查是否在指定球体大小中，加入壳层
            if (!lastCacheSet.contains(pos) && checkInSphere(pos)) {
                shellSet.add(pos);
                if (checkCacheFull_ShouldStop()) {
                    return;
                }
                try {
                    cache.put(pos);
                    if (checkShouldShutdown()) return;
                } catch (InterruptedException e) {
                    logger.warn("缓存队列异常");
                }
            }
        }
    }

    public boolean checkInSphere(Vector3i pos) {
        int distanceSquared = getRadius() * getRadius();
        int actualDistanceSquared = (center.x - pos.x) * (center.x - pos.x)
                                  + (center.y - pos.y) * (center.y - pos.y)
                                  + (center.z - pos.z) * (center.z - pos.z);
        return actualDistanceSquared <= distanceSquared;
    }
}
