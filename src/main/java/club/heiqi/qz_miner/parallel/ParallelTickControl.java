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
     * 尝试消耗工作量预算。
     *
     * @param units 本次工作消耗的预算单位
     * @return 预算足够且当前仍可继续工作时返回 true
     */
    boolean tryConsumeWork(int units);

    /**
     * @return 当前 Tick 已运行的纳秒数
     */
    long getElapsedNanoTime();

    /**
     * @return 当前任务的取消原因，未取消时为空字符串
     */
    String getCancelReason();
}
