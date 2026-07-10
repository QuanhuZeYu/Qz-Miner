package club.heiqi.qz_miner.client;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端连接生命周期 token。
 *
 * <p>所有 advance 与 publication gate 共享同一私有 monitor 线性化。
 * {@link AtomicReference} 仅保证无锁只读 {@link #capture()} 的可见性；
 * 任何「读当前态再推进」或「检查后 publication」不得分离 get/set。</p>
 *
 * <ul>
 *   <li>{@link #advanceActive()}：连上服务器 → 新 active token</li>
 *   <li>{@link #advanceInactive()}：断线 → 新 inactive token</li>
 *   <li>{@link #advanceKeepActive()}：客户端世界卸载 → 推进 token，保持
 *       <em>线性化时刻</em> 的 active 标志（使旧世界排队任务失效；
 *       若 disconnect 已先推进为 inactive，则最终仍为 inactive）</li>
 *   <li>{@link #publishIfCurrentAndActive(Token, Runnable)}：在同一 monitor 内
 *       复核 token 仍为 current 且 active，再执行短小本地 publication</li>
 * </ul>
 *
 * <p>不读取 world / player / Minecraft 主线程状态。重复 connect / disconnect / unload
 * 推进幂等安全。publication 回调禁阻塞、禁反向调用本类 lifecycle 入口（防死锁）。
 * 禁止在 Netty 线程写 {@code ChainClientState}——本类只提供 gate，写主权仍在
 * 客户端主线程 dispatcher 任务内。</p>
 *
 * <h3>线性化语义</h3>
 * <ul>
 *   <li>publication 若先取得 monitor：属于旧生命周期，在 disconnect 推进前完成写入；
 *       这不算跨生命周期写。</li>
 *   <li>disconnect 若先推进：旧 token 的 publication 在 gate 内 no-op。</li>
 *   <li>unload 观察 active 与 disconnect 交错：最终 token 必 inactive（disconnect 后
 *       unload 保持 inactive；unload 后 disconnect 推进 inactive）。</li>
 * </ul>
 */
public final class ClientConnectionLifecycle {

    /**
     * 不可复用的生命周期令牌。
     *
     * <p>相等性按引用；{@link #isActive()} 为构造时快照，不随后续推进改变。</p>
     */
    public static final class Token {
        private final long id;
        private final boolean active;

        private Token(long id, boolean active) {
            this.id = id;
            this.active = active;
        }

        /**
         * @return 该 token 构造时是否为 active 连接
         */
        public boolean isActive() {
            return active;
        }

        /**
         * @return 单调递增 id（诊断用）
         */
        public long id() {
            return id;
        }
    }

    private static final Token INITIAL = new Token(0L, false);
    private static final AtomicReference<Token> CURRENT = new AtomicReference<Token>(INITIAL);
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    /**
     * 所有 advance 与 {@link #publishIfCurrentAndActive} 的统一线性化边界。
     *
     * <p>持锁期间只做 token 读写与短小本地 publication，禁止阻塞与反向获取本锁。</p>
     */
    private static final Object LIFECYCLE_MONITOR = new Object();

    private ClientConnectionLifecycle() {
    }

    /**
     * 连接成功：推进为新的 active token。
     *
     * @return 新的 current token
     */
    public static Token advanceActive() {
        synchronized (LIFECYCLE_MONITOR) {
            return advanceLocked(true);
        }
    }

    /**
     * 断线：推进为新的 inactive token。
     *
     * @return 新的 current token
     */
    public static Token advanceInactive() {
        synchronized (LIFECYCLE_MONITOR) {
            return advanceLocked(false);
        }
    }

    /**
     * 客户端世界卸载：推进 token，并保持<strong>线性化时刻</strong>的 active 标志。
     *
     * <p>在 monitor 内读取 current 再推进，禁止分离 get/set。
     * 已 inactive 时仍推进 id（使旧任务失效），active 保持 false。
     * 若 unload 已观察旧 active 但 disconnect 先提交，unload 重试后最终仍 inactive。</p>
     *
     * @return 新的 current token
     */
    public static Token advanceKeepActive() {
        synchronized (LIFECYCLE_MONITOR) {
            Token previous = CURRENT.get();
            return advanceLocked(previous.isActive());
        }
    }

    /**
     * Netty / 调用方只读捕获当前 token（不推进）。
     *
     * <p>无锁读；与 advance/publication 的 happens-before 由 AtomicReference 保证可见性。</p>
     *
     * @return 当前 token（永不为 null）
     */
    public static Token capture() {
        return CURRENT.get();
    }

    /**
     * 排队任务 publication 前：captured 仍为 current 且 active（只读快照，非 publication gate）。
     *
     * <p>生产 publication 路径请用 {@link #publishIfCurrentAndActive}，避免 check 后裸调用的 TOCTOU。</p>
     *
     * @param captured 入队时捕获的 token
     * @return 仍有效且 active 时为 true
     */
    public static boolean isCurrentAndActive(Token captured) {
        if (captured == null || !captured.isActive()) {
            return false;
        }
        return CURRENT.get() == captured;
    }

    /**
     * 在 lifecycle 线性化边界内：若 captured 仍为 current 且 active，则执行 publication。
     *
     * <p>check 与 publication 共享 monitor，禁止 check 后裸调用。
     * {@code publication} 必须短小、已知本地（仅写客户端状态三字段等），
     * <strong>禁阻塞、禁反向调用本类 advance/publish 入口</strong>（持锁期间）。
     * 不得在 Netty 线程调用本方法写 {@code ChainClientState}——调用方须先经
     * 客户端主线程 dispatcher 收口。</p>
     *
     * <h3>线性化含义</h3>
     * <ul>
     *   <li>本方法先取得 monitor：publication 属于旧生命周期，在 disconnect 推进前完成。</li>
     *   <li>disconnect 先推进：本方法 no-op，旧 token 不得写新生命周期状态。</li>
     * </ul>
     *
     * @param captured 入队时捕获的 token
     * @param publication 短小本地 publication；不得为 null
     * @return 已执行 publication 时为 true；token 失效或 inactive 时为 false
     */
    public static boolean publishIfCurrentAndActive(Token captured, Runnable publication) {
        if (publication == null) {
            throw new IllegalArgumentException("publication must not be null");
        }
        if (captured == null || !captured.isActive()) {
            return false;
        }
        synchronized (LIFECYCLE_MONITOR) {
            if (CURRENT.get() != captured || !captured.isActive()) {
                return false;
            }
            publication.run();
            return true;
        }
    }

    /**
     * 测试钩子：恢复初始 inactive token。
     */
    static void resetForTests() {
        synchronized (LIFECYCLE_MONITOR) {
            CURRENT.set(INITIAL);
            NEXT_ID.set(1L);
        }
    }

    /**
     * 测试钩子：暴露 monitor，供确定性并发测试断言 disconnect 在 publication 持锁时等待。
     *
     * @return lifecycle 线性化 monitor
     */
    static Object lifecycleMonitorForTests() {
        return LIFECYCLE_MONITOR;
    }

    /**
     * 必须在 {@link #LIFECYCLE_MONITOR} 内调用。
     */
    private static Token advanceLocked(boolean active) {
        long id = NEXT_ID.getAndIncrement();
        if (id <= 0L) {
            id = NEXT_ID.incrementAndGet();
            if (id <= 0L) {
                id = 1L;
                NEXT_ID.set(2L);
            }
        }
        Token next = new Token(id, active);
        CURRENT.set(next);
        return next;
    }
}
