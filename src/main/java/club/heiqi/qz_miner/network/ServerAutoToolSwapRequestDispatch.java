package club.heiqi.qz_miner.network;

import java.lang.ref.WeakReference;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapInventoryPort;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapRoundService;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 自动工具换位 C2S 请求的 FIFO 主线程收口。
 *
 * <p>Netty 线程只捕获 UUID、弱 endpoint identity 和原始 wire 值。主线程重新从
 * {@code PlayerManager} 获取同一实例后，才执行协议解码和 projection 核心调用。严格 5.2 intent
 * 只有 FREEZE/CLOSE，不创建或读取库存端口。</p>
 */
public final class ServerAutoToolSwapRequestDispatch {

    private ServerAutoToolSwapRequestDispatch() {
    }

    /** 生产 round start 入口。 */
    public static boolean submitRoundStart(EntityPlayerMP player, int protocolVersion, long clientNonce,
            boolean rawValid) {
        if (player == null) {
            return false;
        }
        return submitRoundStart(player.getUniqueID(), player, protocolVersion, clientNonce, rawValid,
                productionFifo(), productionPlayerLookup(), productionRoundService(), productionTickSource(),
                productionRoundResultSender());
    }

    /** 生产 intent 入口。 */
    public static boolean submitIntent(EntityPlayerMP player, int protocolVersion, long serverRoundId,
            long actionSequence, int actionCode, int anchorSlot, int candidateSlot,
            long anchorFingerprintFirst, long anchorFingerprintSecond, long anchorFingerprintThird,
            long anchorFingerprintFourth, long candidateFingerprintFirst, long candidateFingerprintSecond,
            long candidateFingerprintThird, long candidateFingerprintFourth, boolean rawValid) {
        if (player == null) {
            return false;
        }
        return submitIntent(player.getUniqueID(), player, protocolVersion, serverRoundId, actionSequence,
                actionCode, anchorSlot, candidateSlot, anchorFingerprintFirst, anchorFingerprintSecond,
                anchorFingerprintThird, anchorFingerprintFourth, candidateFingerprintFirst,
                candidateFingerprintSecond, candidateFingerprintThird, candidateFingerprintFourth, rawValid,
                productionFifo(), productionPlayerLookup(), productionRoundService(),
                productionTickSource(), productionActionResultSender());
    }

    /** 纯 JVM round start 边界，普通 FIFO 必须保留每一条请求。 */
    public static boolean submitRoundStart(final UUID playerId, final Object endpoint, final int protocolVersion,
            final long clientNonce, final boolean rawValid, FifoDispatcher dispatcher, final PlayerLookup lookup,
            final RoundService service, final TickSource tickSource, final RoundResultSender sender) {
        if (playerId == null || endpoint == null || dispatcher == null || lookup == null || service == null
                || tickSource == null || sender == null) {
            return false;
        }
        final WeakReference<Object> weakEndpoint = new WeakReference<Object>(endpoint);
        return dispatcher.tryRun(new Runnable() {
            @Override
            public void run() {
                consumeRoundStart(playerId, weakEndpoint, protocolVersion, clientNonce, rawValid, lookup, service,
                        tickSource, sender);
            }
        });
    }

    /** 纯 JVM intent 边界，Netty 捕获的字段在此之前始终保持 raw。 */
    public static boolean submitIntent(final UUID playerId, final Object endpoint, final int protocolVersion,
            final long serverRoundId, final long actionSequence, final int actionCode, final int anchorSlot,
            final int candidateSlot, final long anchorFingerprintFirst, final long anchorFingerprintSecond,
            final long anchorFingerprintThird, final long anchorFingerprintFourth,
            final long candidateFingerprintFirst, final long candidateFingerprintSecond,
            final long candidateFingerprintThird, final long candidateFingerprintFourth, final boolean rawValid,
            FifoDispatcher dispatcher, final PlayerLookup lookup, final RoundService service,
            final TickSource tickSource, final ActionResultSender sender) {
        if (playerId == null || endpoint == null || dispatcher == null || lookup == null || service == null
                || tickSource == null || sender == null) {
            return false;
        }
        final RawIntent rawIntent = new RawIntent(protocolVersion, serverRoundId, actionSequence, actionCode,
                anchorSlot, candidateSlot, anchorFingerprintFirst, anchorFingerprintSecond, anchorFingerprintThird,
                anchorFingerprintFourth, candidateFingerprintFirst, candidateFingerprintSecond,
                candidateFingerprintThird, candidateFingerprintFourth, rawValid);
        final WeakReference<Object> weakEndpoint = new WeakReference<Object>(endpoint);
        return dispatcher.tryRun(new Runnable() {
            @Override
            public void run() {
                consumeIntent(playerId, weakEndpoint, rawIntent, lookup, service, tickSource,
                        sender);
            }
        });
    }

