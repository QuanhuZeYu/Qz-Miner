package club.heiqi.qz_miner.client;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端连接生命周期 token。
 *
 * <p>仅用 {@link AtomicReference} 推进不可复用 token；线程安全，Netty 可只读捕获。
 * 不读取 world / player / Minecraft 主线程状态。重复 connect / disconnect / unload 推进幂等安全。</p>
 *
 * <ul>
 *   <li>{@link #advanceActive()}：连上服务器 → 新 active token，再调度初始化</li>
 *   <li>{@link #advanceInactive()}：断线 → 新 inactive token，再调度清理</li>
 *   <li>{@link #advanceKeepActive()}：客户端世界卸载 → 推进 token 但保持当前 active 标志
 *       （使旧世界排队任务失效；同一连接切维度后未来 S2C 仍可捕获新的 active token）</li>
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

    private ClientConnectionLifecycle() {
    }

    /**
     * 连接成功：推进为新的 active token。
     *
     * @return 新的 current token
     */
    public static Token advanceActive() {
        return advance(true);
    }

    /**
     * 断线：推进为新的 inactive token。
     *
     * @return 新的 current token
     */
    public static Token advanceInactive() {
        return advance(false);
    }

    /**
     * 客户端世界卸载：推进 token，并保持推进前的 active 标志。
     *
     * <p>已 inactive 时仍推进 id（使旧任务失效），active 保持 false。</p>
     *
     * @return 新的 current token
     */
    public static Token advanceKeepActive() {
        Token previous = CURRENT.get();
        return advance(previous.isActive());
    }

    /**
     * Netty / 调用方只读捕获当前 token（不推进）。
     *
     * @return 当前 token（永不为 null）
     */
    public static Token capture() {
        return CURRENT.get();
    }

    /**
     * 排队任务 publication 前：captured 仍为 current 且 active。
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
     * 测试钩子：恢复初始 inactive token。
     */
    static void resetForTests() {
        CURRENT.set(INITIAL);
        NEXT_ID.set(1L);
    }

    private static Token advance(boolean active) {
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
