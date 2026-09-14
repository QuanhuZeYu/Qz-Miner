package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 按键 packet 在同一主线程 FIFO 内解析 round 并固化到事件的结构门禁。
 *
 * <p>窗口 = {@code onMessage} 方法体 → {@code ServerMainThreadDispatcher.run} 的 lambda 块（花括号配平截取，
 * 因此窗口有上界，不会吃到 lambda 之外）。断言的是「松键 local restore → bridge 解析 round → 状态固化 →
 * 事件装配」的先后，以及两个事件必须带上 bridge 返回的那个变量（数据流去向，名称无关）。</p>
 *
 * <p><b>守不到什么</b>：事件消费端的真实状态转移需要玩家 / 世界实体，纯 JVM 证不到。</p>
 */
public class PacketKeyStateToolSwapStructureTest {

    private static final String PACKET_PATH = "src/main/java/club/heiqi/qz_miner/network/PacketKeyState.java";

    private static final String FINALIZE_PLAYER = "autoToolSwapServerBatchService.finalizePlayer";
    private static final String RESOLVE_ROUND = "AutoToolSwapKeyStateBridge.onKeyState(player, pressed)";
    private static final String STATE_WRITE = "setPlayerChainKeyPressed";
    private static final String KEY_EVENT = "new ChainKeyPressed(";
    private static final String CLEANUP_EVENT = "new LifecycleCleanup(";

    @Test
    public void roundResolutionPrecedesStateWriteAndEventPublishInsideDispatcher() throws Exception {
        String handlerBody = JavaSourceSlices.methodBodyWithoutSignature(
                JavaSourceSlices.maskedMainSource(PACKET_PATH), "onMessage");
        String dispatcher = JavaSourceSlices.blockAfter(handlerBody, "ServerMainThreadDispatcher.run");
        Assert.assertFalse("round 解析与状态写入必须收口在 ServerMainThreadDispatcher.run 的 lambda 内",
                dispatcher.isEmpty());

        JavaSourceSlices.assertBefore(dispatcher, FINALIZE_PLAYER, RESOLVE_ROUND,
                "松键 local restore 必须早于 round projection 收口");
        JavaSourceSlices.assertBefore(dispatcher, RESOLVE_ROUND, STATE_WRITE,
                "bridge 解析出 round 之后才允许固化 chain key 状态");
        JavaSourceSlices.assertBefore(dispatcher, STATE_WRITE, KEY_EVENT,
                "状态固化必须早于事件发布");
        Assert.assertEquals("fresh key 必须仍只经同一 bridge 解析新 round", 1,
                JavaSourceSlices.wordCount(dispatcher, "AutoToolSwapKeyStateBridge.onKeyState"));

        int captureAt = JavaSourceSlices.requireAt(dispatcher, "bridge 解析 round", RESOLVE_ROUND);
        String roundId = JavaSourceSlices.assignmentTarget(dispatcher, captureAt,
                "bridge 返回的 round id 必须被捕获成局部变量");
        int keyEventAt = JavaSourceSlices.requireAt(dispatcher, "ChainKeyPressed 事件装配", KEY_EVENT);
        int cleanupEventAt = JavaSourceSlices.requireAt(dispatcher, "LifecycleCleanup 事件装配", CLEANUP_EVENT);
        String keyEventArgs = JavaSourceSlices.splitCallArguments(
                dispatcher.substring(keyEventAt), "ChainKeyPressed").toString();
        String cleanupEventArgs = JavaSourceSlices.splitCallArguments(
                dispatcher.substring(cleanupEventAt), "LifecycleCleanup").toString();
        Assert.assertTrue("ChainKeyPressed 必须携带 bridge 返回的 " + roundId + "，实际实参=" + keyEventArgs,
                JavaSourceSlices.mentions(keyEventArgs, roundId));
        Assert.assertTrue("LifecycleCleanup 必须携带同一个 " + roundId + "，实际实参=" + cleanupEventArgs,
                JavaSourceSlices.mentions(cleanupEventArgs, roundId));
    }
}
