package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 继承类只需处理好搜点工作即可
 */
public abstract class PositionFounder implements Runnable {
    public Logger LOG = LogManager.getLogger();
    public static int heartbeatTimeout = Config.heartbeatTimeout;
    public static int radiusLimit = Config.radiusLimit;
    public static int chainRange = Config.neighborDistance;

    public int canBreakBlockCount = 0;
    public final EntityPlayer player;
    public final World world;
    public final Vector3i center;
    public AtomicLong heartbeatTimer = new AtomicLong(System.currentTimeMillis()+10_000);
    public final AbstractMode mode;
    public final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**获取到的点列表*/
    public volatile ConcurrentLinkedQueue<Vector3i> cache = new ConcurrentLinkedQueue<>();

    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player 执行者
     */
    public PositionFounder(AbstractMode mode, Vector3i center, EntityPlayer player) {
        this.mode = mode;
        this.center = center;
        this.player = player;
        this.world = player.worldObj;
        cache.add(center);
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
                LOG.warn("检测到线程已中断");
                return;
            }
            if (System.currentTimeMillis() - heartbeatTimer.get() >= heartbeatTimeout) {
                LOG.warn(" 心跳超时，主动中断线程");
                Thread.currentThread().interrupt();
                return;
            }
            try {
                mainLogic();
            } catch (Exception e) {
                LOG.error("执行主要逻辑过程中发生错误");
                return;
            }
            try {
                Thread.sleep(0);
            } catch (InterruptedException e) {
                LOG.error("休眠时中断线程");
                Thread.currentThread().interrupt(); // 终止线程
            }
        }
    }

    public abstract void mainLogic();


    public void updateHeartbeat(long timestamp) {
        heartbeatTimer.set(timestamp);
    }

    public void sendHeartbeat() {
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
