package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.Mod_Main;
import club.heiqi.qz_miner.statueStorage.SelfStatue;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.MY_LOG.LOG;
import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

/**
 * 用法指南：<br>
 * 1.构建该类时传入一个点作为中心点<br>
 * 2.运行run方法，自动提交线程池任务<br>
 * 3.直接使用queue.tack()来逐个获取点<br>
 * 该类会频繁销毁与创建，所以无需关注资源重置问题
 */
public abstract class PositionFounder implements Runnable {
    public static int taskTimeLimit = Config.taskTimeLimit;
    public static int cacheSizeMAX = Config.pointFounderCacheSize;
    public static int radiusLimit = Config.radiusLimit;
    public static int blockLimit = Config.blockLimit;
    public static int chainRange = Config.chainRange;

    public int canBreakBlockCount = 0;
    public long runLoopTimer;
    public AtomicLong minerHeartbeat = new AtomicLong(System.currentTimeMillis());
    public Thread thread;
    public AtomicInteger radius = new AtomicInteger(0);
    public EntityPlayer player;
    public World world;
    public Vector3i center;
    public volatile AtomicBoolean isRunning = new AtomicBoolean(false);
    public ReentrantReadWriteLock lock;


    public volatile LinkedBlockingQueue<Vector3i> cache = new LinkedBlockingQueue<>(cacheSizeMAX);

    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     * @param lock
     */
    public PositionFounder(Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
        isRunning.set(true);
        this.lock = lock;
        this.center = center;
        this.player = player;
        this.world = player.worldObj;
        try {
            cache.put(center);
        } catch (InterruptedException e) {
            LOG.error(e);
            Thread.currentThread().interrupt(); // 恢复中断状态
        }
        setRadius(0);
    }

    public void updateTaskState() {
        if (checkShouldShutdown()) {
            isRunning.set(false);
        }
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        // 该方法只会进入一次
        readConfig();
        while (isRunning.get()) {
            runLoopTimer = System.currentTimeMillis();
            loopLogic();
            updateTaskState();
            try { // 默认休眠5ms - 0.1tick
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // 恢复中断状态
            }
        }
        isRunning.set(false); // 标志结束
    }
    public abstract void loopLogic();

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

    public boolean checkCanBreak(Vector3i pos) {
        World world = player.worldObj;;
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        int meta = world.getBlockMetadata(pos.x, pos.y, pos.z);
        if (this.player instanceof EntityPlayerMP) {
            try {
                EntityPlayerMP player = (EntityPlayerMP) this.player;
                ItemInWorldManager iwm = player.theItemInWorldManager;
                // 判断是否为创造模式
                if (iwm.getGameType().isCreative()) {
                    return true;
                }
            } catch (Exception e) {
                Mod_Main.LOG.warn("检查是否可以挖掘时出现异常: {}", e.toString());
            }
        }
        ItemStack holdItem = player.getCurrentEquippedItem();
        // 判断工具能否挖掘
        if (holdItem != null) {
            return block.canHarvestBlock(player, meta);
        }
        // 判断是否为空气
        if (block == Blocks.air) {
            return false;
        }
        // 判断是否为流体
        if (block.getMaterial().isLiquid()) {
            return false;
        }
        // 判断是否为基岩
        if (block == Blocks.bedrock) {
            return false;
        }
        // 判断是否为非固体
        if (!block.getMaterial().isSolid()) {
            return false;
        }
        return true;
    }

    /**
     * 每一次put进行检查
     * @return 返回true表示线程需要停止，false表示可以继续
     */
    public boolean beforePutCheck() {
        long timer = System.currentTimeMillis();
        while (cache.size() >= cacheSizeMAX - 10) {
            if (!getIsReady() || Thread.currentThread().isInterrupted()) {
//                Mod_Main.LOG.info("测试终止点");
                return true;
            }
            if (isHeartbeatTimeout()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                LOG.warn("等待时出现异常: {}", e.toString());
                Thread.currentThread().interrupt(); // 恢复中断状态
                return true;
            }
        }
        return false;
    }

    /**
     * 一定可以停止任务的条件
     * @return
     */
    public boolean checkShouldShutdown() {
        if (getRadius() > radiusLimit) { // 超出最大半径
//            LOG.info("[Founder]超出最大半径");
            return true;
        }
        if (!isRunning.get()) {
//            LOG.info("[Founder]运行标志结束");
            return true;
        }
        if (!getIsReady()) {
//            LOG.info("[Founder]玩家就绪状态为否");
            return true;
        }
        if (Thread.currentThread().isInterrupted()) { // 线程被中断
//            LOG.info("[Founder]线程被中断");
            return true;
        }
        if (isHeartbeatTimeout()) {
//            LOG.info("[Founder]线程心跳超时");
            return true;
        }

        // 特殊终止条件
        if (player.getHealth() <= 2) { // 玩家血量过低
            LOG.info("[Founder]血量过低");
            return true;
        }
        return false;
    }

    public boolean isHeartbeatTimeout() {
        return System.currentTimeMillis() - minerHeartbeat.get() > 3000;
    }

    public boolean getIsReady() {
        try {
            if (allPlayerStorage.playerStatueMap.get(player.getUniqueID()).getIsReady()) { // 玩家未就绪
                return true;
            }
        } catch (Exception e) {
            try {
                if (SelfStatue.modeManager.getIsReady()) {
                    return true;
                }
            } catch (Exception ee) {
                LOG.warn("获取就绪状态时出错: {}", ee.toString());
            }
        }
        return false;
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
