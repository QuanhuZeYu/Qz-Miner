package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

/**
 * T26 / B4.3 能力 → 路径决策表（纯函数，无 GL）：含全部降级分支——
 * shader 可用、legacy 可用、无 VAO、无 GL20、attrib 不足、能力缺失 / 探测失败。
 */
public class ChainPreviewOverlayPathTest {

    @Test
    public void shaderCapableHostsSelectShaderPath() {
        ChainPreviewOverlayPath.Decision decision =
            ChainPreviewOverlayPath.decide("auto", fullCaps(), false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.SHADER, decision.getPath());
        Assert.assertEquals(ChainPreviewBackendSelector.SHADER, decision.getBackendId());
        Assert.assertTrue(decision.isUsable());
        Assert.assertFalse(decision.isUnavailable());
        Assert.assertFalse(decision.getReason().isEmpty());
    }

    @Test
    public void configuredLegacyStaysOnLegacyPath() {
        ChainPreviewOverlayPath.Decision decision =
            ChainPreviewOverlayPath.decide("legacy", fullCaps(), false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.LEGACY, decision.getPath());
        Assert.assertEquals(ChainPreviewBackendSelector.LEGACY, decision.getBackendId());
    }

    @Test
    public void shaderUnsupportedButLegacyCapableStaysUsable() {
        ChainPreviewOverlayPath.Decision decision =
            ChainPreviewOverlayPath.decide("auto", legacyOnlyCaps(), false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.LEGACY, decision.getPath());
        Assert.assertTrue(decision.isUsable());
    }

