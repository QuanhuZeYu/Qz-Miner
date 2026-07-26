package club.heiqi.qz_miner.compat.adapter;

/**
 * 作物当前生长状态。
 *
 * <p>{@link #UNKNOWN} 表示兼容层无法可靠分类，调用方必须 fail-closed。</p>
 */
public enum CropGrowthState {
    /** 已达到可靠可收获状态。 */
    MATURE,
    /** 已可靠确认仍未成熟。 */
    IMMATURE,
    /** 类、成员、状态或调用结果无法可靠确认。 */
    UNKNOWN
}
