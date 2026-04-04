package club.heiqi.qz_miner.chain.mode;

/**
 * 连锁子模式定义。
 */
public enum ChainSubMode {
    /**
     * CHAIN 默认子模式。
     */
    CHAIN_BASE(ChainMode.CHAIN, true, false),
    /**
     * CHAIN 宽泛矿石匹配子模式。
     */
    CHAIN_ORE(ChainMode.CHAIN, false, true),
    /**
     * AREA 同类匹配子模式。
     */
    AREA_SAME_BLOCK(ChainMode.AREA, true, false),
    /**
     * AREA 仅要求可收获的子模式。
     */
    AREA_HARVESTABLE_ALL(ChainMode.AREA, false, false),
    /**
     * AREA 宽泛矿石匹配子模式。
     */
    AREA_ORE(ChainMode.AREA, false, true),
    /**
     * INTERACT 默认子模式。
     */
    INTERACT_BASE(ChainMode.INTERACT, true, false),
    /**
     * INTERACT 作物交互子模式。
     */
    INTERACT_CROP(ChainMode.INTERACT, false, false),
    /**
     * SPECIAL 默认子模式。
     */
    SPECIAL_BASE(ChainMode.SPECIAL, true, false),
    /**
     * SPECIAL 预留扩展子模式。
     */
    SPECIAL_EXTENDED(ChainMode.SPECIAL, false, false);

    private final ChainMode parentMode;
    private final boolean sameBlockMatchRequired;
    private final boolean oreMatchRequired;

    ChainSubMode(ChainMode parentMode, boolean sameBlockMatchRequired, boolean oreMatchRequired) {
        this.parentMode = parentMode;
        this.sameBlockMatchRequired = sameBlockMatchRequired;
        this.oreMatchRequired = oreMatchRequired;
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
     * 判断当前子模式是否允许宽泛矿石匹配。
     *
     * @return 是否按矿石类别匹配
     */
    public boolean requiresOreMatch() {
        return oreMatchRequired;
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
            case CHAIN_ORE:
                return "矿石连锁";
            case AREA_SAME_BLOCK:
                return "同类范围";
            case AREA_HARVESTABLE_ALL:
                return "可收获范围";
            case AREA_ORE:
                return "矿石范围";
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
