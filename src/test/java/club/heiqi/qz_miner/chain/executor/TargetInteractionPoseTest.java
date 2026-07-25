package club.heiqi.qz_miner.chain.executor;

import org.junit.Assert;
import org.junit.Test;

/** 六面目标 eye pose 的有限值与朝向合同。 */
public class TargetInteractionPoseTest {

    /** 六个合法 face 都在块内靠近对应面，并精确朝向块中心。 */
    @Test
    public void allFacesProduceFiniteInternalPoseFacingTargetCenter() {
        for (int face = 0; face <= 5; face++) {
            TargetInteractionPose pose = TargetInteractionPose.forTarget(12, -7, 31, face);

            Assert.assertEquals(face, pose.getNormalizedFace());
            assertFinite(pose);
            Assert.assertTrue(pose.getEyeX() > 12.0D && pose.getEyeX() < 13.0D);
            Assert.assertTrue(pose.getEyeY() > -7.0D && pose.getEyeY() < -6.0D);
            Assert.assertTrue(pose.getEyeZ() > 31.0D && pose.getEyeZ() < 32.0D);
            assertNearFace(pose, face, 12, -7, 31);
            assertFacesCenter(pose, 12.5D, -6.5D, 31.5D);
        }
    }

    /** 非法 face 必须稳定归一为上表面 1。 */
    @Test
    public void invalidFacesNormalizeToUpFace() {
        TargetInteractionPose expected = TargetInteractionPose.forTarget(1, 2, 3, 1);
        int[] invalidFaces = {-1, 6, Integer.MIN_VALUE, Integer.MAX_VALUE};

        for (int face : invalidFaces) {
            TargetInteractionPose actual = TargetInteractionPose.forTarget(1, 2, 3, face);
            Assert.assertEquals(1, actual.getNormalizedFace());
            Assert.assertEquals(expected.getEyeX(), actual.getEyeX(), 0.0D);
            Assert.assertEquals(expected.getEyeY(), actual.getEyeY(), 0.0D);
            Assert.assertEquals(expected.getEyeZ(), actual.getEyeZ(), 0.0D);
            Assert.assertEquals(expected.getYaw(), actual.getYaw(), 0.0F);
            Assert.assertEquals(expected.getPitch(), actual.getPitch(), 0.0F);
        }
    }

    /** 极端合法 int 坐标仍不得产生 NaN/Infinity。 */
    @Test
    public void extremeCoordinatesRemainFinite() {
        assertFinite(TargetInteractionPose.forTarget(
                Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, 5));
    }

    private static void assertNearFace(TargetInteractionPose pose, int face, int x, int y, int z) {
        switch (face) {
            case 0:
                Assert.assertTrue(pose.getEyeY() - y < 0.5D);
                return;
            case 1:
                Assert.assertTrue(y + 1.0D - pose.getEyeY() < 0.5D);
                return;
            case 2:
                Assert.assertTrue(pose.getEyeZ() - z < 0.5D);
                return;
            case 3:
                Assert.assertTrue(z + 1.0D - pose.getEyeZ() < 0.5D);
                return;
            case 4:
                Assert.assertTrue(pose.getEyeX() - x < 0.5D);
                return;
            case 5:
                Assert.assertTrue(x + 1.0D - pose.getEyeX() < 0.5D);
                return;
            default:
                Assert.fail("unexpected face " + face);
        }
    }

    private static void assertFacesCenter(TargetInteractionPose pose,
            double centerX, double centerY, double centerZ) {
        double deltaX = centerX - pose.getEyeX();
        double deltaY = centerY - pose.getEyeY();
        double deltaZ = centerZ - pose.getEyeZ();
        double deltaLength = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        double yaw = Math.toRadians(pose.getYaw());
        double pitch = Math.toRadians(pose.getPitch());
        double lookX = -Math.sin(yaw) * Math.cos(pitch);
        double lookY = -Math.sin(pitch);
        double lookZ = Math.cos(yaw) * Math.cos(pitch);
        double dot = lookX * deltaX / deltaLength
                + lookY * deltaY / deltaLength
                + lookZ * deltaZ / deltaLength;
        Assert.assertEquals(1.0D, dot, 0.000001D);
    }

    private static void assertFinite(TargetInteractionPose pose) {
        Assert.assertFalse(Double.isNaN(pose.getEyeX()) || Double.isInfinite(pose.getEyeX()));
        Assert.assertFalse(Double.isNaN(pose.getEyeY()) || Double.isInfinite(pose.getEyeY()));
        Assert.assertFalse(Double.isNaN(pose.getEyeZ()) || Double.isInfinite(pose.getEyeZ()));
        Assert.assertFalse(Float.isNaN(pose.getYaw()) || Float.isInfinite(pose.getYaw()));
        Assert.assertFalse(Float.isNaN(pose.getPitch()) || Float.isInfinite(pose.getPitch()));
    }
}