    private static void consumeRoundStart(UUID playerId, WeakReference<Object> endpointReference,
            int protocolVersion, long clientNonce, boolean rawValid, PlayerLookup lookup, RoundService service,
            TickSource tickSource, RoundResultSender sender) {
        Object endpoint = matchingEndpoint(playerId, endpointReference, lookup);
        if (endpoint == null) {
            return;
        }
        long serverTick = safeServerTick(tickSource);
        if (!rawValid || protocolVersion != AutoToolSwapProtocol.PROTOCOL_VERSION || clientNonce == 0L) {
            sender.send(playerId, endpoint, clientNonce, rejectedPending(serverTick));
            return;
        }
        AutoToolSwapRoundResult result = service.beginRound(playerId, endpoint, clientNonce, serverTick);
        // 仅新建且尚未分配 roundId 的合法 pending round 等待后续 key 激活。
        if (result.outcome() != AutoToolSwapResultCode.ACCEPTED
                || result.roundState() != AutoToolSwapRoundState.PENDING_KEY
                || result.serverRoundId() != AutoToolSwapProtocol.NO_SERVER_ROUND_ID) {
            sender.send(playerId, endpoint, clientNonce, result);
        }
    }

    private static void consumeIntent(UUID playerId, WeakReference<Object> endpointReference, RawIntent rawIntent,
            PlayerLookup lookup, RoundService service, TickSource tickSource,
            ActionResultSender sender) {
        Object endpoint = matchingEndpoint(playerId, endpointReference, lookup);
        if (endpoint == null) {
            return;
        }
        long serverTick = safeServerTick(tickSource);
        AutoToolSwapIntent intent = decodeIntent(rawIntent);
        if (intent == null) {
            sender.send(playerId, endpoint, rawIntent, rejectedOrphaned(rawIntent.serverRoundId, serverTick));
            return;
        }
        // identity/raw/action 已全部通过后仍不创建 inventory port：FREEZE/CLOSE 只推进 projection。
        AutoToolSwapRoundResult result = service.handleIntent(playerId, endpoint, intent, null, serverTick);
        try {
            sender.send(playerId, endpoint, rawIntent, result);
        } catch (RuntimeException publicationFailure) {
            // sender 未正常返回时不得确认 sequence/gate；exact C2S 重试只会重做 full resend + result send。
            return;
        } catch (LinkageError publicationFailure) {
            return;
        }
        service.confirmIntentResultPublication(playerId, endpoint, intent, result);
    }

    private static Object matchingEndpoint(UUID playerId, WeakReference<Object> endpointReference, PlayerLookup lookup) {
        Object endpoint = endpointReference.get();
        return endpoint != null && lookup.getPlayer(playerId) == endpoint ? endpoint : null;
    }

