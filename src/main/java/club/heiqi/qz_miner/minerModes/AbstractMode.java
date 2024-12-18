package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.breakBlock.BlockBreaker;
import club.heiqi.qz_miner.threadPool.QzMinerThreadPool;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.MY_LOG.logger;
import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

/**
 * 该类被创建后不会频繁创建销毁，资源会持续复用
 */
public abstract class AbstractMode {
    public static int timeout = 1000;
    public static int timeLimit = Config.taskTimeLimit;
    public static int perTickBlock = Config.perTickBlockLimit;

    public PositionFounder positionFounder; // 搜索器
    public BlockBreaker breaker;
    public AtomicBoolean isRunning = new AtomicBoolean(false);

    public long timer;
    public long getCacheFailTimeOutTimer;
    public int getCacheFailCount = 0;
    public int blockCount;
    public ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public TaskState taskState = TaskState.IDLE;
    public Block blockSample;
    public List<ItemStack> dropSample = new ArrayList<>(); // 掉落物样本数组

    public void updateTaskType() {
        if (checkShouldWait()) {
            taskState = TaskState.WAIT;
        }
        if (checkShouldShutdown()) {
            taskState = TaskState.STOP;
        }
    }

    public void setup(World world, EntityPlayerMP player, Vector3i center) {
        if (taskState == TaskState.STOP || taskState == TaskState.WAIT) {
            printMessage("qz_miner.message.pleasewait");
            return;
        }
        breaker = new BlockBreaker(player, world);

        ModeManager modeManager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        positionFounder = modeManager.getPositionFounder(center, player, lock);
    }

    public void run() {
        readConfig();
        register();
        QzMinerThreadPool.pool.submit(positionFounder);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        int perTickBlockCount = 0;
        timer = System.currentTimeMillis();
        updateTaskType();
        if (taskState == TaskState.WAIT || taskState == TaskState.IDLE) taskState = TaskState.RUNNING;
        // 每次循环只挖掘一个点
        while (taskState == TaskState.RUNNING) {
            try {
                Vector3i pos = positionFounder.cache.poll(5, TimeUnit.MILLISECONDS);
                if (pos != null && checkCanBreak(pos) && filter(pos)) {
                    breaker.tryHarvestBlock(pos);
                    blockCount++;
                    perTickBlockCount++;
                }
            } catch (InterruptedException e) {
                logger.warn("线程异常");
            }
            updateTaskType();
            checkToWait(perTickBlockCount);
        }
        updateTaskType();
        if (taskState == TaskState.STOP) {
            unregister();
        }
    }

    public void readConfig() {
        timeLimit = Config.taskTimeLimit;
        perTickBlock = Config.perTickBlockLimit;
        if (perTickBlock <= 0) {
            perTickBlock = Integer.MAX_VALUE;
        }
    }

    public void register() {
        isRunning.set(true);
        // 注册监听器
        FMLCommonHandler.instance().bus().register(this);
        logger.info("玩家: {} 的挖掘任务已启动，注册监听器", breaker.player.getDisplayName());
    }

    public void unregister() {
        isRunning.set(false);
        // 注销监听器
        logger.info("玩家: {} 的挖掘任务已结束，卸载监听器", breaker.player.getDisplayName());
        String text = "挖掘任务结束，共挖掘" + blockCount + "方块，" + positionFounder.getRadius() + "格半径";
        if (text != null && allPlayerStorage.playerStatueMap.get(breaker.player.getUniqueID()).getPrintResult()) {
            printMessage(text);
        }
        reset();
        FMLCommonHandler.instance().bus().unregister(this);
    }

    public void reset() {
        try {
            positionFounder.setTaskState(TaskState.STOP); // 通知结束
            positionFounder.thread.interrupt(); // 中断线程
        } catch (Exception e) {
            logger.warn("线程异常: {}", e.toString());
        }
        positionFounder = null;
        breaker = null;
        timer = 0;
        getCacheFailCount = 0;
        blockCount = 0;
        taskState = TaskState.IDLE;
        blockSample = null;
        dropSample.clear();
    }

    public boolean checkTimeout() {
        long time = System.currentTimeMillis() - timer;
        return time > timeLimit;
    }

    public <T> void checkToWait(T input) {
        int perTickBlockCount = (int) input;
        // 挖掘数量达到限制
        if (perTickBlockCount >= perTickBlock) {
            taskState = TaskState.WAIT;
        }
        if (checkShouldWait()) {
            taskState = TaskState.WAIT;
        }
    }

    public boolean checkShouldWait() {
        if (positionFounder.cache.isEmpty()) { // 如果缓存为空
            return true;
        }
        if (checkTimeout()) { // 超时
            return true;
        }
        long[] tickTimes = FMLCommonHandler.instance().getMinecraftServerInstance().tickTimeArray;
        long lastTickTime = tickTimes[tickTimes.length - 1] / 1000000;
        if (lastTickTime - timer > 50) { // 线程阻塞时间过长
            printMessage("qz_miner.message.tickTooLong");
            return true;
        }
        return false;
    }

    /**
     * 该方法指示了一定会达成停止的条件
     */
    public boolean checkShouldShutdown() {
        if (blockCount >= Config.blockLimit) { // 达到限制数量
            return true;
        }
        if (positionFounder.cache.isEmpty()
            && positionFounder.getTaskState() == TaskState.STOP) { // 缓存为空且任务结束
            return true;
        }
        if (breaker.player.getHealth() <= 2) { // 玩家生命值过低
            return true;
        }
        if (positionFounder.cache.isEmpty()) { // 获取缓存失败
            if (getCacheFailCount <= 1) {
                getCacheFailCount++;
                getCacheFailTimeOutTimer = System.currentTimeMillis();
            } else {
                return System.currentTimeMillis() - getCacheFailTimeOutTimer > timeout;
            }
        } else {
            getCacheFailCount = 0;
        }
        boolean isReady = allPlayerStorage.playerStatueMap.get(breaker.player.getUniqueID()).getIsReady(); // 玩家主动取消-存在一定延迟
        return !isReady;
    }

    public boolean checkCanBreak(Vector3i pos) {
        World world = breaker.world;
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        int meta = world.getBlockMetadata(pos.x, pos.y, pos.z);
        EntityPlayerMP player = breaker.player;
        ItemInWorldManager iwm = player.theItemInWorldManager;
        ItemStack holdItem = iwm.thisPlayerMP.getCurrentEquippedItem();
        // 判断是否为创造模式
        if (iwm.getGameType().isCreative()) {
            return true;
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
        // 判断工具能否挖掘
        if (holdItem != null) {
            return block.canHarvestBlock(player, meta);
        }
        return true;
    }

    public boolean filter(Vector3i pos) {
        return true;
    }

    public void printMessage(String message) {
        ChatComponentText text = new ChatComponentText(message);
        if (text == null) {
            ChatComponentText error = new ChatComponentText("[QZ_Miner] 错误：你不应该看到这段文本，如果看到该段请上报至该模组的github仓库issue，或者在GTNH中文一群报告此信息");
            breaker.player.addChatMessage(error);
            return;
        }
        breaker.player.addChatMessage(text);
    }
}
