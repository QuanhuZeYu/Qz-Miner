package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

/** 远端预览请求门控（B0.6）的无 Minecraft 行为契约。 */
public class RemotePreviewRequestTest {

    private static final long MILLIS = 1000000L;

    @Test
    public void staleRequestIdIsDiscardedAndDoesNotClearPendingRequest() {
        RemotePreviewRequest request = new RemotePreviewRequest();
        request.begin(7, 1000L, 5000L);

        Assert.assertTrue(request.isPending());
        Assert.assertFalse("陈旧 requestId 必须丢弃", request.accept(6));
        Assert.assertFalse(request.accept(8));
        Assert.assertTrue("陈旧响应不得清空在途请求", request.isPending());
        Assert.assertEquals(7, request.getPendingRequestId());

        Assert.assertTrue(request.accept(7));
        Assert.assertFalse(request.isPending());
    }

    @Test
    public void duplicateAndLateResponsesAfterAcceptAreDiscarded() {
        RemotePreviewRequest request = new RemotePreviewRequest();
        request.begin(3, 0L, 1000L);

        Assert.assertTrue(request.accept(3));
        Assert.assertFalse("已消费的请求不得重复接受", request.accept(3));
        Assert.assertFalse("无在途请求时不接受任何响应", request.accept(4));
    }

    @Test
    public void timeoutExpiresOnlyAfterConfiguredWindowAndOnlyOnce() {
        RemotePreviewRequest request = new RemotePreviewRequest();
        request.begin(9, 1000L, 250L);

        Assert.assertFalse(request.expire(1000L + 249L * MILLIS));
        Assert.assertTrue(request.isPending());
        Assert.assertTrue(request.expire(1000L + 250L * MILLIS));
        Assert.assertFalse("超时只报告一次", request.expire(1000L + 60000L * MILLIS));
        Assert.assertFalse(request.isPending());
    }

    @Test
    public void newerRequestSupersedesPendingOneAndClearInvalidatesIt() {
        RemotePreviewRequest request = new RemotePreviewRequest();
        request.begin(1, 0L, 1000L);
        request.begin(2, 0L, 1000L);
        Assert.assertFalse("被覆盖的旧请求必须丢弃", request.accept(1));
        Assert.assertTrue(request.accept(2));

        request.begin(5, 0L, 1000L);
        request.clear();
        Assert.assertFalse(request.isPending());
        Assert.assertFalse(request.accept(5));
    }
}
