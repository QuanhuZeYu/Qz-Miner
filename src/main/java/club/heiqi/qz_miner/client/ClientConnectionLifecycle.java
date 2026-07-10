package club.heiqi.qz_miner.client;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端连接 / 世界生命周期 token（绑定真实连接 identity）。
 *
 * <p>连接身份生产值为 {@code INetHandler} 实例，按对象引用 {@code ==} 判定；
 * <strong>禁止</strong> {@code identityHashCode}/{@code equals} 作为身份。
 * 世界身份为客户端远端 world 对象，仅在 client 监听与本类内处理。</p>
 *
 * <h3>Token 字段</h3>
 * <ul>
 *   <li>不可复用 {@code connectionGeneration}：每次成功 connect 新 handler 递增</li>
 *   <li>不可复用 {@code worldGeneration}：bind/unbind/disconnect 推进世界代际</li>
 *   <li>{@code connectionActive} / {@code worldActive}</li>
 *   <li>{@code connectionIdentity} / {@code worldIdentity}</li>
 * </ul>
 *
 * <h3>线性化</h3>
 * <p>所有 connect/disconnect/bind/unbind 与 gate 执行共享同一私有 monitor。
 * 短小本地 publication（如 config 三字段、event bus offer）可在 monitor 内执行；
 * 回调<strong>禁阻塞、禁反向调用本类 lifecycle 入口</strong>。不引入第二把锁。</p>
 *
 * <h3>Gate 语义</h3>
 * <ul>
 *   <li>连接级（config）：connectionGeneration + connection identity + connectionActive；
 *       首次 world bind / 同连接切维度不废弃连接级 token</li>
 *   <li>世界级（phase/preview）：额外要求 worldGeneration + worldActive</li>
 *   <li>disconnect cleanup：仅 inactive 且 connectionGeneration 仍为 current 时执行</li>
 *   <li>world unbind cleanup：仅该次 unbind 产生的 world-inactive token 仍为 current 时执行</li>
 * </ul>
 *
 * <p>旧连接迟到 disconnect、重复 close、旧 world unload、旧包在新生命周期下均为 no-op。</p>
 */
public final class ClientConnectionLifecycle {

    /**
     * 不可复用的生命周期快照。
     *
     * <p>相等性按引用；字段为构造时快照，不随后续推进改变。</p>
     */
    public static final class Token {
        private final long connectionGeneration;
        private final long worldGeneration;
        private final boolean connectionActive;
        private final boolean worldActive;
        private final Object connectionIdentity;
        private final Object worldIdentity;

        private Token(
                long connectionGeneration,
                long worldGeneration,
                boolean connectionActive,
                boolean worldActive,
                Object connectionIdentity,
                Object worldIdentity) {
            this.connectionGeneration = connectionGeneration;
            this.worldGeneration = worldGeneration;
            this.connectionActive = connectionActive;
            this.worldActive = worldActive;
            this.connectionIdentity = connectionIdentity;
            this.worldIdentity = worldIdentity;
        }

        /**
         * @return 连接是否 active（构造时快照）
         */
        public boolean isConnectionActive() {
            return connectionActive;
        }

        /**
         * @return 世界是否 active（构造时快照）
         */
        public boolean isWorldActive() {
            return worldActive;
        }

        /**
         * 兼容连接级 gate：等同 {@link #isConnectionActive()}。
         *
         * @return 连接是否 active
         */
        public boolean isActive() {
            return connectionActive;
        }

        /**
         * @return 连接代际（诊断）
         */
        public long connectionGeneration() {
            return connectionGeneration;
        }

        /**
         * @return 世界代际（诊断）
         */
        public long worldGeneration() {
            return worldGeneration;
        }

        /**
         * @return 连接 identity（生产为 INetHandler；测试可为任意对象）
         */
        public Object connectionIdentity() {
            return connectionIdentity;
        }

        /**
         * @return 世界 identity；未绑定为 null
         */
        public Object worldIdentity() {
            return worldIdentity;
        }
    }

    /**
     * disconnect 结果：仅成功转移时 {@link #transitioned} 为 true，并携带 cleanup token。
     */
    public static final class DisconnectResult {
        private final boolean transitioned;
        private final Token token;

        private DisconnectResult(boolean transitioned, Token token) {
            this.transitioned = transitioned;
            this.token = token;
        }

        /**
         * @return 是否从 active 转移到 inactive（仅此时应排队 cleanup）
         */
        public boolean transitioned() {
            return transitioned;
        }

        /**
         * @return 转移后的 inactive token；未转移时为当时 current
         */
        public Token token() {
            return token;
        }
    }

    /**
     * world unbind 结果：仅成功转移时 {@link #transitioned} 为 true。
     */
    public static final class WorldUnbindResult {
        private final boolean transitioned;
        private final Token token;

