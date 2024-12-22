package club.heiqi.qz_miner.minerModes.rangeMode.posFounder;

import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.MY_LOG.LOG;

public class Rectangular extends PositionFounder {
    public Vector3i temp1 = new Vector3i(); // 存储需要扫描平面的正负两个端点的坐标
    public Vector3i temp2 = new Vector3i();
    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     * @param lock
     */
    public Rectangular(Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
        super(center, player, lock);
        setRadius(1);
    }

    @Override
    public void loopLogic() {
        scanXZ();
        scanYZ();
        scanXY();
        setRadius(getRadius() + 1);
    }

    public void scanXZ() {
        List<Vector3i> tempList = new ArrayList<>();
        temp1 = new Vector3i(center.x, center.y + getRadius(), center.z);
        temp2 = new Vector3i(center.x, center.y - getRadius(), center.z);
        for (int i = temp1.x - getRadius(); i <= temp1.x + getRadius(); i++) {
            for (int j = temp1.z - getRadius(); j <= temp1.z + getRadius(); j++) {
                Vector3i up = new Vector3i(i, temp1.y, j); // Y坐标双向
                Vector3i down = new Vector3i(i, temp2.y, j);
                if (checkCanBreak(up)) {
                    tempList.add(up); canBreakBlockCount++;
                }
                if (checkCanBreak(down)) {
                    tempList.add(down); canBreakBlockCount++;
                }
            }
        }
        List<Vector3i> sorted = sort(tempList);
        for (Vector3i pos : sorted) {
            if (beforePutCheck()) {
                return;
            }
            try {
                if (checkCanBreak(pos)) {
                    cache.put(pos); canBreakBlockCount++;
                }
            } catch (InterruptedException e) {
//                LOG.info("{} 在睡眠中被打断，已恢复打断标记", this.getClass().getName());
                Thread.currentThread().interrupt(); // 恢复中断状态
                return;
            }
        }
    }

    public void scanYZ() {
        List<Vector3i> tempList = new ArrayList<>();
        temp1 = new Vector3i(center.x + getRadius(), center.y, center.z);
        temp2 = new Vector3i(center.x - getRadius(), center.y, center.z);
        for (int i = temp1.y - getRadius(); i <= temp1.y + getRadius(); i++) {
            for (int j = temp1.z - getRadius(); j <= temp1.z + getRadius(); j++) {
                Vector3i pos1 = new Vector3i(temp1.x, i, j); // X坐标双向
                Vector3i pos2 = new Vector3i(temp2.x, i, j);
                if (checkCanBreak(pos1)) {
                    tempList.add(pos1); canBreakBlockCount++;
                }
                if (checkCanBreak(pos2)) {
                    tempList.add(pos2); canBreakBlockCount++;
                }
            }
        }
        List<Vector3i> sorted = sort(tempList);
        for (Vector3i pos : sorted) {
            if (beforePutCheck()) {
                return;
            }
            try {
                if (checkCanBreak(pos)) {
                    cache.put(pos); canBreakBlockCount++;
                }
            } catch (InterruptedException e) {
//                LOG.info("{} 在睡眠中被打断，已恢复打断标记", this.getClass().getName());
                Thread.currentThread().interrupt(); // 恢复中断状态
                return;
            }
        }
    }

    public void scanXY() {
        List<Vector3i> tempList = new ArrayList<>();
        temp1 = new Vector3i(center.x, center.y, center.z + getRadius());
        temp2 = new Vector3i(center.x, center.y, center.z - getRadius());
        for (int i = temp1.x - getRadius(); i <= temp1.x + getRadius(); i++) {
            for (int j = temp1.y - getRadius(); j <= temp1.y + getRadius(); j++) {
                Vector3i pos1 = new Vector3i(i, j, temp1.z); // Z坐标双向
                Vector3i pos2 = new Vector3i(i, j, temp2.z);
                if (checkCanBreak(pos1)) {
                    tempList.add(pos1); canBreakBlockCount++;
                }
                if (checkCanBreak(pos2)) {
                    tempList.add(pos2); canBreakBlockCount++;
                }
            }
        }
        List<Vector3i> sorted = sort(tempList);
        for (Vector3i pos : sorted) {
            if (beforePutCheck()) {
                return;
            }
            try {
                if (checkCanBreak(pos)) {
                    cache.put(pos); canBreakBlockCount++;
                }
            } catch (InterruptedException e) {
//                LOG.info("{} 在睡眠中被打断，已恢复打断标记", this.getClass().getName());
                Thread.currentThread().interrupt(); // 恢复中断状态
                return;
            }
        }
    }
}

// 只需要关注是否停止即可
