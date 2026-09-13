package club.heiqi.qz_miner.chain.client;

/**
 * 远端预览请求的一次性门控（B0.6）。
 *
 * <p>职责：记录当前在途请求的 id 与发出时刻、丢弃陈旧 requestId 的迟到响应、
 * 按 clientPreviewRemoteTimeoutMs 判定超时。纯 JVM，不依赖 Minecraft / GL / 网络。</p>
 */
final class RemotePreviewRequest {

    private int pendingRequestId;
    private long pendingSinceNanos;
    private long timeoutMillis;

    /** @return 是否存在在途请求 */
    boolean isPending() {
        return pendingRequestId != 0;
    }

    /** @return 在途请求 id；无在途请求时为 0 */
    int getPendingRequestId() {
        return pendingRequestId;
    }

    /**
     * 发起新请求；覆盖仍在途的旧请求（旧请求随后到达即视为陈旧而被丢弃）。
     *
     * @param requestId 请求编号，必须非 0
     * @param nowNanos 发出时刻
     * @param timeoutMillis 超时阈值（毫秒）
     */
    void begin(int requestId, long nowNanos, long timeoutMillis) {
        this.pendingRequestId = requestId;
        this.pendingSinceNanos = nowNanos;
        this.timeoutMillis = Math.max(1L, timeoutMillis);
    }

    /**
     * 接受响应。
     *
     * @param requestId 响应携带的请求编号
     * @return 与在途请求一致时接受并清除在途状态；陈旧或重复响应返回 false
     */
    boolean accept(int requestId) {
        if (!isPending() || requestId != pendingRequestId) {
            return false;
        }
        pendingRequestId = 0;
        return true;
    }

    /**
     * 超时判定。
     *
     * @param nowNanos 当前时刻
     * @return 命中超时并已清除在途状态时为 true
     */
    boolean expire(long nowNanos) {
        if (!isPending() || nowNanos - pendingSinceNanos < timeoutNanos()) {
            return false;
        }
        pendingRequestId = 0;
        return true;
    }

    /** 生命周期/新目标清理：在途请求作废。 */
    void clear() {
        pendingRequestId = 0;
    }

    private long timeoutNanos() {
        return timeoutMillis * 1000000L;
    }
}
