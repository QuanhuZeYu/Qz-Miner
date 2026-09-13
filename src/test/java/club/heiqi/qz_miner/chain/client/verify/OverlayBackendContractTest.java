package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlBindings;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlCapabilities;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlFences;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewOverlayPath;

/**
 * T27 波次 5 后端封装契约探针（B4.3 / task-26）。
 *
 * <p>能力→路径决策表按接口契约独立推导（不读 owner 断言）：shader 路径要求「能力支持 + 上次未失败」，
 * legacy 路径要求「GL20 顶点属性 + VAO + attrib >= 2」，二者都不满足必须显式 UNAVAILABLE 并给原因；
 * configured=legacy 不得改选 shader。围栏部分用假 GL seam 注入，断言「一次捕获一次恢复」、
 * 异常路径仍然配对、未 push 不 pop、未请求时零 GL 调用。</p>
 */
public class OverlayBackendContractTest {

    private static ChainPreviewGlCapabilities capabilities(
            boolean shaderSupported, boolean legacySupported, int attribs, boolean vaoSupported) {
        return new ChainPreviewGlCapabilities(
            shaderSupported, legacySupported, "4.6", "4.60", attribs, vaoSupported);
    }

    private static final ChainPreviewGlCapabilities BOTH =
        capabilities(true, true, 8, true);
    private static final ChainPreviewGlCapabilities SHADER_ONLY =
        capabilities(true, false, 8, false);
    private static final ChainPreviewGlCapabilities LEGACY_ONLY =
        capabilities(false, true, 8, true);
    private static final ChainPreviewGlCapabilities NO_ATTRIBS =
        capabilities(false, false, 1, true);

