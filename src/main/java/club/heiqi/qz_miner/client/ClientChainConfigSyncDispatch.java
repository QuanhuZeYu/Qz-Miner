package club.heiqi.qz_miner.client;

/**
 * 客户端连锁配置同步的纯数据调度边界。
 *
 * <p>该类不依赖 Minecraft 或客户端状态类型，便于纯 JVM 测试证明 publication 只在 dispatcher
 * 任务执行后发生。生产调用方在 ClientProxy 中注入 ClientMainThreadDispatcher 与
 * {@link ClientConnectionLifecycle} token 守卫。</p>
 *
 * <p>{@link #dispatch} 返回 dispatcher 是否接受任务；拒绝时不跨 lifecycle 重试，
 * 由调用方做限频/一次性诊断。inactive token 在入队前直接丢弃并返回 true（表示非 dispatcher
 * 拒绝，调用方不做 rejection warn）。非法整包仍在客户端主线程任务内拒绝。
 * 排队任务在整包校验后、publication 前要求 captured token 仍为 current 且 active。</p>
 */
public final class ClientChainConfigSyncDispatch {

    private ClientChainConfigSyncDispatch() {
    }

    /** 任务投递边界。 */
    public interface Dispatcher {
        /**
         * @param task 客户端状态发布任务
         * @return 任务已接受或执行时为 true
         */
        boolean dispatch(Runnable task);
    }

    /** 三个配置值的发布边界。 */
    public interface Publication {
        /**
         * @param radius 服务端半径
         * @param maxBlocks 服务端目标上限
         * @param matchedCount 已匹配目标数
         */
        void publish(int radius, int maxBlocks, int matchedCount);
    }

    /**
     * 生命周期守卫：入队前与 publication 前校验 token。
     *
     * <p>生产实现委托 {@link ClientConnectionLifecycle}；测试可注入假实现。</p>
     */
    public interface LifecycleGate {
        /**
         * @param token 捕获的 token
         * @return token 在捕获时是否 active
         */
        boolean isActive(Object token);

        /**
         * @param token 入队时捕获的 token
         * @return 仍为 current 且 active 时为 true
         */
        boolean isCurrentAndActive(Object token);
    }

    /**
     * 捕获三个值并投递一次 publication（无 lifecycle 守卫，兼容既有单测）。
     *
     * @param radius 服务端半径
     * @param maxBlocks 服务端目标上限
     * @param matchedCount 已匹配目标数
     * @param dispatcher 客户端主线程调度边界
     * @param publication 客户端状态发布动作
     * @return dispatcher 已接受任务时为 true；拒绝时为 false（不重试）
     */
    public static boolean dispatch(
            final int radius,
            final int maxBlocks,
            final int matchedCount,
            Dispatcher dispatcher,
            final Publication publication) {
        return dispatch(radius, maxBlocks, matchedCount, null, null, dispatcher, publication);
    }

    /**
     * 捕获三个值与 lifecycle token，并投递一次 publication。
     *
     * <p>capturedToken 非 null 且 gate 判定 inactive 时直接丢弃、不入队、返回 true
     * （调用方不得当作 dispatcher 拒绝做 warn）。</p>
     *
     * @param radius 服务端半径
     * @param maxBlocks 服务端目标上限
     * @param matchedCount 已匹配目标数
     * @param capturedToken 入包时捕获的 lifecycle token；可为 null（跳过守卫）
     * @param gate lifecycle 守卫；token 非 null 时必填
     * @param dispatcher 客户端主线程调度边界
     * @param publication 客户端状态发布动作
     * @return inactive 丢弃或 dispatcher 已接受时为 true；dispatcher 拒绝时为 false
     */
    public static boolean dispatch(
            final int radius,
            final int maxBlocks,
            final int matchedCount,
            final Object capturedToken,
            final LifecycleGate gate,
            Dispatcher dispatcher,
            final Publication publication) {
        if (dispatcher == null || publication == null) {
            throw new IllegalArgumentException("dispatcher/publication must not be null");
        }
        if (capturedToken != null && gate == null) {
            throw new IllegalArgumentException("gate must not be null when token is present");
        }
        if (capturedToken != null && !gate.isActive(capturedToken)) {
            // intentional drop: not a dispatcher rejection
            return true;
        }
        return dispatcher.dispatch(new Runnable() {
            @Override
            public void run() {
                if (capturedToken != null && !gate.isCurrentAndActive(capturedToken)) {
                    return;
                }
                if (!isValidPacket(radius, maxBlocks, matchedCount)) {
                    return;
                }
                publication.publish(radius, maxBlocks, matchedCount);
            }
        });
    }

    /**
     * 整包合法性：radius/maxBlocks 大于零且 matchedCount 非负。
     * 不检查 matchedCount&lt;=maxBlocks（规划启动后服务端配置可能下调）。
     */
    private static boolean isValidPacket(int radius, int maxBlocks, int matchedCount) {
        return radius > 0 && maxBlocks > 0 && matchedCount >= 0;
    }
}
