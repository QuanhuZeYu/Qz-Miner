package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static club.heiqi.qz_miner.MY_LOG.logger;
import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

/**
 * 用法指南：<br>
 * 1.构建该类时传入一个点作为中心点<br>
 * 2.创建一个线程，传入该类，并调用线程的start方法<br>
 * 3.直接使用queue.tack()来逐个获取点<br>
 * 该类依赖于TaskState.COMPLETE来结束，挖掘数量上限需要在MC主线程中进行判断和设置该状态为COMPLETE，使用时切勿忘记
 */
public abstract class PositionFounder implements Runnable {
    public static int taskTimeLimit = Config.taskTimeLimit;
    public static int cacheSizeMAX = Config.pointFounderCacheSize;
    public static int radiusLimit = Config.radiusLimit;
    public static int blockLimit = Config.blockLimit;
    public static int chainRange = Config.chainRange;

    public long timer;
    public AtomicInteger radius = new AtomicInteger(0);
    public EntityPlayer player;
    public World world;
    public Vector3i center;

    public AtomicBoolean stop = new AtomicBoolean(false); // 线程停止标志

    public volatile ArrayBlockingQueue<Vector3i> cache = new ArrayBlockingQueue<>(cacheSizeMAX);

    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     */
    public PositionFounder(Vector3i center, EntityPlayer player) {
        this.center = center;
        this.player = player;
        this.world = player.worldObj;
        try {
            cache.put(center);
        } catch (InterruptedException e) {
            logger.error(e);
        }
        setRadius(0);
    }

    @Override
    public void run() {
        readConfig();
    }

    public boolean getStop() {
        return stop.get();
    }

    public void setStop(boolean stop) {
        this.stop.set(stop);
    }

    public int getRadius() {
        return radius.get();
    }

    public void setRadius(int radius) {
        this.radius.set(radius);
    }

    public void increaseRadius() {
        setRadius(getRadius() + 1);
    }

    public void readConfig() {
        taskTimeLimit = Config.taskTimeLimit;
        cacheSizeMAX = Config.pointFounderCacheSize;
        radiusLimit = Config.radiusLimit;
        blockLimit = Config.blockLimit;
        chainRange = Config.chainRange;
    }

    public boolean checkShouldShutdown() {
        if (getRadius() > radiusLimit) {
//            logger.info("半径超限，停止搜索");
            setStop(true);
            return true;
        }
        if (getStop()) {
//            logger.info("玩家取消连锁，停止搜索");
            return true;
        }
        if (!allPlayerStorage.playerStatueMap.get(player.getUniqueID()).modeManager.getIsReady()) {
//            logger.info("玩家未就绪，停止搜索");
            setStop(true);
            return true;
        }
        return false;
    }

    /**
     * 轮询方法，并且轮询时候会检查是否需要停止
     * @return
     */
    public boolean checkCacheFull_ShouldStop() {
        while (cache.size() >= cacheSizeMAX - 10) {
            if (checkShouldShutdown()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return false;
    }

    public boolean checkOutTime(long millis) {
        return System.currentTimeMillis() - timer > millis;
    }
}
