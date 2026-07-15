package club.heiqi.qz_miner.config;

import java.util.ArrayList;
import java.util.Collections;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/**
 * 将已严格校验的快照发布到 {@link Config} 静态字段（分 general / client）。
 *
 * <p>不在此夹取/round 非法值；调用方必须先经 {@link ConfigSemanticValidator}。
 * preInit 可全量发布；运行时 BATCH_SAVE/RELOAD 必须分侧 + 主线程 dispatcher（见 ClientConfigChangeListener）。</p>
 *
 * <p>server-safe，零 MC UI / LWJGL。</p>
 */
public final class ConfigValueBridge {

    private ConfigValueBridge() {
    }

    /**
     * 启动时全量回灌（运行前，可同时写 general + client）。
     *
     * @param snapshot 已校验快照
     */
    public static void applyAll(ValidatedSnapshot snapshot) {
        applyGeneralFromSnapshot(snapshot);
        applyClientFromSnapshot(snapshot);
    }

    /**
     * 仅发布 general 段（服务端主线程或 preInit）。
     *
     * @param snapshot 已校验快照
     */
    public static void applyGeneralFromSnapshot(ValidatedSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Config.greeting = snapshot.greeting;
        Config.chainRadius = snapshot.chainRadius;
        Config.chainMaxBlocks = snapshot.chainMaxBlocks;
        Config.chainLoggingShellLayers = snapshot.chainLoggingShellLayers;
        Config.maxBreakPerTick = snapshot.maxBreakPerTick;
        Config.cableReplaceMaxPerTick = snapshot.cableReplaceMaxPerTick;
        Config.chainWatchdogTimeoutTicks = snapshot.chainWatchdogTimeoutTicks;
        Config.parallelTickMinDurationMs = snapshot.parallelTickMinDurationMs;
        Config.parallelTickServerWorkBudgetUnits = snapshot.parallelTickServerWorkBudgetUnits;
        Config.enableUnlimitedOreFortune = snapshot.enableUnlimitedOreFortune;
        Config.enableFortuneForPlacedOre = snapshot.enableFortuneForPlacedOre;
    }

    /**
     * 仅发布 client 段（客户端主线程）。
     *
     * @param snapshot 已校验快照
     */
    public static void applyClientFromSnapshot(ValidatedSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        Config.clientEnablePreviewRender = snapshot.clientEnablePreviewRender;
        Config.autoToolSwapEnabled = snapshot.autoToolSwapEnabled;
        Config.autoToolPrioritySelectors = Collections.unmodifiableList(
                new ArrayList<club.heiqi.qz_miner.toolswap.ToolSelector>(snapshot.autoToolPrioritySelectors));
        Config.parallelTickClientWorkBudgetUnits = snapshot.parallelTickClientWorkBudgetUnits;
        Config.clientPreviewMaxRadius = snapshot.clientPreviewMaxRadius;
        Config.clientPreviewMaxTargets = snapshot.clientPreviewMaxTargets;
        Config.clientPreviewAlphaFadeStartRadius = snapshot.clientPreviewAlphaFadeStartRadius;
        Config.clientPreviewAlphaFadeEndRadius = snapshot.clientPreviewAlphaFadeEndRadius;
        Config.clientPreviewAlphaStartValue = snapshot.clientPreviewAlphaStartValue;
        Config.clientPreviewAlphaEndValue = snapshot.clientPreviewAlphaEndValue;
    }
}
