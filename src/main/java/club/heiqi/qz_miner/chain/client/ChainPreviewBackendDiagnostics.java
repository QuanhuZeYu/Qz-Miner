package club.heiqi.qz_miner.chain.client;

/**
 * 后端 id 与回退原因的不可变标量快照，供渲染与客户端 tick 之间传递。
 * 工厂在开启时创建候选快照，采样方按值比较后复用旧引用；关闭时使用 DISABLED。
 */
public final class ChainPreviewBackendDiagnostics {

    /** 关闭态：HUD 隐藏诊断行，生产侧跳过后端快照采样。 */
    public static final ChainPreviewBackendDiagnostics DISABLED =
            new ChainPreviewBackendDiagnostics(false, "", "");

    private final boolean enabled;
    private final String activeBackendId;
    private final String fallbackReason;

    private ChainPreviewBackendDiagnostics(boolean enabled, String activeBackendId, String fallbackReason) {
        this.enabled = enabled;
        this.activeBackendId = activeBackendId == null ? "" : activeBackendId;
        this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
    }

    /**
     * @param enabled      配置开关；false 时工厂返回关闭态并忽略其余两项
     * @param activeBackendId 当前生效后端 id（{@code shader} / {@code legacy}）；空串 = 尚未判定
     * @param fallbackReason  一次性回退原因；空串 = 未发生回退
     * @return 诊断快照；{@code enabled=false} 时恒返回 {@link #DISABLED}
     */
    public static ChainPreviewBackendDiagnostics of(boolean enabled, String activeBackendId, String fallbackReason) {
        if (!enabled) {
            return DISABLED;
        }
        return new ChainPreviewBackendDiagnostics(true, activeBackendId, fallbackReason);
    }

    /** @return 配置开关是否打开（false = HUD 不产出诊断行） */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return 当前生效后端 id；空串 = 尚未判定 */
    public String getActiveBackendId() {
        return activeBackendId;
    }

    /** @return 一次性回退原因；空串 = 未发生回退 */
    public String getFallbackReason() {
        return fallbackReason;
    }

    /** @return 值语义相等，采样方据此决定保留旧引用或发布候选 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChainPreviewBackendDiagnostics)) {
            return false;
        }
        ChainPreviewBackendDiagnostics that = (ChainPreviewBackendDiagnostics) other;
        return enabled == that.enabled
            && activeBackendId.equals(that.activeBackendId)
            && fallbackReason.equals(that.fallbackReason);
    }

    @Override
    public int hashCode() {
        int result = enabled ? 1 : 0;
        result = 31 * result + activeBackendId.hashCode();
        result = 31 * result + fallbackReason.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ChainPreviewBackendDiagnostics{enabled=" + enabled
            + ", activeBackendId='" + activeBackendId + '\''
            + ", fallbackReason='" + fallbackReason + '\''
            + '}';
    }
}
