package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.testsupport.JavaSourceSlices;

/**
 * 框选 renderer 必须恢复调用前的 matrix mode。
 *
 * <p><b>为什么是结构断言</b>：GL 调用在 headless 里没有行为探针——没有 GL 上下文时
 * {@code GL11} 的静态入口本身就会失败，因此这里改判<strong>数据流与顺序</strong>：
 * 先读回当前 matrix mode 并捕获进局部变量，压栈绘制、出栈，最后用<strong>捕获到的那个变量</strong>恢复。
 * 旧写法断言 {@code text.contains("glGetInteger(GL11.GL_MATRIX_MODE)")} 一类整段调用文本，
 * 改局部变量名、换等价写法即误报；现在切方法体后只比标识符与位置。</p>
 *
 * <p><b>能证伪什么</b>：删掉读回（改成硬编码模式）、删掉出栈、把恢复写在出栈之前、
 * 恢复时改用别的值——都会红。</p>
 *
 * <p><b>守不到什么</b>：GL 是否真的接受了这次恢复、绘制期间有没有别的状态泄漏——那要真机。</p>
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
