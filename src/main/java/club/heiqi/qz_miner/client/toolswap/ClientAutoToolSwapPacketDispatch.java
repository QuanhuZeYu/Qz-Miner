package club.heiqi.qz_miner.client.toolswap;

/**
 * 自动工具换位 S2C 包的纯主线程调度边界。
 *
 * <p>Netty 调用方按 {@code ctx.netHandler} 捕获 lifecycle token 后传入本类。inactive token 在入队前
 * 有意丢弃；已入队任务仅能通过 lifecycle gate 原子复核并执行短小 publication，避免旧连接或旧世界
 * 在客户端主线程写入状态。</p>
 */
public final class ClientAutoToolSwapPacketDispatch {

    private ClientAutoToolSwapPacketDispatch() {
    }

    /** 客户端主线程任务投递边界。 */
    public interface Dispatcher {

        /**
         * @param task 客户端主线程任务
         * @return 已接受或已执行时为 true；拒绝时为 false
         */
        boolean dispatch(Runnable task);
    }

    /** 生命周期 token 的入队与 publication 线性化边界。 */
    public interface LifecycleGate {

        /**
         * @param token Netty 线程捕获的 token
         * @return token 当前可入队时为 true
         */
        boolean isActive(Object token);

        /**
         * 在 lifecycle 线性化边界中复核 token 并执行短小 publication。
         *
         * @param token Netty 线程捕获的 token
         * @param publication 非阻塞的本地状态发布动作
         * @return publication 已执行时为 true
         */
        boolean publishIfCurrentAndActive(Object token, Runnable publication);
    }

    /**
     * 将已捕获的 S2C publication 投递到客户端主线程。
     *
     * <p>dispatcher 拒绝返回 false，不跨 lifecycle 重试。inactive token 的丢弃返回 true，表示并非
     * dispatcher 拒绝，调用方不应记为 rejection。</p>
     *
     * @param capturedToken Netty 线程按连接 identity 捕获的 token
     * @param gate lifecycle 守卫
     * @param dispatcher 客户端主线程调度器
     * @param publication 短小的客户端状态发布动作
     * @return inactive 丢弃或 dispatcher 接受任务时为 true；dispatcher 拒绝时为 false
     */
    public static boolean dispatch(final Object capturedToken, LifecycleGate gate,
            Dispatcher dispatcher, final Runnable publication) {
        if (gate == null || dispatcher == null || publication == null) {
            throw new IllegalArgumentException("gate, dispatcher, and publication must not be null");
        }
        if (capturedToken == null || !gate.isActive(capturedToken)) {
            return true;
        }
        return dispatcher.dispatch(new Runnable() {
            @Override
            public void run() {
                gate.publishIfCurrentAndActive(capturedToken, publication);
            }
        });
    }
}
