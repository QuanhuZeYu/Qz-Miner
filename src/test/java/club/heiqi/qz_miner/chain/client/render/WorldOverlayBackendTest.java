package club.heiqi.qz_miner.chain.client.render;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

/**
 * T26 / B4.3 封装契约：能力探测缓存与可观察失败、帧级围栏计数、帧外静默围栏、
 * GPU 释放路由与异常隔离、线程契约（debug 门控）、资源重载惰性令牌、生命周期清理。
 */
public class WorldOverlayBackendTest {

    @Test
    public void capabilitiesProbedOnceAndReusedUntilInvalidated() {
        CountingProbe probe = new CountingProbe(fullCaps());
        WorldOverlayBackend overlay = overlay(probe);

        Assert.assertTrue(overlay.capabilities().isShaderSupported());
        Assert.assertTrue(overlay.capabilities().isShaderSupported());
        Assert.assertEquals("能力探测每 lifecycle 一次", 1, probe.calls);
        Assert.assertEquals(1L, overlay.getProbeCount());

        overlay.markResourcesDirty("textureStitch");
        Assert.assertTrue(overlay.isResourceDirty());
        Assert.assertEquals("markResourcesDirty 必须惰性：信号处不得探测", 1, probe.calls);

        Assert.assertTrue(overlay.consumeResourceReload());
        Assert.assertFalse("令牌只消费一次", overlay.consumeResourceReload());
        Assert.assertFalse(overlay.isResourceDirty());

        overlay.capabilities();
        Assert.assertEquals("资源重载后必须重探", 2, probe.calls);
        Assert.assertEquals(2L, overlay.getProbeCount());
        Assert.assertEquals("textureStitch", overlay.getLastResourceReason());
        Assert.assertEquals(1L, overlay.getResourceReloads());
    }

    @Test
    public void probeExceptionIsObservableAndDegradesToUnsupported() {
        CountingProbe probe = new CountingProbe(fullCaps());
        probe.throwFailure = true;
        WorldOverlayBackend overlay = overlay(probe);

        Assert.assertFalse(overlay.capabilities().isShaderSupported());
        Assert.assertFalse(overlay.capabilities().isLegacySupported());
        Assert.assertEquals(1L, overlay.getProbeFailures());
        Assert.assertTrue(overlay.getLastProbeFailure().contains("IllegalStateException"));
        Assert.assertTrue(overlay.describe().contains("probeFailures=1"));
        Assert.assertTrue(overlay.describe().contains("probeFailure="));

        ChainPreviewOverlayPath.Decision decision = overlay.planPath("auto", false);
        Assert.assertTrue(decision.isUnavailable());
        Assert.assertEquals(1L, overlay.getDegradationEvents());
        Assert.assertFalse(overlay.getCurrentDegradationReason().isEmpty());
        Assert.assertTrue(overlay.describe().contains("degradations=1"));
    }

    @Test
    public void probeFailureResultCarriesReasonAndNullResultIsTreatedAsFailure() {
        CountingProbe reported = new CountingProbe(fullCaps());
        reported.failureReason = "GLContext capabilities unavailable (no context)";
        WorldOverlayBackend withReason = overlay(reported);
        withReason.capabilities();
        Assert.assertEquals(
            "GLContext capabilities unavailable (no context)",
            withReason.getLastProbeFailure());

        WorldOverlayBackend withNull = new WorldOverlayBackend(
            new ChainPreviewScaleCounters(),
            new ChainPreviewTestGlAccess(),
            () -> null);
        withNull.capabilities();
        Assert.assertEquals(1L, withNull.getProbeFailures());
        Assert.assertTrue(withNull.getLastProbeFailure().contains("null"));
    }

