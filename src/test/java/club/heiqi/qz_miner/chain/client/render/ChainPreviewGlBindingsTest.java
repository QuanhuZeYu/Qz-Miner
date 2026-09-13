package club.heiqi.qz_miner.chain.client.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

public class ChainPreviewGlBindingsTest {

    @Test
    public void captureQueriesExactlyThreeBindingsOnce() {
        RecordingAccess access = new RecordingAccess(7, 11, 13);

        ChainPreviewGlBindings bindings = ChainPreviewGlBindings.capture(access);

        Assert.assertEquals(3, ChainPreviewGlBindings.CAPTURED_QUERY_COUNT);
        Assert.assertEquals(ChainPreviewGlBindings.CAPTURED_QUERY_COUNT, access.queries.size());
        Assert.assertEquals(
            Arrays.asList(
                Integer.valueOf(GL30.GL_VERTEX_ARRAY_BINDING),
                Integer.valueOf(GL15.GL_ARRAY_BUFFER_BINDING),
                Integer.valueOf(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)),
            access.queries);
        Assert.assertEquals(7, bindings.getVertexArray());
        Assert.assertEquals(11, bindings.getArrayBuffer());
        Assert.assertEquals(13, bindings.getElementArrayBuffer());
    }

    @Test
    public void restoreRebindsCapturedObjectsExactlyOnce() {
        RecordingAccess access = new RecordingAccess(7, 11, 13);

        ChainPreviewGlBindings.capture(access).restore(access);

        Assert.assertEquals(
            Arrays.asList(Integer.valueOf(7), Integer.valueOf(11), Integer.valueOf(13)),
            access.binds);
    }

    @Test
    public void withoutDeletedConvergesRemovedObjectsToZero() {
        ChainPreviewGlBindings bindings = ChainPreviewGlBindings.capture(new RecordingAccess(7, 11, 13));

        ChainPreviewGlBindings cleaned = bindings.withoutDeleted(7, 11, 0, 0);

        Assert.assertEquals(0, cleaned.getVertexArray());
        Assert.assertEquals(0, cleaned.getArrayBuffer());
        Assert.assertEquals(13, cleaned.getElementArrayBuffer());
    }

    @Test
    public void withoutDeletedHandlesColorBufferBinding() {
        ChainPreviewGlBindings bindings = ChainPreviewGlBindings.capture(new RecordingAccess(7, 11, 13));

        ChainPreviewGlBindings cleaned = bindings.withoutDeleted(0, 0, 11, 13);

        Assert.assertEquals(7, cleaned.getVertexArray());
        Assert.assertEquals(0, cleaned.getArrayBuffer());
        Assert.assertEquals(0, cleaned.getElementArrayBuffer());
    }

    private static final class RecordingAccess implements ChainPreviewGlBindings.Access {

        private final List<Integer> queries = new ArrayList<Integer>();
        private final List<Integer> binds = new ArrayList<Integer>();
        private final int vertexArray;
        private final int arrayBuffer;
        private final int elementArrayBuffer;

        private RecordingAccess(int vertexArray, int arrayBuffer, int elementArrayBuffer) {
            this.vertexArray = vertexArray;
            this.arrayBuffer = arrayBuffer;
            this.elementArrayBuffer = elementArrayBuffer;
        }

        @Override
        public int getInteger(int pname) {
            queries.add(Integer.valueOf(pname));
            if (pname == GL30.GL_VERTEX_ARRAY_BINDING) {
                return vertexArray;
            }
            if (pname == GL15.GL_ARRAY_BUFFER_BINDING) {
                return arrayBuffer;
            }
            if (pname == GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING) {
                return elementArrayBuffer;
            }
            throw new IllegalArgumentException("unexpected pname " + pname);
        }

        @Override
        public void bindVertexArray(int value) {
            binds.add(Integer.valueOf(value));
        }

        @Override
        public void bindArrayBuffer(int value) {
            binds.add(Integer.valueOf(value));
        }

        @Override
        public void bindElementArrayBuffer(int value) {
            binds.add(Integer.valueOf(value));
        }
    }
}
