package club.heiqi.qz_miner.chain.client.render;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

/**
 * T48c-B：legacy 上传收尾解绑 VAO 的契约。
 *
 * <p>生产路径 {@code uploadTopology} / {@code uploadColors} 末尾调用同一 helper；
 * 纯 JVM 只能钉住 helper 的绑定动作与静默语义（真实 upload 序列需要 GL 上下文，报告里登记）。</p>
 */
public class ChainPreviewLegacyBackendUnbindTest {

    @Test
    public void unbindIssuesVertexArrayZeroOnce() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();

        ChainPreviewLegacyBackend.unbindVertexArrayQuietly(access);

        Assert.assertEquals(Arrays.asList("bindVao:0"), access.events);
    }

    @Test
    public void unbindIsSilentForNullAccessAndContextLoss() {
        ChainPreviewLegacyBackend.unbindVertexArrayQuietly(null);

        ChainPreviewTestGlAccess contextLost = new ChainPreviewTestGlAccess();
        contextLost.failBindingAccess = true;
        ChainPreviewLegacyBackend.unbindVertexArrayQuietly(contextLost);
        Assert.assertTrue("访问点不可用时零调用且不抛", contextLost.events.isEmpty());

        ChainPreviewTestGlAccess throwingBind = new ChainPreviewTestGlAccess();
        throwingBind.failRestore = true;
        ChainPreviewLegacyBackend.unbindVertexArrayQuietly(throwingBind);
        Assert.assertEquals("绑定抛异常不得逃逸", Arrays.asList("bindVao:0"), throwingBind.events);
    }
}
