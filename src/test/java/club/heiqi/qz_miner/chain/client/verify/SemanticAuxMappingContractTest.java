package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.BuildSession;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderMath;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T17 语义类别 → aAux.x 映射与同序性独立探针（T16a/T16b/T16c）。
 *
 * <p>独立覆盖：状态侧类别载体与快照目标严格同序、跨代重置、构建侧按目标写入 aux.x、
 * 重复目标首读类别胜出、缺失/非法类别 255 兜底与降级计数、builtin 档双路径混色逐位一致。</p>
 */
public class SemanticAuxMappingContractTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);
    private static final float BAR_THICKNESS = 0.045F;
    private static final int UNDEFINED = ChainPreviewSemanticClass.UNDEFINED;

    @Test
    public void classCarrierIsSameOrderAsSnapshotTargets() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0), ChainPreviewSemanticClass.PRIMARY_LOCAL);
        ChainTarget first = new ChainTarget(1, 0, 0);
        ChainTarget second = new ChainTarget(2, 0, 0);
        ChainTarget third = new ChainTarget(3, 0, 0);
        Assert.assertTrue(state.addPreviewTarget(generation, first));
        Assert.assertTrue(state.setSemanticClass(generation, ChainPreviewSemanticClass.SUB_MODE_LOCAL));
        Assert.assertTrue(state.addPreviewTarget(generation, second));
        Assert.assertTrue(state.setSemanticClass(generation, ChainPreviewSemanticClass.REMOTE_PREDICTED));
        Assert.assertTrue(state.addPreviewTarget(generation, third));

        ChainPreviewState.RenderSnapshot snapshot = state.captureRenderSnapshot();
        int[] classes = snapshot.getSemanticClasses();
        List<ChainTarget> iterated = new ArrayList<ChainTarget>();
        List<Integer> expected = new ArrayList<Integer>();
        for (ChainTarget target : snapshot.getTargets()) {
            iterated.add(target);
            if (target.equals(third)) {
                expected.add(Integer.valueOf(ChainPreviewSemanticClass.REMOTE_PREDICTED));
            } else if (target.equals(second)) {
                expected.add(Integer.valueOf(ChainPreviewSemanticClass.SUB_MODE_LOCAL));
            } else {
                expected.add(Integer.valueOf(ChainPreviewSemanticClass.PRIMARY_LOCAL));
            }
        }
        Assert.assertEquals("载体长度必须等于目标数", iterated.size(), classes.length);
        Assert.assertEquals(3, classes.length);
        for (int index = 0; index < classes.length; index++) {
            Assert.assertEquals(
                "第 " + index + " 个目标类别必须与其快照顺序对齐（target=" + iterated.get(index).getX() + "）",
                expected.get(index).intValue(),
                classes[index]);
        }
        Assert.assertFalse(
            "陈旧代不得写入类别",
            state.setSemanticClass(generation - 1, ChainPreviewSemanticClass.TRUNCATED));
    }

    @Test
    public void classCarrierResetsWithGenerationAndNormalizesIllegalIds() {
        ChainPreviewState state = new ChainPreviewState();
        int generation = state.begin(new ChainTarget(0, 0, 0), 99);
        Assert.assertTrue(state.addPreviewTarget(generation, new ChainTarget(0, 0, 0)));
        ChainPreviewState.RenderSnapshot snapshot = state.captureRenderSnapshot();
        Assert.assertEquals(1, snapshot.getSemanticClasses().length);
        Assert.assertEquals(
            "非法类别必须收窄为 UNDEFINED",
            UNDEFINED,
            snapshot.getSemanticClasses()[0]);

        state.clear();
        int next = state.begin(new ChainTarget(5, 5, 5), ChainPreviewSemanticClass.TRUNCATED);
        Assert.assertTrue(state.addPreviewTarget(next, new ChainTarget(5, 5, 5)));
        int[] nextClasses = state.captureRenderSnapshot().getSemanticClasses();
        Assert.assertEquals(1, nextClasses.length);
        Assert.assertEquals(
            "新代必须使用新代类别（跨代重置）",
            ChainPreviewSemanticClass.TRUNCATED,
            nextClasses[0]);
    }

    @Test
    public void builderWritesClassPerTargetAlignedWithAppearOrder() {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        targets.add(new ChainTarget(0, 0, 0));
        targets.add(new ChainTarget(3, 0, 0));
        targets.add(new ChainTarget(6, 0, 0));
        int[] classes = {
            ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.REMOTE_PREDICTED
        };
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(targets, VISUALS, BAR_THICKNESS, classes);
        byte[] aux = mesh.getAux();
        Assert.assertNotNull(aux);
        int vertexCount = mesh.getVertexFloatCount() / 3;
        float[] vertices = mesh.getVertices();
        int[] perBlockVertices = new int[3];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int semanticClass = aux[vertex * 4] & 0xFF;
            int order = (aux[vertex * 4 + 2] & 0xFF) | ((aux[vertex * 4 + 3] & 0xFF) << 8);
            Assert.assertTrue("order 必须在 [0,3)", order >= 0 && order < 3);
            Assert.assertEquals(
                "顶点类别必须与其出现序号对应的目标类别一致（vertex=" + vertex + "）",
                classes[order],
                semanticClass);
            float localX = vertices[vertex * 3];
            for (int block = 0; block < 3; block++) {
                if (localX >= block * 3 - 0.05F && localX <= block * 3 + 1.05F) {
                    perBlockVertices[block]++;
                    Assert.assertEquals(
                        "块 " + block + " 的顶点类别必须等于其类别",
                        classes[block],
                        semanticClass);
                }
            }
        }
        for (int block = 0; block < 3; block++) {
            Assert.assertEquals("每块 64 顶点", 64, perBlockVertices[block]);
        }
    }

    @Test
    public void duplicateTargetKeepsFirstReadClass() {
        ChainTarget repeated = new ChainTarget(7, 0, 0);
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        targets.add(repeated);
        targets.add(new ChainTarget(12, 0, 0));
        targets.add(repeated);
        int[] classes = {
            ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.REMOTE_PREDICTED
        };
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(targets, VISUALS, BAR_THICKNESS, classes);
        byte[] aux = mesh.getAux();
        float[] vertices = mesh.getVertices();
        int repeatedVertices = 0;
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            float localX = vertices[vertex * 3];
            if (localX >= -0.05F && localX <= 1.05F) {
                repeatedVertices++;
                Assert.assertEquals(
                    "重复目标必须取首次读取的类别",
                    ChainPreviewSemanticClass.PRIMARY_LOCAL,
                    aux[vertex * 4] & 0xFF);
            }
        }
        Assert.assertEquals(64, repeatedVertices);
        Assert.assertEquals(2, mesh.getBlockCount());
    }

    @Test
    public void missingOrIllegalClassesFallBackToUndefinedWithCounters() {
        List<ChainTarget> targets = new ArrayList<ChainTarget>();
        targets.add(new ChainTarget(0, 0, 0));
        targets.add(new ChainTarget(3, 0, 0));
        targets.add(new ChainTarget(6, 0, 0));

        ChainPreviewMesh noCarrier = new ChainPreviewMeshBuilder().build(targets, VISUALS);
        assertAllClasses(noCarrier, UNDEFINED);

        BuildSession shortCarrier = new ChainPreviewMeshBuilder().begin(
            targets, VISUALS, BAR_THICKNESS, new int[] {ChainPreviewSemanticClass.TRUNCATED});
        Assert.assertTrue(shortCarrier.advance(null));
        Assert.assertEquals(
            "数组短于目标数时只对缺失目标计数",
            2,
            shortCarrier.getSemanticClassFallbackCount());
        byte[] classes = shortCarrier.getMesh().getAux();
        Assert.assertEquals(
            "已提供的前缀不得错位",
            ChainPreviewSemanticClass.TRUNCATED,
            classes[0] & 0xFF);

        BuildSession illegalCarrier = new ChainPreviewMeshBuilder().begin(
            targets, VISUALS, BAR_THICKNESS,
            new int[] {7, -1, 300});
        Assert.assertTrue(illegalCarrier.advance(null));
        Assert.assertEquals(
            "非法类别必须逐个计数",
            3,
            illegalCarrier.getSemanticClassFallbackCount());
        assertAllClasses(illegalCarrier.getMesh(), UNDEFINED);

        BuildSession nullCarrier = new ChainPreviewMeshBuilder().begin(
            targets, VISUALS, BAR_THICKNESS, null);
        Assert.assertTrue(nullCarrier.advance(null));
        Assert.assertEquals(
            "null 载体表示未提供类别，不计降级",
            0,
            nullCarrier.getSemanticClassFallbackCount());
        assertAllClasses(nullCarrier.getMesh(), UNDEFINED);
    }

    @Test
    public void builtinMixedSourceMatchesLegacyBitExactly() {
        Assert.assertEquals(
            Float.floatToIntBits(0.25F),
            Float.floatToIntBits(ChainPreviewShaderMath.BUILTIN_COLOR_RED));
        Assert.assertEquals(
            Float.floatToIntBits(0.9F),
            Float.floatToIntBits(ChainPreviewShaderMath.BUILTIN_COLOR_GREEN));
        Assert.assertEquals(
            Float.floatToIntBits(1.0F),
            Float.floatToIntBits(ChainPreviewShaderMath.BUILTIN_COLOR_BLUE));

        float[] alphas = {0.0F, 0.15F, 0.5F, 0.78F, 1.0F};
        for (float alpha : alphas) {
            float[] legacy = ChainPreviewShaderMath.legacyMixedSource(
                ChainPreviewShaderMath.BUILTIN_COLOR_RED,
                ChainPreviewShaderMath.BUILTIN_COLOR_GREEN,
                ChainPreviewShaderMath.BUILTIN_COLOR_BLUE,
                alpha);
            float[] shader = ChainPreviewShaderMath.shaderMixedSource(
                ChainPreviewShaderMath.BUILTIN_COLOR_RED,
                ChainPreviewShaderMath.BUILTIN_COLOR_GREEN,
                ChainPreviewShaderMath.BUILTIN_COLOR_BLUE,
                alpha);
            for (int channel = 0; channel < 3; channel++) {
                Assert.assertEquals(
                    "alpha=" + alpha + " channel=" + channel + " 两条路径混色源必须逐位一致",
                    Float.floatToIntBits(legacy[channel]),
                    Float.floatToIntBits(shader[channel]));
            }
        }
    }

    private static void assertAllClasses(ChainPreviewMesh mesh, int expectedClass) {
        byte[] aux = mesh.getAux();
        Assert.assertNotNull("构建路径必须产出语义流", aux);
        for (int vertex = 0; vertex < mesh.getVertexFloatCount() / 3; vertex++) {
            Assert.assertEquals(
                "所有顶点类别应为 " + expectedClass,
                expectedClass,
                aux[vertex * 4] & 0xFF);
        }
    }
}
