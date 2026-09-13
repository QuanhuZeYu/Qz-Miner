package club.heiqi.qz_miner.chain.client;

/**
 * 连锁预览刷新策略的公开纯函数入口（B1.3）。
 *
 * <p>由 {@link ChainPreviewRenderCache} 与独立验证包共同消费：signal 档的 visual revision
 * 提升只由本函数判定，可 headless 直连断言，不依赖 refreshForCamera 的行为间接观察。</p>
 *
 * <p>收敛规则：位移阈值 &lt;= 0 或 NaN 等价于每帧刷新；兜底时间 &lt;= 0 表示不生效
 * （只由位移触发）。位移为 NaN 时不会误判达标，仍由兜底时间保底。</p>
 */
public final class ChainPreviewRefreshPolicy {

    private ChainPreviewRefreshPolicy() {
    }

    /**
     * signal 档是否应提升 visual revision。
     *
     * @param displacement 相对上次提升的相机位移（格）
     * @param distanceThreshold 位移阈值（格）；&lt;= 0 或 NaN 等价每帧刷新
     * @param elapsedMillis 距上次提升的毫秒数；负值按 0 处理
     * @param fallbackMillis 兜底时间（毫秒）；&lt;= 0 表示不生效
     * @return 是否应提升 visual revision
     */
    public static boolean isSignalRefreshDue(
            double displacement, double distanceThreshold, long elapsedMillis, int fallbackMillis) {
        if (!(distanceThreshold > 0.0D)) {
            return true;
        }
        long elapsed = elapsedMillis < 0L ? 0L : elapsedMillis;
        if (fallbackMillis > 0 && elapsed >= (long) fallbackMillis) {
            return true;
        }
        return displacement >= distanceThreshold;
    }
}