    @Test
    public void previousShaderFailureFallsBackToLegacyWhenLegacyCapable() {
        ChainPreviewOverlayPath.Decision decision =
            ChainPreviewOverlayPath.decide("shader", fullCaps(), true);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.LEGACY, decision.getPath());
        Assert.assertEquals(ChainPreviewBackendSelector.LEGACY, decision.getBackendId());
        Assert.assertTrue(decision.isUsable());
    }

    @Test
    public void missingCapabilitiesAreExplicitDegradationNotAssumedLegacy() {
        ChainPreviewOverlayPath.Decision decision = ChainPreviewOverlayPath.decide("auto", null, false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.UNAVAILABLE, decision.getPath());
        Assert.assertFalse(decision.isUsable());
        Assert.assertTrue(decision.isUnavailable());
        Assert.assertNull(decision.getBackendId());
        Assert.assertTrue(decision.getReason().contains("capabilities unavailable"));
    }

    @Test
    public void unsupportedCapabilitiesAreExplicitDegradation() {
        ChainPreviewOverlayPath.Decision decision =
            ChainPreviewOverlayPath.decide("auto", ChainPreviewGlCapabilities.UNSUPPORTED, false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.UNAVAILABLE, decision.getPath());
        Assert.assertTrue(decision.getReason().contains("VAO"));
    }

    @Test
    public void noVaoHostCannotUseLegacyPathEvenWhenConfiguredLegacy() {
        ChainPreviewOverlayPath.Decision auto =
            ChainPreviewOverlayPath.decide("auto", noVaoCaps(), false);
        Assert.assertEquals("shader 可用时无 VAO 仍走 shader", ChainPreviewOverlayPath.Path.SHADER, auto.getPath());

        ChainPreviewOverlayPath.Decision configured =
            ChainPreviewOverlayPath.decide("legacy", noVaoCaps(), false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.UNAVAILABLE, configured.getPath());
        Assert.assertTrue(configured.getReason().contains("VAO"));
        Assert.assertNull(configured.getBackendId());
    }

    @Test
    public void noGl20HostCannotUseLegacyPath() {
        ChainPreviewOverlayPath.Decision decision =
            ChainPreviewOverlayPath.decide("auto", noGl20WithVaoCaps(), false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.UNAVAILABLE, decision.getPath());
        Assert.assertTrue(decision.getReason().contains("GL20"));
    }

    @Test
    public void insufficientVertexAttribsCannotUseLegacyPath() {
        ChainPreviewOverlayPath.Decision decision =
            ChainPreviewOverlayPath.decide("auto", fewAttribsCaps(), false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.UNAVAILABLE, decision.getPath());
        Assert.assertTrue(decision.getReason().contains("GL_MAX_VERTEX_ATTRIBS"));
    }

    @Test
    public void everyDecisionIsPureReasonsAreNeverEmptyAndUsableMatchesSelector() {
        String[] configured = { null, "", "auto", "shader", "legacy", "banana", "  AUTO  " };
        ChainPreviewGlCapabilities[] capabilities = {
            null,
            ChainPreviewGlCapabilities.UNSUPPORTED,
            fullCaps(),
            legacyOnlyCaps(),
            noVaoCaps(),
            noGl20WithVaoCaps(),
            fewAttribsCaps()
        };
        for (String value : configured) {
            for (ChainPreviewGlCapabilities caps : capabilities) {
                for (boolean failed : new boolean[] { false, true }) {
                    ChainPreviewOverlayPath.Decision decision =
                        ChainPreviewOverlayPath.decide(value, caps, failed);
                    Assert.assertNotNull(decision);
                    Assert.assertNotNull(decision.getPath());
                    Assert.assertFalse("reason 不得为空", decision.getReason().isEmpty());
                    Assert.assertEquals(
                        "决策必须可重复（纯函数）",
                        decision.getPath(),
                        ChainPreviewOverlayPath.decide(value, caps, failed).getPath());
                    String selected = ChainPreviewBackendSelector.select(value, caps, failed);
                    if (decision.isUsable()) {
                        Assert.assertEquals(selected, decision.getBackendId());
                    } else {
                        Assert.assertNull(decision.getBackendId());
                        Assert.assertEquals(
                            "不可用只可能来自 legacy 回退",
                            ChainPreviewBackendSelector.LEGACY,
                            selected);
                    }
                }
            }
        }
    }

    @Test
    public void contradictoryCapabilitiesAreRejectedInsteadOfTrustingLegacyFlag() {
        // legacySupported=true 但缺 VAO：不得选 legacy（缺一不可，不能信任单一标志）
        ChainPreviewGlCapabilities contradictoryVao =
            new ChainPreviewGlCapabilities(true, true, "3.3", "4.60", 16, false);
        ChainPreviewOverlayPath.Decision noVao =
            ChainPreviewOverlayPath.decide("legacy", contradictoryVao, false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.UNAVAILABLE, noVao.getPath());
        Assert.assertTrue(noVao.getReason().contains("VAO"));

        // legacySupported=true 但 attrib 不足：同样不得选 legacy
        ChainPreviewGlCapabilities contradictoryAttribs =
            new ChainPreviewGlCapabilities(true, true, "3.3", "4.60", 1, true);
        ChainPreviewOverlayPath.Decision fewAttribs =
            ChainPreviewOverlayPath.decide("legacy", contradictoryAttribs, false);
        Assert.assertEquals(ChainPreviewOverlayPath.Path.UNAVAILABLE, fewAttribs.getPath());
        Assert.assertTrue(fewAttribs.getReason().contains("GL_MAX_VERTEX_ATTRIBS"));

        Assert.assertTrue(
            ChainPreviewOverlayPath.legacyUnavailableReason(contradictoryVao).contains("VAO"));
        Assert.assertTrue(
            ChainPreviewOverlayPath.legacyUnavailableReason(contradictoryAttribs)
                .contains("GL_MAX_VERTEX_ATTRIBS"));
    }

    @Test
    public void legacyUnavailableReasonCoversEachMissingCapability() {
        Assert.assertNull(ChainPreviewOverlayPath.legacyUnavailableReason(fullCaps()));
        Assert.assertNull(ChainPreviewOverlayPath.legacyUnavailableReason(legacyOnlyCaps()));
        Assert.assertNotNull(ChainPreviewOverlayPath.legacyUnavailableReason(null));
        Assert.assertTrue(ChainPreviewOverlayPath.legacyUnavailableReason(noVaoCaps()).contains("VAO"));
        Assert.assertTrue(
            ChainPreviewOverlayPath.legacyUnavailableReason(noGl20WithVaoCaps()).contains("GL20"));
        Assert.assertTrue(
            ChainPreviewOverlayPath.legacyUnavailableReason(fewAttribsCaps())
                .contains("GL_MAX_VERTEX_ATTRIBS"));
    }

    @Test
    public void selectorContractUnaffectedByPathGate() {
        Assert.assertEquals(
            "selector 语义保持冻结（路径可用性判断在其上层）",
            ChainPreviewBackendSelector.LEGACY,
            ChainPreviewBackendSelector.select("auto", ChainPreviewGlCapabilities.UNSUPPORTED, false));
    }

    private static ChainPreviewGlCapabilities fullCaps() {
        return ChainPreviewGlCapabilities.probe(true, "3.3.0 NVIDIA 552.22", "4.60 NVIDIA", 16, true);
    }

    private static ChainPreviewGlCapabilities legacyOnlyCaps() {
        return ChainPreviewGlCapabilities.probe(true, "2.1", "1.10", 16, true);
    }

    private static ChainPreviewGlCapabilities noVaoCaps() {
        return ChainPreviewGlCapabilities.probe(true, "3.3.0", "4.60", 16, false);
    }

    private static ChainPreviewGlCapabilities noGl20WithVaoCaps() {
        return ChainPreviewGlCapabilities.probe(false, "2.1", "1.20", 16, true);
    }

    private static ChainPreviewGlCapabilities fewAttribsCaps() {
        return ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 1, true);
    }
}
