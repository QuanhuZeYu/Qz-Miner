package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlBindings;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlCapabilities;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewGlFences;
import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewOverlayPath;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRenderBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewScaleCounters;
import club.heiqi.qz_miner.chain.client.render.WorldOverlayBackend;

/**
 * T27 波次 5 后端封装契约探针（B4.3 / task-26）。
 *
 * <p>覆盖任务要求：能力探测惰性/缓存/失效、能力→路径决策的降级事件计数（原因变化才计）、
 * 帧围栏与帧外围栏的配对与异常路径、资源释放异常隔离与线程契约（debug 档软校验）、
 * 资源重载惰性消费。全部用注入 seam 离线驱动，不加载 GL。</p>
 */
public class WorldOverlayContractTest {

    private static final ChainPreviewGlCapabilities BOTH =
        new ChainPreviewGlCapabilities(true, true, "4.6", "4.60", 8, true);
    private static final ChainPreviewGlCapabilities UNSUPPORTED =
        ChainPreviewGlCapabilities.UNSUPPORTED;

    private static WorldOverlayBackend backend(
            FakeGl gl, ChainPreviewScaleCounters counters, CapabilityProbeStub probe) {
        return new WorldOverlayBackend(counters, gl, probe);
    }

    @Test
    public void capabilitiesAreLazyCachedAndInvalidatedByResourceDirty() {
        CapabilityProbeStub probe = new CapabilityProbeStub();
        probe.result = ChainPreviewGlCapabilities.ProbeResult.success(BOTH);
        WorldOverlayBackend overlay = backend(new FakeGl(), new ChainPreviewScaleCounters(), probe);
        Assert.assertEquals("probe", "unprobed", overlay.describeCapabilities());

        Assert.assertSame(BOTH, overlay.capabilities());
        Assert.assertEquals("首次调用必须探测一次", 1, probe.calls.get());
        Assert.assertSame(BOTH, overlay.capabilities());
        Assert.assertEquals("缓存命中不得重复探测", 1, probe.calls.get());
        Assert.assertEquals(1L, overlay.getProbeCount());

        overlay.markResourcesDirty("context reload");
        Assert.assertSame("脏位后必须重新探测", BOTH, overlay.capabilities());
        Assert.assertEquals(2, probe.calls.get());
        Assert.assertEquals(2L, overlay.getProbeCount());
        Assert.assertEquals(0L, overlay.getProbeFailures());
    }

    @Test
    public void probeFailureDegradesToUnsupportedWithoutEscaping() {
        CapabilityProbeStub probe = new CapabilityProbeStub();
        WorldOverlayBackend overlay = backend(new FakeGl(), new ChainPreviewScaleCounters(), probe);

        probe.result = null;
        Assert.assertSame(UNSUPPORTED, overlay.capabilities());
        Assert.assertEquals(1L, overlay.getProbeFailures());
        Assert.assertTrue(overlay.getLastProbeFailure().length() > 0);

        overlay.markResourcesDirty("retry");
        probe.failure = new IllegalStateException("gl context lost");
        Assert.assertSame("探测异常必须收敛为 UNSUPPORTED", UNSUPPORTED, overlay.capabilities());
        Assert.assertEquals(2L, overlay.getProbeFailures());
        Assert.assertTrue(overlay.getLastProbeFailure().contains("gl context lost"));

        overlay.markResourcesDirty("retry2");
        probe.failure = null;
        probe.result = ChainPreviewGlCapabilities.ProbeResult.failure(BOTH, "partial");
        Assert.assertSame("探测失败但带能力时必须采用该能力", BOTH, overlay.capabilities());
        Assert.assertEquals(3L, overlay.getProbeFailures());
        Assert.assertEquals("partial", overlay.getLastProbeFailure());
    }

