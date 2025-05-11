package club.heiqi.qz_miner.minerMode.enums;

import java.util.List;

// 主模式枚举类，该枚举中包含所有模式
public enum MainMode {
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
