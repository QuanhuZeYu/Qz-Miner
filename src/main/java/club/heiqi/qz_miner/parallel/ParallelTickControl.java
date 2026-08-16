package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 任务分片可查询的控制对象。
 */
public interface ParallelTickControl {

    /**
     * @return 当前 Tick 编号
     */
    long getTickId();

    /**
     * @return 当前并行阶段
     */
    ParallelTickStage getStage();

    /**
     * @return 当前任务所属窗口是否仍然打开且匹配
     */
    boolean isWindowOpen();

    /**
     * @return 当前任务是否已被请求取消
     */
    boolean isCancelRequested();

    /**
     * @return 当前分片是否应在安全边界让出
     */
    boolean shouldYield();

    /**
     * @return 当前 Tick 已运行的纳秒数
     */
    long getElapsedNanoTime();

    /**
     * @return 当前任务的取消原因，未取消时为空字符串
     */
    String getCancelReason();
}
