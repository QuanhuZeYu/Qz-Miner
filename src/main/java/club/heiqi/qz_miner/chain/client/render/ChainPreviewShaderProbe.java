package club.heiqi.qz_miner.chain.client.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;

import club.heiqi.qz_miner.MyMod;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * T49 临时探针：把「shader 路径这一帧到底拿到了什么」一次性打全，供真机日志定位病灶。
 *
 * <p><b>为什么直接植入、不带开关</b>：上一轮的教训是「诊断开关没人打开 ⇒ 失败不可见 ⇒ 离线全绿而真机假正常」。
 * 本探针在会话内无条件输出，但有界（每份几何一次、会话上限 {@link #MAX_REPORTS} 组），
 * 打完即静默，不影响帧率与观感。</p>
 *
 * <p><b>拆除条件与时机</b>：着色器路径真机取证完成、且与 legacy 观感 A/B 通过后，
 * 删除本类与 {@link ChainPreviewShaderBackend} 中全部 {@code probe.} 调用点（连同 {@code captureMesh}）。
 * 在此之前不得把它当产品代码依赖。</p>
 *
 * <p>输出行语义（全部以 {@link #PREFIX} 开头，便于 grep）：
 * <ul>
 *   <li>{@code ctx}：几何规模、可见范围、句柄；</li>
 *   <li>{@code bindings}：VAO 绑定态下逐槽回读 attrib 的 buffer/格式/enabled（对照期望值，命中「属性布局失效」）；</li>
 *   <li>{@code buffers}：各 buffer 的 GL 容量（命中「容量重建后指针未更新」）；</li>
 *   <li>{@code data}：回读 VBO/EBO 头部与 CPU 期望逐项比对（命中「传上去的数据本身错」）；</li>
 *   <li>{@code matrix}：复用 {@link ChainPreviewShaderMatrixSnapshot}（P/MV/MVP + 锚点 clip/NDC）；</li>
 *   <li>{@code uniform}：{@code glGetUniformfv} 回读 uModelViewProjection / uModelView 与上传值比对
 *       （no-error context 下「上传成功」不等于「GPU 在用」，这条是唯一自证）；</li>
 *   <li>{@code abort}：提前返回（矩阵不可信 / 未就绪）时的停止点与原因。</li>
 * </ul></p>
 */
final class ChainPreviewShaderProbe {

    /** 探针行前缀（真机 grep 用）。 */
    static final String PREFIX = "[ChainPreview][probe] ";

    /** 会话内最多输出组数（有界；连锁预览每代一次几何上传，正常远低于此值）。 */
    private static final int MAX_REPORTS = 8;
    /** CPU 期望快照保留的顶点数 / 索引数（有界，够看头部即可）。 */
    private static final int MAX_VERTICES = 8;
    private static final int MAX_INDICES = 6;

    /** CPU 期望顶点（首 MAX_VERTICES 个，每顶点 3 float）。 */
    private final float[] expectedPositions = new float[MAX_VERTICES * 3];
    /** CPU 期望索引（首 MAX_INDICES 个 quad 索引）。 */
    private final int[] expectedIndices = new int[MAX_INDICES];
    private int expectedVertexCount;
    private int expectedIndexCount;
    private int expectedFloatCount;
    private boolean pending;
    private int reports;

    /** 回读缓冲（渲染线程惰性创建，零静态初始化）。 */
    private ByteBuffer readBuffer;
    private FloatBuffer uniformBuffer;
    /** LWJGL2 只有 glGetVertexAttribfv（整数参数按 float 返回，值域为小整数，精度无损）。 */
    private FloatBuffer attribBuffer;

    /**
     * 上传拓扑时登记 CPU 期望快照（有界）。
     *
     * @param vertices         mesh 顶点流（3 float/顶点）
     * @param vertexFloatCount 顶点流长度
     * @param quadIndices      mesh quad 索引流
     * @param quadIndexCount   索引流长度
     */
    void captureMesh(float[] vertices, int vertexFloatCount, int[] quadIndices, int quadIndexCount) {
        expectedFloatCount = vertexFloatCount;
        expectedVertexCount = Math.min(MAX_VERTICES, Math.max(0, vertexFloatCount / 3));
        for (int index = 0; index < expectedVertexCount * 3; index++) {
            expectedPositions[index] = vertices != null && index < vertices.length ? vertices[index] : 0.0F;
        }
        expectedIndexCount = Math.min(MAX_INDICES, Math.max(0, quadIndexCount));
        for (int index = 0; index < expectedIndexCount; index++) {
            expectedIndices[index] = quadIndices != null && index < quadIndices.length ? quadIndices[index] : -1;
        }
        pending = true;
    }

    /** @return 本次绘制是否需要输出探针 */
    boolean ready() {
        return pending && reports < MAX_REPORTS;
    }

    /** 本次绘制已取证：打上「已消费」标记（无论成功还是提前返回）。 */
    void consumed() {
        pending = false;
        reports++;
    }

    void reportContext(int generation, int vao, int vbo, int cbo, int abo, int ebo,
                       int vertexCount, int indexCount, int visibleIndexCount, int indexOffset,
                       int quadIndexOffset, boolean matrixTrusted) {
        log("ctx{" + "report=" + (reports + 1) + "/" + MAX_REPORTS
            + ", generation=" + generation
            + ", vao=" + vao + ", vbo=" + vbo + ", cbo=" + cbo + ", abo=" + abo + ", ebo=" + ebo
            + ", vertexCount=" + vertexCount + ", indexCount=" + indexCount
            + ", visibleIndexCount=" + visibleIndexCount + ", indexOffset(tri)=" + indexOffset
            + ", indexOffset(quad)=" + quadIndexOffset
            + ", expectedVertexFloatCount=" + expectedFloatCount
            + ", expectedQuadIndexCount=" + expectedIndexCount
            + ", matrixTrusted=" + matrixTrusted + "}");
    }

    void reportBindings(int expectedVao, int expectedEbo, int expectedVbo, int expectedCbo, int expectedAbo) {
        Integer vaoBound = integer(GL30.GL_VERTEX_ARRAY_BINDING);
        Integer eboBound = integer(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        Integer arrayBound = integer(GL15.GL_ARRAY_BUFFER_BINDING);
        StringBuilder text = new StringBuilder("bindings{");
        text.append("vaoBound=").append(vaoBound).append("/expected=").append(expectedVao);
        text.append(", eboBound=").append(eboBound).append("/expected=").append(expectedEbo);
        text.append(", arrayBufferBound=").append(arrayBound);
        for (int slot = 0; slot < 4; slot++) {
            text.append(", a").append(slot).append("={buf=").append(attribInt(slot, GL15.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING))
                .append(",enabled=").append(attribInt(slot, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED))
                .append(",size=").append(attribInt(slot, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE))
                .append(",type=").append(attribInt(slot, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE))
                .append(",stride=").append(attribInt(slot, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE))
                .append(",normalized=").append(attribInt(slot, GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED))
                .append('}');
        }
        text.append("}, expectedBuffers={vbo=").append(expectedVbo).append(", cbo=").append(expectedCbo)
            .append(", abo=").append(expectedAbo).append('}');
        log(text.toString());
    }

    void reportBuffers(int vboSize, int cboSize, int aboSize, int eboSize) {
        log("buffers{vboSize=" + vboSize + ", cboSize=" + cboSize + ", aboSize=" + aboSize
            + ", eboSize=" + eboSize + "}");
    }

    /**
     * 回读 VBO / EBO 头部并与 CPU 期望逐项比对，只报第一处不一致。
     *
     * @param vbo 顶点缓冲句柄
     * @param ebo 索引缓冲句柄
     */
    void reportData(int vbo, int ebo) {
        String firstMismatch = "none";
        float[] gpuVertices = new float[expectedVertexCount * 3];
        int[] gpuIndices = new int[expectedIndexCount];
        try {
            int previousArray = integerValue(GL15.GL_ARRAY_BUFFER_BINDING, 0);
            int previousElement = integerValue(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING, 0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            ByteBuffer vertexBytes = readBuffer(expectedVertexCount * 3 * 4);
            if (vertexBytes != null && expectedVertexCount > 0) {
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, vertexBytes);
                vertexBytes.position(0);
                for (int index = 0; index < gpuVertices.length; index++) {
                    gpuVertices[index] = vertexBytes.asFloatBuffer().get(index);
                }
            }
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            ByteBuffer indexBytes = readBuffer(expectedIndexCount * 4);
            if (indexBytes != null && expectedIndexCount > 0) {
                GL15.glGetBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, indexBytes);
                indexBytes.position(0);
                for (int index = 0; index < gpuIndices.length; index++) {
                    gpuIndices[index] = indexBytes.asIntBuffer().get(index);
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousElement);
        } catch (Throwable failure) {
            log("data{readback-failed=" + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "}");
            return;
        }
        for (int index = 0; index < gpuVertices.length; index++) {
            if (Float.compare(gpuVertices[index], expectedPositions[index]) != 0) {
                firstMismatch = "positionVbo[" + index + "] gpu=" + fixed(gpuVertices[index])
                    + " cpu=" + fixed(expectedPositions[index]);
                break;
            }
        }
        if ("none".equals(firstMismatch)) {
            for (int index = 0; index < gpuIndices.length; index++) {
                if (gpuIndices[index] != expectedIndices[index]) {
                    firstMismatch = "indexEbo[" + index + "] gpu=" + gpuIndices[index] + " cpu=" + expectedIndices[index];
                    break;
                }
            }
        }
        log("data{firstMismatch=" + firstMismatch
            + ", gpuVertex0=" + tuple3(gpuVertices, 0)
            + ", gpuIndex0..3=" + ints(gpuIndices)
            + ", cpuVertex0=" + tuple3(expectedPositions, 0)
            + ", cpuIndex0..3=" + ints(expectedIndices) + "}");
    }

    void reportMatrix(float[] projection, float[] modelView, float[] mvp,
                      double expectedMagnitude, float actualMagnitude,
                      double[] renderPos, float viewYaw, float viewPitch, int[] origin,
                      int indexCount, int vertexCount) {
        float[] anchorLocal = new float[] {expectedPositions[0], expectedPositions[1], expectedPositions[2]};
        float[] anchorClip = new float[4];
        multiplyVector(mvp, anchorLocal, anchorClip);
        log(ChainPreviewShaderMatrixSnapshot.format(
            projection, modelView, mvp, expectedMagnitude, actualMagnitude,
            renderPos, viewYaw, viewPitch, origin, indexCount, vertexCount, anchorLocal, anchorClip));
    }

    /**
     * uniform 回读自证：no-error context 下 {@code glUniformMatrix4fv} 无错可查，
     * 只有把值读回来才能证明 GPU 侧真的拿到了这份矩阵。
     *
     * @param programId 程序句柄
     * @param locations 逐个回读的 (名称, location) 对
     */
    void reportUniform(int programId, int[] locations, String[] names, float[][] uploads) {
        if (programId == 0) {
            log("uniform{programId=0}");
            return;
        }
        StringBuilder text = new StringBuilder("uniform{");
        for (int index = 0; index < names.length; index++) {
            if (index > 0) {
                text.append(", ");
            }
            int location = locations[index];
            text.append(names[index]).append("Location=").append(location);
            if (location < 0) {
                continue;
            }
            try {
                FloatBuffer buffer = uniformBuffer(16);
                if (buffer == null) {
                    continue;
                }
                buffer.clear();
                GL20.glGetUniform(programId, location, buffer);
                buffer.position(0);
                float maxDifference = 0.0F;
                for (int element = 0; element < 16; element++) {
                    float uploaded = uploads[index] != null && element < uploads[index].length
                        ? uploads[index][element] : 0.0F;
                    maxDifference = Math.max(maxDifference, Math.abs(buffer.get(element) - uploaded));
                }
                text.append(", maxAbsDiff=").append(fixed(maxDifference));
            } catch (Throwable failure) {
                text.append(", readbackFailed=").append(failure.getClass().getSimpleName());
            }
        }
        text.append('}');
        log(text.toString());
    }

    void reportAbort(String stage, String reason) {
        log("abort{stage=" + stage + ", reason=" + reason + "}");
    }

    // ---------------------------------------------------------------- 内部

    private void log(String text) {
        try {
            MyMod.LOG.info(PREFIX + text);
        } catch (Throwable ignored) {
            // 诊断日志不得影响渲染帧。
        }
    }

    private Integer integer(int pname) {
        try {
            return Integer.valueOf(GL11.glGetInteger(pname));
        } catch (Throwable failure) {
            return null;
        }
    }

    private int integerValue(int pname, int fallback) {
        Integer value = integer(pname);
        return value == null ? fallback : value.intValue();
    }

    private int attribInt(int slot, int pname) {
        try {
            if (attribBuffer == null) {
                attribBuffer = BufferUtils.createFloatBuffer(16);
            }
            attribBuffer.clear();
            GL20.glGetVertexAttrib(slot, pname, attribBuffer);
            return (int) attribBuffer.get(0);
        } catch (Throwable failure) {
            return Integer.MIN_VALUE;
        }
    }

    private ByteBuffer readBuffer(int bytes) {
        if (bytes <= 0) {
            return null;
        }
        if (readBuffer == null || readBuffer.capacity() < bytes) {
            readBuffer = BufferUtils.createByteBuffer(Math.max(bytes, 1024));
        }
        readBuffer.clear();
        return readBuffer;
    }

    private FloatBuffer uniformBuffer(int floats) {
        if (uniformBuffer == null || uniformBuffer.capacity() < floats) {
            uniformBuffer = BufferUtils.createFloatBuffer(floats);
        }
        return uniformBuffer;
    }

    /** 列主序 4×4 × vec4（GL 约定）。 */
    private static void multiplyVector(float[] matrix, float[] vector, float[] out) {
        if (matrix == null || matrix.length < 16) {
            out[0] = 0.0F; out[1] = 0.0F; out[2] = 0.0F; out[3] = 0.0F;
            return;
        }
        float x = vector.length > 0 ? vector[0] : 0.0F;
        float y = vector.length > 1 ? vector[1] : 0.0F;
        float z = vector.length > 2 ? vector[2] : 0.0F;
        // vec3 视作齐次坐标 w = 1（与 shader 里 vec4(aPos, 1.0) 同源）。
        float w = vector.length > 3 ? vector[3] : 1.0F;
        for (int row = 0; row < 4; row++) {
            out[row] = matrix[row] * x + matrix[4 + row] * y + matrix[8 + row] * z + matrix[12 + row] * w;
        }
    }

    private static String tuple3(float[] values, int vertex) {
        int base = vertex * 3;
        if (values == null || base + 2 >= values.length) {
            return "(0.000000,0.000000,0.000000)";
        }
        return "(" + fixed(values[base]) + "," + fixed(values[base + 1]) + "," + fixed(values[base + 2]) + ")";
    }

    private static String ints(int[] values) {
        if (values == null || values.length == 0) {
            return "[]";
        }
        StringBuilder text = new StringBuilder("[");
        int limit = Math.min(4, values.length);
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                text.append(',');
            }
            text.append(values[index]);
        }
        return text.append(']').toString();
    }

    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
