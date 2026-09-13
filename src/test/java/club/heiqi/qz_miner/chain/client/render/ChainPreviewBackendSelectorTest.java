package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewBackendSelectorTest {

    @Test
    public void autoUsesShaderWhenSupportedAndFresh() {
        Assert.assertEquals("shader", ChainPreviewBackendSelector.select("auto", supported(), false));
    }

    @Test
    public void autoFallsBackWhenLastAttemptFailed() {
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("auto", supported(), true));
    }

    @Test
    public void autoFallsBackWhenCapabilitiesUnsupported() {
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("auto", unsupported(), false));
    }

    @Test
    public void explicitShaderUsesShaderWhenSupported() {
        Assert.assertEquals("shader", ChainPreviewBackendSelector.select("shader", supported(), false));
    }

    @Test
    public void explicitShaderFallsBackWhenUnsupportedWithDiagnostic() {
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("shader", unsupported(), false));
        Assert.assertTrue(ChainPreviewBackendSelector.explain("shader", unsupported(), false)
            .contains("unsupported"));
    }

    @Test
    public void explicitShaderFallsBackAfterFailure() {
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("shader", supported(), true));
        Assert.assertEquals("previous shader attempt failed",
            ChainPreviewBackendSelector.explain("shader", supported(), true));
    }

    @Test
    public void explicitLegacyIsAlwaysLegacy() {
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("legacy", supported(), false));
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("legacy", null, true));
    }

    @Test
    public void unknownOrBlankConfiguredBehavesAsAuto() {
        Assert.assertEquals("shader", ChainPreviewBackendSelector.select(null, supported(), false));
        Assert.assertEquals("shader", ChainPreviewBackendSelector.select("", supported(), false));
        Assert.assertEquals("shader", ChainPreviewBackendSelector.select("  AUTO ", supported(), false));
        Assert.assertEquals("shader", ChainPreviewBackendSelector.select("bogus", supported(), false));
        Assert.assertEquals("shader", ChainPreviewBackendSelector.select("SHADER", supported(), false));
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("SHADER", unsupported(), false));
    }

    @Test
    public void nullCapabilitiesFallBackToLegacy() {
        Assert.assertEquals("legacy", ChainPreviewBackendSelector.select("auto", null, false));
        Assert.assertEquals("capabilities unavailable", ChainPreviewBackendSelector.explain("shader", null, false));
    }

    @Test
    public void explainReportsSupportDecision() {
        Assert.assertEquals("shader supported",
            ChainPreviewBackendSelector.explain("auto", supported(), false));
        Assert.assertEquals("configured=legacy",
            ChainPreviewBackendSelector.explain("legacy", supported(), false));
    }

    private static ChainPreviewGlCapabilities supported() {
        return ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 16, true);
    }

    private static ChainPreviewGlCapabilities unsupported() {
        return ChainPreviewGlCapabilities.probe(false, "", "", 0, false);
    }
}
