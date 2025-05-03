package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;

import java.lang.reflect.Field;
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
    @Nullable
    public PositionFounder positionFounder;
    @Nullable
    public Thread thread,renderThread;
    public AtomicLong heartbeatTimer = new AtomicLong(System.currentTimeMillis());
    public boolean isShut = false;

    public final Vector3i center;
    /**挖掘样本*/
    public final Block blockSample;
    public final TileEntity tileSample;
    public final int blockSampleMeta;

    public AbstractMode(ModeManager modeManager, Vector3i center) {
        this.modeManager = modeManager;
        this.center = center;
        World world = modeManager.world;
        blockSample = world.getBlock(center.x, center.y, center.z);
        tileSample = world.getTileEntity(center.x, center.y, center.z);
        blockSampleMeta = world.getBlockMetadata(center.x, center.y, center.z);
        readConfig();
    }

    public void mineModeAutoSetup() {
        if (positionFounder == null) return;
        thread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        thread.start();
    }

    public AtomicBoolean isRenderMode = new AtomicBoolean(false);
    @SideOnly(Side.CLIENT)
    public void renderModeAutoSetup() {
        if (positionFounder == null) return;
        isRenderMode.set(true);
        renderThread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        renderThread.start();
    }

    public AtomicBoolean isInteractMode = new AtomicBoolean(false);
    public void interactModeAutoSetup() {
        if (positionFounder == null) return;
        isInteractMode.set(true);
        thread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        thread.start();
    }

    @SubscribeEvent
    @SideOnly(Side.SERVER)
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            sendHeartbeat();
            long current = System.currentTimeMillis();
            long heart = heartbeatTimer.get();
            if (current - heart >= heartbeatTimeout) {
                LOG.info("心跳超时结束");
                shutdown();
                return;
            }
            if (!modeManager.getIsReady()) {
                LOG.info("连锁按键已松开");
                shutdown();
                return;
            }
            if (!modeManager.isRunning.get()) {
                LOG.info("已停止运行 - 结束");
                shutdown();
                return;
            }
            if (!isShut) mainLogic();
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
        // 如果卡顿就不执行mainLogic
        if (isInLag()) {
            return;
        }
        if (!isShut) mainLogic();
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

    Field playerInstances;
    public boolean checkCanBreak(Vector3i pos) {
        World world = modeManager.world;
        EntityPlayer player = modeManager.player;
        if (player instanceof EntityPlayerMP playerMP) {
            if (playerMP.playerNetServerHandler == null) return false;
        }
        if (world instanceof WorldServer worldServer) {
            PlayerManager playerManager = worldServer.getPlayerManager();
            LongHashMap instances = playerManager.playerInstances;
            if (instances == null) {
                LOG.error("playerInstances 为 null 异常");
                return false;
            }
        }
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
        // 排除脚下方块
        boolean isUnder = vx == px && vy == (py-1) && vz == pz;
        if (isUnder) {
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
        EntityPlayer player = modeManager.player;
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 关闭点搜寻器并且卸载任务
     */
    public void shutdown() {
        modeManager.isRunning.set(false);
        if (thread != null) {
            thread.interrupt(); // 终止线程
            thread = null;
        }
        if (renderThread != null) {
            renderThread.interrupt();
            renderThread = null;
        }
        positionFounder = null;
        unregister();
    }

    public void unregister() {
        EntityPlayer player = modeManager.player;
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        isShut = true;
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
        World world = modeManager.world;
        if (world.isRemote) return false;
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
