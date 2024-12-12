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

import static club.heiqi.qz_miner.MY_LOG.logger;
import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

/**
 * 继承后在构造函数中需要修改未本地化名称字段
 */
public abstract class AbstractMode {
    public static int timeout = 1000;
    public static int timeLimit = Config.taskTimeLimit;

    public PositionFounder positionFounder; // 搜索器
    public BlockBreaker breaker;
    public AtomicBoolean isRunning = new AtomicBoolean(false);

    public long timer;
    public long getCacheFailTimeOutTimer;
    public int getCacheFailCount = 0;
    public int blockCount;
    public Block blockSample;
    public List<ItemStack> dropSample = new ArrayList<>(); // 掉落物样本数组

    public abstract void setup(World world, EntityPlayerMP player, Vector3i center);

    public void run() {
        readConfig();
        register();
        QzMinerThreadPool.pool.submit(positionFounder);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        /*logger.info("当前线程池运行线程数: {}, 当前等待数: {}, 缓存大小: {}, stop: {}",
            QzMinerThreadPool.pool.getActiveCount(), QzMinerThreadPool.pool.getQueue().size(), positionFounder.cache.size(), positionFounder.getStop());*/
        timer = System.currentTimeMillis();
        while (!checkTimeout() && !positionFounder.cache.isEmpty() && !checkShouldShutdown()) {
            try {
                Vector3i pos = positionFounder.cache.poll(5, TimeUnit.MILLISECONDS);
                if (pos != null && checkCanBreak(pos) && filter(pos) && !checkShouldShutdown()) {
                    breaker.tryHarvestBlock(pos);
                    blockCount++;
                    if (checkShouldShutdown()) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("线程异常");
            }
        }
        if (checkShouldShutdown()) {
            unregister();
        }
    }

    public void readConfig() {
        Config.sync(new File(Config.configFile));
        timeLimit = Config.taskTimeLimit;
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
        logger.info(text);
        printMessage(text);
        reset();
        FMLCommonHandler.instance().bus().unregister(this);
    }

    public void reset() {
        positionFounder.setStop(true);
        positionFounder = null;
        breaker = null;
        timer = 0;
        blockCount = 0;
        blockSample = null;
        dropSample.clear();
    }

    public boolean checkTimeout() {
        long time = System.currentTimeMillis() - timer;
        return time > timeLimit;
    }

    /**
     * 检查并执行清理工作
     */
    public boolean checkShouldShutdown() {
        if (blockCount >= Config.blockLimit) { // 达到限制数量
            return true;
        }
        if (positionFounder.cache.isEmpty()
            && positionFounder.getStop()) { // 缓存为空且任务结束
            return true;
        }
        if (breaker.player.isDead || breaker.player.getHealth() <= 1) {
            return true;
        }
        if (positionFounder.cache.isEmpty()) {
            if (getCacheFailCount <= 1) {
                getCacheFailCount++;
                getCacheFailTimeOutTimer = System.currentTimeMillis();
            } else {
                return System.currentTimeMillis() - getCacheFailTimeOutTimer > timeout;
            }
        } else {
            getCacheFailCount = 0;
        }
        boolean isReady = allPlayerStorage.playerStatueMap.get(breaker.player.getUniqueID()).getIsReady();
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
        breaker.player.addChatMessage(text);
    }
}
