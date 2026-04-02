package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 上下文。
 */
public final class ParallelTickContext {

    public enum Stage {
        PRE,
        POST
    }

    private final long tickId;
    private final long startNanoTime;
    private final long deadlineNanoTime;
    private final Stage stage;

    public ParallelTickContext(long tickId, long startNanoTime, long deadlineNanoTime, Stage stage) {
        this.tickId = tickId;
        this.startNanoTime = startNanoTime;
        this.deadlineNanoTime = deadlineNanoTime;
        this.stage = stage;
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

    /**
     * @return 当前 Tick 并行窗口结束时间
     */
    public long getDeadlineNanoTime() {
        return deadlineNanoTime;
    }

    /**
     * @return 当前并行阶段
     */
    public Stage getStage() {
        return stage;
    }

    /**
     * @return 当前窗口是否还有剩余时间
     */
    public boolean hasTimeLeft() {
        return System.nanoTime() < deadlineNanoTime;
    }
}
