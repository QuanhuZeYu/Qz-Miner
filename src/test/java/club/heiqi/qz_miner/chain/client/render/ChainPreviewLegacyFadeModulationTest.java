package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewLegacyFadeModulationTest {

    @Test
    public void modulationOnlyWhenFadeAlphaBelowOne() {
        Assert.assertFalse("fadeAlpha == 1 必须零额外 GL 调用（逐字等于历史行为）",
            ChainPreviewLegacyBackend.shouldApplyFadeModulation(1.0F));
        Assert.assertTrue(ChainPreviewLegacyBackend.shouldApplyFadeModulation(0.999F));
        Assert.assertTrue(ChainPreviewLegacyBackend.shouldApplyFadeModulation(0.0F));
    }

    @Test
    public void freshBackendHasNoFadeFailureAndCleanDescribe() {
        ChainPreviewLegacyBackend backend = new ChainPreviewLegacyBackend();

        Assert.assertFalse(backend.isFadeModulationUnavailable());
        Assert.assertFalse(backend.describe().contains("fadeModulation=unavailable"));
        Assert.assertEquals("legacy", backend.id());
        Assert.assertTrue(backend.usesCpuColors());
    }
}
