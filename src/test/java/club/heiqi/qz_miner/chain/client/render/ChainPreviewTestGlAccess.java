package club.heiqi.qz_miner.chain.client.render;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

/**
 * 围栏 / 封装测试用 GL 状态假实现（纯 JVM，不加载 GL 上下文）：记录全部访问并支持按需注入失败。
 */
final class ChainPreviewTestGlAccess implements ChainPreviewGlFences.Access, ChainPreviewGlBindings.Access {

    final List<String> events = new ArrayList<>();
    final List<String> queries = new ArrayList<>();

    boolean failBindingAccess;
    boolean failQueries;
    boolean failConsumeGlError;
    /** consumeGlError 注入队列（逐次弹出；空 = 0/GL_NO_ERROR）。 */
    final java.util.ArrayDeque<Integer> glErrors = new java.util.ArrayDeque<Integer>();
    boolean failClientPush;
    boolean failAllPop;
    boolean failTextureRead;
    boolean failRestore;

    boolean textureEnabled = true;
    int textureBinding = 42;
    int vertexArray = 7;
    int arrayBuffer = 11;
    int elementArrayBuffer = 13;

    @Override
    public ChainPreviewGlBindings.Access bindings() {
        if (failBindingAccess) {
            throw new IllegalStateException("context lost");
        }
        return this;
    }

    @Override
    public int getInteger(int pname) {
        queries.add("query:" + pname);
        if (failQueries) {
            throw new IllegalStateException("query failure");
        }
        if (pname == GL30.GL_VERTEX_ARRAY_BINDING) {
            return vertexArray;
        }
        if (pname == GL15.GL_ARRAY_BUFFER_BINDING) {
            return arrayBuffer;
        }
        if (pname == GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING) {
            return elementArrayBuffer;
        }
        throw new IllegalStateException("unexpected pname " + pname);
    }

    @Override
    public void bindVertexArray(int vertexArray) {
        events.add("bindVao:" + vertexArray);
        if (failRestore) {
            throw new IllegalStateException("restore failure");
        }
    }

    @Override
    public void bindArrayBuffer(int buffer) {
        events.add("bindArray:" + buffer);
        if (failRestore) {
            throw new IllegalStateException("restore failure");
        }
    }

    @Override
    public void bindElementArrayBuffer(int buffer) {
        events.add("bindElement:" + buffer);
        if (failRestore) {
            throw new IllegalStateException("restore failure");
        }
    }

    @Override
    public void pushAllAttribs() {
        events.add("pushAll");
    }

    @Override
    public void pushClientVertexArrayAttribs() {
        events.add("pushClient");
        if (failClientPush) {
            throw new IllegalStateException("client push failure");
        }
    }

    @Override
    public void popClientAttribs() {
        events.add("popClient");
    }

    @Override
    public void popAttribs() {
        events.add("popAll");
        if (failAllPop) {
            throw new IllegalStateException("pop failure");
        }
    }

    @Override
    public boolean isTexture2dEnabled() {
        events.add("texRead");
        if (failTextureRead) {
            throw new IllegalStateException("texture read failure");
        }
        return textureEnabled;
    }

    @Override
    public int getTextureBinding2d() {
        events.add("texBindingRead");
        if (failTextureRead) {
            throw new IllegalStateException("texture read failure");
        }
        return textureBinding;
    }

    @Override
    public void setTexture2dEnabled(boolean enabled) {
        events.add("texEnable:" + enabled);
    }

    @Override
    public void setTextureBinding2d(int binding) {
        events.add("texBind:" + binding);
    }

    @Override
    public int consumeGlError() {
        if (failConsumeGlError) {
            throw new IllegalStateException("glGetError failure");
        }
        Integer next = glErrors.poll();
        return next == null ? 0 : next.intValue();
    }
}
