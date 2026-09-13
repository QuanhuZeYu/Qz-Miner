package club.heiqi.qz_miner.chain.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

/**
 * 帧级 GL 绑定快照：VAO + ARRAY_BUFFER + ELEMENT_ARRAY_BUFFER。
 *
 * <p>B0.4 口径：绑定捕获每帧最多 3 次 glGetInteger（VAO / ARRAY_BUFFER / ELEMENT_ARRAY_BUFFER），
 * 帧末恢复一次；历史实现每帧 4 次 glGetInteger（3 次绑定 + 1 次 GL_MATRIX_MODE），
 * 矩阵模式已由调用方 glPushAttrib(GL_ALL_ATTRIB_BITS) / glPopAttrib 覆盖，不再单独查询。</p>
 *
 * <p>注：着色器后端自身的回读不在此口径内——着色器路径另有每帧 1 次 GL_VIEWPORT 与
 * 1 次 program 恢复回读，GL_PROJECTION_MATRIX 仅在 viewport 变化时读取
 * （由 T6 后端自行计数并暴露诊断，T8-D3/D4）。</p>
 *
 * <p>{@link Access} 是唯一 GL 直连点：注入假实现即可在纯 JVM 内断言
 * 「一次捕获 = 3 次查询、一次恢复 = 3 次绑定」。</p>
 */
public final class ChainPreviewGlBindings {

    /** 捕获一次需要读取的绑定查询数量。 */
    public static final int CAPTURED_QUERY_COUNT = 3;

    /** GL 访问 seam（测试注入假实现用）。 */
    public interface Access {

        int getInteger(int pname);

        void bindVertexArray(int vertexArray);

        void bindArrayBuffer(int buffer);

        void bindElementArrayBuffer(int buffer);
    }

    static final Access LWJGL = new Access() {

        @Override
        public int getInteger(int pname) {
            return GL11.glGetInteger(pname);
        }

        @Override
        public void bindVertexArray(int vertexArray) {
            GL30.glBindVertexArray(vertexArray);
        }

        @Override
        public void bindArrayBuffer(int buffer) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        }

        @Override
        public void bindElementArrayBuffer(int buffer) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer);
        }
    };

    private final int vertexArray;
    private final int arrayBuffer;
    private final int elementArrayBuffer;

    private ChainPreviewGlBindings(int vertexArray, int arrayBuffer, int elementArrayBuffer) {
        this.vertexArray = vertexArray;
        this.arrayBuffer = arrayBuffer;
        this.elementArrayBuffer = elementArrayBuffer;
    }

    /** @return 当前绑定快照（恰好 3 次 glGetInteger） */
    public static ChainPreviewGlBindings capture() {
        return capture(LWJGL);
    }

    static ChainPreviewGlBindings capture(Access access) {
        if (access == null) {
            throw new IllegalArgumentException("access");
        }
        return new ChainPreviewGlBindings(
            access.getInteger(GL30.GL_VERTEX_ARRAY_BINDING),
            access.getInteger(GL15.GL_ARRAY_BUFFER_BINDING),
            access.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
    }

    /** 恢复绑定（恰好 3 次 glBindBuffer / glBindVertexArray）。 */
    public void restore() {
        restore(LWJGL);
    }

    void restore(Access access) {
        if (access == null) {
            throw new IllegalArgumentException("access");
        }
        access.bindVertexArray(vertexArray);
        access.bindArrayBuffer(arrayBuffer);
        access.bindElementArrayBuffer(elementArrayBuffer);
    }

    /**
     * 释放被删除对象后的安全快照（无 aux 缓冲后端的简化形式）；
     * 已删除的绑定收敛为 0，避免恢复到悬空对象。
     *
     * @param deletedVao 已删除的 VAO
     * @param deletedVbo 已删除的顶点缓冲
     * @param deletedCbo 已删除的颜色缓冲
     * @param deletedEbo 已删除的索引缓冲
     * @return 修正后的快照
     */
    public ChainPreviewGlBindings withoutDeleted(
            int deletedVao, int deletedVbo, int deletedCbo, int deletedEbo) {
        return withoutDeletedBuffers(deletedVao, deletedVbo, deletedCbo, 0, deletedEbo);
    }

    /**
     * 释放被删除对象后的安全快照：任一已删除对象对应的绑定收敛为 0。
     *
     * <p>带 aux 缓冲（abo）的 shader 后端应使用本形式，避免 ARRAY_BUFFER 恢复到已删除对象
     * （T8-D6）。</p>
     *
     * @param deletedVao 已删除的 VAO
     * @param deletedVbo 已删除的顶点缓冲
     * @param deletedCbo 已删除的颜色缓冲
     * @param deletedAbo 已删除的 aux 缓冲（无则传 0）
     * @param deletedEbo 已删除的索引缓冲
     * @return 修正后的快照
     */
    public ChainPreviewGlBindings withoutDeletedBuffers(
            int deletedVao, int deletedVbo, int deletedCbo, int deletedAbo, int deletedEbo) {
        return withoutDeletedBuffers(deletedVao, deletedVbo, deletedCbo, deletedAbo, 0, deletedEbo);
    }

    public ChainPreviewGlBindings withoutDeletedBuffers(
            int deletedVao, int deletedVbo, int deletedCbo, int deletedAbo, int deletedDbo, int deletedEbo) {
        int safeArrayBuffer = arrayBuffer == deletedVbo
            || arrayBuffer == deletedCbo
            || arrayBuffer == deletedAbo
            || arrayBuffer == deletedDbo ? 0 : arrayBuffer;
        return new ChainPreviewGlBindings(
            vertexArray == deletedVao ? 0 : vertexArray,
            safeArrayBuffer,
            elementArrayBuffer == deletedEbo ? 0 : elementArrayBuffer);
    }

    public int getVertexArray() {
        return vertexArray;
    }

    public int getArrayBuffer() {
        return arrayBuffer;
    }

    public int getElementArrayBuffer() {
        return elementArrayBuffer;
    }
}
