package club.heiqi.qz_miner.chain.executor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 虚拟玩家姿态事务的字段全集、精确碰撞盒与幂等恢复合同。 */
public class ServerPlayerPoseTransactionStructureTest {

    /** current/prev/last/rotation 与 bounding box 六坐标必须全部捕获。 */
    @Test
    public void transactionCapturesEveryMutatedFieldAndExactBoundingBox() throws Exception {
        String source = readSource();
        String[] captures = {
            "this.originalPosX = player.posX;",
            "this.originalPosY = player.posY;",
            "this.originalPosZ = player.posZ;",
            "this.originalPrevPosX = player.prevPosX;",
            "this.originalPrevPosY = player.prevPosY;",
            "this.originalPrevPosZ = player.prevPosZ;",
            "this.originalLastTickPosX = player.lastTickPosX;",
            "this.originalLastTickPosY = player.lastTickPosY;",
            "this.originalLastTickPosZ = player.lastTickPosZ;",
            "this.originalRotationYaw = player.rotationYaw;",
            "this.originalRotationPitch = player.rotationPitch;",
            "this.originalPrevRotationYaw = player.prevRotationYaw;",
            "this.originalPrevRotationPitch = player.prevRotationPitch;",
            "this.originalMinX = originalBoundingBox.minX;",
            "this.originalMinY = originalBoundingBox.minY;",
            "this.originalMinZ = originalBoundingBox.minZ;",
            "this.originalMaxX = originalBoundingBox.maxX;",
            "this.originalMaxY = originalBoundingBox.maxY;",
            "this.originalMaxZ = originalBoundingBox.maxZ;"
        };
        for (String capture : captures) {
            Assert.assertTrue("missing capture: " + capture, source.contains(capture));
        }
    }

    /** 应用必须统一 current/prev/last/rotation；关闭必须幂等且尽最大努力恢复。 */
    @Test
    public void transactionAppliesCompletePoseAndRestoresIdempotently() throws Exception {
        String source = readSource();
        int apply = source.indexOf("void apply(TargetInteractionPose pose)");
        int close = source.indexOf("public void close()", apply);
        String applyBody = source.substring(apply, close);
        String closeBody = source.substring(close);

        String[] virtualAssignments = {
            "player.posX = virtualPosX;",
            "player.posY = virtualPosY;",
            "player.posZ = virtualPosZ;",
            "player.prevPosX = virtualPosX;",
            "player.prevPosY = virtualPosY;",
            "player.prevPosZ = virtualPosZ;",
            "player.lastTickPosX = virtualPosX;",
            "player.lastTickPosY = virtualPosY;",
            "player.lastTickPosZ = virtualPosZ;",
            "player.rotationYaw = pose.getYaw();",
            "player.rotationPitch = pose.getPitch();",
            "player.prevRotationYaw = pose.getYaw();",
            "player.prevRotationPitch = pose.getPitch();"
        };
        for (String assignment : virtualAssignments) {
            Assert.assertTrue("missing virtual assignment: " + assignment,
                    applyBody.contains(assignment));
        }
        Assert.assertTrue(applyBody.contains("originalBoundingBox.setBounds("));
        Assert.assertTrue(closeBody.contains("if (closed) {"));
        Assert.assertTrue(closeBody.contains("closed = true;"));
        Assert.assertTrue(closeBody.contains("player.posX = originalPosX;"));
        Assert.assertTrue(closeBody.contains("player.prevPosY = originalPrevPosY;"));
        Assert.assertTrue(closeBody.contains("player.lastTickPosZ = originalLastTickPosZ;"));
        Assert.assertTrue(closeBody.contains("player.prevRotationPitch = originalPrevRotationPitch;"));
        Assert.assertTrue(closeBody.contains(
                "originalMinX, originalMinY, originalMinZ,"));
        Assert.assertTrue(closeBody.contains(
                "originalMaxX, originalMaxY, originalMaxZ"));
        Assert.assertTrue("恢复各组异常后仍须继续 best-effort",
                closeBody.contains("mergeFailure(restoreFailure, failure)"));
        Assert.assertFalse("虚拟姿态不得向客户端发布位置", source.contains("setPositionAndUpdate"));
        Assert.assertFalse("虚拟姿态不得发网络包", source.contains("sendPacket"));
    }

    private static String readSource() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/executor/ServerPlayerPoseTransaction.java").toPath()),
                StandardCharsets.UTF_8);
    }
}
