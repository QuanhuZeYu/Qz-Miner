package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.minerModes.chainMode.BaseChainMode;
import club.heiqi.qz_miner.minerModes.chainMode.LumberJackMode;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder_Lumberjack;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMineralMode;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMode;
import club.heiqi.qz_miner.minerModes.rangeMode.SphereMode;
import club.heiqi.qz_miner.minerModes.rangeMode.TunnelMode;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.Rectangular;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.Sphere;
import club.heiqi.qz_miner.minerModes.rangeMode.posFounder.Tunnel;
import club.heiqi.qz_miner.network.PacketChainMode;
import club.heiqi.qz_miner.network.PacketMainMode;
import club.heiqi.qz_miner.network.PacketRangeMode;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 每个玩家都有独属于自身的管理类
 */
public class ModeManager {

    public MainMode mainMode = MainMode.CHAIN_MODE; // 默认为范围模式
    public RangeMode rangeMode = RangeMode.RECTANGULAR; // 默认为矩形模式
    public ChainMode chainMode = ChainMode.BASE_CHAIN_MODE; // 默认为矩形模式
    public AtomicBoolean isReady = new AtomicBoolean(false);

    // 实例成员列表顺序和枚举顺序需要一致，添加时务必小心
    public List<AbstractMode> chainModes = new ArrayList<>(Arrays.asList(
        new BaseChainMode(),
        new LumberJackMode()
    ));
    public List<AbstractMode> rangeModes = new ArrayList<>(Arrays.asList(
        new RectangularMode(),
        new RectangularMineralMode(),
        new SphereMode(),
        new TunnelMode()
    ));
    public Map<AbstractMode, PosFounder> posFounderMap = new HashMap<>() {{
        int i = 0;
        for (AbstractMode mode : chainModes) {
            put(mode, PosFounder.values()[i++]);
        }
        for (AbstractMode mode : rangeModes) {
            put(mode, PosFounder.values()[i++]);
        }
    }};

    /**
     * 由方块破坏事件触发该方法，该方法调用模式类中的run方法，完成模式运行
     * @param world 挖掘方块所在的世界
     * @param center 方块所在的坐标
     */
    public void proxyMine(World world, Vector3i center, EntityPlayer player) {
        MainMode proxyMain = mainMode;
        switch (proxyMain) {
            case CHAIN_MODE -> {
                AbstractMode proxyMode = ChainMode.getSubMode(chainMode, chainModes);
                proxyMode.setup(world, (EntityPlayerMP) player, center);
                proxyMode.run();
            }
            case RANGE_MODE -> {
                AbstractMode proxyMode = RangeMode.getSubMode(rangeMode, rangeModes);
                proxyMode.setup(world, (EntityPlayerMP) player, center);
                proxyMode.run();
            }
        }
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

    public AbstractMode getMode() {
        return switch (mainMode) {
            case CHAIN_MODE -> ChainMode.getSubMode(chainMode, chainModes);
            case RANGE_MODE -> RangeMode.getSubMode(rangeMode, rangeModes);
        };
    }

    public PositionFounder getPositionFounder(Vector3i pos, EntityPlayer player, ReentrantReadWriteLock lock) {
        PosFounder posEnum = posFounderMap.get(getMode());
        return PosFounder.createFounder(posEnum, pos, player, lock);
    }

    public synchronized void setIsReady(boolean isReady) {
        this.isReady.set(isReady);
    }

    public synchronized boolean getIsReady() {
        return isReady.get();
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
        public static List<AbstractMode> getSubModes(MainMode mainMode, List<AbstractMode> chainMode, List<AbstractMode> rangeMode) {
            return switch (mainMode) {
                case CHAIN_MODE -> chainMode;
                case RANGE_MODE -> rangeMode;
            };
        }
    }

    public enum ChainMode {
        BASE_CHAIN_MODE("qz_miner.chainmode.base_chain"),
        LUMBER_JACK("qz_miner.chainmode.lumberjack")
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
        public static AbstractMode getSubMode(ChainMode chainMode, List<AbstractMode> chainModes) {
            return chainModes.get(chainMode.ordinal());
        }
    }

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
        public static AbstractMode getSubMode(RangeMode rangeMode, List<AbstractMode> rangeModes) {
            return rangeModes.get(rangeMode.ordinal());
        }
    }

    public enum PosFounder {
        CHAIN_FOUNDER(),
        CHAIN_FOUNDER_LUMBER_JACK(),

        RECTANGULAR(),
        RECTANGULAR_MINERAL(),
        SPHERE(),
        TUNNEL();
        public static PositionFounder createFounder(PosFounder enumFounder, Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
            switch (enumFounder) {
                case CHAIN_FOUNDER -> {
                    return new ChainFounder(center, player, lock);
                }
                case CHAIN_FOUNDER_LUMBER_JACK -> {
                    return new ChainFounder_Lumberjack(center, player, lock);
                }
                case RECTANGULAR, RECTANGULAR_MINERAL -> {
                    return new Rectangular(center, player, lock);
                }
                case SPHERE -> {
                    return new Sphere(center, player, lock);
                }
                case TUNNEL -> {
                    return new Tunnel(center, player, lock);
                }
                default -> throw new IllegalStateException("Unexpected value: " + enumFounder);
            }
        }
    }
}
