package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewGlCapabilitiesTest {

    @Test
    public void probeAcceptsGl20Glsl120AndEnoughAttribs() {
        ChainPreviewGlCapabilities caps = ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 16, true);
        Assert.assertTrue(caps.isShaderSupported());
        Assert.assertEquals("2.1", caps.getGlVersion());
        Assert.assertEquals("1.20", caps.getGlslVersion());
        Assert.assertEquals(16, caps.getMaxVertexAttribs());
        Assert.assertTrue(caps.isVaoSupported());
    }

    @Test
    public void probeRejectsMissingGl20() {
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(false, "2.1", "1.20", 16, true)
            .isShaderSupported());
    }

    @Test
    public void probeRejectsLowGlAndGlslVersions() {
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(true, "2.0", "1.20", 16, true)
            .isShaderSupported());
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(true, "2.1", "1.10", 16, true)
            .isShaderSupported());
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(true, "2.1", null, 16, true)
            .isShaderSupported());
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(true, null, "1.20", 16, true)
            .isShaderSupported());
    }

    @Test
    public void probeRejectsInsufficientVertexAttribs() {
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 1, true)
            .isShaderSupported());
        Assert.assertEquals(2, ChainPreviewGlCapabilities.REQUIRED_MAX_VERTEX_ATTRIBS);
    }

    @Test
    public void probeParsesVendorSuffixedVersions() {
        ChainPreviewGlCapabilities caps = ChainPreviewGlCapabilities.probe(
            true, "3.3.0 NVIDIA 552.22", "3.30 NVIDIA via Cg compiler", 16, true);
        Assert.assertTrue(caps.isShaderSupported());
    }

    @Test
    public void probeRejectsOpenGlEsStrings() {
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(
            true, "OpenGL ES 3.2", "OpenGL ES GLSL ES 3.20", 16, true).isShaderSupported());
    }

    @Test
    public void versionAtLeastParsesTwoSegmentVersions() {
        Assert.assertTrue(ChainPreviewGlCapabilities.versionAtLeast("2.1", 2, 1));
        Assert.assertTrue(ChainPreviewGlCapabilities.versionAtLeast("1.20", 1, 20));
        Assert.assertTrue(ChainPreviewGlCapabilities.versionAtLeast("4.6.0", 2, 1));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast("1.19", 1, 20));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast("2.0", 2, 1));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast(null, 2, 1));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast("", 2, 1));
    }

    @Test
    public void unsupportedConstantAndDetectNeverThrowInHeadlessJvm() {
        Assert.assertFalse(ChainPreviewGlCapabilities.UNSUPPORTED.isShaderSupported());
        Assert.assertFalse(ChainPreviewGlCapabilities.UNSUPPORTED.isVaoSupported());

        ChainPreviewGlCapabilities detected = ChainPreviewGlCapabilities.detect();
        Assert.assertNotNull(detected);
        Assert.assertNotNull(detected.describe());
    }

    @Test
    public void probeSeparatesShaderAndLegacyPathSupport() {
        ChainPreviewGlCapabilities full = ChainPreviewGlCapabilities.probe(true, "3.3", "4.60", 16, true);
        Assert.assertTrue(full.isShaderSupported());
        Assert.assertTrue(full.isLegacySupported());

        ChainPreviewGlCapabilities legacyOnly = ChainPreviewGlCapabilities.probe(true, "2.1", "1.10", 16, true);
        Assert.assertFalse(legacyOnly.isShaderSupported());
        Assert.assertTrue(legacyOnly.isLegacySupported());

        ChainPreviewGlCapabilities noVao = ChainPreviewGlCapabilities.probe(true, "3.3", "4.60", 16, false);
        Assert.assertTrue(noVao.isShaderSupported());
        Assert.assertFalse("无 VAO 不得假定 legacy 可用（T26 / B4.3）", noVao.isLegacySupported());

        Assert.assertFalse(ChainPreviewGlCapabilities.probe(false, "2.1", "1.20", 16, true)
            .isLegacySupported());
        Assert.assertFalse(ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 1, true)
            .isLegacySupported());
        Assert.assertFalse(ChainPreviewGlCapabilities.UNSUPPORTED.isLegacySupported());

        Assert.assertTrue(ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 2, true)
            .describe()
            .contains("legacySupported=true"));
    }

    @Test
    public void detectDetailedNeverThrowsAndReportsFailureReason() {
        ChainPreviewGlCapabilities.ProbeResult result = ChainPreviewGlCapabilities.detectDetailed();
        Assert.assertNotNull(result);
        Assert.assertNotNull(result.getCapabilities());
        Assert.assertNotNull(result.getFailureReason());
        Assert.assertEquals(result.isFailure(), !result.getFailureReason().isEmpty());
        Assert.assertFalse(result.getCapabilities().describe().isEmpty());
        Assert.assertNotNull(ChainPreviewGlCapabilities.detect());

        ChainPreviewGlCapabilities.ProbeResult failed = ChainPreviewGlCapabilities.ProbeResult.failure(
            ChainPreviewGlCapabilities.UNSUPPORTED, "");
        Assert.assertTrue(failed.isFailure());
        Assert.assertEquals("unspecified", failed.getFailureReason());
        Assert.assertFalse(ChainPreviewGlCapabilities.ProbeResult.success(
            ChainPreviewGlCapabilities.UNSUPPORTED).isFailure());
    }

    @Test
    public void describeContainsProbeValues() {
        String text = ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 16, true).describe();
        Assert.assertTrue(text.contains("2.1"));
        Assert.assertTrue(text.contains("1.20"));
        Assert.assertTrue(text.contains("maxVertexAttribs=16"));
    }
}
