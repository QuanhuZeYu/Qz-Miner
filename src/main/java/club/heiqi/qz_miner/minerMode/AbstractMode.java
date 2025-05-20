package club.heiqi.qz_miner.minerMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerMode.enums.Sides;
import club.heiqi.qz_miner.minerMode.breaker.BlockBreaker;
import club.heiqi.qz_miner.minerMode.rightClicker.RightClicker;
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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;
import scala.reflect.internal.util.WeakHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 一个模式下只允许运行一个触发模式 - 挖掘 - 渲染 - 交互 -
 */
public abstract class AbstractMode {
    /**可用于调试追踪启用了多少模式实例 - 不要使用此字段执行任何逻辑操作，可用于只读查看内容*/
    public static WeakHashSet<AbstractMode> TRACER = new WeakHashSet<>();

    public Logger LOG = LogManager.getLogger();
    public final Sides side;
    /**管理器引用*/
    public final ModeManager modeManager;
    /**提供挖掘点坐标的类 由实现类创建*/
    @Nullable
    public PositionFounder positionFounder;
    /**执行搜索器的线程*/
    @Nullable
    public Thread thread;
    /**用于管理搜索器线程是否终止的字段 - 心跳标记 - 时间戳*/
    public AtomicLong heartbeatTimer = new AtomicLong(System.currentTimeMillis());
    /**硬性字段控制搜索线程是否终止*/
    public boolean isShut = false;
    /**标记初始化是否成功，用于中断触发*/
    public boolean initSuccess = true;


    /**记录触发点*/
    public final Vector3i center;
    /**挖掘样本*/
    public final Block blockSample;
    public final TileEntity tileSample;
    public final int blockSampleMeta;

    /**执行器**/
    public final BlockBreaker breaker;
    public final RightClicker rightClicker;

    /**卸载前任务*/
    public List<Runnable> preUnregisterTasks = new ArrayList<>();

    /**
     * 会根据传入的Sides字段来分支执行实例化行为
     */
    public AbstractMode(ModeManager modeManager, Vector3i center, Sides sides) {
        this.modeManager = modeManager;
        this.center = center;
        this.side = sides;
        World world = modeManager.world;
        EntityPlayer player = modeManager.player;
        blockSample = world.getBlock(center.x, center.y, center.z);
        tileSample = world.getTileEntity(center.x, center.y, center.z);
        blockSampleMeta = world.getBlockMetadata(center.x, center.y, center.z);

        breaker = new BlockBreaker(player, world);
        rightClicker = new RightClicker(player, world);

        TRACER.add(this);
    }

    public void mineModeAutoSetup() {
        // 如果在实例化时出现异常 搜索器 可能会为空
        if (positionFounder == null || !initSuccess) return;
        thread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        thread.start();
    }

    public AtomicBoolean isRenderMode = new AtomicBoolean(false);
    @SideOnly(Side.CLIENT)
    public void renderModeAutoSetup() {
        if (positionFounder == null || !initSuccess) return;
        isRenderMode.set(true);
        thread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        thread.start();
    }

    public AtomicBoolean isInteractMode = new AtomicBoolean(false);
    public void interactModeAutoSetup() {
        if (positionFounder == null || !initSuccess) return;
        isInteractMode.set(true);
        thread = new Thread(positionFounder, this + " - 连锁搜索者线程");
        register();
        thread.start();
    }

    @SubscribeEvent
    public void logicTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START && side == Sides.SERVER) {
            sendHeartbeat();
            if (!checkHeartBeat()) {
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
            if (isInLag()) {
                return;
            }
            if (!isShut) mainLogic();
        }
    }

    @SubscribeEvent
    public void renderTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;
        sendHeartbeat();
        if (!checkHeartBeat()) {
            shutdown();
            return;
        }
        if (!modeManager.getIsReady()) {
            shutdown();
            return;
        }
        if (!isShut) mainLogic();
    }


    public int failCounter = 0;
    public long failTimer = 0;
    public long lastTime = System.currentTimeMillis();
    public int tickBreakCount = 0;
    public int allBreakCount = 0;
    /**默认实现主逻辑*/
    public void mainLogic() {
        lastTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - lastTime <= Config.taskTimeLimit && positionFounder != null) { // 任务运行将限制在配置的时间中
            Vector3i pos = positionFounder.cache.poll(); // 立即取出队列中头部元素，如果为空返回null
            /*此段 if 将会在结果持续为空时决定是否终止搜索*/
            if (pos == null) {
                if (failCounter == 0) failTimer = System.currentTimeMillis();
                if (System.currentTimeMillis() - failTimer >= Config.heartbeatTimeout) {
                    shutdown(); // 没有获取到点的时间超过最大等待限制终止任务
                }
                failCounter++;
                return;
            }
            failCounter = 0;
            if (checkCanBreak(pos)) {
                if (side == Sides.CLIENT && isRenderMode.get()) modeManager.renderCache.add(pos);
                else if (side == Sides.SERVER && isInteractMode.get()) {
                    rightClicker.rightClick(pos);
                    tickBreakCount++;
                    allBreakCount++;
                } else if (side == Sides.SERVER) {
                    breaker.tryHarvestBlock(pos);
                    tickBreakCount++;
                    allBreakCount++;
                }
                // 判断挖掘数量是否终止
                if (allBreakCount >= Config.blockLimit) {
                    shutdown();
                    return;
                }
                if (tickBreakCount >= Config.perTickBlockLimit) break;
            }
        }
        tickBreakCount = 0;
    }

    /**
     * 该方法由其 PositionFounder 进行更新
     */
    public void updateHeartbeat(long timestamp) {
        heartbeatTimer.set(timestamp);
    }

    public long sendTime = System.nanoTime();
    public void sendHeartbeat() {
        if (positionFounder == null) return;
        if (System.nanoTime() - sendTime <= 5_000_000) return; // 发送最小间隔 1_000_000ns = 1ms
        sendTime = System.nanoTime();
        positionFounder.updateHeartbeat(System.currentTimeMillis());
    }









    public void register() {
        // 注册监听器
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 关闭点搜寻器并且卸载本类的监听器
     */
    public void shutdown() {
        modeManager.isRunning.set(false);
        if (thread != null) {
            thread.interrupt(); // 终止线程
            thread = null;
        }
        unregister();
        positionFounder = null;
    }

    public void addPreUnregisterTask(Runnable task) {
        preUnregisterTasks.add(task);
    }

    public void unregister() {
        if (!preUnregisterTasks.isEmpty()) {
            for (Runnable runnable : preUnregisterTasks) {
                runnable.run();
            }
        }

        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        isShut = true;
        modeManager.curMode = null;
        modeManager.clientMode = null;
    }


    /**
     * 心跳超时返回false
     */
    public boolean checkHeartBeat() {
        long current = System.currentTimeMillis();
        return current - heartbeatTimer.get() < Config.heartbeatTimeout;
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

    public boolean checkCanBreak(Vector3i pos) {
        World world = modeManager.world;
        EntityPlayer player = modeManager.player;
        if (player instanceof EntityPlayerMP playerMP) {
            if (playerMP.playerNetServerHandler == null) return false;
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
        if (isUnder && Config.posFounderSaveUnder) {
            return false;
        }
        // 判断工具能否挖掘
        if (holdItem != null) {
            return block.canHarvestBlock(player, meta);
        }
        return true;
    }
}
