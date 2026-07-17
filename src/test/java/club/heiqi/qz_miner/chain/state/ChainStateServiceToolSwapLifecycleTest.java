package club.heiqi.qz_miner.chain.state;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
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
        Assert.assertNull(stateService.getPlayerState(playerId));
        stateService.cleanupPlayerState(playerId, "logout-without-state", true);
        Assert.assertNull(MyMod.autoToolSwapRoundService.snapshot(playerId));
        Assert.assertNull(stateService.getPlayerState(playerId));

        MyMod.autoToolSwapRoundService.beginRound(playerId, endpoint, 2L, 1L);
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
