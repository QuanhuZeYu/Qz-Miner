package club.heiqi.qz_miner.chain.interaction;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 共享右键射线的 common-only 与 Item 参数结构合同。 */
public class InteractionRayTraceStructureTest {

    @Test
    public void helperIsSideNeutralAndDoesNotRetainGameObjects() throws Exception {
        String source = source();

        Assert.assertTrue(source.contains("public final class InteractionRayTrace"));
        Assert.assertTrue(source.contains("private InteractionRayTrace() {}"));
        Assert.assertTrue(source.contains(
                "public static MovingObjectPosition trace(EntityPlayer player, double reach, boolean includeLiquids)"));
        Assert.assertFalse(source.contains("import net.minecraft.client."));
        Assert.assertFalse(source.contains("import cpw.mods.fml.relauncher."));
        Assert.assertFalse(source.contains("Minecraft.getMinecraft"));
        Assert.assertFalse(source.contains("private static EntityPlayer"));
        Assert.assertFalse(source.contains("private static World"));
    }

    @Test
    public void helperUsesItemPoseReachAndCallerLiquidFlag() throws Exception {
        String source = source();

        Assert.assertTrue(source.contains("player.prevRotationPitch"));
        Assert.assertTrue(source.contains("player.prevRotationYaw"));
        Assert.assertTrue(source.contains("player.prevPosX + (player.posX - player.prevPosX)"));
        Assert.assertTrue(source.contains("player.prevPosY + (player.posY - player.prevPosY)"));
        Assert.assertTrue(source.contains("player.prevPosZ + (player.posZ - player.prevPosZ)"));
        Assert.assertTrue(source.contains("player.getEyeHeight() - player.getDefaultEyeHeight()"));
        Assert.assertTrue(source.contains(
                "eye.addVector(lookX * reach, pitchSin * reach, lookZ * reach)"));
        Assert.assertTrue(source.contains(
                "player.worldObj.func_147447_a(eye, end, includeLiquids, false, false)"));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/chain/interaction/InteractionRayTrace.java").toPath()),
                StandardCharsets.UTF_8);
    }
}