        private WorldUnbindResult(boolean transitioned, Token token) {
            this.transitioned = transitioned;
            this.token = token;
        }

        /**
         * @return 是否从 world-active 转移到 world-inactive
         */
        public boolean transitioned() {
            return transitioned;
        }

        /**
         * @return 转移后的 world-inactive token（连接仍 active）
         */
        public Token token() {
            return token;
        }
    }

    private static final Token INITIAL = new Token(0L, 0L, false, false, null, null);
    private static final AtomicReference<Token> CURRENT = new AtomicReference<Token>(INITIAL);
    private static final AtomicLong NEXT_CONNECTION_GENERATION = new AtomicLong(1L);
    private static final AtomicLong NEXT_WORLD_GENERATION = new AtomicLong(1L);

    /**
     * 所有 lifecycle 转移与 gate 内执行的统一线性化边界。
     *
     * <p>持锁期间只做 token 读写与短小本地回调，禁止阻塞与反向获取本锁。</p>
     */
    private static final Object LIFECYCLE_MONITOR = new Object();

    private ClientConnectionLifecycle() {
    }

    /**
     * 连接成功：以 handler 对象 identity（{@code ==}）绑定 active connection token。
     *
     * <ul>
     *   <li>相同 active handler 重复事件：no-op，返回当前 token</li>
     *   <li>不同 handler：创建新 active connection token（新 connectionGeneration）</li>
     * </ul>
     *
     * @param handler 连接 identity（生产为 {@code event.handler} / {@code INetHandler}）
     * @return 当前 connection token；handler 为 null 时返回 current 且不推进
     */
    public static Token connect(Object handler) {
        if (handler == null) {
            return CURRENT.get();
        }
        synchronized (LIFECYCLE_MONITOR) {
            Token current = CURRENT.get();
            if (current.connectionActive && current.connectionIdentity == handler) {
                return current;
            }
            long connGen = nextPositiveId(NEXT_CONNECTION_GENERATION);
            long worldGen = nextPositiveId(NEXT_WORLD_GENERATION);
            Token next = new Token(connGen, worldGen, true, false, handler, null);
            CURRENT.set(next);
            return next;
        }
    }

    /**
     * 断线：仅当 handler 为当前连接 identity 且连接仍 active 时转为 inactive。
     *
     * <p>旧连接迟到 / 重复 disconnect：no-op，且不得调度新清理。</p>
     *
     * @param handler 断线事件的 handler
     * @return 是否成功转移及 cleanup token
     */
    public static DisconnectResult disconnect(Object handler) {
        if (handler == null) {
            return new DisconnectResult(false, CURRENT.get());
        }
        synchronized (LIFECYCLE_MONITOR) {
            Token current = CURRENT.get();
            if (!current.connectionActive || current.connectionIdentity != handler) {
                return new DisconnectResult(false, current);
            }
            long worldGen = nextPositiveId(NEXT_WORLD_GENERATION);
            Token next = new Token(
                    current.connectionGeneration,
                    worldGen,
                    false,
                    false,
                    handler,
                    null);
            CURRENT.set(next);
            return new DisconnectResult(true, next);
        }
    }

    /**
     * 绑定客户端远端 world 对象。
     *
     * <p>首次绑定推进 worldGeneration 并设 worldActive，不改变 connectionGeneration
     * （连接级 config token 不被误废弃）。世界替换推进 worldGeneration，旧 world token 失效。
     * 连接非 active 时 no-op。相同 world 重复 load no-op。</p>
     *
     * @param world 客户端 world 对象
     * @return 绑定后的 current token
     */
    public static Token bindWorld(Object world) {
        if (world == null) {
            return CURRENT.get();
        }
        synchronized (LIFECYCLE_MONITOR) {
            Token current = CURRENT.get();
            if (!current.connectionActive) {
                return current;
            }
            if (current.worldActive && current.worldIdentity == world) {
                return current;
            }
            long worldGen = nextPositiveId(NEXT_WORLD_GENERATION);
            Token next = new Token(
                    current.connectionGeneration,
                    worldGen,
                    true,
                    true,
                    current.connectionIdentity,
                    world);
            CURRENT.set(next);
            return next;
        }
    }

