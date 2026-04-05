package club.heiqi.qz_miner.chain.mode;

/**
 * 连锁子模式定义。
 */
public enum ChainSubMode {
    /**
     * CHAIN 默认子模式。
     */
    CHAIN_BASE(ChainMode.CHAIN, true, false, false),
    /**
     * CHAIN 宽泛矿石匹配子模式。
     */
    CHAIN_ORE(ChainMode.CHAIN, false, true, false),
    /**
     * CHAIN 伐木子模式。
     */
    CHAIN_LOGGING(ChainMode.CHAIN, false, false, true),
    /**
     * AREA 同类匹配子模式。
     */
    AREA_SAME_BLOCK(ChainMode.AREA, true, false, false),
    /**
     * AREA 仅要求可收获的子模式。
     */
    AREA_HARVESTABLE_ALL(ChainMode.AREA, false, false, false),
    /**
     * AREA 宽泛矿石匹配子模式。
     */
    AREA_ORE(ChainMode.AREA, false, true, false),
    /**
     * AREA 指向性隧道子模式。
     */
    AREA_TUNNEL(ChainMode.AREA, false, false, false),
    /**
     * INTERACT 默认子模式。
     */
    INTERACT_BASE(ChainMode.INTERACT, true, false, false),
    /**
     * INTERACT 作物交互子模式。
     */
    INTERACT_CROP(ChainMode.INTERACT, false, false, false),
    /**
     * SPECIAL LootGames 扫雷预览子模式。
     */
    SPECIAL_LOOTGAMES_MINESWEEPER(ChainMode.SPECIAL, false, false, false),
    /**
     * SPECIAL 默认子模式。
     */
    SPECIAL_BASE(ChainMode.SPECIAL, true, false, false),
    /**
     * SPECIAL 预留扩展子模式。
     */
    SPECIAL_EXTENDED(ChainMode.SPECIAL, false, false, false);

    private final ChainMode parentMode;
    private final boolean sameBlockMatchRequired;
    private final boolean oreMatchRequired;
    private final boolean logMatchRequired;

    ChainSubMode(ChainMode parentMode, boolean sameBlockMatchRequired, boolean oreMatchRequired) {
        this(parentMode, sameBlockMatchRequired, oreMatchRequired, false);
    }

    ChainSubMode(ChainMode parentMode, boolean sameBlockMatchRequired, boolean oreMatchRequired, boolean logMatchRequired) {
        this.parentMode = parentMode;
        this.sameBlockMatchRequired = sameBlockMatchRequired;
        this.oreMatchRequired = oreMatchRequired;
        this.logMatchRequired = logMatchRequired;
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
     * 判断当前子模式是否要求原木匹配。
     *
     * @return 是否按原木类别匹配
     */
    public boolean requiresLogMatch() {
        return logMatchRequired;
    }

    /**
     * 获取用于 HUD 展示的中文名称。
     *
     * @return 中文名称
     */
    public String getDisplayNameKey() {
        switch (this) {
            case CHAIN_BASE:
                return "hud.qz_miner.sub_mode.chain.base";
            case CHAIN_ORE:
                return "hud.qz_miner.sub_mode.chain.ore";
            case CHAIN_LOGGING:
                return "hud.qz_miner.sub_mode.chain.logging";
            case AREA_SAME_BLOCK:
                return "hud.qz_miner.sub_mode.area.base";
            case AREA_HARVESTABLE_ALL:
                return "hud.qz_miner.sub_mode.area.all";
            case AREA_ORE:
                return "hud.qz_miner.sub_mode.area.ore";
            case AREA_TUNNEL:
                return "hud.qz_miner.sub_mode.area.tunnel";
            case INTERACT_BASE:
                return "hud.qz_miner.sub_mode.interact.base";
            case INTERACT_CROP:
                return "hud.qz_miner.sub_mode.interact.crop";
            case SPECIAL_LOOTGAMES_MINESWEEPER:
                return "hud.qz_miner.sub_mode.special.lootgames_minesweeper";
            case SPECIAL_BASE:
                return "hud.qz_miner.sub_mode.special.base";
            case SPECIAL_EXTENDED:
                return "hud.qz_miner.sub_mode.special.extended";
            default:
                return name();
        }
    }
}
