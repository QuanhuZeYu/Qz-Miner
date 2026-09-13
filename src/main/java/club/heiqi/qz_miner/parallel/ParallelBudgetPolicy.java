package club.heiqi.qz_miner.parallel;

import java.util.concurrent.TimeUnit;

/**
 * 并行 Tick 预算决策纯函数。
 *
 * <p>零 MC / GL / 配置依赖，可在纯 JVM 内构造与断言；{@link ParallelTickExecutor}
 * 只消费本类结果，使预算语义与线程/锁实现可以分别验收。</p>
 *
 * <p>语义不变量：窗口预算只是供协作式观察的 soft deadline，两个档位都不改变
 * 「已开始的分片必须完整返回到安全边界」这一屏障契约。</p>
 */
public final class ParallelBudgetPolicy {

    /** 与 tick 预算同源的下限（毫秒）。 */
    public static final int TICK_BUDGET_MIN_MS = 1;
    /** 与 tick 预算同源的上限（毫秒）。 */
    public static final int TICK_BUDGET_MAX_MS = 40;
    /** slice 档并行让出预算的允许区间（毫秒）。 */
    public static final int SLICE_BUDGET_MIN_MS = 1;
    /** slice 档并行让出预算的允许区间（毫秒）。 */
    public static final int SLICE_BUDGET_MAX_MS = 40;

    private ParallelBudgetPolicy() {
    }

    /**
     * 收窄 tick 预算到执行器允许区间。
     *
     * @param tickBudgetMs 配置值
     * @return [1, 40] 内的毫秒数
     */
    public static int clampTickBudgetMs(int tickBudgetMs) {
        return Math.max(TICK_BUDGET_MIN_MS, Math.min(TICK_BUDGET_MAX_MS, tickBudgetMs));
    }

    /**
     * 收窄 slice 档并行让出预算到允许区间。
     *
     * @param sliceBudgetMs 配置值
     * @return [1, 40] 内的毫秒数
     */
    public static int clampSliceBudgetMs(int sliceBudgetMs) {
        return Math.max(SLICE_BUDGET_MIN_MS, Math.min(SLICE_BUDGET_MAX_MS, sliceBudgetMs));
    }

    /**
     * 判断该档位是否对指定 stage 启用并行让出切片。
     *
     * <p>切片只作用于客户端 stage：客户端 stage 的并行让出直接消耗渲染帧时间，
     * 而服务端 stage 承担规划吞吐，不应被预览优化牵动。未知档位与未知 stage
     * 一律返回 false（等于基线行为）。</p>
     *
     * @param mode 预算档位，null 视为 {@link ParallelBudgetMode#DEFAULT}
     * @param stage 并行阶段，null 视为不启用
     * @return true 表示该 stage 使用切片预算
     */
    public static boolean usesSliceBudget(ParallelBudgetMode mode, ParallelTickStage stage) {
        ParallelBudgetMode effectiveMode = mode == null ? ParallelBudgetMode.DEFAULT : mode;
        if (effectiveMode != ParallelBudgetMode.SLICE || stage == null) {
            return false;
        }
        return stage == ParallelTickStage.CLIENT_PRE || stage == ParallelTickStage.CLIENT_POST;
    }

    /**
     * 计算本次窗口冻结的并行预算（纳秒）。
     *
     * <p>未启用切片时等于基线（只受 tickBudgetMs 约束）；启用切片时取二者较小值，
     * 使并行让出不再等于整个 tick 预算。</p>
     *
     * @param sliceActive 是否启用切片预算
     * @param tickBudgetMs tick 预算毫秒
     * @param sliceBudgetMs slice 档并行让出预算毫秒
     * @return 冻结的窗口预算纳秒数，恒为正
     */
    public static long windowBudgetNanos(boolean sliceActive, int tickBudgetMs, int sliceBudgetMs) {
        int effectiveMs = clampTickBudgetMs(tickBudgetMs);
        if (sliceActive) {
            effectiveMs = Math.min(effectiveMs, clampSliceBudgetMs(sliceBudgetMs));
        }
        return TimeUnit.MILLISECONDS.toNanos(effectiveMs);
    }

    /**
     * 主线程是否还应在本 stage 的窗口内等待。
     *
     * <p>基线（未启用切片）：只要该 stage 仍有注册任务就等到窗口 deadline。
     * 启用切片后增加两个提前返回条件——没有活跃分片、且每个注册任务都已获得
     * 本 tick 的调度机会——从而不再为「已经让出的任务」空耗墙钟。</p>
     *
     * <p>注意：本函数只决定「还要不要给未启动的分片机会」，不参与窗口关闭后的
     * worker 屏障等待；屏障等待不受本函数影响。</p>
     *
     * @param sliceActive 是否启用切片预算
     * @param registeredTasks 该 stage 当前注册任务数
     * @param activeWorkers 正在分片事务内的 worker 数
     * @param scheduledTasks 本窗口已获得调度机会的注册任务数
     * @param nowNanoTime 当前单调时钟
     * @param deadlineNanoTime 本窗口冻结的绝对预算终点
     * @return true 表示主线程还应继续 park
     */
    public static boolean shouldMainThreadWait(
            boolean sliceActive,
            int registeredTasks,
            int activeWorkers,
            int scheduledTasks,
            long nowNanoTime,
            long deadlineNanoTime) {
        if (registeredTasks <= 0) {
            return false;
        }
        if (nowNanoTime >= deadlineNanoTime) {
            return false;
        }
        if (activeWorkers > 0) {
            return true;
        }
        if (sliceActive) {
            return scheduledTasks < registeredTasks;
        }
        return true;
    }
}
