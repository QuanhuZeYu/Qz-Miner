package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.network.PacketChainConfigSync;
/**
 * 客户端连锁配置同步的纯数据调度边界。
 *
 * <p>该类不依赖 Minecraft 或客户端状态类型，便于纯 JVM 测试证明 publication 只在 dispatcher
 * 任务执行后发生。生产调用方在 ClientProxy 中注入 ClientMainThreadDispatcher 与
 * {@link ClientConnectionLifecycle} token 守卫。</p>
 *
 * <p>{@link #dispatch} 返回 dispatcher 是否接受任务；拒绝时不跨 lifecycle 重试，
 * 由调用方做限频/一次性诊断。inactive token 在入队前直接丢弃并返回 true（表示非 dispatcher
 * 拒绝，调用方不做 rejection warn）。</p>
 *
 * <h3>主线程任务顺序（契约）</h3>
 * <ol>
 *   <li><strong>先</strong>整包数值校验（radius/maxBlocks&gt;0 且 matchedCount≥0）</li>
 *   <li><strong>再</strong>在 lifecycle 线性化边界内复核 token 仍为 current 且 active，并 publication</li>
 * </ol>
 *
 * <p>校验与 gate 不得颠倒；gate 内 check+publication 必须原子（见
 * {@link LifecycleGate#publishIfCurrentAndActive}），禁止 check 后裸调用 publication。</p>
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

    /** 四个 accepted 配置值的原子发布边界。 */
    public interface AcceptedPublication {
        void publish(int radius, int maxBlocks, int matchedCount, TunnelDirectionSource source);
    }

    /**
     * 生命周期守卫：入队前 active 检查与 publication 线性化 gate。
     *
     * <p>生产实现委托 {@link ClientConnectionLifecycle}；测试可注入假实现。
     * publication 路径必须走 {@link #publishIfCurrentAndActive}，不得
     * {@link #isCurrentAndActive} 后裸写状态。</p>
     */
    public interface LifecycleGate {
        /**
         * @param token 捕获的 token
         * @return token 在捕获时是否 active
         */
        boolean isActive(Object token);

        /**
         * 只读快照：captured 是否仍为 current 且 active。
         *
         * <p>不得用于「check 后裸 publication」；生产 publication 用
         * {@link #publishIfCurrentAndActive}。</p>
         *
         * @param token 入队时捕获的 token
         * @return 仍为 current 且 active 时为 true
         */
        boolean isCurrentAndActive(Object token);

        /**
         * 在 lifecycle 线性化边界内：token 仍 current 且 active 时执行 publication。
         *
         * <p>{@code publication} 必须短小、已知本地，禁阻塞、禁反向获取 lifecycle 入口。</p>
         *
         * @param token 入队时捕获的 token
         * @param publication 短小本地 publication
         * @return 已执行时为 true
         */
        boolean publishIfCurrentAndActive(Object token, Runnable publication);
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
     * <p>主线程任务内：先整包校验，再经 {@link LifecycleGate#publishIfCurrentAndActive}
     * 在 lifecycle 线性化边界内复核并 publication。</p>
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
        if (publication == null) {
            throw new IllegalArgumentException("publication must not be null");
        }
        return dispatch(radius, maxBlocks, matchedCount,
                PacketChainConfigSync.LEGACY_PROTOCOL_VERSION,
                TunnelDirectionSource.legacyDefault().wireCode(), true,
                capturedToken, gate, dispatcher, new AcceptedPublication() {
                    @Override
                    public void publish(int publishedRadius, int publishedMaxBlocks, int publishedMatchedCount,
                            TunnelDirectionSource source) {
                        publication.publish(publishedRadius, publishedMaxBlocks, publishedMatchedCount);
                    }
                });
    }

    /** v2 调度入口：先整包校验 framing/version/code，再在 lifecycle gate 内原子发布四字段。 */
    public static boolean dispatch(
            final int radius, final int maxBlocks, final int matchedCount,
            final int protocolVersion, final int directionCode, final boolean rawValid,
            final Object capturedToken, final LifecycleGate gate,
            Dispatcher dispatcher, final AcceptedPublication publication) {
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
                // 1) 先整包数值校验（gate 外，避免非法包进入 lifecycle monitor）
                final TunnelDirectionSource source = resolveSource(protocolVersion, directionCode, rawValid);
                if (!isValidPacket(radius, maxBlocks, matchedCount) || source == null) {
                    return;
                }
                if (capturedToken == null) {
                    publication.publish(radius, maxBlocks, matchedCount, source);
                    return;
                }
                // 2) 再在 lifecycle 线性化边界内复核 token 并 publication（禁 check 后裸调用）
                gate.publishIfCurrentAndActive(capturedToken, new Runnable() {
                    @Override
                    public void run() {
                        publication.publish(radius, maxBlocks, matchedCount, source);
                    }
                });
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

    static TunnelDirectionSource resolveSource(int protocolVersion, int directionCode, boolean rawValid) {
        if (!rawValid) {
            return null;
        }
        if (protocolVersion == PacketChainConfigSync.LEGACY_PROTOCOL_VERSION) {
            return TunnelDirectionSource.legacyDefault();
        }
        return protocolVersion == PacketChainConfigSync.PROTOCOL_VERSION
                ? TunnelDirectionSource.fromWireCode(directionCode) : null;
    }
}
