package club.heiqi.qz_miner.autotool;

/** 自动工具预选的不可变配置值。 */
public final class AutoToolSelectionConfig {
    public final boolean enabled;
    public final String searchScope;
    public final boolean restoreOriginal;
    public final EnchantmentPolicy enchantmentPolicy;
    public final int minimumRemainingDurability;
    public final int targetStableTicks;
    public final int emptyTargetGraceTicks;

    public AutoToolSelectionConfig(boolean enabled, String searchScope, boolean restoreOriginal,
            EnchantmentPolicy enchantmentPolicy, int minimumRemainingDurability,
            int targetStableTicks, int emptyTargetGraceTicks) {
        this.enabled = enabled;
        this.searchScope = searchScope;
        this.restoreOriginal = restoreOriginal;
        this.enchantmentPolicy = enchantmentPolicy;
        this.minimumRemainingDurability = minimumRemainingDurability;
        this.targetStableTicks = targetStableTicks;
        this.emptyTargetGraceTicks = emptyTargetGraceTicks;
    }

    /** 附魔保留策略。 */
    public enum EnchantmentPolicy { PRESERVE_CURRENT }
}
