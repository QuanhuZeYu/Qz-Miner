package club.heiqi.qz_miner.chain.client.verify;

import java.util.List;

import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/** T51 临时探针：打印各基线形状在「顶点按面分裂」后的新顶点数/quad 数。用完即删。 */
public class TempBaselineDumpTest {

    private static final VisualParameters VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);

    @Test
    public void dumpBaselines() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        dump(builder, "single", VerifyShapes.single(0, 0, 0));
        dump(builder, "adjacent_x2", VerifyShapes.line(2));
        dump(builder, "line_100", VerifyShapes.line(100));
        dump(builder, "plane_16x16", VerifyShapes.plane(16));
        dump(builder, "solid_10_cube", VerifyShapes.solidCube(10));
        dump(builder, "lshape_64", VerifyShapes.lShape(64));
        dump(builder, "edge_contact", VerifyShapes.edgeContact());
        dump(builder, "corner_contact", VerifyShapes.cornerContact());
        dump(builder, "duplicates_1024", VerifyShapes.duplicated(1024));
        dump(builder, "scattered_4096", VerifyShapes.scatteredX(4096, 3));
        dump(builder, "scatter_lattice_4096", VerifyShapes.deterministicScatter(4096));
    }

    private static void dump(ChainPreviewMeshBuilder builder, String label, List<ChainTarget> targets) {
        ChainPreviewMesh mesh = builder.build(targets, VISUALS);
        VerifyMeshAudit.Report report = VerifyMeshAudit.audit(mesh);
        System.out.println("[t51-dump] " + label
            + " vertices=" + (mesh.getVertexFloatCount() / 3)
            + " quads=" + (mesh.getIndexCount() / 4)
            + " dupPositions=" + report.duplicateVertexPosition
            + " inconsistentShared=" + report.inconsistentSharedVertexSemantics
            + " openEdge=" + report.openEdge
            + " nonManifold=" + report.nonManifoldEdge
            + " winding=" + report.inconsistentWinding
            + " volume>0=" + (report.signedVolume > 0.0D));
    }
}
