package club.heiqi.qz_miner.chain.planner;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/** 连锁输入事件必须在 publish 时固化当前工具 round 的结构门禁。 */
public class AutoToolSwapRoundPropagationStructureTest {

    @Test
    public void plannersAndModePacketsCaptureEndpointBoundRoundBeforeEventConstruction() {
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainPlanner.java",
                "new BlockBreakObserved(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/chain/planner/ChainInteractPlanner.java",
                "new RightClickObserved(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/chain/planner/GregTechCableReplacePlanner.java",
                "new LeftClickObserved(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/network/PacketChainModeSwitch.java",
                "new ModeSwitched(");
        assertRoundCapturedBeforeEvent(
                "src/main/java/club/heiqi/qz_miner/network/PacketChainSubModeSwitch.java",
                "new ModeSwitched(");
    }

    /**
     * 断言「端点绑定轮次先捕获、再作为实参进入事件构造」的数据流。
     *
     * <p>旧写法是在事件构造点之后的开区间里找 {@code serverRoundId} 字样——文件后面任何位置出现一次即绿，
     * 无法证明构造事件时真的传了这个值。现在改为：定位捕获调用语句取出被赋值变量，
     * 再要求该变量出现在事件构造的配平实参区间内。变量名无关（改名不误报），
     * 但把轮次实参改成 {@code 0L}（真回归）会红。</p>
     */
    private static void assertRoundCapturedBeforeEvent(String path, String eventConstructor) {
        String source = JavaSourceSlices.stripped(path);
        int capture = source.indexOf("currentRoundId(player.getUniqueID(), player)");
        if (capture < 0) {
            capture = source.indexOf("currentRoundId(playerId, captured)");
        }
        int event = source.indexOf(eventConstructor);
        Assert.assertTrue(path + "：未捕获端点绑定轮次", capture >= 0);
        Assert.assertTrue(path + "：轮次捕获必须先于事件构造", capture < event);

        String capturedRound = JavaSourceSlices.assignmentTarget(source, capture, path);
        String arguments = JavaSourceSlices.callArguments(source, eventConstructor, path + " " + eventConstructor);
        JavaSourceSlices.assertContains(arguments, capturedRound,
                path + "：事件构造必须传入捕获到的轮次变量 " + capturedRound);
    }
}
