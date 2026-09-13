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
    /** 前两个 quad 的 CPU 索引（展开后前 12 个三角形索引的期望来源）。 */
    private final int[] expectedQuadIndices = new int[8];
    /** CPU 侧顶点包围盒（minX,minY,minZ,maxX,maxY,maxZ；mesh 局部坐标）。 */
    private final float[] cpuBounds = new float[6];
    /** 顶点流总 float 数：用于回读整段 VBO 判「几何是否塌缩」。 */
    private int expectedFullFloatCount;
    private boolean pending;
    private int reports;
    /** EBO 展开后抽样的三角形索引个数（2 个 quad）。 */
    private static final int TRIANGLE_INDEX_SAMPLE = 12;

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
        expectedFullFloatCount = Math.max(0, vertexFloatCount);
        for (int index = 0; index < expectedQuadIndices.length; index++) {
            expectedQuadIndices[index] = quadIndices != null && index < quadIndexCount && index < quadIndices.length
                    ? quadIndices[index] : -1;
        }
        resetBounds(cpuBounds);
        accumulateBounds(vertices, expectedFullFloatCount, cpuBounds);
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
     * 回读整段顶点缓冲与 EBO 头部，判定「几何是否塌缩」：CPU 包围盒 vs GPU 包围盒、
     * 顶点头部逐项比对、EBO 前 12 个三角形索引逐项比对。
     *
     * <p>为什么必须回读整段：真机表型是「整链塌缩成一个面」，只看首个顶点无法区分
     * 「VBO 里的几何本身就塌了」与「投影把几何压平了」。</p>
     *
     * @param vbo 顶点缓冲句柄
     * @param ebo 索引缓冲句柄
     */
    void reportData(int vbo, int ebo, int triangleIndexCount, int gpuVertexCount) {
        float[] gpuBounds = new float[6];
        resetBounds(gpuBounds);
        boolean vertexHeadEqual = false;
        boolean indexEqual = false;
        String note = "ok";
        try {
            int previousArray = integerValue(GL15.GL_ARRAY_BUFFER_BINDING, 0);
            int previousElement = integerValue(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING, 0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            ByteBuffer vertexBytes = readBuffer(expectedFullFloatCount * 4);
            if (vertexBytes != null && expectedFullFloatCount >= 3) {
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, vertexBytes);
                vertexBytes.position(0);
                java.nio.FloatBuffer floats = vertexBytes.asFloatBuffer();
                accumulateBounds(floats, expectedFullFloatCount, gpuBounds);
                vertexHeadEqual = true;
                for (int index = 0; index < expectedVertexCount * 3; index++) {
                    if (Float.compare(floats.get(index), expectedPositions[index]) != 0) {
                        vertexHeadEqual = false;
                        note = "positionHead[" + index + "] gpu=" + fixed(floats.get(index))
                            + " cpu=" + fixed(expectedPositions[index]);
                        break;
                    }
                }
            }
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            int scanCount = Math.max(TRIANGLE_INDEX_SAMPLE, triangleIndexCount);
            ByteBuffer indexBytes = readBuffer(scanCount * 4);
            if (indexBytes != null) {
                GL15.glGetBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, 0L, indexBytes);
                indexBytes.position(0);
                java.nio.IntBuffer indices = indexBytes.asIntBuffer();
                // 整段扫描：最大索引必须小于顶点数，否则 GPU 会读到越界顶点（未定义值 ⇒ 几何塌缩外观）。
                indexMax = -1;
                for (int index = 0; index < triangleIndexCount; index++) {
                    int value = indices.get(index);
                    if (value > indexMax) {
                        indexMax = value;
                    }
                }
                indexOutOfRange = indexMax >= gpuVertexCount;
                int[] expectedTriangles = expandCpuQuads();
                indexEqual = true;
                for (int index = 0; index < expectedTriangles.length; index++) {
                    int actual = indices.get(index);
                    if (expectedTriangles[index] < 0 || actual != expectedTriangles[index]) {
                        indexEqual = false;
                        note = "indexEbo[" + index + "] gpu=" + actual + " cpu=" + expectedTriangles[index];
                        break;
                    }
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousElement);
        } catch (Throwable failure) {
            log("data{readback-failed=" + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "}");
            return;
        }
        boolean boundsMatch = boundsEqual(gpuBounds);
        log("data{note=" + note
            + ", vertexHeadEqual=" + vertexHeadEqual
            + ", indexEboHeadEqual=" + indexEqual
            + ", indexMax=" + indexMax
            + ", indexOutOfRange=" + indexOutOfRange
            + ", bboxCpu=" + boundsText(cpuBounds)
            + ", bboxGpu=" + boundsText(gpuBounds)
            + ", bboxEqual=" + boundsMatch
            + ", vertices=" + (expectedFullFloatCount / 3) + "}");
        // 结论行：把三类事实压成一条，真机一眼可判「是数据没上去，还是几何本身塌了，还是索引越界」。
        log("verdict{geometry=" + (boundsEquivalent() ? "cpu-uncollapsed" : "cpu-collapsed")
            + ", vbo=" + (boundsMatch ? "matches-cpu" : "MISMATCH")
            + ", index=" + (indexOutOfRange ? "OUT-OF-RANGE" : "ok")
            + ", head=" + (vertexHeadEqual ? "ok" : "mismatch")
            + "}");
    }

    /** 包围盒清零（空集用 min > max 表示）。 */
    private static void resetBounds(float[] bounds) {
        bounds[0] = Float.MAX_VALUE;
        bounds[1] = Float.MAX_VALUE;
        bounds[2] = Float.MAX_VALUE;
        bounds[3] = -Float.MAX_VALUE;
        bounds[4] = -Float.MAX_VALUE;
        bounds[5] = -Float.MAX_VALUE;
    }

    private static void accumulateBounds(float[] vertices, int floatCount, float[] bounds) {
        for (int index = 0; index + 2 < floatCount; index += 3) {
            accumulateVertex(bounds, vertices[index], vertices[index + 1], vertices[index + 2]);
        }
    }

    private static void accumulateBounds(java.nio.FloatBuffer vertices, int floatCount, float[] bounds) {
        for (int index = 0; index + 2 < floatCount; index += 3) {
            accumulateVertex(bounds, vertices.get(index), vertices.get(index + 1), vertices.get(index + 2));
        }
    }

    private static void accumulateVertex(float[] bounds, float x, float y, float z) {
        bounds[0] = Math.min(bounds[0], x);
        bounds[1] = Math.min(bounds[1], y);
        bounds[2] = Math.min(bounds[2], z);
        bounds[3] = Math.max(bounds[3], x);
        bounds[4] = Math.max(bounds[4], y);
        bounds[5] = Math.max(bounds[5], z);
    }

    private static String boundsText(float[] bounds) {
        if (bounds[0] > bounds[3]) {
            return "(empty)";
        }
        return "(" + fixed(bounds[0]) + "," + fixed(bounds[1]) + "," + fixed(bounds[2]) + ")..("
            + fixed(bounds[3]) + "," + fixed(bounds[4]) + "," + fixed(bounds[5]) + ")";
    }

    /** 索引最大值（整段扫描；-1 表示未扫描）。 */
    private int indexMax = -1;
    /** 最大索引是否越界（>= 顶点数 ⇒ GPU 读到未定义顶点）。 */
    private boolean indexOutOfRange;
    /** CPU 几何是否自证未塌缩（跨度 > 1.5 格即视为正常规模）。 */
    private boolean boundsEquivalent() {
        if (cpuBounds[0] > cpuBounds[3]) {
            return false;
        }
        return (cpuBounds[3] - cpuBounds[0]) > 1.5F || (cpuBounds[5] - cpuBounds[2]) > 1.5F;
    }

    /** GPU 侧包围盒是否与 CPU 侧一致（1e-4 容差；不一致即 VBO 内容不是这份几何）。 */
    private boolean boundsEqual(float[] gpu) {
        if (gpu[0] > gpu[3]) {
            return false;
        }
        for (int index = 0; index < 6; index++) {
            if (Math.abs(gpu[index] - cpuBounds[index]) > 1e-4F) {
                return false;
            }
        }
        return true;
    }

    /** 前两个 quad 按后端同样的规则展开为三角形索引（[a,b,c, a,c,d]）。 */
    private int[] expandCpuQuads() {
        int[] triangles = new int[TRIANGLE_INDEX_SAMPLE];
        int cursor = 0;
        for (int quad = 0; quad < 2; quad++) {
            int base = quad * 4;
            int a = expectedQuadIndices[base];
            int b = expectedQuadIndices[base + 1];
            int c = expectedQuadIndices[base + 2];
            int d = expectedQuadIndices[base + 3];
            if (a < 0 || b < 0 || c < 0 || d < 0 || cursor + 6 > triangles.length) {
                break;
            }
            triangles[cursor++] = a;
            triangles[cursor++] = b;
            triangles[cursor++] = c;
            triangles[cursor++] = a;
            triangles[cursor++] = c;
            triangles[cursor++] = d;
        }
        return triangles;
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

    /**
     * 上传后立即自证：在 {@code uploadTopology} 末尾调用（VAO 仍绑定、缓冲区刚写完）。
     *
     * <p>为什么必须与 draw 期的回读**同时存在**：draw 期读到 0 有两种完全不同的原因——
     * 「上传根本没写进去」与「写进去之后被清空（外部渲染路径 / 驱动）」；只有上传后立即回读能区分。</p>
     */
    void reportUpload(int vbo, int cbo, int abo, int ebo,
                      float[] vertices, int vertexFloatCount,
                      float[] colors, int colorFloatCount,
                      byte[] aux, int[] uploadIndices, int uploadIndexCount) {
        log("upload{vbo=" + probeFloat(vbo, GL15.GL_ARRAY_BUFFER, vertices, vertexFloatCount)
            + ", cbo=" + probeFloat(cbo, GL15.GL_ARRAY_BUFFER, colors, colorFloatCount)
            + ", abo=" + probeBytes(abo, GL15.GL_ARRAY_BUFFER, aux)
            + ", ebo=" + probeInts(ebo, GL15.GL_ELEMENT_ARRAY_BUFFER, uploadIndices, uploadIndexCount)
            + ", arrayBufferBound=" + integer(GL15.GL_ARRAY_BUFFER_BINDING)
            + ", elementBufferBound=" + integer(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING) + "}");
    }

    private String probeFloat(int bufferId, int target, float[] expected, int expectedCount) {
        if (bufferId == 0 || expected == null || expectedCount <= 0) {
            return "n/a";
        }
        int sample = Math.min(8, expectedCount);
        try {
            GL15.glBindBuffer(target, bufferId);
            ByteBuffer bytes = readBuffer(sample * 4);
            if (bytes == null) {
                return "no-buffer";
            }
            GL15.glGetBufferSubData(target, 0L, bytes);
            bytes.position(0);
            java.nio.FloatBuffer floats = bytes.asFloatBuffer();
            boolean allZero = true;
            for (int index = 0; index < sample; index++) {
                float value = floats.get(index);
                if (Float.compare(value, 0.0F) != 0) {
                    allZero = false;
                }
                if (Float.compare(value, expected[index]) != 0) {
                    return "mismatch[" + index + "]=" + fixed(value) + "/exp=" + fixed(expected[index]);
                }
            }
            return allZero && Float.compare(expected[0], 0.0F) != 0 ? "ZERO" : "ok";
        } catch (Throwable failure) {
            return "read-failed:" + failure.getClass().getSimpleName();
        }
    }

    private String probeInts(int bufferId, int target, int[] expected, int expectedCount) {
        if (bufferId == 0 || expected == null || expectedCount <= 0) {
            return "n/a";
        }
        int sample = Math.min(12, expectedCount);
        try {
            GL15.glBindBuffer(target, bufferId);
            ByteBuffer bytes = readBuffer(sample * 4);
            if (bytes == null) {
                return "no-buffer";
            }
            GL15.glGetBufferSubData(target, 0L, bytes);
            bytes.position(0);
            java.nio.IntBuffer ints = bytes.asIntBuffer();
            boolean allZero = true;
            for (int index = 0; index < sample; index++) {
                int value = ints.get(index);
                if (value != 0) {
                    allZero = false;
                }
                if (value != expected[index]) {
                    return "mismatch[" + index + "]=" + value + "/exp=" + expected[index];
                }
            }
            return allZero && expected[0] != 0 ? "ZERO" : "ok";
        } catch (Throwable failure) {
            return "read-failed:" + failure.getClass().getSimpleName();
        }
    }

    private String probeBytes(int bufferId, int target, byte[] expected) {
        if (bufferId == 0 || expected == null || expected.length == 0) {
            return "n/a";
        }
        int sample = Math.min(8, expected.length);
        try {
            GL15.glBindBuffer(target, bufferId);
            ByteBuffer bytes = readBuffer(sample);
            if (bytes == null) {
                return "no-buffer";
            }
            GL15.glGetBufferSubData(target, 0L, bytes);
            for (int index = 0; index < sample; index++) {
                if (bytes.get(index) != expected[index]) {
                    return "mismatch[" + index + "]=" + bytes.get(index) + "/exp=" + expected[index];
                }
            }
            return "ok";
        } catch (Throwable failure) {
            return "read-failed:" + failure.getClass().getSimpleName();
        }
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
