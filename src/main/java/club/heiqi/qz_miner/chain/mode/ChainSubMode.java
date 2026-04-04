package club.heiqi.qz_miner.chain.mode;

/**
 * 连锁子模式定义。
 */
public enum ChainSubMode {
    /**
     * CHAIN 默认子模式。
     */
    CHAIN_BASE(ChainMode.CHAIN, true),
    /**
     * AREA 同类匹配子模式。
     */
    AREA_SAME_BLOCK(ChainMode.AREA, true),
    /**
     * AREA 仅要求可收获的子模式。
     */
    AREA_HARVESTABLE_ALL(ChainMode.AREA, false),
    /**
     * INTERACT 默认子模式。
     */
    INTERACT_BASE(ChainMode.INTERACT, true),
    /**
     * INTERACT 作物交互子模式。
     */
    INTERACT_CROP(ChainMode.INTERACT, false),
    /**
     * SPECIAL 默认子模式。
     */
    SPECIAL_BASE(ChainMode.SPECIAL, true),
    /**
     * SPECIAL 预留扩展子模式。
     */
    SPECIAL_EXTENDED(ChainMode.SPECIAL, false);

    private final ChainMode parentMode;
    private final boolean sameBlockMatchRequired;

    ChainSubMode(ChainMode parentMode, boolean sameBlockMatchRequired) {
        this.parentMode = parentMode;
        this.sameBlockMatchRequired = sameBlockMatchRequired;
    }

    /**
     * 获取所属主模式。
     *
     * @return 所属主模式
     */
    public ChainMode getParentMode() {
        return parentMode;
    }

    /**
     * 判断当前子模式是否要求同类匹配。
     *
     * @return 是否要求同类匹配
     */
    public boolean requiresSameBlockMatch() {
        return sameBlockMatchRequired;
    }

    /**
     * 获取用于 HUD 展示的中文名称。
     *
     * @return 中文名称
     */
    public String getDisplayName() {
        switch (this) {
            case CHAIN_BASE:
                return "基础连锁";
            case AREA_SAME_BLOCK:
                return "同类范围";
            case AREA_HARVESTABLE_ALL:
                return "可收获范围";
            case INTERACT_BASE:
                return "基础交互";
            case INTERACT_CROP:
                return "作物交互";
            case SPECIAL_BASE:
                return "基础特殊";
            case SPECIAL_EXTENDED:
                return "扩展特殊";
            default:
                return name();
        }
    }
}
