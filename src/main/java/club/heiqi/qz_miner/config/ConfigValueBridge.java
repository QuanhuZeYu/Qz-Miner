package club.heiqi.qz_miner.config;

import club.heiqi.config.runtime.Authority;
import club.heiqi.qz_miner.Config;

/**
 * 从 UILib {@link Authority} 全量回灌 {@link Config} 静态字段。
 *
 * <p>NUMBER → int 使用 {@link Math#round(double)}，避免 2.999→2 浮点截断；
 * 预览 alpha 与衰减半径约束与历史 Forge load 语义对齐。</p>
 *
 * <p>本类 server-safe，零 MC UI / LWJGL 依赖。</p>
 */
public final class ConfigValueBridge {

    private ConfigValueBridge() {
    }

    /**
     * 从权威快照回灌全部静态配置字段。
     *
     * @param authority 配置权威源，不可为 null
     */
    public static void applyFromAuthority(Authority authority) {
        if (authority == null) {
            throw new IllegalArgumentException("authority must not be null");
        }

        Config.greeting = nullToEmpty(authority.getString("general.greeting"));
        Config.chainRadius = toInt(authority.getNumber("general.chainRadius"), 1);
        Config.chainMaxBlocks = toInt(authority.getNumber("general.chainMaxBlocks"), 1);
        Config.chainLoggingShellLayers = toInt(authority.getNumber("general.chainLoggingShellLayers"), 1);
        Config.maxBreakPerTick = toInt(authority.getNumber("general.maxBreakPerTick"), 1);
        Config.cableReplaceMaxPerTick = toInt(authority.getNumber("general.cableReplaceMaxPerTick"), 1);
        Config.chainWatchdogTimeoutTicks = toInt(authority.getNumber("general.chainWatchdogTimeoutTicks"), 20);
        Config.parallelTickMinDurationMs = toInt(authority.getNumber("general.parallelTickMinDurationMs"), 10);
        Config.parallelTickServerWorkBudgetUnits =
                toInt(authority.getNumber("general.parallelTickServerWorkBudgetUnits"), 1);
        Config.enableUnlimitedOreFortune = authority.getBool("general.enableUnlimitedOreFortune");
        Config.enableFortuneForPlacedOre = authority.getBool("general.enableFortuneForPlacedOre");

        Config.clientEnablePreviewRender = authority.getBool("client.clientEnablePreviewRender");
        Config.parallelTickClientWorkBudgetUnits =
                toInt(authority.getNumber("client.parallelTickClientWorkBudgetUnits"), 1);
        Config.clientPreviewMaxRadius = toInt(authority.getNumber("client.clientPreviewMaxRadius"), 1);
        Config.clientPreviewMaxTargets = toInt(authority.getNumber("client.clientPreviewMaxTargets"), 1);
        Config.clientPreviewAlphaFadeStartRadius =
                authority.getNumber("client.clientPreviewAlphaFadeStartRadius");
        Config.clientPreviewAlphaFadeEndRadius =
                authority.getNumber("client.clientPreviewAlphaFadeEndRadius");
        Config.clientPreviewAlphaStartValue = authority.getNumber("client.clientPreviewAlphaStartValue");
        Config.clientPreviewAlphaEndValue = authority.getNumber("client.clientPreviewAlphaEndValue");

        applyAlphaConstraints();
    }

    /**
     * 对齐历史 load 的 alpha / 衰减半径夹取语义。
     */
    public static void applyAlphaConstraints() {
        Config.clientPreviewAlphaFadeStartRadius = Math.max(0.0D, Config.clientPreviewAlphaFadeStartRadius);
        Config.clientPreviewAlphaFadeEndRadius =
                Math.max(Config.clientPreviewAlphaFadeStartRadius + 0.001D, Config.clientPreviewAlphaFadeEndRadius);
        Config.clientPreviewAlphaStartValue =
                Math.max(0.0D, Math.min(1.0D, Config.clientPreviewAlphaStartValue));
        Config.clientPreviewAlphaEndValue =
                Math.max(0.0D, Math.min(Config.clientPreviewAlphaStartValue, Config.clientPreviewAlphaEndValue));
    }

    /**
     * NUMBER → int：四舍五入后夹到 {@code min} 与 {@link Integer#MAX_VALUE}。
     *
     * @param value 权威数值
     * @param min   下界
     * @return 整数
     */
    public static int toInt(double value, int min) {
        long rounded = Math.round(value);
        if (rounded < min) {
            return min;
        }
        if (rounded > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) rounded;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
