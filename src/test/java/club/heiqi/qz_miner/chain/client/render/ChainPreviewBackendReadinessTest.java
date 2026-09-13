package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

/**
 * T48c-B：后端未就绪 ⇒ 一次性永久回退 legacy 的纯决策契约（含不重试语义）。
 */
public class ChainPreviewBackendReadinessTest {

    @Test
    public void legacyNotReadyKeepsActiveBackendWithoutSwitch() {
        Assert.assertEquals(ChainPreviewBackendReadiness.Action.KEEP_ACTIVE,
            ChainPreviewBackendReadiness.onNotReady("legacy", true));
        Assert.assertEquals(ChainPreviewBackendReadiness.Action.KEEP_ACTIVE,
            ChainPreviewBackendReadiness.onNotReady("legacy", false));
        Assert.assertFalse(ChainPreviewBackendReadiness.isFallback(
            ChainPreviewBackendReadiness.Action.KEEP_ACTIVE));
    }

    @Test
    public void shaderNotReadyFallsBackToLegacyWhenLegacyCapable() {
        ChainPreviewBackendReadiness.Action action =
            ChainPreviewBackendReadiness.onNotReady("shader", true);

        Assert.assertEquals(ChainPreviewBackendReadiness.Action.SWITCH_TO_LEGACY, action);
        Assert.assertTrue(ChainPreviewBackendReadiness.isFallback(action));
    }

    @Test
    public void shaderNotReadyWithoutLegacyCapabilityDegradesExplicitly() {
        Assert.assertEquals(ChainPreviewBackendReadiness.Action.NO_USABLE_PATH,
            ChainPreviewBackendReadiness.onNotReady("shader", false));
        Assert.assertFalse(ChainPreviewBackendReadiness.isFallback(
            ChainPreviewBackendReadiness.Action.NO_USABLE_PATH));
    }

    @Test
    public void unknownOrNullIdIsTreatedAsShader() {
        Assert.assertEquals(ChainPreviewBackendReadiness.Action.SWITCH_TO_LEGACY,
            ChainPreviewBackendReadiness.onNotReady(null, true));
        Assert.assertEquals(ChainPreviewBackendReadiness.Action.NO_USABLE_PATH,
            ChainPreviewBackendReadiness.onNotReady("mystery", false));
        Assert.assertEquals("大小写 / 空白按未知处理",
            ChainPreviewBackendReadiness.Action.SWITCH_TO_LEGACY,
            ChainPreviewBackendReadiness.onNotReady("LEGACY", true));
    }

    @Test
    public void failedAttemptMakesFallbackPermanentNoRetry() {
        ChainPreviewGlCapabilities capable =
            ChainPreviewGlCapabilities.probe(true, "3.3", "4.60", 16, true);

        Assert.assertEquals("未失败时 auto 首选 shader",
            ChainPreviewBackendSelector.SHADER,
            ChainPreviewBackendSelector.select("auto", capable, false));
        Assert.assertEquals("一次失败后 auto 永久 legacy（不再重试）",
            ChainPreviewBackendSelector.LEGACY,
            ChainPreviewBackendSelector.select("auto", capable, true));
        Assert.assertEquals("配置 legacy 恒 legacy",
            ChainPreviewBackendSelector.LEGACY,
            ChainPreviewBackendSelector.select("legacy", capable, false));
        Assert.assertNull("legacy 可用性判据来自 overlay 路径",
            ChainPreviewOverlayPath.legacyUnavailableReason(capable));
    }
}