    @Test
    public void planPathCachesDecisionAndCountsOnlyChangedDegradation() {
        CapabilityProbeStub probe = new CapabilityProbeStub();
        probe.result = ChainPreviewGlCapabilities.ProbeResult.success(UNSUPPORTED);
        WorldOverlayBackend overlay = backend(new FakeGl(), new ChainPreviewScaleCounters(), probe);

        ChainPreviewOverlayPath.Decision first = overlay.planPath("auto", false);
        Assert.assertTrue(first.isUnavailable());
        Assert.assertEquals("首次降级计一次", 1L, overlay.getDegradationEvents());
        Assert.assertSame("输入未变必须复用同一决策实例", first, overlay.planPath("auto", false));
        Assert.assertEquals("重复决策不得累加噪声", 1L, overlay.getDegradationEvents());

        Assert.assertTrue(overlay.planPath("auto", true).isUnavailable());
        Assert.assertEquals("原因未变不得重复计数", 1L, overlay.getDegradationEvents());
        Assert.assertTrue(overlay.getCurrentDegradationReason().length() > 0);

        probe.result = ChainPreviewGlCapabilities.ProbeResult.success(BOTH);
        overlay.markResourcesDirty("caps change");
        Assert.assertTrue("能力恢复后必须可选 shader", overlay.planPath("auto", false).isUsable());
        Assert.assertEquals("恢复可用必须清空当前降级原因", "", overlay.getCurrentDegradationReason());
        Assert.assertEquals("可用不得新增降级事件", 1L, overlay.getDegradationEvents());
    }

    @Test
    public void frameFenceCountsCapturesOncePerFrameAndPairsPushPop() {
        FakeGl gl = new FakeGl();
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        WorldOverlayBackend overlay = backend(gl, counters, successProbe(BOTH));

        ChainPreviewGlFences.Frame frame = overlay.beginFrame();
        Assert.assertTrue(frame.isCaptured());
        Assert.assertEquals(1L, overlay.getFrames());
        Assert.assertEquals("帧级捕获计入计数器", 1L, counters.getFrameCaptures());
        Assert.assertEquals(3L, counters.getGlIntegerReads());
        frame.close();
        Assert.assertEquals("[getInteger:1, getInteger:2, getInteger:3, pushAll, pushClient,"
            + " popClient, popAll, bindVertexArray:1, bindArrayBuffer:2, bindElementArrayBuffer:3]",
            gl.ops.toString());

        overlay.beginFrame().close();
        Assert.assertEquals(2L, overlay.getFrames());
        Assert.assertEquals(2L, counters.getFrameCaptures());
        Assert.assertEquals(0L, overlay.getFrameOpenFailures());

        FakeGl broken = new FakeGl();
        broken.failCapture = true;
        WorldOverlayBackend brokenOverlay = backend(broken, new ChainPreviewScaleCounters(), successProbe(BOTH));
        ChainPreviewGlFences.Frame failed = brokenOverlay.beginFrame();
        Assert.assertFalse(failed.isCaptured());
        Assert.assertEquals(1L, brokenOverlay.getFrameOpenFailures());
        Assert.assertTrue(brokenOverlay.getLastFrameFailure().length() > 0);
        failed.close();
        Assert.assertEquals("捕获失败仍必须完成压栈配对", 1, broken.count("pushAll"));
        Assert.assertEquals(1, broken.count("popAll"));
    }

    @Test
    public void runFencedAndClearMeshStaySilentOnFailure() {
        FakeGl gl = new FakeGl();
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        WorldOverlayBackend overlay = backend(gl, counters, successProbe(BOTH));

        final AtomicInteger runs = new AtomicInteger();
        overlay.runFenced("probe", new Runnable() {
            @Override
            public void run() {
                runs.incrementAndGet();
            }
        });
        Assert.assertEquals(1, runs.get());
        Assert.assertEquals("[getInteger:1, getInteger:2, getInteger:3,"
            + " bindVertexArray:1, bindArrayBuffer:2, bindElementArrayBuffer:3]", gl.ops.toString());
        Assert.assertEquals("帧外围栏不得计入帧级捕获", 0L, counters.getFrameCaptures());

        FakeGl throwingBody = new FakeGl();
        WorldOverlayBackend bodyOverlay = backend(throwingBody, counters, successProbe(BOTH));
        try {
            bodyOverlay.runFenced("body", new Runnable() {
                @Override
                public void run() {
                    throw new IllegalStateException("body failure");
                }
            });
            Assert.fail("body 异常必须照常上抛");
        } catch (IllegalStateException expected) {
            Assert.assertNotNull(expected);
        }
        Assert.assertEquals("body 异常后仍必须恢复绑定", 1, throwingBody.count("bindVertexArray"));

        FakeGl contextLost = new FakeGl();
        contextLost.failCapture = true;
        WorldOverlayBackend lostOverlay = backend(contextLost, counters, successProbe(BOTH));
        final AtomicInteger lostRuns = new AtomicInteger();
        lostOverlay.runFenced("lost", new Runnable() {
            @Override
            public void run() {
                lostRuns.incrementAndGet();
            }
        });
        Assert.assertEquals("上下文丢失不得阻断帧外操作", 1, lostRuns.get());
        Assert.assertEquals("无快照时不得恢复绑定", 0, contextLost.count("bindVertexArray"));

        overlay.runFenced("nullBody", null);
        overlay.clearMesh(null);
        Assert.assertEquals("空输入必须零 GL 调用", 6, gl.ops.size());

        FakeBackend target = new FakeBackend();
        overlay.clearMesh(target);
        Assert.assertEquals("[upload:empty]", target.calls.toString());
    }

