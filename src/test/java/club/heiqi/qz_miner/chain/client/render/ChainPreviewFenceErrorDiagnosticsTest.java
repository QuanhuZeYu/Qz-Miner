package club.heiqi.qz_miner.chain.client.render;

import java.util.Arrays;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * T48c-B：帧围栏 push/pop 的 GL 错误一次性诊断（1282 真机定位用）。
 *
 * <p>契约：错误只上报一次/阶段；诊断不改变围栏捕获/压栈/弹出/恢复的配对语义；
 * glGetError 自身抛异常也不得逃逸渲染帧。</p>
 */
public class ChainPreviewFenceErrorDiagnosticsTest {

    @Before
    public void resetDiagnostics() {
        ChainPreviewGlFences.resetFenceErrorDiagnosticsForTest();
    }

    @After
    public void clearDiagnostics() {
        ChainPreviewGlFences.resetFenceErrorDiagnosticsForTest();
    }

    @Test
    public void pushAttribErrorIsReportedOnceWithPhaseAndCode() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.glErrors.add(Integer.valueOf(1282));

        ChainPreviewGlFences.Frame first = ChainPreviewGlFences.Frame.open(access, null);
        Assert.assertEquals("pushAttrib", ChainPreviewGlFences.getLastFenceErrorPhase());
        Assert.assertEquals(1282, ChainPreviewGlFences.getLastFenceErrorCode());
        Assert.assertEquals(1, ChainPreviewGlFences.getFenceErrorReportCount());
        first.close();

        ChainPreviewTestGlAccess second = new ChainPreviewTestGlAccess();
        second.glErrors.add(Integer.valueOf(1282));
        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(second, null);
        Assert.assertEquals("同一阶段只上报一次", 1, ChainPreviewGlFences.getFenceErrorReportCount());
        frame.close();
    }

    @Test
    public void distinctPhasesReportIndependently() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.glErrors.add(Integer.valueOf(1282));
        access.glErrors.add(Integer.valueOf(0));
        access.glErrors.add(Integer.valueOf(0));
        access.glErrors.add(Integer.valueOf(1281));

        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, null);
        frame.close();

        Assert.assertEquals("popAttrib", ChainPreviewGlFences.getLastFenceErrorPhase());
        Assert.assertEquals(1281, ChainPreviewGlFences.getLastFenceErrorCode());
        Assert.assertEquals(2, ChainPreviewGlFences.getFenceErrorReportCount());
    }

    @Test
    public void noErrorKeepsDiagnosticsEmptyAndPairingIntact() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();

        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, null);
        frame.close();

        Assert.assertEquals("", ChainPreviewGlFences.getLastFenceErrorPhase());
        Assert.assertEquals(0, ChainPreviewGlFences.getLastFenceErrorCode());
        Assert.assertEquals(0, ChainPreviewGlFences.getFenceErrorReportCount());
        Assert.assertEquals(
            Arrays.asList(
                "pushAll", "pushClient", "popClient", "popAll",
                "bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);
    }

    @Test
    public void throwingGlGetErrorNeverEscapesTheFence() {
        ChainPreviewTestGlAccess access = new ChainPreviewTestGlAccess();
        access.failConsumeGlError = true;

        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(access, null);
        frame.close();

        Assert.assertEquals(0, ChainPreviewGlFences.getFenceErrorReportCount());
        Assert.assertEquals(
            Arrays.asList(
                "pushAll", "pushClient", "popClient", "popAll",
                "bindVao:7", "bindArray:11", "bindElement:13"),
            access.events);
    }

    @Test
    public void legacyFakeImplementationsKeepWorkingWithDefaultConsumeGlError() {
        ChainPreviewGlFences.Access defaultAccess = new ChainPreviewGlFences.Access() {
            @Override
            public ChainPreviewGlBindings.Access bindings() {
                return null;
            }

            @Override
            public void pushAllAttribs() {
            }

            @Override
            public void pushClientVertexArrayAttribs() {
            }

            @Override
            public void popClientAttribs() {
            }

            @Override
            public void popAttribs() {
            }

            @Override
            public boolean isTexture2dEnabled() {
                return false;
            }

            @Override
            public int getTextureBinding2d() {
                return 0;
            }

            @Override
            public void setTexture2dEnabled(boolean enabled) {
            }

            @Override
            public void setTextureBinding2d(int binding) {
            }
        };

        Assert.assertEquals(0, defaultAccess.consumeGlError());
        ChainPreviewGlFences.Frame frame = ChainPreviewGlFences.Frame.open(defaultAccess, null);
        frame.close();
        Assert.assertEquals(0, ChainPreviewGlFences.getFenceErrorReportCount());
    }
}
