package club.heiqi.qz_miner.client;

import java.io.File;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.state.ChainStateService;
import club.heiqi.qz_miner.client.hud.MinerHudModel;
import club.heiqi.qz_miner.client.settings.ClientServerConfigFacts;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigTestSupport;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.network.PacketChainConfigSync;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 合法包经原校验/gate发布，世界入口执行真实清理；不使用会跳过 miner-hud 的 cleanup hook。 */
public class ClientServerFactsLifecycleTest {
    private File directory;
    private ChainStateService previousState;
    private club.heiqi.qz_miner.network.NetworkMain previousNetwork;
    @Before public void setUp() throws Exception {
        ConfigTestSupport.reset(); ClientConnectionLifecycle.resetForTests(); ClientServerConfigFacts.clear();
        directory = Files.createTempDirectory("qz-connection-facts-").toFile(); ConfigBootstrap.bootstrap(directory, null);
        previousState = MyMod.chainStateService; previousNetwork = MyMod.networkMain;
        MyMod.chainStateService = new ChainStateService(); MyMod.networkMain = null;
    }
    @After public void tearDown() {
        MyMod.chainStateService = previousState; MyMod.networkMain = previousNetwork;
        ClientServerConfigFacts.clear(); ClientConnectionLifecycle.resetForTests(); ConfigTestSupport.reset(); delete(directory);
    }
    @Test public void confirmedValuesSurviveSameHandlerWorldCleanupButNotDisconnectOrTakeover() throws Exception {
        ClientConnectionListener listener = new ClientConnectionListener(Runnable::run);
        Object firstHandler = new Object(), firstWorld = new Object();
        listener.handleConnected(firstHandler); listener.handleWorldLoad(firstWorld, true);
        publishValidPacket(37, 777);
        assertFactsAndNewHud(37, 777);
        // active 世界直接替换与先 unload 再 load 两种维度转移均经实际资源清理。
        Object secondWorld = new Object(); listener.handleWorldLoad(secondWorld, true);
        assertFactsAndNewHud(37, 777);
        listener.handleWorldUnload(secondWorld, true); listener.handleWorldLoad(new Object(), true);
        assertFactsAndNewHud(37, 777);
        listener.handleDisconnected(firstHandler);
        Assert.assertFalse(ClientServerConfigFacts.confirmed());
        Assert.assertEquals("", ClientServerConfigFacts.value(QzMinerConfigSchema.CHAIN_RADIUS));
        Object secondHandler = new Object(); listener.handleConnected(secondHandler); listener.handleWorldLoad(new Object(), true);
        Assert.assertFalse("连接初始化的本机 fallback 不是远端确认", ClientServerConfigFacts.confirmed());
        publishValidPacket(43, 888); assertFactsAndNewHud(43, 888);
        listener.handleConnected(new Object());
        Assert.assertFalse(ClientServerConfigFacts.confirmed());
        Assert.assertEquals("", ClientServerConfigFacts.value(QzMinerConfigSchema.CHAIN_MAX_BLOCKS));
    }
    private void publishValidPacket(int radius, int blocks) throws Exception {
        PacketChainConfigSync outgoing = new PacketChainConfigSync(radius, blocks, 0);
        PacketChainConfigSync received = new PacketChainConfigSync();
        ByteBuf wire = Unpooled.buffer();
        try { outgoing.toBytes(wire); received.fromBytes(wire); } finally { wire.release(); }
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        java.lang.reflect.Field field = ClientProxy.class.getDeclaredField("LIFECYCLE_GATE"); field.setAccessible(true);
        ClientChainConfigSyncDispatch.LifecycleGate gate = (ClientChainConfigSyncDispatch.LifecycleGate) field.get(null);
        Assert.assertTrue(ClientChainConfigSyncDispatch.dispatch(received.chainRadius, received.chainMaxBlocks,
                received.matchedTargetCount, received.protocolVersion, received.tunnelDirectionCode, received.rawValid,
                token, gate, task -> { task.run(); return true; }, (r, b, m, source) -> {
                    ChainClientState state = MyMod.chainStateService.getClientState();
                    state.setServerChainRadius(r); state.setServerChainMaxBlocks(b); state.setServerMatchedTargetCount(m);
                    state.setAcceptedTunnelDirectionSource(source); ClientServerConfigFacts.received(token);
                }));
    }
    private void assertFactsAndNewHud(int radius, int blocks) {
        Assert.assertTrue(ClientServerConfigFacts.confirmed());
        Assert.assertEquals(String.valueOf(radius), ClientServerConfigFacts.value(QzMinerConfigSchema.CHAIN_RADIUS));
        Assert.assertEquals(String.valueOf(blocks), ClientServerConfigFacts.value(QzMinerConfigSchema.CHAIN_MAX_BLOCKS));
        ChainClientState state = MyMod.chainStateService.getClientState(); state.setChainKeyPressed(true);
        ReactiveDomain domain = ReactiveDomain.direct(); domain.execute(() -> {
            MinerHudModel model = new MinerHudModel(domain, key -> key);
            model.context(state, ClientServerConfigFacts.confirmed(), "en_US");
            Assert.assertTrue(model.rows.get().stream().anyMatch(row -> row.id.equals("radius") && row.value.endsWith(" / " + radius)));
            Assert.assertTrue(model.rows.get().stream().anyMatch(row -> row.id.equals("blocks") && row.value.endsWith(" / " + blocks)));
            Assert.assertFalse(model.status.get().equals("hud.qz_miner.sync.pending"));
        });
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete();
    }
}