    @Test
    public void disposeIsIsolatedAndThreadContractIsSoftDebugOnly() {
        FakeGl gl = new FakeGl();
        WorldOverlayBackend overlay = backend(gl, new ChainPreviewScaleCounters(), successProbe(BOTH));
        overlay.setThreadContractEnforced(Boolean.TRUE);
        Assert.assertTrue(overlay.isThreadContractEnforced());

        overlay.beginFrame().close();
        Assert.assertEquals("首次 beginFrame 绑定渲染线程", 0L, overlay.getContractViolations());

        FakeBackend backend = new FakeBackend();
        runOffThread(new Runnable() {
            @Override
            public void run() {
                overlay.dispose(backend);
            }
        });
        Assert.assertEquals("非渲染线程释放必须被记录", 1L, overlay.getContractViolations());
        Assert.assertTrue(overlay.getLastContractViolation().contains("dispose"));
        Assert.assertEquals("线程契约是软校验：释放照常执行", 1, backend.calls.size());
        runOffThread(new Runnable() {
            @Override
            public void run() {
                overlay.dispose(new FakeBackend());
            }
        });
        // 契约计数器统计每次违反；去重只作用于诊断日志（避免刷屏）。
        Assert.assertEquals("每次违反都必须计数", 2L, overlay.getContractViolations());
        Assert.assertEquals("违反原因文本保持稳定", 1, overlay.getLastContractViolation().indexOf("dispose") >= 0 ? 1 : 0);
        Assert.assertEquals(2L, overlay.getDisposals());

        FakeBackend failing = new FakeBackend();
        failing.failDispose = true;
        overlay.dispose(failing);
        Assert.assertEquals(1L, overlay.getDisposalFailures());
        Assert.assertTrue(overlay.getLastDisposalFailure().contains("dispose failed"));
        Assert.assertEquals(3L, overlay.getDisposals());
        overlay.dispose(null);
        Assert.assertEquals("null 后端必须无操作", 3L, overlay.getDisposals());

        Assert.assertEquals("", WorldOverlayBackend.threadContractViolation(false, false, "dispose"));
        Assert.assertEquals("", WorldOverlayBackend.threadContractViolation(true, true, "dispose"));
        Assert.assertTrue(WorldOverlayBackend.threadContractViolation(true, false, "dispose").contains("dispose"));
        Assert.assertTrue(WorldOverlayBackend.threadContractViolation(true, false, null).contains("operation"));

        overlay.setThreadContractEnforced(Boolean.FALSE);
        Assert.assertFalse(overlay.isThreadContractEnforced());
        overlay.setThreadContractEnforced(null);
        Assert.assertNotNull(Boolean.valueOf(overlay.isThreadContractEnforced()));

        overlay.setThreadContractEnforced(Boolean.TRUE);
        overlay.resetForLifecycle();
        Assert.assertEquals("生命周期重置必须清零契约诊断", 0L, overlay.getContractViolations());
        Assert.assertEquals(0L, overlay.getFrames());
        runOffThread(new Runnable() {
            @Override
            public void run() {
                overlay.beginFrame().close();
            }
        });
        Assert.assertEquals("重置后新线程重新成为渲染线程属预期", 0L, overlay.getContractViolations());
    }

