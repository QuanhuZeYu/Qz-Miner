package club.heiqi.qz_miner.network;

import java.lang.ref.WeakReference;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.thread.KeyedLatestTaskLane;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 对象组 C2S 的服务端 latest-wins 调度；解析、revision 检查和状态发布都在主线程。
 */
public final class ServerObjectGroupConfigRequestDispatch {

    private ServerObjectGroupConfigRequestDispatch() {
    }

    /** 生产入口：Netty 线程只提交弱 identity 和有界 raw 值。 */
    public static boolean submit(final EntityPlayerMP player, final ObjectGroupWireConfig payload) {
        if (player == null) {
            return false;
        }
        return submit(player.getUniqueID(), player, payload,
                new KeyedDispatcher() {
                    @Override
                    public boolean tryRunLatest(Object key, Runnable task) {
                        return ServerMainThreadDispatcher.tryRunLatest(key, task);
                    }
                },
                new PlayerLookup() {
                    @Override
                    public Object getPlayer(UUID uuid) {
                        return MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(uuid);
                    }
                },
                new RevisionLookup() {
                    @Override
                    public long currentRevision(UUID uuid) {
                        ChainPlayerState state = MyMod.chainStateService == null
                                ? null : MyMod.chainStateService.getPlayerState(uuid);
                        return state == null ? 0L : state.getObjectGroupRevision();
                    }
                },
                new StateWriter() {
                    @Override
                    public void write(UUID uuid, Object endpoint, ObjectGroupRuleSet rules, long revision) {
                        if (MyMod.chainStateService == null) {
                            return;
                        }
                        MyMod.chainStateService.getOrCreatePlayerState(uuid).setObjectGroupRules(rules, revision);
                    }
                },
                new AckSender() {
                    @Override
                    public void send(UUID uuid, Object endpoint, long requestedRevision,
                            long authoritativeRevision, boolean accepted, int groupCount) {
                        if (MyMod.networkMain != null && endpoint instanceof EntityPlayerMP) {
                            MyMod.networkMain.network.sendTo(new PacketObjectGroupConfigSync(
                                    ObjectGroupWireConfig.PROTOCOL_VERSION, requestedRevision,
                                    authoritativeRevision, accepted, groupCount),
                                    (EntityPlayerMP) endpoint);
                        }
                    }
                });
    }

    /** 可注入边界，供纯 JVM 覆盖 identity/latest-wins/非法保留旧值。 */
    public static boolean submit(
            final UUID uuid, final Object endpoint, final ObjectGroupWireConfig payload,
            KeyedDispatcher dispatcher, final PlayerLookup lookup, final RevisionLookup revisions,
            final StateWriter writer, final AckSender ackSender) {
        if (uuid == null || endpoint == null || dispatcher == null || lookup == null
                || revisions == null || writer == null || ackSender == null) {
            return false;
        }
        final EndpointKey key = new EndpointKey(uuid, endpoint);
        final WeakReference<Object> weakEndpoint = new WeakReference<Object>(endpoint);
        return dispatcher.tryRunLatest(key, new Runnable() {
            @Override
            public void run() {
                consume(uuid, weakEndpoint, payload, lookup, revisions, writer, ackSender);
            }
        });
    }

    private static void consume(UUID uuid, WeakReference<Object> endpointRef, ObjectGroupWireConfig payload,
            PlayerLookup lookup, RevisionLookup revisions, StateWriter writer, AckSender ackSender) {
        Object endpoint = endpointRef.get();
        Object current = endpoint == null ? null : lookup.getPlayer(uuid);
        if (endpoint == null || current != endpoint) {
            return;
        }
        long oldRevision = revisions.currentRevision(uuid);
        long requestedRevision = payload == null ? -1L : payload.revision();
        if (payload == null || !payload.isValid()
                || payload.protocolVersion() != ObjectGroupWireConfig.PROTOCOL_VERSION
                || payload.revision() <= oldRevision) {
            ackSender.send(uuid, endpoint, requestedRevision, oldRevision, false, 0);
            return;
        }
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(payload);
        if (!parsed.isValid()) {
            ackSender.send(uuid, endpoint, requestedRevision, oldRevision, false, 0);
            return;
        }
        ObjectGroupRuleSet rules = parsed.rules();
        writer.write(uuid, endpoint, rules, payload.revision());
        ackSender.send(uuid, endpoint, requestedRevision, payload.revision(), true, rules.groups().size());
    }

    public interface KeyedDispatcher {
        boolean tryRunLatest(Object key, Runnable task);
    }

    public interface PlayerLookup {
        Object getPlayer(UUID uuid);
    }

    public interface RevisionLookup {
        long currentRevision(UUID uuid);
    }

    public interface StateWriter {
        void write(UUID uuid, Object endpoint, ObjectGroupRuleSet rules, long revision);
    }

    public interface AckSender {
        void send(UUID uuid, Object endpoint, long requestedRevision, long authoritativeRevision,
                boolean accepted, int groupCount);
    }

    /** 与已有配置 C2S 一致的弱 identity endpoint key。 */
    public static final class EndpointKey implements KeyedLatestTaskLane.StaleDetectableKey {
        private final UUID uuid;
        private final WeakReference<Object> endpoint;
        private final int identityHash;

        public EndpointKey(UUID uuid, Object endpoint) {
            if (uuid == null || endpoint == null) {
                throw new IllegalArgumentException("uuid/endpoint must not be null");
            }
            this.uuid = uuid;
            this.endpoint = new WeakReference<Object>(endpoint);
            this.identityHash = System.identityHashCode(endpoint);
        }

        @Override
        public boolean isStale() {
            return endpoint.get() == null;
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
            Object a = endpoint.get();
            Object b = that.endpoint.get();
            return uuid.equals(that.uuid) && a != null && b != null && a == b;
        }

        @Override
        public int hashCode() {
            return 31 * uuid.hashCode() + identityHash;
        }
    }
}