    @Test
    public void degradationEventsAccumulateOnlyWhenReasonChanges() {
        CountingProbe probe = new CountingProbe(noVaoCaps());
        WorldOverlayBackend overlay = overlay(probe);

        Assert.assertTrue(overlay.planPath("legacy", false).isUnavailable());
        Assert.assertTrue(overlay.planPath("legacy", false).isUnavailable());
        Assert.assertEquals("同一原因不重复计数", 1L, overlay.getDegradationEvents());

        probe.capabilities = fewAttribsCaps();
        overlay.markResourcesDirty("test");
        Assert.assertTrue(overlay.planPath("auto", false).isUnavailable());
        Assert.assertEquals("原因变化后重新计数", 2L, overlay.getDegradationEvents());

        probe.capabilities = fullCaps();
        overlay.markResourcesDirty("test");
        Assert.assertTrue(overlay.planPath("auto", false).isUsable());
        Assert.assertEquals("恢复可用后清空当前降级原因", "", overlay.getCurrentDegradationReason());
        Assert.assertEquals(2L, overlay.getDegradationEvents());
    }

    @Test
    public void frameFenceCountsCaptureThroughWrapper() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        WorldOverlayBackend overlay = new WorldOverlayBackend(counters, access, new CountingProbe(fullCaps()));

        ChainPreviewGlFences.Frame frame = overlay.beginFrame();
        Assert.assertEquals(1L, overlay.getFrames());
        Assert.assertEquals(1L, counters.getFrameCaptures());
        Assert.assertEquals(
            ChainPreviewGlBindings.CAPTURED_QUERY_COUNT,
            counters.getGlIntegerReads());
        frame.close();
        Assert.assertEquals(
            Arrays.asList(
                "pushAll", "pushClient", "popClient", "popAll",
                "bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);

        overlay.beginFrame().close();
        Assert.assertEquals(2L, overlay.getFrames());
        Assert.assertEquals(2L, counters.getFrameCaptures());
        Assert.assertEquals(2L * ChainPreviewGlBindings.CAPTURED_QUERY_COUNT, counters.getGlIntegerReads());
    }

    @Test
    public void frameOpenFailureIsObservableAndDoesNotThrow() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.failQueries = true;
        WorldOverlayBackend overlay = new WorldOverlayBackend(
            new ChainPreviewScaleCounters(), access, new CountingProbe(fullCaps()));

        overlay.beginFrame().close();
        Assert.assertEquals(1L, overlay.getFrameOpenFailures());
        Assert.assertTrue(overlay.getLastFrameFailure().contains("IllegalStateException"));
        Assert.assertTrue(overlay.describe().contains("frameOpenFailures=1"));
    }

    @Test
    public void clearMeshUsesQuietFenceWithoutChargingFrameCounters() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        WorldOverlayBackend overlay = new WorldOverlayBackend(counters, access, new CountingProbe(fullCaps()));
        RecordingBackend backend = new RecordingBackend();

        overlay.clearMesh(backend);

