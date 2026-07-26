package club.heiqi.qz_miner.toolswap.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.network.PacketAutoToolSwapRoundPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;

/** 服务端工具换位 round 阶段投影桥的纯 JVM 合同。 */
public class AutoToolSwapRoundPhaseProjectionBridgeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000AA");

    @Test
    public void immutableEventFieldsAndReturnedSequenceDefinePacketAndPhaseEffects() {
        Fixture fixture = fixture(new AutoToolSwapRoundService(0L));

        publish(fixture, fixture.roundId, 17, ChainPhase.IDLE, ChainPhase.ARMED, 41L);
        Assert.assertEquals(1, fixture.sender.packets.size());
        assertPacket(fixture.sender.packets.get(0), fixture.roundId, 1L, ChainPhase.ARMED, 17, 41L);
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, fixture.service.snapshot(PLAYER).roundState());

        publish(fixture, fixture.roundId, 18, ChainPhase.ARMED, ChainPhase.PLANNING, 42L);
        Assert.assertEquals(2, fixture.sender.packets.size());
        assertPacket(fixture.sender.packets.get(1), fixture.roundId, 2L, ChainPhase.PLANNING, 18, 42L);
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, fixture.service.snapshot(PLAYER).roundState());

        Fixture running = fixture(new AutoToolSwapRoundService(0L));
        publish(running, running.roundId, 20, ChainPhase.PLANNING, ChainPhase.RUNNING, 44L);
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, running.service.snapshot(PLAYER).roundState());

        publish(fixture, fixture.roundId, 19, ChainPhase.FINISHING, ChainPhase.IDLE, 43L);
        Assert.assertEquals(3, fixture.sender.packets.size());
        assertPacket(fixture.sender.packets.get(2), fixture.roundId, 3L, ChainPhase.IDLE, 19, 43L);
        Assert.assertEquals(AutoToolSwapRoundState.CLOSING, fixture.service.snapshot(PLAYER).roundState());
        Assert.assertFalse(fixture.service.snapshot(PLAYER).keyDown());
    }

    @Test
    public void staleEndpointTerminalAndOldRoundEventsNeverSendOrRelabelNewRound() {
        Fixture fixture = fixture(new AutoToolSwapRoundService(0L));
        fixture.lookup.endpoint = new Object();
        publish(fixture, fixture.roundId, 1, ChainPhase.ARMED, ChainPhase.PLANNING, 1L);
        Assert.assertTrue(fixture.sender.packets.isEmpty());

        fixture.lookup.endpoint = fixture.endpoint;
        AutoToolSwapIntent close = closeIntent(fixture.roundId, 1L);
        club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult closeResult = fixture.service
                .handleIntent(PLAYER, fixture.endpoint, close, null, 2L);
        Assert.assertTrue(fixture.service.confirmIntentResultPublication(
                PLAYER, fixture.endpoint, close, closeResult));
        publish(fixture, fixture.roundId, 2, ChainPhase.RUNNING, ChainPhase.FINISHING, 3L);
        Assert.assertTrue(fixture.sender.packets.isEmpty());

        fixture.service.beginRound(PLAYER, fixture.endpoint, 2L, 4L);
        long newRoundId = fixture.service.activatePendingRound(PLAYER, fixture.endpoint, 5L).serverRoundId();
        publish(fixture, fixture.roundId, 3, ChainPhase.FINISHING, ChainPhase.IDLE, 6L);
        Assert.assertTrue(fixture.sender.packets.isEmpty());
        Assert.assertEquals(newRoundId, fixture.service.currentRoundId(PLAYER, fixture.endpoint));
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, fixture.service.snapshot(PLAYER).roundState());
    }

    @Test
    public void phaseSequenceOverflowOrphansRoundAndSuppressesPacket() {
        Fixture fixture = fixture(new AutoToolSwapRoundService(0L, AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                Long.MAX_VALUE));
        publish(fixture, fixture.roundId, 1, ChainPhase.ARMED, ChainPhase.PLANNING, 1L);
        Assert.assertTrue(fixture.sender.packets.isEmpty());
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED, fixture.service.snapshot(PLAYER).roundState());
    }

    private static Fixture fixture(AutoToolSwapRoundService service) {
        Object endpoint = new Object();
        service.beginRound(PLAYER, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(PLAYER, endpoint, 1L).serverRoundId();
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        Lookup lookup = new Lookup(endpoint);
        Sender sender = new Sender();
        new AutoToolSwapRoundPhaseProjectionBridge(bus, service, lookup, sender);
        return new Fixture(service, endpoint, roundId, bus, lookup, sender);
    }

    private static void publish(Fixture fixture, long roundId, int generation, ChainPhase from, ChainPhase to,
            long serverTick) {
        fixture.bus.publish(new ChainPhaseChanged(PLAYER, roundId, generation, from, to, serverTick, 99L));
        Assert.assertEquals(1, fixture.bus.drain());
    }

    private static AutoToolSwapIntent closeIntent(long roundId, long sequence) {
        AutoToolSwapStackState stack = AutoToolSwapStackState.occupied("mod:pickaxe",
                AutoToolSwapContentFingerprint.fromContent("mod:pickaxe", "original"), 90);
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, sequence,
                AutoToolSwapAction.CLOSE, 0, 9, stack.contentFingerprint(), stack.contentFingerprint());
    }

    private static void assertPacket(PacketAutoToolSwapRoundPhase packet, long roundId, long phaseSequence,
            ChainPhase phase, int generation, long serverTick) {
        Assert.assertEquals(AutoToolSwapProtocol.PROTOCOL_VERSION, packet.protocolVersion);
        Assert.assertEquals(roundId, packet.serverRoundId);
        Assert.assertEquals(phaseSequence, packet.phaseSequence);
        Assert.assertEquals(phase.ordinal(), packet.phaseOrdinal);
        Assert.assertEquals(generation, packet.generation);
        Assert.assertEquals(serverTick, packet.serverTick);
    }

    private static final class Fixture {
        private final AutoToolSwapRoundService service;
        private final Object endpoint;
        private final long roundId;
        private final ChainEventBus bus;
        private final Lookup lookup;
        private final Sender sender;

        private Fixture(AutoToolSwapRoundService service, Object endpoint, long roundId, ChainEventBus bus,
                Lookup lookup, Sender sender) {
            this.service = service;
            this.endpoint = endpoint;
            this.roundId = roundId;
            this.bus = bus;
            this.lookup = lookup;
            this.sender = sender;
        }
    }

    private static final class Lookup implements AutoToolSwapRoundPhaseProjectionBridge.PlayerLookup {
        private Object endpoint;

        private Lookup(Object endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public Object getPlayer(UUID playerId) {
            return endpoint;
        }
    }

    private static final class Sender implements AutoToolSwapRoundPhaseProjectionBridge.PacketSender {
        private final List<PacketAutoToolSwapRoundPhase> packets = new ArrayList<PacketAutoToolSwapRoundPhase>();

        @Override
        public void send(UUID playerId, Object endpoint, PacketAutoToolSwapRoundPhase packet) {
            packets.add(packet);
        }
    }
}
