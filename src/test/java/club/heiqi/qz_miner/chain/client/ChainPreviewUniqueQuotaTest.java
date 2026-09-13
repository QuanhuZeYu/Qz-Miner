package club.heiqi.qz_miner.chain.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.BuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * B0.7：先去重（有界 unique collector）再占用 {@link ChainPreviewMeshBuilder#MAX_RENDER_TARGETS} 配额。
 *
 * <p>断言均为行为契约：唯一目标数决定配额与截断，重复目标既不占配额也不占 appearOrder 序号。
 * 构建入口只接收显式标量/不可变参数，不触碰配置静态字段。</p>
 */
public class ChainPreviewUniqueQuotaTest {

    @Test
    public void duplicateTargetsDoNotConsumeQuotaOrAppearOrder() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget first = new ChainTarget(0, 0, 0);
        ChainTarget second = new ChainTarget(2, 0, 0);
        ChainTarget third = new ChainTarget(4, 0, 0);

        ChainPreviewMesh mesh = builder.build(
            production(Arrays.asList(first, first, second, first, third, second)),
            visuals());

        Assert.assertFalse(mesh.isTruncated());
        Assert.assertEquals(3, mesh.getBlockCount());
        Assert.assertEquals(0, mesh.getOriginX());
        assertAppearOrders(mesh, 3);
        assertBlockAppearOrder(mesh, 0, 0);
        assertBlockAppearOrder(mesh, 2, 1);
        assertBlockAppearOrder(mesh, 4, 2);
    }

    @Test
    public void duplicateHeavyStreamUsesFullUniqueQuotaWithoutTruncation() {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < 1024; index++) {
            ChainTarget target = new ChainTarget(index * 2, 0, 0);
            targets.add(target);
            targets.add(target);
        }
        for (int index = 1024; index < ChainPreviewMeshBuilder.MAX_RENDER_TARGETS; index++) {
            targets.add(new ChainTarget(index * 2, 0, 0));
        }

        final int[] targetsRead = {0};
        BuildSession session = new ChainPreviewMeshBuilder().begin(
            production(counted(targets, targetsRead)), visuals());
        Assert.assertTrue(session.advance(null));
        ChainPreviewMesh mesh = session.getMesh();

        Assert.assertEquals(5120, targetsRead[0]);
        Assert.assertFalse(
            "重复目标不得消耗唯一配额，也不得触发截断", mesh.isTruncated());
        Assert.assertEquals(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, mesh.getBlockCount());
        Assert.assertEquals(0, mesh.getOriginX());
        assertAppearOrders(mesh, ChainPreviewMeshBuilder.MAX_RENDER_TARGETS);
    }

    @Test
    public void uniqueOverflowAfterSkippedDuplicatesStillTruncates() {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        for (int index = 0; index < ChainPreviewMeshBuilder.MAX_RENDER_TARGETS; index++) {
            targets.add(new ChainTarget(index * 2, 0, 0));
        }
        targets.add(new ChainTarget(0, 0, 0));
        targets.add(new ChainTarget(8192, 0, 0));

        final int[] targetsRead = {0};
        BuildSession session = new ChainPreviewMeshBuilder().begin(
            production(counted(targets, targetsRead)), visuals());
        Assert.assertTrue(session.advance(null));
        ChainPreviewMesh mesh = session.getMesh();

        Assert.assertEquals(4098, targetsRead[0]);
        Assert.assertTrue(mesh.isTruncated());
        Assert.assertEquals(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, mesh.getBlockCount());
        assertAppearOrders(mesh, ChainPreviewMeshBuilder.MAX_RENDER_TARGETS);
    }

    /** 生产快照序（最新→最早）：公共 begin/build 入口的喂入语义。 */
    private static List<ChainTarget> production(Iterable<ChainTarget> chronological) {
        List<ChainTarget> reversed = new ArrayList<ChainTarget>();
        for (ChainTarget target : chronological) {
            reversed.add(target);
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static VisualParameters visuals() {
        return new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.04F);
    }

    private static Iterable<ChainTarget> counted(
            final Iterable<ChainTarget> targets, final int[] targetsRead) {
        return new Iterable<ChainTarget>() {
            @Override
            public Iterator<ChainTarget> iterator() {
                final Iterator<ChainTarget> delegate = targets.iterator();
                return new Iterator<ChainTarget>() {
                    @Override
                    public boolean hasNext() {
                        return delegate.hasNext();
                    }

                    @Override
                    public ChainTarget next() {
                        targetsRead[0]++;
                        return delegate.next();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }

    private static void assertAppearOrders(ChainPreviewMesh mesh, int expectedCount) {
        Set<Integer> orders = new HashSet<Integer>();
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        Assert.assertEquals(mesh.getVertexFloatCount() / 3 * 4, aux.length);
        for (int offset = 0; offset < aux.length; offset += 4) {
            orders.add(Integer.valueOf(appearOrder(aux, offset)));
        }
        Assert.assertEquals(expectedCount, orders.size());
        for (int order = 0; order < expectedCount; order++) {
            Assert.assertTrue("appearOrder 不得跳号：" + order, orders.contains(Integer.valueOf(order)));
        }
    }

    private static void assertBlockAppearOrder(ChainPreviewMesh mesh, int worldX, int expectedOrder) {
        float[] vertices = mesh.getVertices();
        byte[] aux = mesh.getAux();
        int found = 0;
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX < worldX - 0.03F || localX > worldX + 1.03F) {
                continue;
            }
            found++;
            Assert.assertEquals(
                "block@" + worldX + " vertex " + vertex,
                expectedOrder,
                appearOrder(aux, vertex * 4));
        }
        Assert.assertTrue("block@" + worldX + " vertices not found", found > 0);
    }

    private static int appearOrder(byte[] aux, int offset) {
        return (aux[offset + 2] & 0xFF) | ((aux[offset + 3] & 0xFF) << 8);
    }
}
