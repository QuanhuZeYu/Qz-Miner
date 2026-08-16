package club.heiqi.qz_miner.parallel;

/**
 * 单个逻辑 Tick 内共享的单调时钟 soft deadline。
 *
 * <p>deadline 只能在安全边界协作式观察；已经开始的世界读取、Forge 回调或主线程目标事务
 * 必须完整返回，因而本接口不承诺硬实时中断。</p>
 */
public interface TickTimeBudget {

    /** @return 当前逻辑 Tick 编号 */
    long getTickId();

    /** @return 本窗口开始时的单调纳秒戳 */
    long getStartNanoTime();

    /** @return 本窗口冻结的绝对 deadline */
    long getDeadlineNanoTime();

    /** @return 当前窗口已运行的纳秒数 */
    long getElapsedNanoTime();

    /** @return 当前单调时钟仍早于 deadline */
    boolean hasTimeLeft();
}
