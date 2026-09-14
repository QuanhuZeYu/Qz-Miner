package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.testsupport.JavaSourceSlices;
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
        stateService.removePlayerState(playerId, "remove-without-state");
        Assert.assertNull(MyMod.autoToolSwapRoundService.snapshot(playerId));
        Assert.assertNull(stateService.getPlayerState(playerId));
    }

    /**
     * 结构契约：cleanup 先清轮次再查状态；LOGIN 分支先清理旧轮次再建状态。
     *
     * <p>断言收敛为「先切方法体，再比两个标识符的相对位置」：旧写法用无上界的 {@code indexOf}
     * 从方法签名一直扫到文件尾，后续方法（甚至别的方法体）里的同名调用也能满足位置关系；
     * LOGIN 的顺序现在被限制在 {@code case LOGIN:} 到 {@code case RESPAWN:} 的分支区间内。</p>
     */
    @Test
    public void cleanupPrecedesNullStateReturnAndLoginTakesOverOldEndpoint() {
        String source = JavaSourceSlices.stripped(STATE_SERVICE_PATH);

        String cleanup = JavaSourceSlices.methodBody(source,
                "public void cleanupPlayerState(UUID playerUUID, EntityPlayer player,",
                "ChainStateService.cleanupPlayerState(UUID, EntityPlayer, ...)");
        JavaSourceSlices.assertBefore(cleanup, "cleanupAutoToolSwapRound(", "getPlayerState(",
                "cleanup 必须先清轮次再查状态");

        String stateChanged = JavaSourceSlices.methodBody(source, "private void onPlayerStateChanged(",
                "ChainStateService.onPlayerStateChanged");
        int login = stateChanged.indexOf("case LOGIN:");
        int respawn = stateChanged.indexOf("case RESPAWN:");
        Assert.assertTrue("LOGIN 分支必须存在", login >= 0);
        Assert.assertTrue("LOGIN 分支必须早于 RESPAWN 分支", respawn > login);
        String loginBranch = stateChanged.substring(login, respawn);
        JavaSourceSlices.assertBefore(loginBranch, "cleanupAutoToolSwapRound(", "getOrCreatePlayerState(",
                "LOGIN 必须先清理旧轮次再建状态");
    }

    private static final String STATE_SERVICE_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/state/ChainStateService.java";
}
