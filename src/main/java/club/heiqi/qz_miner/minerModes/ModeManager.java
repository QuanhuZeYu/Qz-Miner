package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.minerModes.chainMode.*;
import club.heiqi.qz_miner.minerModes.chainMode.RelaxChainMode;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMineralMode;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMode;
import club.heiqi.qz_miner.minerModes.rangeMode.SphereMode;
import club.heiqi.qz_miner.minerModes.rangeMode.TunnelMode;
import club.heiqi.qz_miner.network.PacketChainMode;
import club.heiqi.qz_miner.network.PacketMainMode;
import club.heiqi.qz_miner.network.PacketRangeMode;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import club.heiqi.qz_miner.util.MessageUtil;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 每个玩家都有独属于自身的管理类
 */
public class ModeManager {
    public Logger LOG = LogManager.getLogger();
    /**此锁用于连锁 - 触发连锁时提交锁防止多次触发竞态*/
    public ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /**缓存的玩家引用*/
    public EntityPlayer player;
    public World world;

    /**模式枚举 - 通过网络发包修改值*/
    public MainMode mainMode = MainMode.CHAIN_MODE; // 默认为范围模式
    public RangeMode rangeMode = RangeMode.RECTANGULAR; // 默认为矩形模式
    public ChainMode chainMode = ChainMode.BASE_CHAIN_MODE; // 默认为矩形模式

    /**当前模式*/
    public AbstractMode curMode;

    public volatile AtomicBoolean isRunning = new AtomicBoolean(false);
    public volatile AtomicBoolean isReady = new AtomicBoolean(false);
    public volatile AtomicBoolean printResult = new AtomicBoolean(true);

    /**
     * 由方块破坏事件触发该方法，该方法调用模式类中的run方法，完成模式运行
     * @param world 挖掘方块所在的世界
     * @param center 方块所在的坐标
     */
    public void proxyMine(World world, Vector3i center, EntityPlayer player) {
        this.world = world;
        switch (mainMode) {
            case CHAIN_MODE -> {
                curMode = chainMode.newAbstractMode(this, center);
                curMode.autoSetup();
            }
            case RANGE_MODE -> {
                curMode = rangeMode.newAbstractMode(this, center);
                curMode.autoSetup();
            }
        }
        isRunning.set(true);
    }

    public void nextMainMode() {
        mainMode = MainMode.values()[(mainMode.ordinal() + 1) % MainMode.values().length];
        QzMinerNetWork.sendMessageToServer(new PacketMainMode(mainMode));
    }

    public void nextSubMode() {
        switch (mainMode) {
            case CHAIN_MODE -> {
                chainMode = ChainMode.values()[(chainMode.ordinal() + 1) % ChainMode.values().length];
                QzMinerNetWork.sendMessageToServer(new PacketChainMode(chainMode));
            }
            case RANGE_MODE -> {
                rangeMode = RangeMode.values()[(rangeMode.ordinal() + 1) % RangeMode.values().length];
                QzMinerNetWork.sendMessageToServer(new PacketRangeMode(rangeMode));
            }
        }
    }

    public void setIsReady(boolean isReady) {
        this.isReady.set(isReady);
    }

    public boolean getIsReady() {
        return isReady.get();
    }

    public void setPrintResult(boolean printResult) {
        this.printResult.set(printResult);
    }

    public boolean getPrintResult() {
        return printResult.get();
    }



    // 主模式枚举类，该枚举中包含所有模式
    public enum MainMode{
        CHAIN_MODE("qz_miner.chain_mode", ChainMode.getModes()),
        RANGE_MODE("qz_miner.range_mode", RangeMode.getModes()),
        ;
        public final String unLocalizedName;
        public final List<String> modes;

        MainMode(String unLocalizedName, List<String> modes) {
            this.unLocalizedName = unLocalizedName;
            this.modes = modes;
        }
    }
    // ========== 连锁模式
    public enum ChainMode {
        BASE_CHAIN_MODE("qz_miner.chainmode.base_chain"),
        STRICT("qz_miner.chainmode.strict"),
        CHAIN_GROUP("qz_miner.chainmode.chain_group"),
        LUMBER_JACK("qz_miner.chainmode.lumberjack"),
        RELAX("qz_miner.chainmode.relax"),
        ;
        public final String unLocalizedName;

        ChainMode(String unLocalizedName) {
            this.unLocalizedName = unLocalizedName;
        }
        public static List<String> getModes() {
            List<String> list = new ArrayList<>();
            for (ChainMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
        public AbstractMode newAbstractMode(ModeManager manager, Vector3i center) {
            switch (this) {
                case BASE_CHAIN_MODE -> {
                    return new BaseChainMode(manager, center);
                }
                case STRICT -> {
                    return new StrictChainMode(manager, center);
                }
                case CHAIN_GROUP -> {
                    return new ChainGroupMode(manager, center);
                }
                case LUMBER_JACK -> {
                    return new LumberJackMode(manager, center);
                }
                case RELAX -> {
                    return new RelaxChainMode(manager, center);
                }
                default -> {
                    return new BaseChainMode(manager, center);
                }
            }
        }
    }
    // ========== 范围模式
    public enum RangeMode {
        RECTANGULAR("qz_miner.rangemode.rectangular"),
        RECTANGULAR_MINERAL("qz_miner.rangemode.rectangular_mineral"),
        SPHERE("qz_miner.rangemode.sphere"),
        TUNNEL("qz_miner.rangemode.tunnel"),
        ;
        public final String unLocalizedName;

        RangeMode(String unLocalizedName) {
            this.unLocalizedName = unLocalizedName;
        }
        public static List<String> getModes() {
            List<String> list = new ArrayList<>();
            for (RangeMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
        public AbstractMode newAbstractMode(ModeManager manager, Vector3i center) {
            switch (this) {
                case RECTANGULAR -> {
                    return new RectangularMode(manager, center);
                }
                case RECTANGULAR_MINERAL ->  {
                    return new RectangularMineralMode(manager, center);
                }
                case SPHERE -> {
                    return new SphereMode(manager, center);
                }
                case TUNNEL -> {
                    return new TunnelMode(manager, center);
                }
                default -> {
                    return new RectangularMode(manager, center);
                }
            }
        }
    }



    // ==================== 事件订阅 - 监听玩家自身即可，简化触发流程
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }
    public void unregister() {
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
    }
    @SubscribeEvent
    public void blockBreakEvent(BlockEvent.BreakEvent event) {
        if (isRunning.get()) return;
        LOG.info("玩家:{} 触发了挖掘事件", event.getPlayer().getDisplayName());
        World world = event.world;
        EntityPlayer player = event.getPlayer();
        if (player.getUniqueID() != this.player.getUniqueID()) return;
        if (player instanceof FakePlayer) {
            return;
        }
        try {
            if (!isReady.get()) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        // 获取破坏方块的坐标
        Vector3i breakBlockPos = new Vector3i(event.x, event.y, event.z);
        try {
            proxyMine(world, breakBlockPos, player);
        } catch (Exception e) {
            LOG.info("代理挖掘时发生错误![An error occurred while proxy mining]");
            LOG.info(e);
        }
    }
}
