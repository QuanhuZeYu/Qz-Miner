package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 按方法内数据流验证 renderer 捕获 matrix mode、成对压栈出栈并恢复捕获值。
 * 真实 GL 接受恢复及其余状态归还由实机确认。
 */
public class CuboidSelectionRendererStructureTest {

    private static final String RENDERER_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/CuboidSelectionRenderer.java";

    @Test
    public void restoresPreviousMatrixMode() throws Exception {
        String render = JavaSourceSlices.methodBody(JavaSourceSlices.read(RENDERER_PATH),
                "private static void render(CuboidBounds bounds)", "CuboidSelectionRenderer.render");

        int capture = JavaSourceSlices.requireAt(render, "必须读回当前 matrix mode",
                "glGetInteger(GL11.GL_MATRIX_MODE)");
        String capturedMode = JavaSourceSlices.assignmentTarget(render, capture,
                "读回的 matrix mode 必须捕获进局部变量");

        JavaSourceSlices.assertBefore(render, "glPushMatrix()", "glPopMatrix()",
                "必须先压矩阵栈再弹出");
        JavaSourceSlices.assertBefore(render, "glPopMatrix()", "glMatrixMode(" + capturedMode + ")",
                "必须在弹出矩阵之后再恢复原 matrix mode");
        JavaSourceSlices.assertContains(render, "glMatrixMode(" + capturedMode + ")",
                "恢复必须用读回的变量（不得硬编码某个模式）");
    }
}
