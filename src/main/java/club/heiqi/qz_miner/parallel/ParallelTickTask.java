package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 分片任务。
 *
 * 返回 {@code true} 表示任务未完成，下一次 Tick 继续执行；
 * 返回 {@code false} 表示任务完成，框架会自动注销该任务。
 */
@FunctionalInterface
public interface ParallelTickTask {

    /**
     * 执行一次任务分片。
     *
     * @param context 当前 Tick 上下文
     * @return 是否需要在后续 Tick 继续执行
     * @throws Exception 任务执行异常
     */
    boolean run(ParallelTickContext context) throws Exception;
}
