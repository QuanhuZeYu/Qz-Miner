package club.heiqi.qz_miner.compat;

import club.heiqi.qz_miner.Config;

/**
 * 矿石时运兼容逻辑工具。
 */
public final class FortuneCompatHelper {

    private FortuneCompatHelper() {}

    /**
     * 根据配置判断当前矿石是否应按自然矿处理。
     *
     * @param natural 原始自然矿标记
     * @return 是否允许参与自然矿限定逻辑
     */
    public static boolean shouldTreatOreAsNatural(boolean natural) {
        return natural || Config.enableFortuneForPlacedOre;
    }

    /**
     * 根据配置决定是否保留原版的时运 3 级上限判断。
     *
     * @param original 原版判断结果
     * @return 最终是否保留原版判断
     */
    public static boolean shouldKeepFortuneCapCheck(boolean original) {
        return original && !Config.enableUnlimitedOreFortune;
    }
}
