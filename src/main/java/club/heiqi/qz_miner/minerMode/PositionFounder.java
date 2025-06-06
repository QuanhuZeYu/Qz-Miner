package club.heiqi.qz_miner.minerMode;

import club.heiqi.qz_miner.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 继承类只需处理好搜点工作即可
 */
public abstract class PositionFounder implements Runnable {
    public Logger LOG = LogManager.getLogger();
    public static int heartbeatTimeout = Config.heartbeatTimeout;
    public static int radiusLimit = Config.radiusLimit;
    public static int chainRange = Config.neighborDistance;

    public int canBreakBlockCount = 0;
    public final Vector3i center;
    /**初始化时添加10ms的误差容量避免初始化后立即卸载*/
    public AtomicLong heartbeatTimer = new AtomicLong(System.currentTimeMillis()+10_000);
    public final AbstractMode mode;
    public final ModeManager manager;

    /**获取到的点列表*/
    public ConcurrentLinkedQueue<Vector3i> cache = new ConcurrentLinkedQueue<>();

    /**
     * 构造函数准备执行搜索前的准备工作
     */
    public PositionFounder(AbstractMode mode) {
        this.mode = mode;
        this.manager = mode.modeManager;
        this.center = mode.center;
        cache.add(this.center);
        readConfig();
    }

    /**
     * 只有主线程AbstractMode结束，该类的run才会结束
     */
    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            sendHeartbeat();
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (!checkHeartBeat()) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                mainLogic();
            } catch (Exception e) {
                LOG.error("执行主要逻辑过程中发生错误 -- {}", String.valueOf(e));
                return;
            }
            try {
                Thread.sleep(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 终止线程
            }
        }
    }

    public abstract void mainLogic();


    public void updateHeartbeat(long timestamp) {
        heartbeatTimer.set(timestamp);
    }

    public long sendTime = System.nanoTime();
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 50_000_000) return;
        sendTime = System.nanoTime();
        mode.updateHeartbeat(System.currentTimeMillis());
    }

    public void readConfig() {
        heartbeatTimeout = Config.heartbeatTimeout;
        radiusLimit = Config.radiusLimit;
        chainRange = Config.neighborDistance;
    }

    public boolean checkCanBreak(Vector3i pos) {
        return mode.checkCanBreak(pos);
    }

    /**心跳超时返回false*/
    public boolean checkHeartBeat() {
        return System.currentTimeMillis() - heartbeatTimer.get() < heartbeatTimeout;
    }

    public List<Vector3i> sort(List<Vector3i> list) {
        // 根据到center的距离进行排序
        list.sort((o1, o2) -> {
            int d1 = (int) o1.distanceSquared(center);
            int d2 = (int) o2.distanceSquared(center);
            return d1 - d2;
        });
        return list;
    }
}
