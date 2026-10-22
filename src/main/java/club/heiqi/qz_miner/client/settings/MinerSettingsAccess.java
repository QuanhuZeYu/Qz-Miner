package club.heiqi.qz_miner.client.settings;

import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.config.ConfigKey;

/** 页面权限仅控制本机提交；远端服务器策略没有本地替代权威。 */
public enum MinerSettingsAccess {
    MENU, INTEGRATED_ADMIN, INTEGRATED_READ_ONLY, REMOTE;

    public static boolean serverControlled(ConfigKey<?> key) {
        return key.name().startsWith("general.") || key == QzMinerConfigSchema.AUTO_TOOL_SWAP_ENABLED
                || key == QzMinerConfigSchema.AUTO_TOOL_PRIORITY_SELECTORS;
    }

    public boolean canEdit(ConfigKey<?> key) {
        return !serverControlled(key) || this == MENU || this == INTEGRATED_ADMIN;
    }

}
