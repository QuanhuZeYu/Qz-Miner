package club.heiqi.qz_miner.chain.client;

/**
 * 预览后端诊断快照（不可变值对象，Q4 的 HUD 数据面）。
 *
 * <p><b>为什么是一个值对象而不是三个字段</b>：表现投影 header 有一条「字段数必须有界（O(1) 结构）」
 * 的契约断言（{@code ProjectionContractTest.headerCarriesNoCollectionOrArrayFields}，上界 24）。
 * 三个裸字段会顶破上界；而这三项本来就是同一件事（「当前用的是哪个后端、有没有回退过」），
 * 合成一个不可变值对象既守住结构上界，也让投影参数只增一个。</p>
 *
 * <p><b>零分配口径</b>：生产侧只在<b>值真正变化</b>时调用 {@link #of} 生成新实例，
 * 未变化时复用上一实例；两个字符串引用来自渲染线程发布的 volatile 快照，
 * 不在本类内部复制。因此稳态下每 tick 零分配。</p>
 *
 * <p><b>跨线程</b>：实例不可变、字段 final、不持有任何 GL 或渲染对象，
 * 可在渲染线程与客户端 tick 线程之间安全传递。</p>
 */
public final class ChainPreviewBackendDiagnostics {

    /** 关闭态：HUD 不产出诊断行，且生产侧不读取后端快照。 */
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
     * @param enabled      配置开关 {@code clientPreviewBackendDiagnostics}；false 时其余两项必须为空串
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

    /** @return 值语义相等（生产侧用它判断「是否需要重建实例」） */
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
