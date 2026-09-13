package club.heiqi.qz_miner.chain.client.render;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

/**
 * T26 / B4.3 围栏配对契约：一次捕获一次恢复、异常路径恢复、未 push 不 pop、
 * 「不请求就零 GL 调用」。全部纯 JVM（假 Access），不加载 GL。
 */
public class ChainPreviewGlFencesTest {

    @Test
    public void frameFenceCapturesOnceAndRestoresOnceInHistoricalOrder() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        int[] captures = { 0 };
        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, () -> captures[0]++);

        Assert.assertTrue(frame.isCaptured());
        Assert.assertEquals(1, captures[0]);
        Assert.assertEquals(ChainPreviewGlBindings.CAPTURED_QUERY_COUNT, access.queries.size());
        Assert.assertEquals(Arrays.asList("pushAll", "pushClient"), access.events);

        frame.close();
        Assert.assertTrue(frame.isClosed());
        Assert.assertEquals(
            Arrays.asList(
                "pushAll", "pushClient", "popClient", "popAll",
                "bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);

        frame.close();
        Assert.assertEquals(
            "close 幂等：第二次调用零 GL 调用",
            7,
            access.events.size());
    }

    @Test
    public void frameFenceRestoresWhenBodyThrows() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        boolean thrown = false;
        try (ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, null)) {
            Assert.assertTrue(frame.isCaptured());
            throw new IllegalStateException("body boom");
        } catch (IllegalStateException expected) {
            thrown = true;
        }
        Assert.assertTrue("body 异常必须上抛", thrown);
        Assert.assertEquals(
            Arrays.asList(
                "pushAll", "pushClient", "popClient", "popAll",
                "bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);
    }

    @Test
    public void frameFenceCaptureFailureIsTolerantAndStillPairsStateStack() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.failQueries = true;
        int[] captures = { 0 };

        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, () -> captures[0]++);

        Assert.assertFalse(frame.isCaptured());
        Assert.assertEquals("捕获失败不得计入帧级捕获", 0, captures[0]);
        Assert.assertTrue(frame.getFailure().contains("IllegalStateException"));
        frame.close();
        Assert.assertEquals(Arrays.asList("pushAll", "pushClient", "popClient", "popAll"), access.events);
    }

    @Test
    public void frameFenceSkipsPopForStateThatWasNeverPushed() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.failClientPush = true;

        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, null);
        Assert.assertTrue(frame.getFailure().contains("client push failure"));
        frame.close();

        Assert.assertEquals(
            Arrays.asList(
                "pushAll", "pushClient", "popAll",
                "bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);
    }

    @Test
    public void frameFencePopFailureDoesNotBlockBindingRestore() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.failAllPop = true;

        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, null);
        frame.close();

        Assert.assertEquals(
            Arrays.asList(
                "pushAll", "pushClient", "popClient", "popAll",
                "bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);
    }

    @Test
    public void frameFenceToleratesContextLossWhileResolvingBindingAccess() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.failBindingAccess = true;

        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, null);
        Assert.assertFalse(frame.isCaptured());
        Assert.assertFalse("必须记录失败原因", frame.getFailure().isEmpty());
        Assert.assertNull(ChainPreviewGlFences.bindingsQuietly(access));

        frame.close();
        Assert.assertTrue("close 不得因上下文失效抛异常", frame.isClosed());
        Assert.assertEquals(
            Arrays.asList("pushAll", "pushClient", "popClient", "popAll"),
            access.events);
    }

    @Test
    public void frameFenceRejectsNullAccess() {
        try {
            ChainPreviewGlFences.Frame.open(null, null);
            Assert.fail("null access 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void textureFenceRestoresOnlyWhenRequestedAndCaptured() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.textureEnabled = true;
        access.textureBinding = 42;

        ChainPreviewGlFences.Texture fence = ChainPreviewGlFences.Texture.captureIfRequested(true, access);
        Assert.assertTrue(fence.isActive());
        Assert.assertTrue(fence.isPreviousEnabled());
        Assert.assertEquals(42, fence.getPreviousBinding());

        fence.close();
        Assert.assertEquals(
            Arrays.asList("texRead", "texBindingRead", "texEnable:true", "texBind:42"),
            access.events);
        fence.close();
        Assert.assertEquals("close 幂等：第二次调用零 GL 调用", 4, access.events.size());
    }

    @Test
    public void textureFenceDoesNothingWhenNotRequested() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();

        ChainPreviewGlFences.Texture fence = ChainPreviewGlFences.Texture.captureIfRequested(false, access);
        Assert.assertFalse(fence.isActive());
        Assert.assertFalse(fence.isCaptureFailed());
        fence.close();

        Assert.assertEquals("fadeAlpha == 1 时不请求围栏 = 零额外 GL 调用", Collections.emptyList(), access.events);
    }

    @Test
    public void textureFenceCaptureFailureDegradesWithoutRestore() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.failTextureRead = true;

        ChainPreviewGlFences.Texture fence = ChainPreviewGlFences.Texture.captureIfRequested(true, access);
        Assert.assertFalse("读取失败必须降级为不施加乘子", fence.isActive());
        Assert.assertTrue(fence.isCaptureFailed());
        Assert.assertTrue(fence.getFailure().contains("texture read failure"));
        fence.close();

        Assert.assertEquals("捕获失败不得产生恢复调用（不冒险改状态）", Arrays.asList("texRead"), access.events);
    }

    @Test
    public void textureFenceRejectsNullAccess() {
        try {
            ChainPreviewGlFences.Texture.captureIfRequested(true, null);
            Assert.fail("null access 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void quietBindingHelpersSwallowContextLoss() {
        ChainPreviewTestGlAccess failing = new ChainPreviewTestGlAccess();
        failing.failQueries = true;
        Assert.assertNull(ChainPreviewGlFences.captureBindingsQuietly(failing));
        Assert.assertNull(ChainPreviewGlFences.captureBindingsQuietly(null));

        ChainPreviewTestGlAccess healthy = new ChainPreviewTestGlAccess();
        ChainPreviewGlBindings snapshot = ChainPreviewGlFences.captureBindingsQuietly(healthy);
        Assert.assertNotNull(snapshot);
        Assert.assertEquals(3, healthy.queries.size());

        ChainPreviewGlFences.restoreBindingsQuietly(snapshot, null);
        ChainPreviewGlFences.restoreBindingsQuietly(null, healthy);
        Assert.assertEquals("null 快照 / null access 时零恢复调用", 0, healthy.events.size());

        ChainPreviewTestGlAccess failingRestore = new ChainPreviewTestGlAccess();
        failingRestore.failRestore = true;
        ChainPreviewGlFences.restoreBindingsQuietly(snapshot, failingRestore);
        Assert.assertEquals(1, failingRestore.events.size());
    }
}
