package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.minerModes.chainMode.BaseChainMode;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMineralMode;
import club.heiqi.qz_miner.minerModes.rangeMode.RectangularMode;
import club.heiqi.qz_miner.minerModes.rangeMode.SphereMode;
import club.heiqi.qz_miner.minerModes.rangeMode.TunnelMode;
import club.heiqi.qz_miner.network.PacketChainMode;
import club.heiqi.qz_miner.network.PacketMainMode;
import club.heiqi.qz_miner.network.PacketRangeMode;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每个玩家都有独属于自身的管理类
 */
public class ModeManager {

    public MainMode mainMode = MainMode.CHAIN_MODE; // 默认为范围模式
    public RangeMode rangeMode = RangeMode.RECTANGULAR; // 默认为矩形模式
    public ChainMode chainMode = ChainMode.BASE_CHAIN_MODE; // 默认为矩形模式
    public AtomicBoolean isReady = new AtomicBoolean(false);

    /**
     * 由方块破坏事件触发该方法，该方法调用模式类中的run方法，完成模式运行
     * @param world 挖掘方块所在的世界
     * @param center 方块所在的坐标
     */
    public void proxyMine(World world, Vector3i center, EntityPlayer player) {
        MainMode proxyMain = mainMode;
        switch (proxyMain) {
            case CHAIN_MODE -> {
                AbstractMode proxyMode = chainMode.mode;
                proxyMode.setup(world, (EntityPlayerMP) player, center);
                proxyMode.run();
            }
            case RANGE_MODE -> {
                AbstractMode proxyMode = rangeMode.mode;
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

    public synchronized void setIsReady(boolean isReady) {
        this.isReady.set(isReady);
    }

    public synchronized boolean getIsReady() {
        return isReady.get();
    }

    // 主模式枚举类，该枚举中包含所有模式
    public enum MainMode implements GetUnLocalized{
        CHAIN_MODE("qz_miner.chain_mode", ChainMode.getModes()),
        RANGE_MODE("qz_miner.range_mode", RangeMode.getModes()),
        ;
        public final String unLocalizedName;
        public final Map<String, AbstractMode> modes;

        MainMode(String unLocalizedName, Map<String, AbstractMode> modes) {
            this.unLocalizedName = unLocalizedName;
            this.modes = modes;
        }
        public static List<String> getUnLocalizedNames() {
            List<String> list = new ArrayList<>();
            for (MainMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
    }



    public enum ChainMode implements GetUnLocalized{
        BASE_CHAIN_MODE("qz_miner.chainmode.base_chain", new BaseChainMode()),
        ;
        public final String unLocalizedName;
        public final AbstractMode mode;

        ChainMode(String unLocalizedName, AbstractMode mode) {
            this.unLocalizedName = unLocalizedName;
            this.mode = mode;
        }
        public static Map<String, AbstractMode> getModes() {
            Map<String, AbstractMode> map = new HashMap<>();
            for (ChainMode mode : values()) {
                map.put(mode.unLocalizedName, mode.mode);
            }
            return map;
        }
        public static List<String> getUnLocalizedNames() {
            List<String> list = new ArrayList<>();
            for (ChainMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
    }

    public enum RangeMode implements GetUnLocalized{
        RECTANGULAR("qz_miner.rangemode.rectangular", new RectangularMode()),
        RECTANGULAR_MINERAL("qz_miner.rangemode.rectangular_mineral", new RectangularMineralMode()),
        SPHERE("qz_miner.rangemode.sphere", new SphereMode()),
        TUNNEL("qz_miner.rangemode.tunnel", new TunnelMode()),
        ;
        public final String unLocalizedName;
        public final AbstractMode mode;

        RangeMode(String unLocalizedName, AbstractMode mode) {
            this.unLocalizedName = unLocalizedName;
            this.mode = mode;
        }
        public static Map<String, AbstractMode> getModes() {
            Map<String, AbstractMode> map = new HashMap<>();
            for (RangeMode mode : values()) {
                map.put(mode.unLocalizedName, mode.mode);
            }
            return map;
        }
        public static List<String> getUnLocalizedNames() {
            List<String> list = new ArrayList<>();
            for (RangeMode mode : values()) {
                list.add(mode.unLocalizedName);
            }
            return list;
        }
    }
}
