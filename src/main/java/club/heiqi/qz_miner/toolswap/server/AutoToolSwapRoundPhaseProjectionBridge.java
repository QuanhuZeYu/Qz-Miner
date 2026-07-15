package club.heiqi.qz_miner.toolswap.server;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.network.PacketAutoToolSwapRoundPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 将不可变连锁阶段广播投影为工具换位 round 阶段包的服务端桥。
 *
 * <p>本桥只消费事件自带的 {@code serverRoundId}，由 round service 精确校验 endpoint 与 round；
 * 不读取可变的当前 round，因此陈旧事件不能被重新关联到新 round。</p>
 */
public final class AutoToolSwapRoundPhaseProjectionBridge {

    private final AutoToolSwapRoundService roundService;
    private final PlayerLookup playerLookup;
    private final PacketSender packetSender;

    /** 使用生产 endpoint 查询与发包边界构造桥。 */
    public AutoToolSwapRoundPhaseProjectionBridge(ChainEventBus bus, AutoToolSwapRoundService roundService) {
        this(bus, roundService, productionPlayerLookup(), productionPacketSender());
    }

    /** 使用可注入边界构造桥，供纯 JVM 测试与未来装配使用。 */
    AutoToolSwapRoundPhaseProjectionBridge(ChainEventBus bus, AutoToolSwapRoundService roundService,
            PlayerLookup playerLookup, PacketSender packetSender) {
        if (bus == null || roundService == null || playerLookup == null || packetSender == null) {
            throw new IllegalArgumentException("bridge dependencies must not be null");
        }
        this.roundService = roundService;
        this.playerLookup = playerLookup;
        this.packetSender = packetSender;
        bus.subscribe(ChainPhaseChanged.class, this::onPhaseChanged);
    }

    /**
     * 在服务端主线程 event drain 中投影单个阶段广播。
     *
     * @param event 状态机已完成转移后的不可变广播
     */
    private void onPhaseChanged(ChainPhaseChanged event) {
        if (event == null || event.getPlayerUUID() == null
                || event.getServerRoundId() == AutoToolSwapProtocol.NO_SERVER_ROUND_ID) {
            return;
        }
        UUID playerId = event.getPlayerUUID();
        Object endpoint = playerLookup.getPlayer(playerId);
        if (endpoint == null) {
            return;
        }
        ChainPhase toPhase = event.getToPhase();
        long phaseSequence = roundService.observeChainPhase(playerId, endpoint, event.getServerRoundId(),
                freezesSwap(toPhase), closesRound(toPhase));
        if (phaseSequence == AutoToolSwapRoundService.NO_PHASE_SEQUENCE) {
            return;
        }
        packetSender.send(playerId, endpoint, new PacketAutoToolSwapRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION,
                event.getServerRoundId(), phaseSequence, event.getToPhaseOrdinal(), event.getGeneration(),
                event.getServerTick()));
    }

    private static boolean freezesSwap(ChainPhase phase) {
        return phase == ChainPhase.PLANNING || phase == ChainPhase.RUNNING || phase == ChainPhase.FINISHING;
    }

    private static boolean closesRound(ChainPhase phase) {
        return phase == ChainPhase.IDLE;
    }

    private static PlayerLookup productionPlayerLookup() {
        return new PlayerLookup() {
            @Override
            public Object getPlayer(UUID playerId) {
                return MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerId);
            }
        };
    }

    private static PacketSender productionPacketSender() {
        return new PacketSender() {
            @Override
            public void send(UUID playerId, Object endpoint, PacketAutoToolSwapRoundPhase packet) {
                if (MyMod.networkMain != null && endpoint instanceof EntityPlayerMP) {
                    MyMod.networkMain.network.sendTo(packet, (EntityPlayerMP) endpoint);
                }
            }
        };
    }

    /** 按 UUID 查询当前在线 endpoint 的边界。 */
    interface PlayerLookup {
        Object getPlayer(UUID playerId);
    }

    /** round phase 包的下发边界。 */
    interface PacketSender {
        void send(UUID playerId, Object endpoint, PacketAutoToolSwapRoundPhase packet);
    }
}
