package club.heiqi.qz_miner.minerMode.enums;

import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.ModeManager;
import club.heiqi.qz_miner.minerMode.rangeMode.RectangularMineralMode;
import club.heiqi.qz_miner.minerMode.rangeMode.RectangularMode;
import club.heiqi.qz_miner.minerMode.rangeMode.TunnelMode;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

// ========== 范围模式
public enum RangeMode {
    RECTANGULAR("qz_miner.rangemode.rectangular"),
    RECTANGULAR_MINERAL("qz_miner.rangemode.rectangular_mineral"),
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

    public AbstractMode newAbstractMode(ModeManager manager, Vector3i center, Sides sides) {
        switch (this) {
            case RECTANGULAR -> {
                return new RectangularMode(manager, center, sides);
            }
            case RECTANGULAR_MINERAL -> {
                return new RectangularMineralMode(manager, center, sides);
            }
            case TUNNEL -> {
                return new TunnelMode(manager, center, sides);
            }
            default -> {
                return new RectangularMode(manager, center, sides);
            }
        }
    }
}
