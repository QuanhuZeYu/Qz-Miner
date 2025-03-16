package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.breaker.BlockBreaker;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抽象类，实际并没有执行任何功能，功能请在实现类中完成
 */
public abstract class AbstractMode {
    public Logger LOG = LogManager.getLogger();
    public static int heartbeatTimeout = Config.heartbeatTimeout;
    public static int taskTimeLimit = Config.taskTimeLimit;
    public static int perTickBlock = Config.perTickBlockLimit;
    public static int blockLimit = Config.blockLimit;

    public final ModeManager modeManager;
    /**提供挖掘点坐标的类*/
    public PositionFounder positionFounder;
    @Nullable
    public Thread thread;
    public AtomicLong heartbeatTimer = new AtomicLong(System.currentTimeMillis());

    public final Vector3i center;
    /**挖掘样本*/
    public final Block blockSample;
    public final TileEntity tileSample;
    public final int blockSampleMeta;

    public AbstractMode(ModeManager modeManager, Vector3i center) {
        EntityPlayer player = modeManager.player;
        this.modeManager = modeManager;
        World world = modeManager.world;
        this.center = center;
        blockSample = world.getBlock(center.x, center.y, center.z);
        tileSample = world.getTileEntity(center.x, center.y, center.z);
        blockSampleMeta = world.getBlockMetadata(center.x, center.y, center.z);
        readConfig();
    }

    public void mineModeAutoSetup() {
        thread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        thread.start();
    }

    public AtomicBoolean isRenderMode = new AtomicBoolean(false);
    @SideOnly(Side.CLIENT)
    public void renderModeAutoSetup() {
        isRenderMode.set(true);
        thread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        thread.start();
    }

    @SubscribeEvent
    @SideOnly(Side.SERVER)
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START && !modeManager.world.isRemote) {
            sendHeartbeat();
            long current = System.currentTimeMillis();
            long heart = heartbeatTimer.get();
            if (current - heart >= heartbeatTimeout) {
                shutdown();
                return;
            }
            if (!modeManager.getIsReady()) {
                shutdown();
                return;
            }
            if (!modeManager.isRunning.get()) {
                shutdown();
                return;
            }
            mainLogic();
        }
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void clientTick(TickEvent.ClientTickEvent event) {
        sendHeartbeat();
        long current = System.currentTimeMillis();
        long heart = heartbeatTimer.get();
        if (current - heart >= heartbeatTimeout) {
            /*LOG.info("[渲染] 心跳超时");*/
            shutdown();
            return;
        }
        if (!modeManager.getIsReady()) {
            /*LOG.info("[渲染] 未准备");*/
            shutdown();
            return;
        }
        mainLogic();
    }

    public abstract void mainLogic();








    /**
     * 该方法由其 PositionFounder 进行更新
     * @param timestamp
     */
    public void updateHeartbeat(long timestamp) {
        heartbeatTimer.set(timestamp);
    }

    public void sendHeartbeat() {
        if (positionFounder == null) return;
        positionFounder.updateHeartbeat(System.currentTimeMillis());
    }

    public boolean checkCanBreak(Vector3i pos) {
        World world = modeManager.world;
        EntityPlayer player = modeManager.player;
        int vx = pos.x; int vy = pos.y; int vz = pos.z;
        int px = (int) Math.floor(player.posX); int py = (int) Math.floor(player.posY); int pz = (int) Math.floor(player.posZ);
        Block block = world.getBlock(vx,vy,vz);
        ItemStack holdItem = player.getCurrentEquippedItem();
        int meta = world.getBlockMetadata(vx,vy,vz);
        // 判断是否为创造模式
        if (player.capabilities.isCreativeMode) {
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
        boolean b = vx == px && vy == (py-1) && vz == pz;
        /*LOG.info("[挖掘检查] 脚下:({}, {}, {}) - ({}, {}, {}) - 结果:{}",
            px,py-1,pz,vx,vy,vz, b);*/
        if (b) {
            /*LOG.info("已排除点:({}, {}, {})",vx,vy,vz);*/
            return false;
        }
        // 判断工具能否挖掘
        if (holdItem != null) {
            return block.canHarvestBlock(player, meta);
        }
        return true;
    }







    public void register() {
        // 注册监听器
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("玩家: {} 的挖掘任务已启动，注册监听器", modeManager.player.getDisplayName());
    }

    /**
     * 关闭点搜寻器并且卸载任务
     */
    public void shutdown() {
        modeManager.isRunning.set(false);
        if (thread != null) {
            thread.interrupt(); // 终止线程
        }
        positionFounder = null;
        unregister();
    }

    /**
     * 请使用 shutdownPositionFounder 方法终止任务
     */
    public void unregister() {
        // 注销监听器
        LOG.info("玩家: {} 的挖掘任务已结束，卸载监听器", modeManager.player.getDisplayName());
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
    }










    public void readConfig() {
        heartbeatTimeout = Config.heartbeatTimeout;
        perTickBlock = Config.perTickBlockLimit;
        blockLimit = Config.blockLimit;
        if (perTickBlock <= 0) {
            perTickBlock = Integer.MAX_VALUE;
        }
    }

    public boolean isInLag() {
        MinecraftServer server = MinecraftServer.getServer();
        int tickCounter = server.getTickCounter();
        long[] ticks = server.tickTimeArray;
        double[] tickMillis = new double[ticks.length];
        for (int i = 0; i < ticks.length; i++) {
            tickMillis[i] = ((float) ticks[i] / 1000000);
        }
        double average = Arrays.stream(tickMillis).filter(x -> x > 0).average().orElse(0);
        double lastTick = tickMillis[tickCounter % ticks.length];
        return average > 50 || lastTick > 50;
    }
}
