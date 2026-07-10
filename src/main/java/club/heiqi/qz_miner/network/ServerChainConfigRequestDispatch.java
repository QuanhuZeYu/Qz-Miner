package club.heiqi.qz_miner.network;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.network.ServerChainConfigRequestValidator.Result;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * C2S 连锁配置请求的服务端 keyed 调度适配。
 *
 * <p>Netty 线程只捕获原始 int 与端点身份，经 {@link ServerMainThreadDispatcher#tryRunLatest}
 * 进入与 FIFO 隔离的 latest-wins 泳道。消费时在服务端主线程重取在线玩家，
 * 要求实例身份仍匹配后再整包校验与写入。</p>
 */
public final class ServerChainConfigRequestDispatch {

    private static final long DIAG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(10L);
    private static final AtomicLong CAPACITY_REJECTS = new AtomicLong();
    private static final AtomicLong INVALID_REJECTS = new AtomicLong();
    private static final AtomicLong STALE_REJECTS = new AtomicLong();
    private static final AtomicLong LAST_DIAG_NS = new AtomicLong();

    private ServerChainConfigRequestDispatch() {
    }

    /**
     * 端点键：UUID + 玩家实例身份（identityHashCode），区分重连/克隆后的不同会话。
     */
    public static final class EndpointKey {
        public final UUID uuid;
        public final int playerIdentity;

        /**
         * @param uuid           玩家 UUID
         * @param playerIdentity {@link System#identityHashCode(Object)} 的端点身份
         */
        public EndpointKey(UUID uuid, int playerIdentity) {
            if (uuid == null) {
                throw new IllegalArgumentException("uuid must not be null");
            }
            this.uuid = uuid;
            this.playerIdentity = playerIdentity;
        }

        /**
         * 从玩家实例构造键。
         *
         * @param player 服务端玩家
         * @return 端点键
         */
        public static EndpointKey of(EntityPlayer player) {
            if (player == null) {
                throw new IllegalArgumentException("player must not be null");
            }
            return new EndpointKey(player.getUniqueID(), System.identityHashCode(player));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof EndpointKey)) {
                return false;
            }
            EndpointKey that = (EndpointKey) other;
            return playerIdentity == that.playerIdentity && uuid.equals(that.uuid);
        }

        @Override
        public int hashCode() {
            return 31 * uuid.hashCode() + playerIdentity;
        }

        @Override
        public String toString() {
            return "EndpointKey{uuid=" + uuid + ", id=" + playerIdentity + '}';
        }
    }

    /** keyed 调度边界（测试可注入）。 */
    public interface KeyedDispatcher {
        /**
         * @param key  槽位键
         * @param task 最新任务
         * @return 已接受时为 true
         */
        boolean tryRunLatest(Object key, Runnable task);
    }

    /** 在线玩家查询边界。 */
    public interface PlayerLookup {
        /**
         * @param uuid 玩家 UUID
         * @return 当前在线玩家端点；不在线为 null
         */
        Object getPlayer(UUID uuid);
    }

    /** 服务端配置上限读取边界。 */
    public interface ConfigCaps {
        /** @return 服务端半径上限 */
        int chainRadius();

        /** @return 服务端目标数上限 */
        int chainMaxBlocks();
    }

    /** 校验通过后的状态写入边界。 */
    public interface StateWriter {
        /**
         * @param uuid      玩家 UUID
         * @param radius    已夹紧半径
         * @param maxBlocks 已夹紧上限
         */
        void write(UUID uuid, int radius, int maxBlocks);
    }

    /**
     * 生产路径：捕获端点并提交到服务端 keyed lane。
     *
     * @param player                 服务端玩家端点
     * @param requestedChainRadius   原始半径
     * @param requestedChainMaxBlocks 原始上限
     * @return 任务已接受时为 true；容量拒绝/关闭时为 false
     */
    public static boolean submit(
            EntityPlayerMP player,
            int requestedChainRadius,
            int requestedChainMaxBlocks) {
        if (player == null) {
            return false;
        }
        return submit(
                player.getUniqueID(),
                player,
                requestedChainRadius,
                requestedChainMaxBlocks,
                new KeyedDispatcher() {
                    @Override
                    public boolean tryRunLatest(Object key, Runnable task) {
                        return ServerMainThreadDispatcher.tryRunLatest(key, task);
                    }
                },
                new PlayerLookup() {
                    @Override
                    public Object getPlayer(UUID uuid) {
                        if (MyMod.playerManager == null) {
                            return null;
                        }
                        return MyMod.playerManager.getPlayer(uuid);
                    }
                },
                new ConfigCaps() {
                    @Override
                    public int chainRadius() {
                        return Config.chainRadius;
                    }

                    @Override
                    public int chainMaxBlocks() {
                        return Config.chainMaxBlocks;
                    }
                },
                new StateWriter() {
                    @Override
                    public void write(UUID uuid, int radius, int maxBlocks) {
                        if (MyMod.chainStateService == null) {
                            return;
                        }
                        ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(uuid);
                        state.setRequestedChainRadius(radius);
                        state.setRequestedChainMaxBlocks(maxBlocks);
                    }
                });
    }

    /**
     * 可注入核心路径，便于纯 JVM 测试。
     *
     * <p>pending 任务仅弱持有玩家端点，避免无界强引用存活。</p>
     *
     * @param uuid                    玩家 UUID
     * @param playerEndpoint          捕获时的玩家实例（仅弱持有）
     * @param requestedChainRadius    原始半径
     * @param requestedChainMaxBlocks 原始上限
     * @param dispatcher              keyed 调度
     * @param lookup                  主线程重取玩家
     * @param caps                    服务端上限
     * @param writer                  状态写入
     * @return 任务已接受时为 true
     */
    public static boolean submit(
            final UUID uuid,
            final Object playerEndpoint,
            final int requestedChainRadius,
            final int requestedChainMaxBlocks,
            KeyedDispatcher dispatcher,
            final PlayerLookup lookup,
            final ConfigCaps caps,
            final StateWriter writer) {
        if (uuid == null || playerEndpoint == null || dispatcher == null
                || lookup == null || caps == null || writer == null) {
            return false;
        }
        final EndpointKey key = new EndpointKey(uuid, System.identityHashCode(playerEndpoint));
        final WeakReference<Object> weakEndpoint = new WeakReference<Object>(playerEndpoint);
        boolean accepted = dispatcher.tryRunLatest(key, new Runnable() {
            @Override
            public void run() {
                consume(uuid, weakEndpoint, requestedChainRadius, requestedChainMaxBlocks, lookup, caps, writer);
            }
        });
        if (!accepted) {
            CAPACITY_REJECTS.incrementAndGet();
            maybeLogRejectSummary();
        }
        return accepted;
    }

    private static void consume(
            UUID uuid,
            WeakReference<Object> weakEndpoint,
            int requestedChainRadius,
            int requestedChainMaxBlocks,
            PlayerLookup lookup,
            ConfigCaps caps,
            StateWriter writer) {
        Object captured = weakEndpoint.get();
        if (captured == null) {
            noteStale();
            return;
        }
        Object current = lookup.getPlayer(uuid);
        if (current == null || current != captured) {
            noteStale();
            return;
        }
        Result validated = ServerChainConfigRequestValidator.validateAndClamp(
                requestedChainRadius,
                requestedChainMaxBlocks,
                caps.chainRadius(),
                caps.chainMaxBlocks());
        if (!validated.accepted) {
            noteInvalid();
            return;
        }
        writer.write(uuid, validated.radius, validated.maxBlocks);
    }

    private static void noteInvalid() {
        INVALID_REJECTS.incrementAndGet();
        maybeLogRejectSummary();
    }

    private static void noteStale() {
        STALE_REJECTS.incrementAndGet();
        maybeLogRejectSummary();
    }

    private static void maybeLogRejectSummary() {
        long now = System.nanoTime();
        long previous = LAST_DIAG_NS.get();
        if (previous != 0L && now - previous < DIAG_INTERVAL_NS) {
            return;
        }
        if (!LAST_DIAG_NS.compareAndSet(previous, now)) {
            return;
        }
        long capacity = CAPACITY_REJECTS.getAndSet(0L);
        long invalid = INVALID_REJECTS.getAndSet(0L);
        long stale = STALE_REJECTS.getAndSet(0L);
        if (capacity + invalid + stale == 0L) {
            return;
        }
        MyMod.LOG.warn(
                "[ChainConfig] Request rejects (last ~10s): capacity={} invalid={} stale={}",
                Long.valueOf(capacity),
                Long.valueOf(invalid),
                Long.valueOf(stale));
    }

    /** 测试钩子：重置诊断计数。 */
    static void resetDiagnosticsForTests() {
        CAPACITY_REJECTS.set(0L);
        INVALID_REJECTS.set(0L);
        STALE_REJECTS.set(0L);
        LAST_DIAG_NS.set(0L);
    }
}
