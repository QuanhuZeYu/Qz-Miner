package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 上下文。
 */
public final class ParallelTickContext {

    private final long tickId;
    private final long startNanoTime;

    public ParallelTickContext(long tickId, long startNanoTime) {
        this.tickId = tickId;
        this.startNanoTime = startNanoTime;
    }

    /**
     * @return 当前逻辑服务器 Tick 编号
     */
    public long getTickId() {
        return tickId;
    }

    /**
     * @return 当前 Tick 开始时间
     */
    public long getStartNanoTime() {
        return startNanoTime;
    }

    /**
     * @return 当前 Tick 已运行的纳秒数
     */
    public long getElapsedNanoTime() {
        return System.nanoTime() - startNanoTime;
    }
}
