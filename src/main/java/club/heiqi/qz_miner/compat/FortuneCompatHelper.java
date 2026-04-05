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
     * 计算 GT 普通矿时运随机上界。
     *
     * @param currentBound 原逻辑计算出的随机上界
     * @param originalFortune 原始时运等级
     * @return 最终随机上界
     */
    public static int resolveGtOreFortuneRollBound(int currentBound, int originalFortune) {
        if (!Config.enableUnlimitedOreFortune || originalFortune <= 3) {
            return currentBound;
        }

        return originalFortune + 2;
    }

    /**
     * 计算 BW/GT++ 普通矿时运随机上界。
     *
     * @param currentBound 原逻辑计算出的随机上界
     * @param originalFortune 原始时运等级
     * @return 最终随机上界
     */
    public static int resolveCommonOreFortuneRollBound(int currentBound, int originalFortune) {
        if (!Config.enableUnlimitedOreFortune || originalFortune <= 3) {
            return currentBound;
        }

        return originalFortune;
    }
}