    private static AutoToolSwapIntent decodeIntent(RawIntent raw) {
        if (!raw.rawValid || raw.protocolVersion != AutoToolSwapProtocol.PROTOCOL_VERSION) {
            return null;
        }
        try {
            return new AutoToolSwapIntent(raw.protocolVersion, raw.serverRoundId, raw.actionSequence,
                    AutoToolSwapAction.fromWireCode(raw.actionCode), raw.anchorSlot, raw.candidateSlot,
                    AutoToolSwapContentFingerprint.fromWire(raw.anchorFingerprintFirst, raw.anchorFingerprintSecond,
                            raw.anchorFingerprintThird, raw.anchorFingerprintFourth),
                    AutoToolSwapContentFingerprint.fromWire(raw.candidateFingerprintFirst,
                            raw.candidateFingerprintSecond, raw.candidateFingerprintThird,
                            raw.candidateFingerprintFourth));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static AutoToolSwapRoundResult rejectedPending(long serverTick) {
        return new AutoToolSwapRoundResult(AutoToolSwapProtocol.NO_SERVER_ROUND_ID,
                AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.PENDING_KEY,
                AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, serverTick);
    }

    private static AutoToolSwapRoundResult rejectedOrphaned(long rawRoundId, long serverTick) {
        return new AutoToolSwapRoundResult(rawRoundId < 0L ? AutoToolSwapProtocol.NO_SERVER_ROUND_ID : rawRoundId,
                AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.ORPHANED,
                AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, serverTick);
    }

    private static long safeServerTick(TickSource tickSource) {
        return Math.max(0L, tickSource.currentServerTick());
    }

    private static FifoDispatcher productionFifo() {
        return new FifoDispatcher() {
            @Override
            public boolean tryRun(Runnable task) {
                return ServerMainThreadDispatcher.tryRun(task);
            }
        };
    }

    private static PlayerLookup productionPlayerLookup() {
        return new PlayerLookup() {
            @Override
            public Object getPlayer(UUID playerId) {
                return MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerId);
            }
        };
    }

    private static RoundService productionRoundService() {
        final AutoToolSwapRoundService roundService = MyMod.autoToolSwapRoundService;
        if (roundService == null) {
            return null;
        }
        return new RoundService() {
            @Override
            public AutoToolSwapRoundResult beginRound(UUID playerId, Object endpoint, long clientNonce,
                    long serverTick) {
                return roundService.beginRound(playerId, endpoint, clientNonce, serverTick);
            }

            @Override
            public AutoToolSwapRoundResult handleIntent(UUID playerId, Object endpoint, AutoToolSwapIntent intent,
                    AutoToolSwapInventoryPort inventory, long serverTick) {
                return roundService.handleIntent(playerId, endpoint, intent, inventory, serverTick);
            }

            @Override
            public boolean confirmIntentResultPublication(UUID playerId, Object endpoint,
                    AutoToolSwapIntent intent, AutoToolSwapRoundResult result) {
                return roundService.confirmIntentResultPublication(playerId, endpoint, intent, result);
            }
        };
    }

    private static TickSource productionTickSource() {
        return new TickSource() {
            @Override
            public long currentServerTick() {
                return ChainTickSource.currentServerTick();
            }
        };
    }

    private static RoundResultSender productionRoundResultSender() {
        return new RoundResultSender() {
            @Override
            public void send(UUID playerId, Object endpoint, long clientNonce, AutoToolSwapRoundResult result) {
                if (MyMod.networkMain != null && endpoint instanceof EntityPlayerMP) {
                    MyMod.networkMain.network.sendTo(new PacketAutoToolSwapRoundResult(
                            AutoToolSwapProtocol.PROTOCOL_VERSION, clientNonce, result), (EntityPlayerMP) endpoint);
                }
            }
        };
    }

    private static ActionResultSender productionActionResultSender() {
        return new ActionResultSender() {
            @Override
            public void send(UUID playerId, Object endpoint, RawIntent rawIntent, AutoToolSwapRoundResult result) {
                if (MyMod.networkMain != null && endpoint instanceof EntityPlayerMP) {
                    MyMod.networkMain.network.sendTo(new PacketAutoToolSwapActionResult(
                            rawIntent.protocolVersion, result.serverRoundId(), rawIntent.actionSequence,
                            rawIntent.actionCode, rawIntent.anchorSlot, rawIntent.candidateSlot, result),
                            (EntityPlayerMP) endpoint);
                }
            }
        };
    }

    /** 普通 FIFO 的可注入主线程边界。 */
    public interface FifoDispatcher {
        boolean tryRun(Runnable task);
    }

    /** 按 UUID 重取当前玩家实例的边界。 */
    public interface PlayerLookup {
        Object getPlayer(UUID playerId);
    }

    /** 事务核心的可注入边界。 */
    public interface RoundService {
        AutoToolSwapRoundResult beginRound(UUID playerId, Object endpoint, long clientNonce, long serverTick);

        AutoToolSwapRoundResult handleIntent(UUID playerId, Object endpoint, AutoToolSwapIntent intent,
                AutoToolSwapInventoryPort inventory, long serverTick);

        boolean confirmIntentResultPublication(UUID playerId, Object endpoint,
                AutoToolSwapIntent intent, AutoToolSwapRoundResult result);
    }

    /** 服务端权威 tick 边界。 */
    public interface TickSource {
        long currentServerTick();
    }

    /** round result 下发边界。 */
    public interface RoundResultSender {
        void send(UUID playerId, Object endpoint, long clientNonce, AutoToolSwapRoundResult result);
    }

    /** action result 下发边界。 */
    public interface ActionResultSender {
        void send(UUID playerId, Object endpoint, RawIntent rawIntent, AutoToolSwapRoundResult result);
    }

    /** 仅保存 C2S 固定帧原始字段，不含 enum 或库存对象。 */
    public static final class RawIntent {
        public final int protocolVersion;
        public final long serverRoundId;
        public final long actionSequence;
        public final int actionCode;
        public final int anchorSlot;
        public final int candidateSlot;
        public final long anchorFingerprintFirst;
        public final long anchorFingerprintSecond;
        public final long anchorFingerprintThird;
        public final long anchorFingerprintFourth;
        public final long candidateFingerprintFirst;
        public final long candidateFingerprintSecond;
        public final long candidateFingerprintThird;
        public final long candidateFingerprintFourth;
        public final boolean rawValid;

        private RawIntent(int protocolVersion, long serverRoundId, long actionSequence, int actionCode,
                int anchorSlot, int candidateSlot, long anchorFingerprintFirst, long anchorFingerprintSecond,
                long anchorFingerprintThird, long anchorFingerprintFourth, long candidateFingerprintFirst,
                long candidateFingerprintSecond, long candidateFingerprintThird, long candidateFingerprintFourth,
                boolean rawValid) {
            this.protocolVersion = protocolVersion;
            this.serverRoundId = serverRoundId;
            this.actionSequence = actionSequence;
            this.actionCode = actionCode;
            this.anchorSlot = anchorSlot;
            this.candidateSlot = candidateSlot;
            this.anchorFingerprintFirst = anchorFingerprintFirst;
            this.anchorFingerprintSecond = anchorFingerprintSecond;
            this.anchorFingerprintThird = anchorFingerprintThird;
            this.anchorFingerprintFourth = anchorFingerprintFourth;
            this.candidateFingerprintFirst = candidateFingerprintFirst;
            this.candidateFingerprintSecond = candidateFingerprintSecond;
            this.candidateFingerprintThird = candidateFingerprintThird;
            this.candidateFingerprintFourth = candidateFingerprintFourth;
            this.rawValid = rawValid;
        }
    }
}