        Assert.assertEquals(1, backend.uploadTopologyCalls);
        Assert.assertNotNull(backend.lastMesh);
        Assert.assertTrue("clearMesh 上传空网格", backend.lastMesh.isEmpty());
        Assert.assertEquals("帧外围栏不计帧级捕获", 0L, counters.getFrameCaptures());
        Assert.assertEquals(0L, counters.getGlIntegerReads());
        Assert.assertEquals(3, access.queries.size());
        Assert.assertEquals(
            Arrays.asList("bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);
    }

    @Test
    public void runFencedRestoresBindingsWhenBodyThrows() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        WorldOverlayBackend overlay = new WorldOverlayBackend(
            new ChainPreviewScaleCounters(), access, new CountingProbe(fullCaps()));

        boolean thrown = false;
        try {
            overlay.runFenced("test", () -> {
                throw new IllegalStateException("body boom");
            });
        } catch (IllegalStateException expected) {
            thrown = true;
        }

        Assert.assertTrue("body 异常照常上抛", thrown);
        Assert.assertEquals(
            Arrays.asList("bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);
    }

    @Test
    public void disposeRoutesThroughOverlayAndIsolatesFailures() {
        WorldOverlayBackend overlay = overlay(new CountingProbe(fullCaps()));
        RecordingBackend backend = new RecordingBackend();

        overlay.dispose(backend);
        Assert.assertEquals(1, backend.disposeCalls);
        Assert.assertEquals(1L, overlay.getDisposals());
        Assert.assertEquals(0L, overlay.getDisposalFailures());

        overlay.dispose(new FailingDisposeBackend());
        Assert.assertEquals(2L, overlay.getDisposals());
        Assert.assertEquals(1L, overlay.getDisposalFailures());
        Assert.assertTrue(overlay.getLastDisposalFailure().contains("dispose failure"));
        Assert.assertTrue(overlay.describe().contains("disposalFailures=1"));

        overlay.dispose(null);
        Assert.assertEquals("null 后端无操作", 2L, overlay.getDisposals());
    }

    @Test
    public void threadContractViolationIsPureAndDebugGated() {
        Assert.assertEquals("", WorldOverlayBackend.threadContractViolation(true, true, "dispose"));
        Assert.assertEquals("", WorldOverlayBackend.threadContractViolation(false, false, "dispose"));
        String violation = WorldOverlayBackend.threadContractViolation(true, false, "dispose");
        Assert.assertTrue(violation.contains("render-thread"));
        Assert.assertTrue(violation.contains("dispose"));
        Assert.assertFalse(WorldOverlayBackend.threadContractViolation(true, false, null).isEmpty());
    }

    @Test
    public void offRenderThreadDisposeIsReportedOnlyWhenEnforced() throws Exception {
        WorldOverlayBackend overlay = overlay(new CountingProbe(fullCaps()));
        overlay.setThreadContractEnforced(Boolean.TRUE);
        overlay.beginFrame().close();
        Assert.assertEquals(0L, overlay.getContractViolations());

        Thread worker = new Thread(() -> overlay.dispose(new RecordingBackend()), "overlay-probe");
        worker.start();
        worker.join();

        Assert.assertEquals(1L, overlay.getContractViolations());
        Assert.assertTrue(overlay.getLastContractViolation().contains("render-thread"));
        Assert.assertTrue(overlay.describe().contains("contractViolations=1"));

        overlay.dispose(new RecordingBackend());
        Assert.assertEquals("渲染线程内释放不再计违反", 1L, overlay.getContractViolations());

        WorldOverlayBackend ungated = overlay(new CountingProbe(fullCaps()));
        ungated.setThreadContractEnforced(Boolean.FALSE);
        ungated.beginFrame().close();
        Thread second = new Thread(() -> ungated.dispose(new RecordingBackend()), "overlay-probe-off");
        second.start();
        second.join();
        Assert.assertEquals("debug 关闭时零校验开销（不记录违反）", 0L, ungated.getContractViolations());
    }

    @Test
    public void resetForLifecycleClearsDiagnosticsAndForcesReprobe() {
        CountingProbe probe = new CountingProbe(fullCaps());
        WorldOverlayBackend overlay = overlay(probe);
        overlay.capabilities();
        overlay.markResourcesDirty("x");
        overlay.beginFrame().close();
        overlay.dispose(new RecordingBackend());

        overlay.resetForLifecycle();

        Assert.assertEquals(0L, overlay.getProbeCount());
        Assert.assertEquals(0L, overlay.getFrames());
        Assert.assertEquals(0L, overlay.getDisposals());
        Assert.assertEquals(0L, overlay.getResourceReloads());
        Assert.assertEquals(0L, overlay.getContractViolations());
        Assert.assertFalse(overlay.isResourceDirty());
        Assert.assertTrue(overlay.describe().contains("caps=unprobed"));
        Assert.assertTrue(overlay.getLastResourceReason().isEmpty());

        overlay.capabilities();
        Assert.assertEquals("生命周期后必须重新探测", 2, probe.calls);
    }

    @Test
    public void describeExposesEveryDiagnosticChannel() {
        WorldOverlayBackend overlay = overlay(new CountingProbe(fullCaps()));
        overlay.capabilities();
        overlay.beginFrame().close();
        overlay.dispose(new RecordingBackend());
        overlay.markResourcesDirty("textureStitch");
        overlay.planPath("auto", false);

        String text = overlay.describe();
        for (String channel : new String[] {
            "overlay{", "caps=", "probes=", "probeFailures=", "frames=", "frameOpenFailures=",
            "disposals=", "disposalFailures=", "degradations=", "resourceReloads=",
            "resourceDirty=", "contractViolations="
        }) {
            Assert.assertTrue("describe 缺少诊断通道 " + channel + ": " + text, text.contains(channel));
        }
    }

    @Test
    public void injectedGlAccessIsSharedWithLegacyBackend() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        WorldOverlayBackend overlay = new WorldOverlayBackend(
            new ChainPreviewScaleCounters(), access, new CountingProbe(fullCaps()));
        Assert.assertSame(access, overlay.glAccess());
        Assert.assertSame(access, overlay.glAccess());
        Assert.assertNotNull(overlay.getScaleCounters());
    }

    private static WorldOverlayBackend overlay(CountingProbe probe) {
        return new WorldOverlayBackend(new ChainPreviewScaleCounters(), new ChainPreviewTestGlAccess(), probe);
    }

    private static ChainPreviewGlCapabilities fullCaps() {
        return ChainPreviewGlCapabilities.probe(true, "3.3.0", "4.60", 16, true);
    }

    private static ChainPreviewGlCapabilities noVaoCaps() {
        return ChainPreviewGlCapabilities.probe(true, "3.3.0", "4.60", 16, false);
    }

    private static ChainPreviewGlCapabilities fewAttribsCaps() {
        return ChainPreviewGlCapabilities.probe(true, "2.1", "1.20", 1, true);
    }

    /** 可注入探测点：计数 + 可切换结果 / 失败 / 异常。 */
    private static final class CountingProbe implements WorldOverlayBackend.CapabilityProbe {

        int calls;
        ChainPreviewGlCapabilities capabilities;
        String failureReason;
        boolean throwFailure;

        CountingProbe(ChainPreviewGlCapabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public ChainPreviewGlCapabilities.ProbeResult probe() {
            calls++;
            if (throwFailure) {
                throw new IllegalStateException("probe boom");
            }
            ChainPreviewGlCapabilities caps = capabilities == null
                ? ChainPreviewGlCapabilities.UNSUPPORTED
                : capabilities;
            if (failureReason != null) {
                return ChainPreviewGlCapabilities.ProbeResult.failure(caps, failureReason);
            }
            return ChainPreviewGlCapabilities.ProbeResult.success(caps);
        }
    }

    /** 后端假实现：记录上传 / 释放。 */
    private static class RecordingBackend implements ChainPreviewRenderBackend {

        int uploadTopologyCalls;
        int disposeCalls;
        ChainPreviewMesh lastMesh;

        @Override
        public String id() {
            return ChainPreviewBackendSelector.LEGACY;
        }

        @Override
        public boolean usesCpuColors() {
            return true;
        }

        @Override
        public boolean ensureReady() {
            return true;
        }

        @Override
        public void uploadTopology(ChainPreviewMesh mesh) {
            uploadTopologyCalls++;
            lastMesh = mesh;
        }

        @Override
        public boolean uploadColors(ChainPreviewMesh mesh) {
            return true;
        }

        @Override
        public void draw(ChainPreviewDrawPlan plan) {
        }

        @Override
        public void dispose() {
            disposeCalls++;
        }

        @Override
        public String describe() {
            return "recording";
        }
    }

    /** 释放抛异常的后端：验证封装层异常隔离。 */
    private static final class FailingDisposeBackend extends RecordingBackend {

        @Override
        public void dispose() {
            throw new IllegalStateException("dispose failure");
        }
    }
}
