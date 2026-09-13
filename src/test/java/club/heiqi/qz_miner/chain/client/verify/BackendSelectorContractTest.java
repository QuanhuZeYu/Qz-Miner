package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewBackendSelector;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlCapabilities;

/**
 * T7 后端选择与能力探测独立契约探针（接口冻结 §C / §G，Lead 裁定删除 shader pack 维度后）。
 *
 * <p>决策表只包含真实能力与「上次 shader 失败」两个维度；pack 接管维度已按裁定删除，
 * 未接线注入点不保留，故本探针不得再依赖它。</p>
 */
public class BackendSelectorContractTest {

    @Test
    public void selectorDecisionTableMatchesFrozenContract() {
        ChainPreviewGlCapabilities supported = caps(true);
        ChainPreviewGlCapabilities unsupported = caps(false);

        assertSelect("legacy", supported, false, "legacy");
        assertSelect("legacy", unsupported, false, "legacy");
        assertSelect("auto", null, false, "legacy");
        assertSelect("auto", unsupported, false, "legacy");
        assertSelect("auto", supported, false, "shader");
        assertSelect("auto", supported, true, "legacy");
        assertSelect("shader", supported, false, "shader");
        assertSelect("shader", unsupported, false, "legacy");
        assertSelect("shader", supported, true, "legacy");
        assertSelect(null, supported, false, "shader");
        assertSelect("", supported, false, "shader");
        assertSelect("  AUTO  ", supported, false, "shader");
        assertSelect("SHADER", supported, false, "shader");
        assertSelect("banana", supported, false, "shader");
    }

    @Test
    public void selectorNeverReturnsNullAndIsStable() {
        String[] configured = {null, "", "auto", "shader", "legacy", "AUTO", "ShAdEr", "unknown"};
        ChainPreviewGlCapabilities[] capabilities = {
            null,
            ChainPreviewGlCapabilities.UNSUPPORTED,
            caps(true),
            caps(false)
        };
        for (String value : configured) {
            for (ChainPreviewGlCapabilities capability : capabilities) {
                for (boolean failed : new boolean[] {false, true}) {
                    String selected = ChainPreviewBackendSelector.select(value, capability, failed);
                    Assert.assertNotNull(selected);
                    Assert.assertTrue(
                        "非 shader 即 legacy：" + selected,
                        ChainPreviewBackendSelector.LEGACY.equals(selected)
                            || ChainPreviewBackendSelector.SHADER.equals(selected));
                    Assert.assertEquals(
                        "纯函数必须可重复",
                        selected,
                        ChainPreviewBackendSelector.select(value, capability, failed));
                }
            }
        }
    }

    @Test
    public void explainCoversEveryFallbackCause() {
        ChainPreviewGlCapabilities supported = caps(true);
        String configuredLegacy = ChainPreviewBackendSelector.explain("legacy", supported, false);
        String unavailable = ChainPreviewBackendSelector.explain("auto", null, false);
        String unsupported = ChainPreviewBackendSelector.explain("auto", caps(false), false);
        String failed = ChainPreviewBackendSelector.explain("auto", supported, true);
        String ok = ChainPreviewBackendSelector.explain("auto", supported, false);
        for (String reason : new String[] {configuredLegacy, unavailable, unsupported, failed, ok}) {
            Assert.assertNotNull(reason);
            Assert.assertTrue("诊断原因不得为空", reason.length() > 0);
        }
        Assert.assertNotEquals(configuredLegacy, ok);
        Assert.assertNotEquals(unavailable, ok);
        Assert.assertNotEquals(unsupported, ok);
        Assert.assertNotEquals(failed, ok);
    }

    @Test
    public void capabilityProbeParsesVersionsAndFallsBackSafely() {
        Assert.assertFalse(capabilityProbe(false, "4.6", "4.60", 16, true).isShaderSupported());
        Assert.assertTrue(capabilityProbe(true, "2.1", "1.20", 2, true).isShaderSupported());
        Assert.assertFalse(
            "attrib 数不足必须不支持",
            capabilityProbe(true, "2.1", "1.20", 1, true).isShaderSupported());
        Assert.assertFalse(
            "GL 版本不足必须不支持",
            capabilityProbe(true, "1.4", "1.20", 16, true).isShaderSupported());
        Assert.assertFalse(
            "GLSL 版本不足必须不支持",
            capabilityProbe(true, "2.1", "1.10", 16, true).isShaderSupported());
        Assert.assertTrue(
            "带厂商后缀的版本串必须可解析",
            capabilityProbe(true, "3.3.0 NVIDIA 552.22", "4.60 NVIDIA", 32, true).isShaderSupported());
        Assert.assertFalse(
            "OpenGL ES 串必须兜底不支持",
            capabilityProbe(true, "OpenGL ES 3.0", "OpenGL ES GLSL ES 3.0", 32, true).isShaderSupported());
        Assert.assertFalse(
            "GLSL 串缺失必须不支持",
            capabilityProbe(true, "2.1", null, 16, true).isShaderSupported());
        Assert.assertFalse(
            "GLSL 空串必须不支持",
            capabilityProbe(true, "2.1", "", 16, true).isShaderSupported());
        Assert.assertFalse(
            "无 GL20 上下文必须不支持",
            capabilityProbe(true, null, null, 0, false).isShaderSupported());

        Assert.assertTrue(ChainPreviewGlCapabilities.versionAtLeast("2.1", 2, 1));
        Assert.assertTrue(ChainPreviewGlCapabilities.versionAtLeast("3", 2, 1));
        Assert.assertTrue(ChainPreviewGlCapabilities.versionAtLeast("10.0", 2, 1));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast("2.0", 2, 1));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast("1.20", 2, 1));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast(null, 2, 1));
        Assert.assertFalse(ChainPreviewGlCapabilities.versionAtLeast("", 2, 1));

        Assert.assertFalse(ChainPreviewGlCapabilities.UNSUPPORTED.isShaderSupported());
        Assert.assertFalse(ChainPreviewGlCapabilities.UNSUPPORTED.isVaoSupported());
        Assert.assertTrue(ChainPreviewGlCapabilities.UNSUPPORTED.describe().length() > 0);
    }

    private static void assertSelect(
            String configured,
            ChainPreviewGlCapabilities capabilities,
            boolean lastAttemptFailed,
            String expected) {
        Assert.assertEquals(
            "configured=" + configured + " caps=" + (capabilities == null ? "null" : capabilities.describe())
                + " failed=" + lastAttemptFailed,
            expected,
            ChainPreviewBackendSelector.select(configured, capabilities, lastAttemptFailed));
    }

    private static ChainPreviewGlCapabilities caps(boolean shaderSupported) {
        return new ChainPreviewGlCapabilities(shaderSupported, "2.1", "1.20", 16, true);
    }

    private static ChainPreviewGlCapabilities capabilityProbe(
            boolean gl20Available,
            String glVersion,
            String glslVersion,
            int maxVertexAttribs,
            boolean vaoSupported) {
        return ChainPreviewGlCapabilities.probe(
            gl20Available, glVersion, glslVersion, maxVertexAttribs, vaoSupported);
    }
}
