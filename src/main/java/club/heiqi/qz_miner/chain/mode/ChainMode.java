package club.heiqi.qz_miner.chain.mode;

/**
 * 连锁模式定义。
 */
public enum ChainMode {
    /**
     * 默认同类连锁模式。
     */
    CHAIN,
    /**
     * 范围模式。
     */
    AREA,
    /**
     * 交互模式。
     */
    INTERACT,
    /**
     * 预留的特殊模式。
     */
    SPECIAL

    ;

    /**
     * 获取模式显示名称语言键。
     *
     * @return 语言键
     */
    public String getDisplayNameKey() {
        switch (this) {
            case CHAIN:
                return "hud.qz_miner.mode.chain";
            case AREA:
                return "hud.qz_miner.mode.area";
            case INTERACT:
                return "hud.qz_miner.mode.interact";
            case SPECIAL:
                return "hud.qz_miner.mode.special";
            default:
                return name();
        }
    }
}
