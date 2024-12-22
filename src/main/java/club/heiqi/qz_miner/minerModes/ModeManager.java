package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.minerModes.chainMode.BaseChainMode;
import club.heiqi.qz_miner.minerModes.chainMode.ChainGroupMode;
import club.heiqi.qz_miner.minerModes.chainMode.LumberJackMode;
import club.heiqi.qz_miner.minerModes.chainMode.StrictChainMode;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder_Lumberjack;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainFounder_Strict;
import club.heiqi.qz_miner.minerModes.chainMode.posFounder.ChainGroup;
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
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 每个玩家都有独属于自身的管理类
 */
public class ModeManager {
    public ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public EntityPlayerMP playerMP;

    public MainMode mainMode = MainMode.CHAIN_MODE; // 默认为范围模式
    public RangeMode rangeMode = RangeMode.RECTANGULAR; // 默认为矩形模式
    public ChainMode chainMode = ChainMode.BASE_CHAIN_MODE; // 默认为矩形模式

    public volatile AtomicBoolean isReady = new AtomicBoolean(false);
    public volatile AtomicBoolean printResult = new AtomicBoolean(true);

    /**
     * 由方块破坏事件触发该方法，该方法调用模式类中的run方法，完成模式运行
     * @param world 挖掘方块所在的世界
     * @param center 方块所在的坐标
     */
    public void proxyMine(World world, Vector3i center, EntityPlayer player) {
        MainMode proxyMain = mainMode;
        switch (proxyMain) {
            case CHAIN_MODE -> {
                AbstractMode proxyMode = ChainMode.newAbstractMode(chainMode);
                if (proxyMode == null) player.addChatMessage(new ChatComponentText("[QZ_Miner] 错误：创建模式失败"));
                proxyMode.setup(world, (EntityPlayerMP) player, center);
                proxyMode.run();
            }
            case RANGE_MODE -> {
                AbstractMode proxyMode = RangeMode.newAbstractMode(rangeMode);
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

    /**
     * @return 根据当前模式返回对应的模式类
     */
    public AbstractMode getMode() {
        return switch (mainMode) {
            case CHAIN_MODE -> ChainMode.newAbstractMode(chainMode);
            case RANGE_MODE -> RangeMode.newAbstractMode(rangeMode);
        };
    }

    @Nullable
    public PositionFounder getPositionFounder(Vector3i pos, EntityPlayer player, ReentrantReadWriteLock lock) {
        return switch (mainMode) {
            case CHAIN_MODE -> ChainMode.createFounder(chainMode, pos, player, lock);
            case RANGE_MODE -> RangeMode.createFounder(rangeMode, pos, player, lock);
        };
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
        BASE_CHAIN_MODE("qz_miner.chainmode.base_chain", BaseChainMode.class, ChainFounder.class),
        STRICT("qz_miner.chainmode.strict", StrictChainMode.class, ChainFounder_Strict.class),
        CHAIN_GROUP("qz_miner.chainmode.chain_group", ChainGroupMode.class, ChainGroup.class),
        LUMBER_JACK("qz_miner.chainmode.lumberjack", LumberJackMode.class, ChainFounder_Lumberjack.class)
        ;
        public final String unLocalizedName;
        public final Class<? extends AbstractMode> mode;
        public final Class<? extends PositionFounder> positionFounder;
        public final List<Constructor> constructors = new ArrayList<>();

        ChainMode(String unLocalizedName, Class<? extends AbstractMode> mode, Class<? extends PositionFounder> positionFounder) {
            this.unLocalizedName = unLocalizedName;
            this.mode = mode;
            this.positionFounder = positionFounder;
            try {
                Constructor<? extends AbstractMode> constructor = mode.getDeclaredConstructor();
                Constructor<? extends PositionFounder> constructor1 = positionFounder.getDeclaredConstructor(Vector3i.class, EntityPlayer.class, ReentrantReadWriteLock.class);
                constructor.setAccessible(true); constructor1.setAccessible(true);
                constructors.add(constructor); constructors.add(constructor1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public static List<String> getModes() {
            List<String> list = new ArrayList<>();
            for (ChainMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
        public static AbstractMode newAbstractMode(ChainMode chainMode) {
            try {
                Constructor<? extends AbstractMode> constructor = chainMode.constructors.get(0);
                return constructor.newInstance();
            } catch (Exception e) {
                MY_LOG.LOG.error(e.toString());
                return null;
            }
        }
        public static PositionFounder createFounder(ChainMode chainMode,
                                                    Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
            try {
                Constructor<? extends PositionFounder> positionFounder = chainMode.constructors.get(1);
                // 获取匹配的构造函数（需要明确指定参数类型）
                return positionFounder.newInstance(center, player, lock);
            } catch (Exception e) {
                MY_LOG.LOG.error(e);
                return null;
            }
        }
    }
    // ========== 范围模式
    public enum RangeMode {
        RECTANGULAR("qz_miner.rangemode.rectangular", RectangularMode.class, Rectangular.class),
        RECTANGULAR_MINERAL("qz_miner.rangemode.rectangular_mineral", RectangularMineralMode.class, Rectangular.class),
        SPHERE("qz_miner.rangemode.sphere", SphereMode.class, Sphere.class),
        TUNNEL("qz_miner.rangemode.tunnel", TunnelMode.class, Tunnel.class),
        ;
        public final String unLocalizedName;
        public final Class<? extends AbstractMode> mode;
        public final Class<? extends PositionFounder> positionFounder;
        public final List<Constructor> constructors = new ArrayList<>();

        RangeMode(String unLocalizedName, Class<? extends AbstractMode> mode, Class<? extends PositionFounder> positionFounder) {
            this.unLocalizedName = unLocalizedName;
            this.mode = mode;
            this.positionFounder = positionFounder;
            try {
                Constructor<? extends AbstractMode> constructor = mode.getDeclaredConstructor();
                Constructor<? extends PositionFounder> constructor1 = positionFounder.getDeclaredConstructor(Vector3i.class, EntityPlayer.class, ReentrantReadWriteLock.class);
                constructor.setAccessible(true); constructor1.setAccessible(true);
                constructors.add(constructor); constructors.add(constructor1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public static List<String> getModes() {
            List<String> list = new ArrayList<>();
            for (RangeMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
        public static AbstractMode newAbstractMode(RangeMode rangeMode) {
            try {
                Constructor<? extends AbstractMode> mode = rangeMode.constructors.get(0);
                return mode.newInstance();
            } catch (Exception e) {
                MY_LOG.LOG.error(e.toString());
                throw new RuntimeException(e);
            }
        }
        public static PositionFounder createFounder(RangeMode rangeMode,
                                                    Vector3i center, EntityPlayer player, ReentrantReadWriteLock lock) {
            try {
                Constructor<? extends PositionFounder> positionFounder = rangeMode.constructors.get(1);
                // 获取匹配的构造函数（需要明确指定参数类型）
                return positionFounder.newInstance(center, player, lock);
            } catch (Exception e) {
                MY_LOG.LOG.error(e.toString());
                throw new RuntimeException(e);
            }
        }
    }
}
