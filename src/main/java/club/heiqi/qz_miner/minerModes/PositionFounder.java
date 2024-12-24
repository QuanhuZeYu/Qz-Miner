package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.Mod_Main;
import club.heiqi.qz_miner.statueStorage.SelfStatue;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
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
    public Thread thread;
    public AtomicInteger radius = new AtomicInteger(0);
    public EntityPlayer player;
    public World world;
    public Vector3i center;
    public volatile TaskState taskState = TaskState.WAIT;
    public ReentrantReadWriteLock lock;

    public TaskState getTaskState() {
        lock.readLock().lock();
        try {
            return taskState;
        } finally {
            lock.readLock().unlock();
        }
    }
    public void setTaskState(TaskState taskState) {
        lock.writeLock().lock();
        try {
            this.taskState = taskState;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public volatile LinkedBlockingQueue<Vector3i> cache = new LinkedBlockingQueue<>(cacheSizeMAX);

    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     * @param lock
     */
    public PositionFounder(Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
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
            taskState = TaskState.STOP;
        }
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        // 该方法只会进入一次
        readConfig();
        while (getTaskState() != TaskState.STOP) {
            setTaskState(TaskState.RUNNING);
            runLoopTimer = System.currentTimeMillis();
            loopLogic();
            updateTaskState();
            try { // 默认休眠50ms - 1tick
                Thread.sleep(5);
            } catch (InterruptedException e) {
                LOG.error(e);
                Thread.currentThread().interrupt(); // 恢复中断状态
            }
        }
        setTaskState(TaskState.STOP);
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

    /**
     * 一定可以停止任务的条件
     * @return
     */
    public boolean checkShouldShutdown() {
        if (getRadius() > radiusLimit) { // 超出最大半径
            return true;
        }
        if (getTaskState() == TaskState.STOP) {
            return true;
        }
        if (!getIsReady()) {
            return true;
        }
        if (Thread.currentThread().isInterrupted()) { // 线程被中断
            return true;
        }

        // 特殊终止条件
        if (player.getHealth() <= 2) { // 玩家血量过低
            return true;
        }
        return false;
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

    /**
     * 每一次put进行检查
     * @return 返回true表示线程需要停止，false表示可以继续
     */
    public boolean beforePutCheck() {
        long timer = System.currentTimeMillis();
        while (cache.size() >= cacheSizeMAX - 10) {
            if (System.currentTimeMillis() - timer > 3000) { // 死等倒计时
                Mod_Main.LOG.info("出现死等现象，强行终止连锁任务!");
                if (world.isRemote) return true;
                MinecraftServer server = MinecraftServer.getServer();
                for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
                    player.addChatMessage(new ChatComponentText(this.player.getDisplayName() + "出现死等现象，强行终止" + Thread.currentThread().getName() + "连锁任务!"));
                }
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

    public List<Vector3i> sort(List<Vector3i> list) {
        // 根据到center的距离进行排序
        list.sort((o1, o2) -> {
            int d1 = (int) o1.distanceSquared(center);
            int d2 = (int) o2.distanceSquared(center);
            return d1 - d2;
        });
        return list;
    }

    public void printMessage(String message) {
        ChatComponentText text = new ChatComponentText(message);
        if (text == null) {
            ChatComponentText error = new ChatComponentText("[QZ_Miner] 错误：你不应该看到这段文本，如果看到该段请上报至该模组的github仓库issue，或者在GTNH中文一群报告此信息");
            player.addChatMessage(error);
            return;
        }
        player.addChatMessage(text);
    }
}
