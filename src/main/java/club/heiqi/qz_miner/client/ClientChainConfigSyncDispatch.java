package club.heiqi.qz_miner.client;

/**
 * 客户端连锁配置同步的纯数据调度边界。
 *
 * <p>该类不依赖 Minecraft 或客户端状态类型，便于纯 JVM 测试证明 publication 只在 dispatcher
 * 任务执行后发生。生产调用方在 ClientProxy 中注入 ClientMainThreadDispatcher。</p>
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
     * 捕获三个值并投递一次 publication。
     *
     * @param radius 服务端半径
     * @param maxBlocks 服务端目标上限
     * @param matchedCount 已匹配目标数
     * @param dispatcher 客户端主线程调度边界
     * @param publication 客户端状态发布动作
     */
    public static void dispatch(
            final int radius,
            final int maxBlocks,
            final int matchedCount,
            Dispatcher dispatcher,
            final Publication publication) {
        if (dispatcher == null || publication == null) {
            throw new IllegalArgumentException("dispatcher/publication must not be null");
        }
        dispatcher.dispatch(new Runnable() {
            @Override
            public void run() {
                if (!isValidPacket(radius, maxBlocks, matchedCount)) {
                    return;
                }
                publication.publish(radius, maxBlocks, matchedCount);
            }
        });
    }

    private static boolean isValidPacket(int radius, int maxBlocks, int matchedCount) {
        return radius > 0 && maxBlocks > 0 && matchedCount >= 0;
    }
}