    /**
     * 解绑 world：仅当 event.world 为当前 world identity 且连接仍 current/active 时推进。
     *
     * <p>旧 world / 重复 unload / disconnect 后 unload：no-op。
     * 成功时连接保持 active，world 变为 inactive（同连接切维度后旧 world token 失效）。</p>
     *
     * @param world 卸载的 world
     * @return 是否成功转移及 cleanup token
     */
    public static WorldUnbindResult unbindWorld(Object world) {
        if (world == null) {
            return new WorldUnbindResult(false, CURRENT.get());
        }
        synchronized (LIFECYCLE_MONITOR) {
            Token current = CURRENT.get();
            if (!current.connectionActive
                    || !current.worldActive
                    || current.worldIdentity != world) {
                return new WorldUnbindResult(false, current);
            }
            long worldGen = nextPositiveId(NEXT_WORLD_GENERATION);
            Token next = new Token(
                    current.connectionGeneration,
                    worldGen,
                    true,
                    false,
                    current.connectionIdentity,
                    null);
            CURRENT.set(next);
            return new WorldUnbindResult(true, next);
        }
    }

    /**
     * 只读捕获当前 token（不推进）。
     *
     * @return 当前 token（永不为 null）
     */
    public static Token capture() {
        return CURRENT.get();
    }

    /**
     * 按连接 identity 捕获：仅当 handler 为当前连接且 connection active 时返回 current。
     *
     * <p>Netty 入包路径：用 {@code ctx.netHandler} 捕获，避免全局 capture 误绑新连接。</p>
     *
     * @param handler 包上下文 netHandler
     * @return 匹配的 current token；不匹配或 inactive 时为 null
     */
    public static Token captureForConnection(Object handler) {
        if (handler == null) {
            return null;
        }
        Token current = CURRENT.get();
        if (!current.connectionActive || current.connectionIdentity != handler) {
            return null;
        }
        return current;
    }

    /**
     * 连接级只读：captured 的 connection 仍为 current 且 active。
     *
     * <p>生产 publication 请用 {@link #publishIfConnectionCurrentAndActive}，避免 check 后裸写。</p>
     *
     * @param captured 入队时捕获的 token
     * @return 仍有效时为 true
     */
    public static boolean isConnectionCurrentAndActive(Token captured) {
        if (captured == null || !captured.connectionActive) {
            return false;
        }
        Token current = CURRENT.get();
        return current.connectionActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.connectionIdentity == captured.connectionIdentity;
    }

    /**
     * 兼容旧名：等同 {@link #isConnectionCurrentAndActive(Token)}。
     *
     * @param captured 入队 token
     * @return 连接仍 current 且 active
     */
    public static boolean isCurrentAndActive(Token captured) {
        return isConnectionCurrentAndActive(captured);
    }

    /**
     * 世界级只读：连接与 world 均仍为 current 且 active。
     *
     * @param captured 入队 token
     * @return 仍有效时为 true
     */
    public static boolean isWorldCurrentAndActive(Token captured) {
        if (captured == null || !captured.connectionActive || !captured.worldActive) {
            return false;
        }
        Token current = CURRENT.get();
        return current.connectionActive
                && current.worldActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.worldGeneration == captured.worldGeneration
                && current.connectionIdentity == captured.connectionIdentity
                && current.worldIdentity == captured.worldIdentity;
    }

    /**
     * disconnect cleanup 只读：inactive token 的 connectionGeneration 仍为 current。
     *
     * @param captured disconnect 成功转移产生的 inactive token
     * @return 仍为 current inactive 时为 true
     */
    public static boolean isInactiveDisconnectCurrent(Token captured) {
        if (captured == null || captured.connectionActive) {
            return false;
        }
        Token current = CURRENT.get();
        return !current.connectionActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.connectionIdentity == captured.connectionIdentity;
    }

    /**
     * world unbind cleanup 只读：该次 unbind 的 world-inactive token 仍为 current。
     *
     * @param captured unbind 成功转移产生的 token
     * @return 仍为 current 时为 true
     */
    public static boolean isWorldUnbindCurrent(Token captured) {
        if (captured == null || !captured.connectionActive || captured.worldActive) {
            return false;
        }
        Token current = CURRENT.get();
        return current.connectionActive
                && !current.worldActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.worldGeneration == captured.worldGeneration
                && current.connectionIdentity == captured.connectionIdentity;
    }

    /**
     * 连接级 publication gate：check 与短小 publication 共享 monitor。
     *
     * <p>用于 config 三字段等已知短小本地写。禁阻塞、禁反向 lifecycle 入口。</p>
     *
     * @param captured 入队 token
     * @param publication 短小本地 publication
     * @return 已执行时为 true
     */
    public static boolean publishIfConnectionCurrentAndActive(Token captured, Runnable publication) {
        if (publication == null) {
            throw new IllegalArgumentException("publication must not be null");
        }
        if (captured == null || !captured.connectionActive) {
            return false;
        }
        synchronized (LIFECYCLE_MONITOR) {
            if (!isConnectionCurrentAndActiveLocked(captured)) {
                return false;
            }
            publication.run();
            return true;
        }
    }

