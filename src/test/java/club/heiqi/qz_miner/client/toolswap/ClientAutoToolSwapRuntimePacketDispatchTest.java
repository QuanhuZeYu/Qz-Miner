package club.heiqi.qz_miner.client.toolswap;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * ClientProxy 的自动工具 S2C 发布结构：三个 handler 都必须「按本次 netHandler 捕获连接 token →
 * 经 world gate 投递 → 在 publication 内回调 adapter」。world gate 自身的语义（陈旧连接/世界解绑
 * 必须使发布失效）由 {@code AutoToolClientWiringStructureTest#worldGateRejectsStaleEndpointOrUnboundWorld}
 * 用真实 lifecycle token 行为化断言；此处只钉住 handler 的结构，避免重复防线。
 */
public class ClientAutoToolSwapRuntimePacketDispatchTest {

    private static final String PROXY_SOURCE = "src/main/java/club/heiqi/qz_miner/ClientProxy.java";
    private static final String LIFECYCLE_GATE_FIELD = "AUTO_TOOL_SWAP_LIFECYCLE_GATE";

    @Test
    public void proxyCapturesConnectionAndPublishesThreeProjectionPacketsThroughWorldGate() throws Exception {
        String source = JavaSourceSlices.stripCommentsIgnoringStringLiterals(JavaSourceSlices.read(PROXY_SOURCE));
        assertHandlerBody(source, "public void handleClientAutoToolSwapRoundResult(", "onRoundResult");
        assertHandlerBody(source, "public void handleClientAutoToolSwapActionResult(", "onActionResult");
        assertHandlerBody(source, "public void handleClientAutoToolSwapRoundPhase(", "onRoundPhase");
    }

    private static void assertHandlerBody(String source, String signature, String callback) {
        String body = JavaSourceSlices.methodBody(source, signature, callback);
        Assert.assertEquals(callback + " 只允许一次 gate 投递", 1,
                JavaSourceSlices.count(body, "ClientAutoToolSwapPacketDispatch.dispatch("));
        JavaSourceSlices.assertBefore(body, "captureForConnection(netHandler)", "ClientAutoToolSwapPacketDispatch.dispatch(",
                callback + " 必须用本次包上下文捕获连接 token");
        JavaSourceSlices.requireAt(body, callback + " 必须经 world gate 发布", LIFECYCLE_GATE_FIELD);
        JavaSourceSlices.requireAt(body, callback + " 必须在 publication 内回调 adapter",
                "autoToolSwapAdapter." + callback);
    }
}