    @Test
    public void resourceReloadIsLazyAndConsumable() {
        CapabilityProbeStub probe = new CapabilityProbeStub();
        probe.result = ChainPreviewGlCapabilities.ProbeResult.success(BOTH);
        WorldOverlayBackend overlay = backend(new FakeGl(), new ChainPreviewScaleCounters(), probe);
        Assert.assertSame(BOTH, overlay.capabilities());
        Assert.assertEquals(1, probe.calls.get());
        Assert.assertFalse(overlay.consumeResourceReload());

        overlay.markResourcesDirty("context lost");
        Assert.assertTrue(overlay.isResourceDirty());
        Assert.assertEquals(1L, overlay.getResourceReloads());
        Assert.assertEquals("context lost", overlay.getLastResourceReason());
        Assert.assertTrue("重载信号必须置能力脏位", overlay.capabilities() == BOTH);
        Assert.assertEquals("脏位后重新探测", 2, probe.calls.get());

        Assert.assertTrue("首次消费必须返回 true", overlay.consumeResourceReload());
        Assert.assertFalse("二次消费必须返回 false", overlay.consumeResourceReload());
        Assert.assertFalse(overlay.isResourceDirty());

        overlay.markResourcesDirty(null);
        Assert.assertEquals("unspecified", overlay.getLastResourceReason());

        overlay.resetForLifecycle();
        Assert.assertEquals(0L, overlay.getResourceReloads());
        Assert.assertEquals("", overlay.getLastResourceReason());
        Assert.assertFalse(overlay.isResourceDirty());
        Assert.assertEquals(0L, overlay.getProbeCount());
        Assert.assertSame(BOTH, overlay.capabilities());
        Assert.assertEquals("重置后后端计数归零并重新探测", 1L, overlay.getProbeCount());
        Assert.assertEquals("桩累计调用数继续增长", 3, probe.calls.get());
    }

    private static CapabilityProbeStub successProbe(ChainPreviewGlCapabilities caps) {
        CapabilityProbeStub probe = new CapabilityProbeStub();
        probe.result = ChainPreviewGlCapabilities.ProbeResult.success(caps);
        return probe;
    }

    private static void runOffThread(Runnable body) {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    body.run();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }
        }, "verify-off-render-thread");
        thread.start();
        try {
            thread.join(10000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        Assert.assertNull("离线程操作不得抛异常", failure.get());
    }

    /** 能力探测 seam 假实现。 */
    private static final class CapabilityProbeStub implements WorldOverlayBackend.CapabilityProbe {

        final AtomicInteger calls = new AtomicInteger();
        volatile ChainPreviewGlCapabilities.ProbeResult result;
        volatile RuntimeException failure;

        @Override
        public ChainPreviewGlCapabilities.ProbeResult probe() {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** 后端假实现：记录调用并可按需在释放时抛异常。 */
    private static final class FakeBackend implements ChainPreviewRenderBackend {

        final List<String> calls = new ArrayList<String>();
        boolean failDispose;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean usesCpuColors() {
            return true;
        }

        @Override
        public boolean ensureReady() {
            calls.add("ensureReady");
            return true;
        }

        @Override
        public void uploadTopology(ChainPreviewMesh mesh) {
            calls.add("upload:" + (mesh == null ? "null" : (mesh.isEmpty() ? "empty" : "full")));
        }

        @Override
        public boolean uploadColors(ChainPreviewMesh mesh) {
            calls.add("colors");
            return true;
        }

        @Override
        public void draw(ChainPreviewDrawPlan plan) {
            calls.add("draw");
        }

        @Override
        public void dispose() {
            calls.add("dispose");
            if (failDispose) {
                throw new IllegalStateException("dispose failed");
            }
        }

        @Override
        public String describe() {
            return "fake backend";
        }
    }

    /** 假 GL seam：记录调用序列；failCapture 模拟上下文丢失。 */
    private static final class FakeGl implements ChainPreviewGlFences.Access, ChainPreviewGlBindings.Access {

        final List<String> ops = new ArrayList<String>();
        boolean failCapture;
        private int integerCalls;

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
        }

        @Override
        public void pushClientVertexArrayAttribs() {
            ops.add("pushClient");
        }

        @Override
        public void popClientAttribs() {
            ops.add("popClient");
        }

        @Override
        public void popAttribs() {
            ops.add("popAll");
        }

        @Override
        public boolean isTexture2dEnabled() {
            ops.add("isTexture2dEnabled");
            return true;
        }

        @Override
        public int getTextureBinding2d() {
            ops.add("getTextureBinding2d");
            return 7;
        }

        @Override
        public void setTexture2dEnabled(boolean enabled) {
            ops.add("setTexture2dEnabled:" + enabled);
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