    /**
     * 兼容旧名：等同 {@link #publishIfConnectionCurrentAndActive}。
     *
     * @param captured 入队 token
     * @param publication 短小 publication
     * @return 已执行时为 true
     */
    public static boolean publishIfCurrentAndActive(Token captured, Runnable publication) {
        return publishIfConnectionCurrentAndActive(captured, publication);
    }

    /**
     * 世界级 gate：连接与 world 仍 current+active 时在 monitor 内执行短回调。
     *
     * <p>用于 phase event-bus offer、preview 应用等主线程短动作。
     * 回调禁阻塞、禁反向 lifecycle 入口；生产路径须已在客户端主线程。</p>
     *
     * @param captured 入队 token
     * @param action 短小动作
     * @return 已执行时为 true
     */
    public static boolean runIfWorldCurrentAndActive(Token captured, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (captured == null || !captured.connectionActive || !captured.worldActive) {
            return false;
        }
        synchronized (LIFECYCLE_MONITOR) {
            if (!isWorldCurrentAndActiveLocked(captured)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * 连接级 gate：connection current+active 时在 monitor 内执行短回调。
     *
     * <p>用于 connect init（reset 投影 + 发 C2S）。</p>
     *
     * @param captured connect 返回的 token
     * @param action 短小动作
     * @return 已执行时为 true
     */
    public static boolean runIfConnectionCurrentAndActive(Token captured, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (captured == null || !captured.connectionActive) {
            return false;
        }
        synchronized (LIFECYCLE_MONITOR) {
            if (!isConnectionCurrentAndActiveLocked(captured)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * disconnect cleanup gate：仅 inactive 且仍为 current 时在 monitor 内执行。
     *
     * <p>B connect 后 A cleanup no-op。回调须短小（停预览/清 pending/清 phase），
     * 禁阻塞、禁反向 lifecycle。</p>
     *
     * @param captured disconnect 成功转移 token
     * @param action cleanup
     * @return 已执行时为 true
     */
    public static boolean runIfInactiveDisconnectCurrent(Token captured, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (captured == null || captured.connectionActive) {
            return false;
        }
        synchronized (LIFECYCLE_MONITOR) {
            if (!isInactiveDisconnectCurrentLocked(captured)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * world unbind cleanup gate：该次 unbind token 仍 current 时在 monitor 内执行。
     *
     * <p>B world/connection 建立后旧 cleanup no-op。</p>
     *
     * @param captured unbind 成功转移 token
     * @param action cleanup
     * @return 已执行时为 true
     */
    public static boolean runIfWorldUnbindCurrent(Token captured, Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (captured == null || !captured.connectionActive || captured.worldActive) {
            return false;
        }
        synchronized (LIFECYCLE_MONITOR) {
            if (!isWorldUnbindCurrentLocked(captured)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * 测试钩子：恢复初始 inactive token。
     */
    static void resetForTests() {
        synchronized (LIFECYCLE_MONITOR) {
            CURRENT.set(INITIAL);
            NEXT_CONNECTION_GENERATION.set(1L);
            NEXT_WORLD_GENERATION.set(1L);
        }
    }

    /**
     * 测试钩子：暴露 monitor，供确定性并发测试。
     *
     * @return lifecycle 线性化 monitor
     */
    static Object lifecycleMonitorForTests() {
        return LIFECYCLE_MONITOR;
    }

    private static boolean isConnectionCurrentAndActiveLocked(Token captured) {
        Token current = CURRENT.get();
        return captured.connectionActive
                && current.connectionActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.connectionIdentity == captured.connectionIdentity;
    }

    private static boolean isWorldCurrentAndActiveLocked(Token captured) {
        Token current = CURRENT.get();
        return captured.connectionActive
                && captured.worldActive
                && current.connectionActive
                && current.worldActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.worldGeneration == captured.worldGeneration
                && current.connectionIdentity == captured.connectionIdentity
                && current.worldIdentity == captured.worldIdentity;
    }

    private static boolean isInactiveDisconnectCurrentLocked(Token captured) {
        Token current = CURRENT.get();
        return !captured.connectionActive
                && !current.connectionActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.connectionIdentity == captured.connectionIdentity;
    }

    private static boolean isWorldUnbindCurrentLocked(Token captured) {
        Token current = CURRENT.get();
        return captured.connectionActive
                && !captured.worldActive
                && current.connectionActive
                && !current.worldActive
                && current.connectionGeneration == captured.connectionGeneration
                && current.worldGeneration == captured.worldGeneration
                && current.connectionIdentity == captured.connectionIdentity;
    }

    private static long nextPositiveId(AtomicLong counter) {
        long id = counter.getAndIncrement();
        if (id <= 0L) {
            id = counter.incrementAndGet();
            if (id <= 0L) {
                id = 1L;
                counter.set(2L);
            }
        }
        return id;
    }
}
