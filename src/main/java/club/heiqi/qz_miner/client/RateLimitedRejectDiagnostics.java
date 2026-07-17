package club.heiqi.qz_miner.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 限频拒绝诊断聚合器（纯 JVM 可测，不依赖 Minecraft / GL）。
 *
 * <p>只允许成功 CAS 更新时间戳的线程记录；获胜后 {@link AtomicLong#getAndSet(0)}
 * 独占本窗口批次计数。并发窗口只一条日志、计数不负、后续窗口可再记录。</p>
 */
public final class RateLimitedRejectDiagnostics {

    /** 批次日志回调。 */
    public interface BatchLogger {
        /**
         * @param batchCount 本窗口独占汇总的拒绝次数（&gt; 0）
         */
        void log(long batchCount);
    }

    private final long intervalNs;
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong lastDiagNs = new AtomicLong();

    /**
     * @param intervalNs 诊断窗口最小间隔（纳秒，须 &gt; 0）
     */
    public RateLimitedRejectDiagnostics(long intervalNs) {
        if (intervalNs <= 0L) {
            throw new IllegalArgumentException("intervalNs must be positive");
        }
        this.intervalNs = intervalNs;
    }

    /**
     * 记录一次拒绝；仅 CAS 获胜线程可能触发日志。
     *
     * @param nowNs 当前时间（纳秒）
     * @param logger 批次日志；batchCount 为 0 时不会调用
     */
    public void note(long nowNs, BatchLogger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("logger must not be null");
        }
        count.incrementAndGet();
        long previous = lastDiagNs.get();
        if (previous != 0L && nowNs - previous < intervalNs) {
            return;
        }
        if (!lastDiagNs.compareAndSet(previous, nowNs)) {
            return;
        }
        long batch = count.getAndSet(0L);
        if (batch > 0L) {
            logger.log(batch);
        }
    }

    /**
     * 测试钩子：当前累计未刷出的拒绝次数。
     *
     * @return 近似计数
     */
    long pendingCountForTests() {
        return count.get();
    }

    /**
     * 测试钩子：上次诊断时间戳。
     *
     * @return 纳秒时间戳，0 表示尚未记录
     */
    long lastDiagNsForTests() {
        return lastDiagNs.get();
    }

    /**
     * 测试钩子：清零。
     */
    void resetForTests() {
        count.set(0L);
        lastDiagNs.set(0L);
    }
}
