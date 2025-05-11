package club.heiqi.qz_miner.minerMode.enums;

import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.ModeManager;
import club.heiqi.qz_miner.minerMode.chainMode.BaseChainMode;
import club.heiqi.qz_miner.minerMode.chainMode.GroupMode;
import club.heiqi.qz_miner.minerMode.chainMode.LumberJackMode;
import club.heiqi.qz_miner.minerMode.chainMode.StrictChainMode;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

// ========== 连锁模式
public enum ChainMode {
    BASE_CHAIN_MODE("qz_miner.chainmode.base_chain"),
    STRICT("qz_miner.chainmode.strict"),
    LUMBER_JACK("qz_miner.chainmode.lumberjack"),
    GROUP("qz_miner.chainmode.group");
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

    public AbstractMode newAbstractMode(ModeManager manager, Vector3i center, Sides sides) {
        switch (this) {
            case BASE_CHAIN_MODE -> {
                return new BaseChainMode(manager, center, sides);
            }
            case STRICT -> {
                return new StrictChainMode(manager, center, sides);
            }
            case LUMBER_JACK -> {
                return new LumberJackMode(manager, center, sides);
            }
            case GROUP -> {
                return new GroupMode(manager, center, sides);
            }
            default -> {
                return new BaseChainMode(manager, center, sides);
            }
        }
    }
}
