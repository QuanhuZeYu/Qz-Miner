package club.heiqi.qz_miner.chain.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.BuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

public class ChainPreviewMeshBuilderTest {

    private static final float EPSILON = 0.00001F;

    @Test
    public void singleBlockBuildsWatertightJoinedFrame() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            Collections.singletonList(new ChainTarget(0, 0, 0)),
            visuals(0.5D, 0.5D, 0.5D));

        Assert.assertEquals(1, mesh.getBlockCount());
        Assert.assertEquals(64 * 3, mesh.getVertexFloatCount());
        Assert.assertEquals(64 * 4, mesh.getColorFloatCount());
        Assert.assertEquals((12 * 4 + 8 * 3) * 4, mesh.getIndexCount());
        assertIndicesInRange(mesh);
        assertNoDuplicateQuads(mesh);
        assertClosedSurface(mesh);

        float[] vertices = mesh.getVertices();
        Assert.assertEquals(-0.02F, minimum(vertices, 0), EPSILON);
        Assert.assertEquals(1.02F, maximum(vertices, 0), EPSILON);
        Assert.assertEquals(-0.02F, minimum(vertices, 1), EPSILON);
        Assert.assertEquals(1.02F, maximum(vertices, 1), EPSILON);
        Assert.assertEquals(-0.02F, minimum(vertices, 2), EPSILON);
        Assert.assertEquals(1.02F, maximum(vertices, 2), EPSILON);
    }

    @Test
    public void duplicateTargetDoesNotDuplicateGeometryAndDirectNeighborRemovesSharedFaceBars() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        VisualParameters visuals = visuals(0.5D, 0.5D, 0.5D);
        ChainTarget origin = new ChainTarget(0, 0, 0);

        ChainPreviewMesh duplicate = builder.build(Arrays.asList(origin, origin), visuals);
        Assert.assertEquals((12 * 4 + 8 * 3) * 4, duplicate.getIndexCount());
        Assert.assertEquals(1, duplicate.getBlockCount());
        assertNoDuplicateQuads(duplicate);

        ChainPreviewMesh adjacent = builder.build(
            Arrays.asList(origin, new ChainTarget(1, 0, 0)),
            visuals);
        Assert.assertEquals(2, adjacent.getBlockCount());
        Assert.assertEquals(80 * 3, adjacent.getVertexFloatCount());
        Assert.assertEquals((16 * 4 + 8 * 3) * 4, adjacent.getIndexCount());
        assertIndicesInRange(adjacent);
        assertNoDuplicateQuads(adjacent);
        assertClosedSurface(adjacent);
        assertNoQuadInPlane(adjacent, 0, 1.0F);
    }

    @Test
    public void diagonalContactsShareJunctionGeometryWithoutOverlappingQuads() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        VisualParameters visuals = visuals(0.5D, 0.5D, 0.5D);

        ChainPreviewMesh edgeContact = builder.build(
            Arrays.asList(new ChainTarget(0, 0, 0), new ChainTarget(1, 1, 0)),
            visuals);
        Assert.assertEquals((23 * 4 + 12 * 3 + 2) * 4, edgeContact.getIndexCount());
        assertNoDuplicateQuads(edgeContact);
        assertClosedSurface(edgeContact);

        ChainPreviewMesh cornerContact = builder.build(
            Arrays.asList(new ChainTarget(0, 0, 0), new ChainTarget(1, 1, 1)),
            visuals);
        Assert.assertEquals((24 * 4 + 14 * 3) * 4, cornerContact.getIndexCount());
        assertNoDuplicateQuads(cornerContact);
        assertClosedSurface(cornerContact);
    }

    @Test
    public void distanceAlphaUsesFrozenVisualParameters() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget target = new ChainTarget(0, 0, 0);

        ChainPreviewMesh near = builder.build(
            Collections.singletonList(target),
            visuals(0.5D, 0.5D, 0.5D));
        ChainPreviewMesh far = builder.build(
            Collections.singletonList(target),
            visuals(100.0D, 100.0D, 100.0D));

        assertAllAlpha(near.getColors(), 0.8F);
        assertAllAlpha(far.getColors(), 0.2F);
    }

    @Test
    public void yieldedBuildResumesAcrossGeometryPhasesWithoutDuplication() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        java.util.List<ChainTarget> targets = Arrays.asList(
            new ChainTarget(0, 0, 0),
            new ChainTarget(1, 0, 0),
            new ChainTarget(1, 1, 0));
        VisualParameters visuals = visuals(0.5D, 0.5D, 0.5D);
        BuildSession session = builder.begin(targets, visuals);

        int slices = 0;
        while (!session.advance(oneUnitGate())) {
            slices++;
            Assert.assertTrue("build session did not converge", slices < 10_000);
        }
        Assert.assertTrue(slices > targets.size());
        ChainPreviewMesh resumed = session.getMesh();
        ChainPreviewMesh synchronous = builder.build(targets, visuals);
        Assert.assertArrayEquals(synchronous.getVertices(), resumed.getVertices(), 0.0F);
        Assert.assertArrayEquals(synchronous.getColors(), resumed.getColors(), 0.0F);
        Assert.assertArrayEquals(synchronous.getIndices(), resumed.getIndices());
    }

    @Test
    public void renderMeshCapacityKeepsNewestSnapshotPrefixAndBoundsIteration() {
        java.util.List<ChainTarget> targets = new java.util.ArrayList<ChainTarget>();
        int totalTargets = ChainPreviewMeshBuilder.MAX_RENDER_TARGETS + 100;
        for (int index = 0; index < totalTargets; index++) {
            targets.add(new ChainTarget(index * 2, 0, 0));
        }
        Collections.reverse(targets);
        final int[] targetsRead = {0};
        Iterable<ChainTarget> countedTargets = counted(targets, targetsRead);

        BuildSession session = new ChainPreviewMeshBuilder().begin(
            countedTargets,
            visuals(0.5D, 0.5D, 0.5D));
        Assert.assertTrue(session.advance(null));
        ChainPreviewMesh mesh = session.getMesh();
        Assert.assertTrue(mesh.isTruncated());
        Assert.assertEquals(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS, mesh.getBlockCount());
        Assert.assertEquals(ChainPreviewMeshBuilder.MAX_RENDER_TARGETS + 1, targetsRead[0]);
        Assert.assertEquals((totalTargets - 1) * 2, mesh.getOriginX());
        Assert.assertEquals(
            ChainPreviewMeshBuilder.MAX_RENDER_TARGETS * 12 * 6 * 4,
            mesh.getIndexCount());
    }

    @Test
    public void farWorldCoordinatesRemainThickAroundLocalMeshOrigin() {
        int worldX = 30_000_000;
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            Collections.singletonList(new ChainTarget(worldX, 64, -worldX)),
            visuals(worldX + 0.5D, 64.5D, -worldX + 0.5D));

        Assert.assertEquals(worldX, mesh.getOriginX());
        Assert.assertEquals(64, mesh.getOriginY());
        Assert.assertEquals(-worldX, mesh.getOriginZ());
        float[] vertices = mesh.getVertices();
        Assert.assertEquals(-0.02F, minimum(vertices, 0), EPSILON);
        Assert.assertEquals(1.02F, maximum(vertices, 0), EPSILON);
        Assert.assertEquals(-0.02F, minimum(vertices, 1), EPSILON);
        Assert.assertEquals(1.02F, maximum(vertices, 1), EPSILON);
    }

    @Test
    public void integerCoordinateExtremesDoNotWrapIntoFalseNeighbors() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            Arrays.asList(
                new ChainTarget(Integer.MAX_VALUE, 0, 0),
                new ChainTarget(Integer.MIN_VALUE, 0, 0)),
            visuals(0.0D, 0.0D, 0.0D));

        Assert.assertEquals(2, mesh.getBlockCount());
        Assert.assertEquals(2 * 12 * 6 * 4, mesh.getIndexCount());
    }

    @Test
    public void colorOnlySessionReusesTopologyAndMatchesPerVertexBuild() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainTarget target = new ChainTarget(0, 0, 0);
        ChainPreviewMesh topology = builder.build(
            Collections.singletonList(target),
            visuals(0.5D, 0.5D, 0.5D));
        VisualParameters movedVisuals = visuals(-2.0D, 0.5D, 0.5D);
        ChainPreviewMeshBuilder.ColorBuildSession recolor = builder.beginRecolor(
            topology,
            movedVisuals);

        Assert.assertFalse(recolor.advance(new ChainPreviewMeshBuilder.WorkGate() {
            private int checks;

            @Override
            public boolean shouldYield() {
                return ++checks > 4;
            }
        }));
        Assert.assertTrue(recolor.advance(new ChainPreviewMeshBuilder.WorkGate() {
            @Override
            public boolean shouldYield() {
                return false;
            }
        }));

        ChainPreviewMesh recolored = recolor.getMesh();
        Assert.assertSame(topology.vertexArray(), recolored.vertexArray());
        Assert.assertSame(topology.indexArray(), recolored.indexArray());
        Assert.assertNotSame(topology.colorArray(), recolored.colorArray());
        ChainPreviewMesh rebuilt = builder.build(Collections.singletonList(target), movedVisuals);
        Assert.assertArrayEquals(rebuilt.getColors(), recolored.getColors(), 0.0F);
        assertAlphaVaries(recolored.getColors());
    }

    private static VisualParameters visuals(double cameraX, double cameraY, double cameraZ) {
        return new VisualParameters(cameraX, cameraY, cameraZ, 2.0D, 6.0D, 0.8F, 0.2F, 0.04F);
    }

    private static void assertIndicesInRange(ChainPreviewMesh mesh) {
        int vertexCount = mesh.getVertexFloatCount() / 3;
        for (int index : mesh.getIndices()) {
            Assert.assertTrue(index >= 0);
            Assert.assertTrue(index < vertexCount);
        }
    }

    private static void assertNoDuplicateQuads(ChainPreviewMesh mesh) {
        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        Set<String> quads = new HashSet<String>();
        for (int offset = 0; offset < indices.length; offset += 4) {
            String[] corners = new String[4];
            for (int corner = 0; corner < corners.length; corner++) {
                corners[corner] = vertexKey(vertices, indices[offset + corner]);
            }
            Arrays.sort(corners);
            String quad = Arrays.toString(corners);
            Assert.assertTrue("duplicate geometric quad " + quad, quads.add(quad));
        }
    }

    private static void assertClosedSurface(ChainPreviewMesh mesh) {
        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        Map<String, Integer> edgeUseCounts = new HashMap<String, Integer>();
        Map<String, Integer> edgeDirectionBalances = new HashMap<String, Integer>();
        double signedVolume = 0.0D;
        for (int offset = 0; offset < indices.length; offset += 4) {
            Assert.assertTrue("degenerate first quad triangle", triangleAreaSquared(
                vertices, indices[offset], indices[offset + 1], indices[offset + 2]) > EPSILON * EPSILON);
            Assert.assertTrue("degenerate second quad triangle", triangleAreaSquared(
                vertices, indices[offset], indices[offset + 2], indices[offset + 3]) > EPSILON * EPSILON);
            signedVolume += signedTriangleVolume(
                vertices, indices[offset], indices[offset + 1], indices[offset + 2]);
            signedVolume += signedTriangleVolume(
                vertices, indices[offset], indices[offset + 2], indices[offset + 3]);
            for (int corner = 0; corner < 4; corner++) {
                String first = vertexKey(vertices, indices[offset + corner]);
                String second = vertexKey(vertices, indices[offset + (corner + 1) % 4]);
                boolean canonicalDirection = first.compareTo(second) <= 0;
                String edge = canonicalDirection
                    ? first + "|" + second
                    : second + "|" + first;
                Integer current = edgeUseCounts.get(edge);
                edgeUseCounts.put(edge, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
                Integer balance = edgeDirectionBalances.get(edge);
                int direction = canonicalDirection ? 1 : -1;
                edgeDirectionBalances.put(
                    edge,
                    Integer.valueOf(balance == null ? direction : balance.intValue() + direction));
            }
        }
        for (Map.Entry<String, Integer> entry : edgeUseCounts.entrySet()) {
            Assert.assertEquals("open or non-manifold edge " + entry.getKey(), 2, entry.getValue().intValue());
            Assert.assertEquals(
                "inconsistent face winding at edge " + entry.getKey(),
                0,
                edgeDirectionBalances.get(entry.getKey()).intValue());
        }
        Assert.assertTrue("closed surface must use outward CCW winding", signedVolume > EPSILON);
    }

    private static void assertNoQuadInPlane(ChainPreviewMesh mesh, int component, float plane) {
        float[] vertices = mesh.getVertices();
        int[] indices = mesh.getIndices();
        for (int offset = 0; offset < indices.length; offset += 4) {
            boolean inPlane = true;
            for (int corner = 0; corner < 4; corner++) {
                float coordinate = vertices[indices[offset + corner] * 3 + component];
                inPlane &= Math.abs(coordinate - plane) <= EPSILON;
            }
            Assert.assertFalse("internal quad remains in shared plane", inPlane);
        }
    }

    private static String vertexKey(float[] vertices, int vertexIndex) {
        int offset = vertexIndex * 3;
        return floatBits(vertices[offset]) + ":"
            + floatBits(vertices[offset + 1]) + ":"
            + floatBits(vertices[offset + 2]);
    }

    private static int floatBits(float value) {
        return Float.floatToIntBits(value == 0.0F ? 0.0F : value);
    }

    private static double triangleAreaSquared(float[] vertices, int first, int second, int third) {
        int firstOffset = first * 3;
        int secondOffset = second * 3;
        int thirdOffset = third * 3;
        double abX = vertices[secondOffset] - vertices[firstOffset];
        double abY = vertices[secondOffset + 1] - vertices[firstOffset + 1];
        double abZ = vertices[secondOffset + 2] - vertices[firstOffset + 2];
        double acX = vertices[thirdOffset] - vertices[firstOffset];
        double acY = vertices[thirdOffset + 1] - vertices[firstOffset + 1];
        double acZ = vertices[thirdOffset + 2] - vertices[firstOffset + 2];
        double crossX = abY * acZ - abZ * acY;
        double crossY = abZ * acX - abX * acZ;
        double crossZ = abX * acY - abY * acX;
        return crossX * crossX + crossY * crossY + crossZ * crossZ;
    }

    private static double signedTriangleVolume(float[] vertices, int first, int second, int third) {
        int firstOffset = first * 3;
        int secondOffset = second * 3;
        int thirdOffset = third * 3;
        double firstX = vertices[firstOffset];
        double firstY = vertices[firstOffset + 1];
        double firstZ = vertices[firstOffset + 2];
        double secondX = vertices[secondOffset];
        double secondY = vertices[secondOffset + 1];
        double secondZ = vertices[secondOffset + 2];
        double thirdX = vertices[thirdOffset];
        double thirdY = vertices[thirdOffset + 1];
        double thirdZ = vertices[thirdOffset + 2];
        return (firstX * (secondY * thirdZ - secondZ * thirdY)
            + firstY * (secondZ * thirdX - secondX * thirdZ)
            + firstZ * (secondX * thirdY - secondY * thirdX)) / 6.0D;
    }

    private static void assertAllAlpha(float[] colors, float expected) {
        for (int offset = 3; offset < colors.length; offset += 4) {
            Assert.assertEquals(expected, colors[offset], EPSILON);
        }
    }

    private static void assertAlphaVaries(float[] colors) {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (int offset = 3; offset < colors.length; offset += 4) {
            minimum = Math.min(minimum, colors[offset]);
            maximum = Math.max(maximum, colors[offset]);
        }
        Assert.assertTrue("vertex alpha should vary with distance", maximum - minimum > EPSILON);
    }

    private static ChainPreviewMeshBuilder.WorkGate oneUnitGate() {
        return new ChainPreviewMeshBuilder.WorkGate() {
            private int checks;

            @Override
            public boolean shouldYield() {
                return ++checks > 1;
            }
        };
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

    private static float minimum(float[] vertices, int component) {
        float result = Float.POSITIVE_INFINITY;
        for (int offset = component; offset < vertices.length; offset += 3) {
            result = Math.min(result, vertices[offset]);
        }
        return result;
    }

    private static float maximum(float[] vertices, int component) {
        float result = Float.NEGATIVE_INFINITY;
        for (int offset = component; offset < vertices.length; offset += 3) {
            result = Math.max(result, vertices[offset]);
        }
        return result;
    }
}