    @Test
    public void decisionTablePinsUnambiguousRows() {
        // auto / shader：能力支持且上次未失败 -> shader
        assertDecision("auto+both", ChainPreviewOverlayPath.decide("auto", BOTH, false),
            ChainPreviewOverlayPath.Path.SHADER, "shader");
        assertDecision("shader+both", ChainPreviewOverlayPath.decide("shader", BOTH, false),
            ChainPreviewOverlayPath.Path.SHADER, "shader");
        // legacy 显式档：即使 shader 可用也必须走 legacy
        assertDecision("legacy+both", ChainPreviewOverlayPath.decide("legacy", BOTH, false),
            ChainPreviewOverlayPath.Path.LEGACY, "legacy");
        // 能力不足 -> legacy
        assertDecision("auto+shaderUnsupported",
            ChainPreviewOverlayPath.decide("auto", LEGACY_ONLY, false),
            ChainPreviewOverlayPath.Path.LEGACY, "legacy");
        // 上次 shader 失败 -> legacy
        assertDecision("auto+shaderFailed", ChainPreviewOverlayPath.decide("auto", BOTH, true),
            ChainPreviewOverlayPath.Path.LEGACY, "legacy");
        // 两条路径都不可用 -> 显式降级
        assertDecision("shader+unsupported",
            ChainPreviewOverlayPath.decide("shader", NO_ATTRIBS, false),
            ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        assertDecision("legacy+shaderOnly",
            ChainPreviewOverlayPath.decide("legacy", SHADER_ONLY, false),
            ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        assertDecision("auto+shaderOnly+failed",
            ChainPreviewOverlayPath.decide("auto", SHADER_ONLY, true),
            ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        // 能力探测缺失：不得假定 legacy 可用
        assertDecision("auto+nullCaps", ChainPreviewOverlayPath.decide("auto", null, false),
            ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        assertDecision("null+unsupported", ChainPreviewOverlayPath.decide(null,
            ChainPreviewGlCapabilities.UNSUPPORTED, false),
            ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        // 未配置 / 未知值按 auto 归一
        assertDecision("null+both", ChainPreviewOverlayPath.decide(null, BOTH, false),
            ChainPreviewOverlayPath.Path.SHADER, "shader");
        assertDecision("bogus+both", ChainPreviewOverlayPath.decide("bogus", BOTH, false),
            ChainPreviewOverlayPath.Path.SHADER, "shader");
    }

    @Test
    public void decisionNeverSelectsPathThatIsNotUsable() {
        String[] configured = {null, "auto", "shader", "legacy", "bogus", "  shader  "};
        // 矛盾构造也纳入不变量：legacy 路径必须 VAO + attrib>=2 缺一不可
        // （render-core 已在 9b29b48 修：legacyUnavailableReason 逐项显式校验）。
        ChainPreviewGlCapabilities[] all = {
            null, ChainPreviewGlCapabilities.UNSUPPORTED, BOTH, SHADER_ONLY, LEGACY_ONLY, NO_ATTRIBS,
            capabilities(false, true, 8, true), capabilities(true, false, 1, true),
            capabilities(false, true, 8, false), capabilities(false, true, 1, true),
            capabilities(true, true, 8, false)
        };
        boolean[] failed = {false, true};
        int checked = 0;
        for (String value : configured) {
            for (ChainPreviewGlCapabilities caps : all) {
                for (boolean shaderFailed : failed) {
                    ChainPreviewOverlayPath.Decision decision =
                        ChainPreviewOverlayPath.decide(value, caps, shaderFailed);
                    checked++;
                    Assert.assertNotNull(decision.getPath());
                    Assert.assertTrue("原因必须永不为空", decision.getReason() != null
                        && decision.getReason().length() > 0);
                    Assert.assertEquals("backendId 仅在不可用时为 null",
                        decision.isUnavailable(), decision.getBackendId() == null);
                    Assert.assertEquals("usable 与 path 必须一致",
                        decision.getPath() != ChainPreviewOverlayPath.Path.UNAVAILABLE,
                        decision.isUsable());
                    if (decision.getPath() == ChainPreviewOverlayPath.Path.SHADER) {
                        Assert.assertNotNull(caps);
                        Assert.assertTrue("shader 路径必须能力支持", caps.isShaderSupported());
                        Assert.assertFalse("shader 上次失败后不得再选 shader", shaderFailed);
                    }
                    if (decision.getPath() == ChainPreviewOverlayPath.Path.LEGACY) {
                        Assert.assertNotNull("legacy 路径不得在能力缺失时选择", caps);
                        Assert.assertTrue("legacy 路径必须自报可用", caps.isLegacySupported());
                        Assert.assertTrue("legacy 路径必须 VAO 可用", caps.isVaoSupported());
                        Assert.assertTrue("legacy 路径必须 attrib >= 2",
                            caps.getMaxVertexAttribs() >= ChainPreviewGlCapabilities.REQUIRED_MAX_VERTEX_ATTRIBS);
                    }
                    if (decision.isUnavailable()) {
                        // 显式 legacy 档时 shader 不可选（配置优先），必须按「可选路径」判降级是否合理。
                        boolean legacyRequested = value != null && value.trim().equalsIgnoreCase("legacy");
                        boolean shaderSelectable = !legacyRequested && caps != null
                            && caps.isShaderSupported() && !shaderFailed;
                        boolean legacyUsable = caps != null && caps.isLegacySupported()
                            && caps.isVaoSupported()
                            && caps.getMaxVertexAttribs() >= ChainPreviewGlCapabilities.REQUIRED_MAX_VERTEX_ATTRIBS;
                        Assert.assertFalse("有可选路径时不得降级: configured=" + value,
                            shaderSelectable || legacyUsable);
                    }
                }
            }
        }
        Assert.assertEquals(configured.length * all.length * failed.length, checked);
    }

    @Test
    public void contradictoryCapabilityMustDegradeInsteadOfSelectingLegacy() {
        // 回归锁（F2 修复）：legacySupported=true 但缺 VAO / attrib<2 时不得选 LEGACY
        assertDecision("legacy+noVao", ChainPreviewOverlayPath.decide("legacy",
            capabilities(true, true, 8, false), false), ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        assertDecision("legacy+noAttribs", ChainPreviewOverlayPath.decide("legacy",
            capabilities(true, true, 1, true), false), ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        assertDecision("auto+shaderNoVao", ChainPreviewOverlayPath.decide("auto",
            capabilities(true, true, 8, false), true), ChainPreviewOverlayPath.Path.UNAVAILABLE, null);
        Assert.assertNotNull(ChainPreviewOverlayPath.legacyUnavailableReason(
            capabilities(false, true, 8, false)));
        Assert.assertNotNull(ChainPreviewOverlayPath.legacyUnavailableReason(
            capabilities(false, true, 1, true)));
    }

    @Test
    public void productionProbeNeverYieldsSelfContradictoryLegacyCapability() {
        // legacy 支持性必须蕴含 VAO + attribs>=2（否则 decide 的「缺一不可」判据形同虚设）
        ChainPreviewGlCapabilities[] probed = {
            ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 8, true),
            ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 8, false),
            ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 1, true),
            ChainPreviewGlCapabilities.probe(false, "4.6", "4.60", 8, true),
            ChainPreviewGlCapabilities.probe(true, "2.0", "1.10", 8, true)
        };
        for (ChainPreviewGlCapabilities caps : probed) {
            if (caps.isLegacySupported()) {
                Assert.assertTrue("probe 产物必须自洽: " + caps.describe(), caps.isVaoSupported());
                Assert.assertTrue("probe 产物必须自洽: " + caps.describe(),
                    caps.getMaxVertexAttribs() >= ChainPreviewGlCapabilities.REQUIRED_MAX_VERTEX_ATTRIBS);
            }
        }
        Assert.assertFalse("无 VAO 不得声称 legacy 可用",
            ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 8, false).isLegacySupported());
        Assert.assertFalse("attrib < 2 不得声称 legacy 可用",
            ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 1, true).isLegacySupported());
        Assert.assertTrue(ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 8, true).isLegacySupported());
    }

    @Test
    public void frameFencePairsCaptureAndRestoreExactlyOnce() {
        FakeGl gl = new FakeGl();
        int captures = 0;
        ChainPreviewGlFences.Frame frame =
            ChainPreviewGlFences.Frame.open(gl, new Runnable() {
                @Override
                public void run() {
                    // 捕获计数回调
                }
            });
        captures++;
        Assert.assertTrue(frame.isCaptured());
        Assert.assertFalse(frame.isClosed());
        Assert.assertEquals("", frame.getFailure());
        Assert.assertEquals("捕获必须恰好 3 次 glGetInteger",
            "[getInteger:1, getInteger:2, getInteger:3, pushAll, pushClient]", gl.ops.toString());
        frame.close();
        Assert.assertTrue(frame.isClosed());
        Assert.assertEquals("关闭必须按相反顺序弹出并恢复绑定",
            "[getInteger:1, getInteger:2, getInteger:3, pushAll, pushClient, popClient, popAll,"
                + " bindVertexArray:1, bindArrayBuffer:2, bindElementArrayBuffer:3]",
            gl.ops.toString());
        frame.close();
        Assert.assertEquals("close 必须幂等", 10, gl.ops.size());
        Assert.assertEquals(1, captures);
    }

    @Test
    public void frameFenceKeepsPairingOnExceptionPaths() {
        // pushAll 失败：不得弹出未压入的栈
        FakeGl pushAllFailed = new FakeGl().failPushAll();
        ChainPreviewGlFences.Frame first = ChainPreviewGlFences.Frame.open(pushAllFailed, null);
        first.close();
        Assert.assertTrue("push 失败必须记录原因", first.getFailure().length() > 0);
        Assert.assertEquals("未 push 不得 pop", 0, pushAllFailed.count("pop"));
        Assert.assertEquals("绑定恢复不得额外压栈", 1, pushAllFailed.count("pushAll"));
        Assert.assertFalse(pushAllFailed.ops.contains("pushClient"));

        // pushClient 失败：只弹已压入的 all
        FakeGl pushClientFailed = new FakeGl().failPushClient();
        ChainPreviewGlFences.Frame second = ChainPreviewGlFences.Frame.open(pushClientFailed, null);
        second.close();
        Assert.assertEquals(1, pushClientFailed.count("popAll"));
        Assert.assertEquals(0, pushClientFailed.count("popClient"));
        Assert.assertTrue(second.getFailure().length() > 0);

        // 绑定捕获失败：仍必须压栈并配对弹出，但不做绑定恢复
        FakeGl capturedFailed = new FakeGl().failCapture();
        ChainPreviewGlFences.Frame third = ChainPreviewGlFences.Frame.open(capturedFailed, null);
        Assert.assertFalse(third.isCaptured());
        Assert.assertTrue(third.getFailure().length() > 0);
        third.close();
        Assert.assertEquals(1, capturedFailed.count("popAll"));
        Assert.assertEquals(1, capturedFailed.count("popClient"));
        Assert.assertEquals(0, capturedFailed.count("bindVertexArray"));

        // 弹出抛异常：必须吞掉且继续恢复绑定
        FakeGl popFailed = new FakeGl().failPopClient();
        ChainPreviewGlFences.Frame fourth = ChainPreviewGlFences.Frame.open(popFailed, null);
        fourth.close();
        Assert.assertEquals(1, popFailed.count("popClient"));
        Assert.assertEquals(1, popFailed.count("popAll"));
        Assert.assertEquals(1, popFailed.count("bindVertexArray"));

        // 回调抛异常：不得逃逸，且不得影响围栏装配
        ChainPreviewGlFences.Frame fifth = ChainPreviewGlFences.Frame.open(new FakeGl(), new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("counter failure");
            }
        });
        Assert.assertTrue(fifth.isCaptured());
        fifth.close();
    }

    @Test
    public void frameFenceRejectsNullAccess() {
        try {
            ChainPreviewGlFences.Frame.open(null, null);
            Assert.fail("null access 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected);
        }
    }

    @Test
    public void textureFenceOnlyTouchesGlWhenRequestedAndCaptured() {
        FakeGl untouched = new FakeGl();
        ChainPreviewGlFences.Texture skipped = ChainPreviewGlFences.Texture.captureIfRequested(false, untouched);
        Assert.assertFalse(skipped.isActive());
        Assert.assertFalse(skipped.isCaptureFailed());
        skipped.close();
        Assert.assertEquals("未请求时必须零 GL 调用", "[]", untouched.ops.toString());

        FakeGl gl = new FakeGl();
        gl.enabled = false;
        gl.textureBinding = 4242;
        ChainPreviewGlFences.Texture texture = ChainPreviewGlFences.Texture.captureIfRequested(true, gl);
        Assert.assertTrue(texture.isActive());
        Assert.assertFalse(texture.isCaptureFailed());
        Assert.assertFalse(texture.isPreviousEnabled());
        Assert.assertEquals(4242, texture.getPreviousBinding());
        Assert.assertEquals("[isTexture2dEnabled, getTextureBinding2d]", gl.ops.toString());
        texture.close();
        Assert.assertEquals("关闭必须恢复 enable 与绑定",
            "[isTexture2dEnabled, getTextureBinding2d, setTexture2dEnabled:false, setTextureBinding2d:4242]",
            gl.ops.toString());
        texture.close();
        Assert.assertEquals("纹理围栏 close 必须幂等", 4, gl.ops.size());

        // 捕获失败：必须降级为不施加纹理乘子，且 close 零 GL 调用（不冒险改状态）
        FakeGl failed = new FakeGl().failTextureBinding();
        ChainPreviewGlFences.Texture broken = ChainPreviewGlFences.Texture.captureIfRequested(true, failed);
        Assert.assertFalse(broken.isActive());
        Assert.assertTrue(broken.isCaptureFailed());
        Assert.assertTrue(broken.getFailure().length() > 0);
        broken.close();
        Assert.assertEquals("捕获失败后不得改状态", "[isTexture2dEnabled, getTextureBinding2d]",
            failed.ops.toString());

        // 恢复抛异常：必须吞掉且继续恢复另一项
        FakeGl restoreFailed = new FakeGl().failSetEnabled();
        ChainPreviewGlFences.Texture fence = ChainPreviewGlFences.Texture.captureIfRequested(true, restoreFailed);
        fence.close();
        Assert.assertEquals("[isTexture2dEnabled, getTextureBinding2d, setTexture2dEnabled:true,"
            + " setTextureBinding2d:77]", restoreFailed.ops.toString());

        try {
            ChainPreviewGlFences.Texture.captureIfRequested(true, null);
            Assert.fail("null access 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected);
        }
    }

    private static void assertDecision(
            String label, ChainPreviewOverlayPath.Decision decision,
            ChainPreviewOverlayPath.Path expectedPath, String expectedBackendId) {
        Assert.assertEquals(label + " path", expectedPath, decision.getPath());
        Assert.assertEquals(label + " backendId", expectedBackendId, decision.getBackendId());
        Assert.assertTrue(label + " reason", decision.getReason().length() > 0);
    }

    /** 假 GL seam：记录调用序列，并可按需注入异常。 */
    private static final class FakeGl implements ChainPreviewGlFences.Access, ChainPreviewGlBindings.Access {

        final List<String> ops = new ArrayList<String>();
        boolean enabled = true;
        int textureBinding = 77;
        private int integerCalls;
        private boolean failPushAll;
        private boolean failPushClient;
        private boolean failPopClient;
        private boolean failCapture;
        private boolean failTextureBinding;
        private boolean failSetEnabled;

        FakeGl failPushAll() {
            failPushAll = true;
            return this;
        }

        FakeGl failPushClient() {
            failPushClient = true;
            return this;
        }

        FakeGl failPopClient() {
            failPopClient = true;
            return this;
        }

        FakeGl failCapture() {
            failCapture = true;
            return this;
        }

        FakeGl failTextureBinding() {
            failTextureBinding = true;
            return this;
        }

        FakeGl failSetEnabled() {
            failSetEnabled = true;
            return this;
        }

        int count(String prefix) {
            int total = 0;
            for (String op : ops) {
                if (op.startsWith(prefix)) {
                    total++;
                }
            }
            return total;
        }

        @Override
        public ChainPreviewGlBindings.Access bindings() {
            if (failCapture) {
                throw new IllegalStateException("context lost");
            }
            return this;
        }

        @Override
        public void pushAllAttribs() {
            ops.add("pushAll");
            if (failPushAll) {
                throw new IllegalStateException("pushAll failed");
            }
        }

        @Override
        public void pushClientVertexArrayAttribs() {
            ops.add("pushClient");
            if (failPushClient) {
                throw new IllegalStateException("pushClient failed");
            }
        }

        @Override
        public void popClientAttribs() {
            ops.add("popClient");
            if (failPopClient) {
                throw new IllegalStateException("popClient failed");
            }
        }

        @Override
        public void popAttribs() {
            ops.add("popAll");
        }

        @Override
        public boolean isTexture2dEnabled() {
            ops.add("isTexture2dEnabled");
            return enabled;
        }

        @Override
        public int getTextureBinding2d() {
            ops.add("getTextureBinding2d");
            if (failTextureBinding) {
                throw new IllegalStateException("binding query failed");
            }
            return textureBinding;
        }

        @Override
        public void setTexture2dEnabled(boolean nextEnabled) {
            ops.add("setTexture2dEnabled:" + nextEnabled);
            if (failSetEnabled) {
                throw new IllegalStateException("enable failed");
            }
        }

        @Override
        public void setTextureBinding2d(int binding) {
            ops.add("setTextureBinding2d:" + binding);
        }

        @Override
        public int getInteger(int pname) {
            int captured = ++integerCalls;
            ops.add("getInteger:" + captured);
            return captured;
        }

        @Override
        public void bindVertexArray(int vertexArray) {
            ops.add("bindVertexArray:" + vertexArray);
        }

        @Override
        public void bindArrayBuffer(int buffer) {
            ops.add("bindArrayBuffer:" + buffer);
        }

        @Override
        public void bindElementArrayBuffer(int buffer) {
            ops.add("bindElementArrayBuffer:" + buffer);
        }
    }
}
