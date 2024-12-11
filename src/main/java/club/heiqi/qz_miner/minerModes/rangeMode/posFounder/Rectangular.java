package club.heiqi.qz_miner.minerModes.rangeMode.posFounder;

import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import static club.heiqi.qz_miner.MY_LOG.logger;

public class Rectangular extends PositionFounder {
    public Vector3i temp1 = new Vector3i(); // 存储需要扫描平面的正负两个端点的坐标
    public Vector3i temp2 = new Vector3i();
    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     */
    public Rectangular(Vector3i center, EntityPlayer player) {
        super(center, player);
        setRadius(1);
    }

    @Override
    public void run() {
        super.run();
        while (!getStop()) {
//            logger.info("很吵的日志, cachesize: {}", cache.size());
            timer = System.currentTimeMillis();
            scanXZ();
            if (checkShouldShutdown()) return;
            scanYZ();
            if (checkShouldShutdown()) return;
            scanXY();
            if (checkShouldShutdown()) return;
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

    public void scanXZ() {
        temp1 = new Vector3i(center.x, center.y + getRadius(), center.z);
        temp2 = new Vector3i(center.x, center.y - getRadius(), center.z);
        for (int i = temp1.x - getRadius(); i <= temp1.x + getRadius(); i++) {
            for (int j = temp1.z - getRadius(); j <= temp1.z + getRadius(); j++) {
                Vector3i up = new Vector3i(i, temp1.y, j); // Y坐标双向
                Vector3i down = new Vector3i(i, temp2.y, j);
                if (checkCacheFull_ShouldStop()) {
                    return;
                }
                try {
                    cache.put(up);
                    cache.put(down);
                    if (checkShouldShutdown()) return;
                } catch (InterruptedException e) {
                    logger.error("缓存队列异常");
                }
            }
        }
    }

    public void scanYZ() {
        temp1 = new Vector3i(center.x + getRadius(), center.y, center.z);
        temp2 = new Vector3i(center.x - getRadius(), center.y, center.z);
        for (int i = temp1.y - getRadius(); i <= temp1.y + getRadius(); i++) {
            for (int j = temp1.z - getRadius(); j <= temp1.z + getRadius(); j++) {
                Vector3i pos1 = new Vector3i(temp1.x, i, j); // X坐标双向
                Vector3i pos2 = new Vector3i(temp2.x, i, j);
                if (checkCacheFull_ShouldStop()) {
                    return;
                }
                try {
                    cache.put(pos1);
                    cache.put(pos2);
                    if (checkShouldShutdown()) return;
                } catch (InterruptedException e) {
                    logger.error("缓存队列异常");
                }
            }
        }
    }

    public void scanXY() {
        temp1 = new Vector3i(center.x, center.y, center.z + getRadius());
        temp2 = new Vector3i(center.x, center.y, center.z - getRadius());
        for (int i = temp1.x - getRadius(); i <= temp1.x + getRadius(); i++) {
            for (int j = temp1.y - getRadius(); j <= temp1.y + getRadius(); j++) {
                Vector3i pos1 = new Vector3i(i, j, temp1.z); // Z坐标双向
                Vector3i pos2 = new Vector3i(i, j, temp2.z);
                if (checkCacheFull_ShouldStop()) {
                    return;
                }
                try {
                    cache.put(pos1);
                    cache.put(pos2);
                    if (checkShouldShutdown()) return;
                } catch (InterruptedException e) {
                    logger.error("缓存队列异常");
                }
            }
        }
    }
}
