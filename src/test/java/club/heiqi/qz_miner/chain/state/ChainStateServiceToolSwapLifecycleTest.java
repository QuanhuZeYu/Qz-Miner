package club.heiqi.qz_miner.chain.state;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapRoundService;

/** 生命周期即使没有 ChainPlayerState 也必须销毁工具换位账本。 */
public class ChainStateServiceToolSwapLifecycleTest {

    @After
    public void clearGlobalService() {
        MyMod.autoToolSwapRoundService = null;
    }

    @Test
    public void cleanupAndRemoveDiscardRoundWithoutCreatingChainState() {
        UUID playerId = UUID.randomUUID();
        Object endpoint = new Object();
        MyMod.autoToolSwapRoundService = new AutoToolSwapRoundService();
        ChainStateService stateService = new ChainStateService();

        MyMod.autoToolSwapRoundService.beginRound(playerId, endpoint, 1L, 0L);
        long roundId = MyMod.autoToolSwapRoundService.activatePendingRound(playerId, endpoint, 1L)
                .serverRoundId();
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent close = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                roundId, 1L, AutoToolSwapAction.CLOSE, 0, 0, empty, empty);
        MyMod.autoToolSwapRoundService.handleIntent(playerId, endpoint, close, null, 2L);
        Assert.assertTrue("硬生命周期前先建立 committed result publication",
                MyMod.autoToolSwapRoundService.snapshot(playerId, endpoint)
                        .hasPendingResultPublication());
        Assert.assertNull(stateService.getPlayerState(playerId));
        stateService.cleanupPlayerState(playerId, "logout-without-state", true);
        Assert.assertNull(MyMod.autoToolSwapRoundService.snapshot(playerId));
        Assert.assertNull(stateService.getPlayerState(playerId));

        MyMod.autoToolSwapRoundService.beginRound(playerId, endpoint, 2L, 1L);
        Assert.assertNotNull(MyMod.autoToolSwapRoundService.snapshot(playerId, endpoint));
        Assert.assertFalse("新 lifecycle 不得继承旧 publication pending",
                MyMod.autoToolSwapRoundService.snapshot(playerId, endpoint)
                        .hasPendingResultPublication());
        Assert.assertEquals("新 lifecycle 必须从 fresh ordinary sequence 开始", 1L,
                MyMod.autoToolSwapRoundService.snapshot(playerId, endpoint).nextActionSequence());
        Assert.assertEquals("新 lifecycle 不得继承旧 takeover request 水位", 0L,
                MyMod.autoToolSwapRoundService.snapshot(playerId, endpoint)
                        .lastIssuedTakeoverRequestId());
        stateService.removePlayerState(playerId, "remove-without-state");
        Assert.assertNull(MyMod.autoToolSwapRoundService.snapshot(playerId));
        Assert.assertNull(stateService.getPlayerState(playerId));
    }

    @Test
    public void cleanupPrecedesNullStateReturnAndLoginTakesOverOldEndpoint() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/state/ChainStateService.java").toPath()),
                StandardCharsets.UTF_8);
        int cleanupMethod = source.indexOf("public void cleanupPlayerState(UUID playerUUID, EntityPlayer player");
        int cleanupRound = source.indexOf("cleanupAutoToolSwapRound(playerUUID);", cleanupMethod);
        int stateLookup = source.indexOf("ChainPlayerState state = getPlayerState(playerUUID);", cleanupMethod);
        int login = source.indexOf("case LOGIN:");
        int loginCleanup = source.indexOf("cleanupAutoToolSwapRound(playerUUID);", login);
        int loginCreate = source.indexOf("getOrCreatePlayerState(playerUUID);", login);

        Assert.assertTrue(cleanupMethod < cleanupRound);
        Assert.assertTrue(cleanupRound < stateLookup);
        Assert.assertTrue(login < loginCleanup);
        Assert.assertTrue(loginCleanup < loginCreate);
    }
}
