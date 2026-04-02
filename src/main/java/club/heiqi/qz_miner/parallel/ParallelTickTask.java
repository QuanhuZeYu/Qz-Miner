package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 任务。
 *
 * 该任务会在逻辑服务器每个 Tick 的并行区内执行。
 */
@FunctionalInterface
public interface ParallelTickTask {

    /**
     * 执行并行任务。
     *
     * @param context 当前 Tick 上下文
     * @throws Exception 任务执行异常
     */
    void run(ParallelTickContext context) throws Exception;
}
